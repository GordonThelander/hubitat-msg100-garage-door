/*
 * MSG100 Garage Door Setup
 * Namespace: Hubitat Integrations
 * Version: 1.0.0
 *
 * Logs into a Meross account once, finds MSG100 garage door openers on
 * that account (and only MSG100s - anything else Meross returns is
 * filtered out before it ever reaches the UI), and creates a preconfigured
 * "MSG100 Garage Door" child device for the one you pick.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.URLEncoder
import java.security.MessageDigest

private static final String MEROSS_APP_SECRET = '23x17ahWarFH6w29'
private static final String CHILD_NAMESPACE = 'Hubitat Integrations'
private static final String CHILD_DRIVER_NAME = 'MSG100 Garage Door'

definition(
    name: 'MSG100 Garage Door Setup',
    namespace: CHILD_NAMESPACE,
    author: 'Gordon Thelander',
    description: 'Finds and adds Meross MSG100 garage door openers as Hubitat devices.',
    category: 'Bluetooth',
    menu: 'Integrations',
    iconUrl: '',
    iconX2Url: '',
    singleInstance: true
)

preferences {
    page(name: 'mainPage')
    page(name: 'addDeviceStep1')
    page(name: 'addDeviceStep2')
    page(name: 'addDeviceStep3')
    page(name: 'listDevicesPage')
}

def mainPage() {
    return dynamicPage(name: 'mainPage', title: 'MSG100 Garage Door Setup', install: true, uninstall: true) {
        section {
            paragraph('Adds Meross MSG100 WiFi garage door openers as Hubitat devices.')
            href(name: 'toAdd', page: 'addDeviceStep1', title: '<b>Add a Garage Door</b>', description: 'Log into Meross and add an MSG100.')
            href(name: 'toList', page: 'listDevicesPage', title: '<b>List Garage Doors</b>', description: 'Show devices already added.')
            input('debugLogging', 'bool', title: 'Enable debug logging', submitOnChange: true, defaultValue: false)
        }
    }
}

def listDevicesPage() {
    def message = getChildDevices()?.collect { "${it.label} (${it.getDeviceNetworkId()})" }?.join('\n') ?: 'No garage doors added yet.'
    return dynamicPage(name: 'listDevicesPage', title: 'Garage Doors', nextPage: 'mainPage') {
        section {
            paragraph(message)
        }
    }
}

def addDeviceStep1() {
    return dynamicPage(name: 'addDeviceStep1', title: 'Add a Garage Door (1 of 3): Account', nextPage: 'addDeviceStep2') {
        section {
            input('merossEmail', 'string', title: 'Meross account email', required: true)
            input('merossPassword', 'password', title: 'Meross account password', required: true)
            input('merossApiBase', 'string', title: 'Meross API base URL', required: true, defaultValue: 'https://iotx-ap.meross.com',
                  description: 'Region-specific. If login fails, try https://iotx-us.meross.com or https://iotx-eu.meross.com.')
            input('deviceIp', 'text', title: 'Garage door opener LAN IP address', required: true,
                  description: "Meross's cloud device list does not include this - find it in your router or the Meross app's WiFi settings.")
        }
    }
}

def addDeviceStep2() {
    def login = loginMeross(merossEmail, merossPassword, merossApiBase)
    app.removeSetting('merossPassword')

    if (!login.success) {
        return dynamicPage(name: 'addDeviceStep2', title: 'Login Failed', nextPage: 'mainPage') {
            section {
                paragraph(login.error)
            }
        }
    }

    state.merossKey = login.key
    def devicesResult = fetchDeviceList(login.token, merossApiBase)
    if (!devicesResult.success) {
        return dynamicPage(name: 'addDeviceStep2', title: 'Device Lookup Failed', nextPage: 'mainPage') {
            section {
                paragraph(devicesResult.error)
            }
        }
    }

    def msg100Devices = devicesResult.devices.findAll { (it.deviceType ?: '').toString().toLowerCase().contains('msg100') }
    state.data = msg100Devices

    if (msg100Devices.isEmpty()) {
        def seenTypes = devicesResult.devices.collect { it.deviceType }.unique().join(', ') ?: 'none'
        return dynamicPage(name: 'addDeviceStep2', title: 'No MSG100 Found', nextPage: 'mainPage') {
            section {
                paragraph("No MSG100 devices were found on this Meross account.")
                paragraph("Device types seen on this account: ${seenTypes}")
            }
        }
    }

    def options = msg100Devices.collectEntries { [(it.uuid): (it.devName ?: it.uuid)] }
    return dynamicPage(name: 'addDeviceStep2', title: 'Add a Garage Door (2 of 3): Select Device', nextPage: 'addDeviceStep3') {
        section {
            input('selectedDevice', 'enum', title: "Select the garage door to add (${options.size()} MSG100 device(s) found)",
                  required: true, multiple: false, options: options, defaultValue: (options.size() == 1 ? options.keySet().first() : null))
        }
    }
}

def addDeviceStep3() {
    def device = state.data?.find { it.uuid == selectedDevice }
    def dni = "msg100:${selectedDevice}"
    def message

    if (getChildDevice(dni)) {
        message = "A device for '${device?.devName ?: selectedDevice}' already exists."
    } else {
        try {
            def child = addChildDevice(CHILD_NAMESPACE, CHILD_DRIVER_NAME, dni, [label: device?.devName ?: 'MSG100 Garage Door'])
            child.updateSetting('deviceIp', deviceIp)
            child.updateSetting('uuid', selectedDevice)
            child.updateSetting('key', state.merossKey)
            child.updateSetting('pollFrequencySeconds', [value: '300', type: 'enum'])
            child.updateSetting('openVerifyDelaySeconds', [value: 5, type: 'number'])
            child.updateSetting('closeVerifyDelaySeconds', [value: 20, type: 'number'])
            child.initialize()
            message = "Added '${device?.devName ?: 'MSG100 Garage Door'}' successfully."
        } catch (Exception e) {
            message = "Failed to add device: ${e}"
        }
    }

    app.removeSetting('selectedDevice')
    state.remove('data')
    state.remove('merossKey')

    return dynamicPage(name: 'addDeviceStep3', title: 'Add a Garage Door (3 of 3): Result', nextPage: 'mainPage') {
        section {
            paragraph(message)
        }
    }
}

def uninstalled() {
    getChildDevices()?.each { child ->
        try {
            deleteChildDevice(child.getDeviceNetworkId())
        } catch (Exception e) {
            log.warn("Could not delete ${child.displayName}: ${e.message}")
        }
    }
}

private Map loginMeross(String email, String password, String apiBase) {
    def sign = buildSign()
    def params = JsonOutput.toJson([email: email, password: password]).bytes.encodeBase64().toString()
    def signature = md5Hex(MEROSS_APP_SECRET + sign.timestamp + sign.nonce + params)

    def body = "params=${urlEncode(params)}&sign=${signature}&timestamp=${sign.timestamp}&nonce=${sign.nonce}"
    def result = [success: false, error: 'Unknown login error']

    try {
        httpPost([uri: "${apiBase}/v1/Auth/signIn", contentType: 'application/x-www-form-urlencoded', body: body]) { resp ->
            if (resp.status != 200) {
                result = [success: false, error: "HTTP error ${resp.status} logging into Meross."]
                return
            }
            def parsed = asMap(resp.data)
            if (parsed?.apiStatus != 0) {
                result = [success: false, error: "Meross login failed (apiStatus=${parsed?.apiStatus}): ${parsed?.info ?: 'no details returned'}"]
                return
            }
            def data = parsed.data
            if (!data?.token || !data?.key) {
                result = [success: false, error: "Meross login succeeded but response was missing a token/key."]
                return
            }
            result = [success: true, token: data.token.toString(), key: data.key.toString()]
        }
    } catch (Exception e) {
        result = [success: false, error: "Error contacting Meross: ${e}"]
    }
    return result
}

private Map fetchDeviceList(String token, String apiBase) {
    def sign = buildSign()
    def emptyParams = JsonOutput.toJson([:]).bytes.encodeBase64().toString()
    def signature = md5Hex(MEROSS_APP_SECRET + sign.timestamp + sign.nonce + emptyParams)

    def result = [success: false, error: 'Unknown device lookup error']
    try {
        httpPostJson([
            uri    : "${apiBase}/v1/Device/devList",
            headers: ['Authorization': "Basic ${token}"],
            body   : [params: emptyParams, sign: signature, timestamp: sign.timestamp, nonce: sign.nonce]
        ]) { resp ->
            if (resp.status != 200) {
                result = [success: false, error: "HTTP error ${resp.status} fetching device list."]
                return
            }
            def parsed = asMap(resp.data)
            if (parsed?.apiStatus != 0) {
                result = [success: false, error: "Meross device lookup failed (apiStatus=${parsed?.apiStatus}): ${parsed?.info ?: 'no details returned'}"]
                return
            }
            result = [success: true, devices: (parsed.data instanceof List ? parsed.data : [])]
        }
    } catch (Exception e) {
        result = [success: false, error: "Error contacting Meross: ${e}"]
    }
    return result
}

// Hubitat's httpPost/httpPostJson have been observed returning Meross's JSON
// response either already parsed or as a raw string, depending on the
// endpoint - normalise both shapes to a Map here.
private Map asMap(raw) {
    def parsed = raw
    if (parsed instanceof String) {
        try {
            parsed = new JsonSlurper().parseText(parsed)
        } catch (Exception ignored) {
            return [:]
        }
    }
    return (parsed instanceof Map) ? parsed : [:]
}

private Map buildSign() {
    def chars = ('A'..'Z') + ('0'..'9')
    def random = new Random()
    String nonce = (1..16).collect { chars[random.nextInt(chars.size())] }.join()
    long timestamp = (System.currentTimeMillis() / 1000L) as long
    return [nonce: nonce, timestamp: timestamp]
}

private static String md5Hex(String value) {
    MessageDigest digest = MessageDigest.getInstance('MD5')
    digest.update(value.bytes)
    return new BigInteger(1, digest.digest()).toString(16).padLeft(32, '0')
}

private static String urlEncode(String value) {
    return URLEncoder.encode(value, 'UTF-8')
}

private void logDebug(String msg) {
    if (debugLogging) {
        log.debug(msg)
    }
}
