# Hubitat MSG100 Garage Door

Control a Meross MSG100 WiFi Garage Door Opener from Hubitat - open, close, and monitor its status entirely over your local network. Setup finds the device automatically (network scan or manual IP entry), and once it's configured, control never touches the cloud again.

Built specifically for the MSG100 - no other Meross device types are supported. It talks to the device directly over the LAN using Meross's own local device API, the same protocol every open-source Meross integration has to implement, since it comes from the device firmware itself, not from any particular project.

## What's included

- `apps/msg100_garage_door_setup.groovy` - a setup app that logs into your Meross account once, finds MSG100 devices on it, and creates a preconfigured child device.
- `drivers/msg100-garage-door.groovy` - the garage door driver. Exposes `DoorControl`/`GarageDoorControl` (open/close, door state), `ContactSensor`, `Refresh`, and `Polling`.

## Installation

1. In the Hubitat admin UI, go to **Drivers Code > New Driver**, paste in `drivers/msg100-garage-door.groovy`, and save.
2. Go to **Apps Code > New App**, paste in `apps/msg100_garage_door_setup.groovy`, and save.
3. Go to **Apps > Add User App**, select **MSG100 Garage Door Setup**.

## Setup

1. **Add a Garage Door** in the app.
2. Enter your Meross account email and password, and the API base URL for your account's region (defaults to `https://iotx-ap.meross.com`; try `https://iotx-us.meross.com` or `https://iotx-eu.meross.com` if login fails).
3. If your account has more than one MSG100, pick which one to add. If it only has one, it's selected automatically and you can name the Hubitat device right there (defaults to its name in the Meross app).
4. Find the device's LAN IP address - Meross's cloud device list doesn't include it, so you have two options:
   - **Scan my network for it** - probes your local subnet for a device that answers with the selected MSG100's UUID. This works without a valid key: even an intentionally-wrong signature gets a structured "sign error" response from a genuine Meross device, which is enough to identify it. The page updates automatically while waiting on results.
   - **Enter it manually** - type in the IP yourself (router DHCP client list, or the Meross app's WiFi details for the device).
5. The app creates a child device, preconfigured with the device's IP, UUID, and account key.

Your Meross password is only used for that one login call and is discarded from the app's settings immediately after - it's never stored.

## Driver settings

- **Polling frequency** - how often Hubitat re-checks door state over the LAN, independent of any command you send.
- **Status re-check delay after open / close** - how long to wait after sending an open or close command before polling for the real result (closing generally takes longer to settle than opening).
- **Debug logging**

## Notes

- MSG100 is a single-channel device, so there's no channel/port selector anywhere in this driver - channel `0` is the only door.
- The driver signs every LAN request fresh using the account key obtained during setup; there's no static/legacy signing fallback to configure.
