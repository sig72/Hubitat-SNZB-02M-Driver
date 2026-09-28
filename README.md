# Hubitat Driver: SONOFF SNZB-02M AirGuard TH Pressure

A Hubitat Elevation device driver for the [SONOFF SNZB-02M AirGuard](https://sonoff.tech/en-us/products/sonoff-airguard-th-pressure-zigbee-temperature-humidity-air-pressure-sensor-snzb-02m) Zigbee temperature / humidity / barometric pressure sensor.

Hubitat has no built-in driver for this model, so out of the box it pairs with the generic, do-nothing "Device" driver and reports nothing. This driver decodes all of its readings, including the non-standard manufacturer pressure attribute, and adds a few things the sensor itself doesn't send.

## Features

- Temperature, humidity, battery
- Barometric (station) pressure, decoded from Sonoff's non-standard high-resolution attribute, with automatic fallback to the standard low-resolution attribute if a sensor firmware ever rejects the high-resolution one
- Calculated **dew point** and **VPD** (vapor pressure deficit), using the same formula Zigbee2MQTT uses for this device
- Optional **altimeter setting** (inHg), calculated from station pressure and a configured elevation — useful for comparing against a local METAR/ATIS
- Configurable calibration offsets for temperature, humidity, and pressure
- Configurable reporting thresholds (how much change triggers a report, min/max report interval)
- Online/offline health tracking based on how long it's been since the last report
- Automatic retry of the sensor's reporting configuration, since this is a sleepy battery end device that is only briefly awake right after it transmits

## Requirements

- A Hubitat Elevation hub
- A SONOFF SNZB-02M already paired to the hub (it will show up using Hubitat's generic "Device" driver, which reports nothing — that's expected until this driver is installed)

## Installation

1. On your Hubitat hub's web interface, go to **Drivers Code** (under **For developers** in the left-hand menu).
2. Click **+ Add driver**.
3. Open [`SNZB-02M-driver.groovy`](./SNZB-02M-driver.groovy) from this repository, select all of its contents, and copy it.
4. Paste it into the empty code box on the Hubitat page.
5. Click **Save**.
6. Open the device page for your SNZB-02M sensor, go to **Device Info**, and change the **Type** dropdown to **SONOFF SNZB-02M AirGuard TH Pressure**. Click **Save**.
7. Go to the **Commands** tab and click **Configure**. Press the sensor's button once within a few seconds so it's awake to receive the configuration — it's a sleepy battery device and only listens briefly right after it transmits. If it misses the configuration, the driver retries automatically after the sensor's next report (up to 5 times).

## Preferences

| Preference | What it does |
|---|---|
| Temperature offset | Added to every temperature reading (hub's configured scale, °F or °C) |
| Humidity offset | Added to every humidity reading (%) |
| Pressure unit | Display unit for pressure: inHg, hPa, or mmHg |
| Pressure offset | Added to station pressure, in the unit selected above |
| Sensor elevation (ft) | When set above 0, also reports an altimeter setting comparable to a METAR/ATIS |
| Temperature / humidity / pressure change thresholds | How much a reading has to move before the sensor reports it |
| Min / max report interval | How often the sensor is allowed / required to report |
| Offline hours | Mark the device offline after this many hours without a report |
| Enable descriptionText logging | Normal Hubitat event logging |
| Enable debug logging | Verbose logging for troubleshooting (auto-disables after 30 minutes) |

## A gotcha worth knowing if you modify this

The pressure reading comes from a **non-standard, vendor-defined attribute** (cluster `0x0403`, attribute `0x0004`) that Sonoff added on top of the standard Pressure Measurement cluster. It's tempting to treat this as manufacturer-specific and add a manufacturer code to the Zigbee reads/writes for it — **don't**. This exact sensor has a documented bug where reads of that attribute *fail* if a manufacturer code is included ([zigbee-herdsman-converters#13270](https://github.com/Koenkk/zigbee-herdsman-converters/pull/13270)). This driver intentionally sends no manufacturer code for that attribute, matching that fix.

## Credits

The cluster/attribute map this driver is built from — including the non-standard pressure attribute and the manufacturer-code gotcha above — comes from the [Zigbee2MQTT / zigbee-herdsman-converters](https://github.com/Koenkk/zigbee-herdsman-converters) project (MIT licensed). This driver is an independent implementation for Hubitat, not a port of their code.

## Changelog

- **1.1.0** — Fixed a decimal-rounding bug, added a plausibility check on pressure readings, gave the pressure-attribute fallback its own retry budget, spaced out the Zigbee commands sent to the sensor, added the PowerSource capability
- **1.0.1** — Configuration retries now re-send only unconfirmed clusters, capped at 5 attempts
- **1.0.0** — Initial release

## License

MIT — see [LICENSE](./LICENSE).
