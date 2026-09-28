/**
 *  SONOFF SNZB-02M (AirGuard TH Pressure) - Hubitat Elevation driver
 *
 *  Reports: temperature, humidity, barometric (station) pressure, battery,
 *           plus calculated dew point, VPD, and (optionally) altimeter setting.
 *
 *  Protocol facts used here come from the Zigbee2MQTT / zigbee-herdsman-converters
 *  definition for this model (src/devices/sonoff.ts, Sept 2026):
 *    - Temperature : cluster 0x0402 attr 0x0000, INT16, hundredths of a degree C
 *    - Humidity    : cluster 0x0405 attr 0x0000, UINT16, hundredths of a percent
 *    - Pressure    : cluster 0x0403 attr 0x0004 (non-standard), INT32, hundredths of hPa
 *    - Battery     : cluster 0x0001 attr 0x0021, UINT8, half-percent units
 *    - Dew point / VPD are NOT sent by the sensor; they are calculated here
 *      using the same Magnus formula Zigbee2MQTT uses.
 *
 *  Version 1.0.0 - 2026-09-28 - initial release
 *  Version 1.0.1 - 2026-09-28 - retries re-send only unconfirmed clusters, capped at 5 attempts
 *  Version 1.1.0 - 2026-09-28 - fix decimal-rounding bug (new BigDecimal(double) -> BigDecimal.valueOf);
 *                               add a plausibility guard on pressure readings; de-duplicate the confirmed-
 *                               cluster list; give the pressure-attribute fallback its own retry budget;
 *                               space out Configure Reporting / Read Attribute commands; clamp reporting
 *                               interval prefs; add PowerSource capability; add uninstalled() cleanup
 *
 *  Credits: the cluster/attribute map this driver is built on (including the
 *  non-standard manufacturer pressure attribute and the manufacturer-code
 *  gotcha noted below) comes from the Zigbee2MQTT / zigbee-herdsman-converters
 *  project (https://github.com/Koenkk/zigbee-herdsman-converters), MIT licensed.
 *  This driver is an independent implementation for Hubitat, not a port of
 *  their code.
 *
 *  License: MIT (or pick whatever you prefer before publishing - see README).
 */

import hubitat.zigbee.zcl.DataType
import groovy.transform.Field
import java.math.RoundingMode

@Field static final String DRIVER_VERSION = "1.1.0"
@Field static final List<String> CFG_CLUSTERS = ["0402", "0405", "0403", "0001"]
@Field static final int MAX_CFG_RETRIES = 5
@Field static final double HPA_PER_INHG = 33.8639
@Field static final double MMHG_PER_HPA = 0.750062

// Plausibility bounds for a station-pressure reading, in hPa. 300 hPa is roughly
// the pressure at the summit of Everest; 1100 hPa is above any pressure ever
// recorded at the surface. Unlike temperature (-32768 sentinel) and humidity
// (0xFFFF sentinel), this vendor attribute has no documented "invalid value"
// marker, so a physical-plausibility range is used instead to reject boot-time
// garbage or a corrupted frame before it reaches the altimeter-setting formula
// (which divides by a pressure-derived term and can return NaN for nonsense input).
@Field static final double MIN_PLAUSIBLE_HPA = 300.0
@Field static final double MAX_PLAUSIBLE_HPA = 1100.0

metadata {
    definition(name: "SONOFF SNZB-02M AirGuard TH Pressure", namespace: "sig", author: "Sig Freund") {
        capability "Sensor"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "PressureMeasurement"
        capability "Battery"
        capability "Configuration"
        capability "Refresh"
        capability "PowerSource"

        attribute "dewPoint", "number"
        attribute "vpd", "number"
        attribute "altimeterSetting", "number"
        attribute "healthStatus", "enum", ["online", "offline"]

        fingerprint profileId: "0104", endpointId: "01",
                    inClusters: "0000,0001,0003,0020,0402,0403,0405,FC57,FC11", outClusters: "0019",
                    model: "SNZB-02M", manufacturer: "SONOFF", controllerType: "ZGB"
    }

    preferences {
        input name: "tempOffset", type: "decimal", title: "Temperature offset",
              description: "Added to every reading, in your hub's temperature scale (°F or °C). Default 0.", defaultValue: 0
        input name: "humidityOffset", type: "decimal", title: "Humidity offset (%)",
              description: "Added to every reading. Default 0.", defaultValue: 0
        input name: "pressureUnit", type: "enum", title: "Pressure unit",
              options: ["inHg": "inHg", "hPa": "hPa (millibars)", "mmHg": "mmHg"], defaultValue: "inHg"
        input name: "pressureOffset", type: "decimal", title: "Pressure offset",
              description: "Added to station pressure, in the pressure unit selected above. Default 0.", defaultValue: 0
        input name: "elevationFt", type: "number", title: "Sensor elevation (feet above sea level)",
              description: "When set above 0, the driver also reports an altimeter setting (inHg), comparable to a METAR/ATIS. 0 = off.", defaultValue: 0
        input name: "tempChange", type: "decimal", title: "Temperature change that triggers a report",
              description: "In your hub's temperature scale. Default 0.5.", defaultValue: 0.5
        input name: "humidityChange", type: "decimal", title: "Humidity change that triggers a report (%)",
              description: "Default 1.", defaultValue: 1
        input name: "pressureChangeHpa", type: "decimal", title: "Pressure change that triggers a report (hPa)",
              description: "Always in hPa. 0.5 hPa is about 0.015 inHg. Default 0.5.", defaultValue: 0.5
        input name: "minInterval", type: "number", title: "Minimum seconds between reports",
              description: "Lower = faster response but shorter battery life. Default 10.", defaultValue: 10
        input name: "maxInterval", type: "number", title: "Maximum seconds between reports",
              description: "Sensor reports at least this often even if nothing changed. Default 3600 (1 hour).", defaultValue: 3600
        input name: "offlineHours", type: "number", title: "Mark offline after this many hours of silence",
              description: "Default 3.", defaultValue: 3
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "logEnable", type: "bool", title: "Enable debug logging (turns itself off after 30 minutes)", defaultValue: false
    }
}

/* ============================== lifecycle ============================== */

def installed() {
    log.info "${device.displayName}: driver v${DRIVER_VERSION} installed"
    initialize()
}

def updated() {
    log.info "${device.displayName}: preferences saved (driver v${DRIVER_VERSION})"
    if (logEnable) runIn(1800, "logsOff")
    initialize()

    // Offsets and units only affect the hub's math, so re-publish the last readings right away.
    republishAll()

    // Reporting thresholds live in the sensor itself, so they must be sent to it.
    if (reportingSignature() != state.reportingSig) {
        log.info "${device.displayName}: reporting settings changed - sending to sensor now and again at its next report. Press the sensor's button once to wake it."
        state.reportingSig = reportingSignature()
        queueConfig()
        sendZigbee(configCmds())
    }
}

def initialize() {
    unschedule("healthCheck")
    runEvery30Minutes("healthCheck")
    if (state.lastRx == null) state.lastRx = now()
    if (state.pressureAttr == null) state.pressureAttr = 0x0004
    if (state.havePrecisePressure == null) state.havePrecisePressure = false
    // This is a battery sensor; set it once so dashboards/rules that key off
    // powerSource (e.g. to skip "device offline" alerts differently for
    // battery vs. mains devices) see the right value from the start.
    sendEvent(name: "powerSource", value: "battery")
}

def uninstalled() {
    unschedule()
}

def logsOff() {
    log.warn "${device.displayName}: debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

/* ============================== commands ============================== */

def configure() {
    log.info "${device.displayName}: configure - press the sensor's button once now so it is awake to receive this. " +
             "If it misses it, the driver re-sends automatically right after the sensor's next report."
    initialize()
    state.reportingSig = reportingSignature()
    queueConfig()
    return configCmds()
}

def refresh() {
    logDebug "refresh requested (the sensor sleeps; press its button if nothing comes back)"
    return refreshCmds()
}

/* ============================== Zigbee command builders ============================== */

/**
 * Build the configuration commands. With no argument, configures everything.
 * With a list of cluster IDs (e.g. ["0402"]), configures only those - used for
 * retries so already-confirmed clusters are not re-sent.
 */
private List<String> configCmds(List<String> only = null) {
    List<String> want = only ?: CFG_CLUSTERS
    List<String> cmds = []
    String dni = device.deviceNetworkId
    String zid = device.zigbeeId

    // Bind each measurement cluster to the hub so reports are delivered here.
    want.each { cl ->
        cmds += "zdo bind 0x${dni} 0x01 0x01 0x${cl} {${zid}} {}"
        cmds += "delay 200"
    }

    // Clamp so a fat-fingered or blank preference (e.g. a negative number)
    // never gets sent to the radio verbatim.
    int minI = Math.max(0, safeInt(settings.minInterval, 10))
    int maxI = Math.max(1, safeInt(settings.maxInterval, 3600))
    if (maxI < minI) maxI = minI

    // Temperature threshold: convert from the hub scale into hundredths of a degree C.
    double tChange = safeDouble(settings.tempChange, 0.5)
    if (location.temperatureScale == "F") tChange = tChange / 1.8
    int tRaw = Math.max(1, Math.round(tChange * 100) as int)

    int hRaw = Math.max(1, Math.round(safeDouble(settings.humidityChange, 1) * 100) as int)
    int pRaw = Math.max(1, Math.round(safeDouble(settings.pressureChangeHpa, 0.5) * 100) as int)

    // Space the Configure Reporting requests out, same as the binds above.
    // This is a sleepy battery end device with a short receive window after
    // it transmits; sending several ZCL commands back-to-back risks the last
    // one or two landing after it has already gone back to sleep. This isn't
    // confirmed to be the cause of any specific miss, but it's the standard
    // precaution for this class of device and costs nothing.
    if ("0402" in want) {
        cmds += zigbee.configureReporting(0x0402, 0x0000, DataType.INT16, minI, maxI, tRaw)
        cmds += "delay 200"
    }
    if ("0405" in want) {
        cmds += zigbee.configureReporting(0x0405, 0x0000, DataType.UINT16, minI, maxI, hRaw)
        cmds += "delay 200"
    }
    if ("0403" in want) {
        // IMPORTANT: attribute 0x0004 is described by the upstream Zigbee2MQTT
        // definition as a "manufacturer attribute", which invites adding a
        // manufacturer code (e.g. Zcl.ManufacturerCode / an options map with
        // mfgCode) to these calls to look "more correct". Don't. This exact
        // sensor shipped with a bug where reads of 0x0004 that included a
        // manufacturer-specific header were rejected by the device with an
        // "unsupported attribute" response; the fix was to remove the
        // manufacturer code, not add one. See:
        // https://github.com/Koenkk/zigbee-herdsman-converters/pull/13270
        // zigbee.configureReporting()/readAttribute() below intentionally
        // pass no mfgCode option, which matches that fix.
        if ((state.pressureAttr ?: 0x0004) == 0x0004) {
            cmds += zigbee.configureReporting(0x0403, 0x0004, DataType.INT32, minI, maxI, pRaw)
        } else {
            // Fallback to the standard attribute (whole hPa) if the sensor rejected 0x0004.
            cmds += zigbee.configureReporting(0x0403, 0x0000, DataType.INT16, minI, maxI, Math.max(1, (pRaw / 100) as int))
        }
        cmds += "delay 200"
    }
    // Battery: at most every hour, at least every 12 hours, or on a 2% change.
    if ("0001" in want) {
        cmds += zigbee.configureReporting(0x0001, 0x0021, DataType.UINT8, 3600, 43200, 4)
        cmds += "delay 200"
    }

    // A full configure also reads current values; a retry does not (saves battery).
    if (only == null) cmds += refreshCmds()
    return cmds
}

private List<String> refreshCmds() {
    List<String> cmds = []
    cmds += zigbee.readAttribute(0x0402, 0x0000)
    cmds += "delay 200"
    cmds += zigbee.readAttribute(0x0405, 0x0000)
    cmds += "delay 200"
    // See the manufacturer-code note above configCmds()'s 0403 block - no mfgCode here either.
    cmds += zigbee.readAttribute(0x0403, (state.pressureAttr ?: 0x0004) as int)
    cmds += "delay 200"
    cmds += zigbee.readAttribute(0x0001, 0x0021)
    return cmds
}

private void sendZigbee(List<String> cmds) {
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

/*
 * The sensor is a sleepy battery device: it only listens briefly after it
 * transmits. So after a report arrives, any clusters the sensor has not yet
 * confirmed are re-sent (at most once a minute, at most MAX_CFG_RETRIES times).
 */
private void queueConfig() {
    state.configPending = true
    state.cfgConfirmed = []
    state.cfgRetries = 0
    state.lastConfigSend = now()
}

private List<String> unconfirmedClusters() {
    List done = (state.cfgConfirmed ?: []) as List
    return CFG_CLUSTERS.findAll { !(it in done) }
}

private String reportingSignature() {
    return "${settings.minInterval}|${settings.maxInterval}|${settings.tempChange}|${settings.humidityChange}|${settings.pressureChangeHpa}|${location.temperatureScale}|${state.pressureAttr}"
}

/* ============================== parsing ============================== */

def parse(String description) {
    state.lastRx = now()
    if (device.currentValue("healthStatus") != "online") {
        sendEvent(name: "healthStatus", value: "online", descriptionText: "${device.displayName} is online")
    }

    Map msg
    try {
        msg = zigbee.parseDescriptionAsMap(description)
    } catch (Exception e) {
        logDebug "could not parse: ${description}"
        return
    }
    logDebug "parse: ${msg}"

    if (description.startsWith("catchall")) {
        handleCatchall(msg)
    } else if (msg?.attrId != null) {
        handleAttribute(msg.cluster as String, msg.attrId as String, msg.value as String)
        msg.additionalAttrs?.each { Map a ->
            handleAttribute((a.cluster ?: msg.cluster) as String, a.attrId as String, a.value as String)
        }
    }

    // Deliver any still-unconfirmed configuration while the sensor is awake.
    if (state.configPending && (now() - ((state.lastConfigSend ?: 0) as long)) > 60000L) {
        List<String> missing = unconfirmedClusters()
        int tries = (state.cfgRetries ?: 0) as int
        if (missing.isEmpty()) {
            state.configPending = false
        } else if (tries >= MAX_CFG_RETRIES) {
            state.configPending = false
            log.warn "${device.displayName}: sensor never confirmed reporting for cluster(s) ${missing} after ${tries} retries - " +
                     "giving up. Those readings still arrive on the sensor's own default schedule. Press Configure to try again."
        } else {
            state.cfgRetries = tries + 1
            state.lastConfigSend = now()
            log.info "${device.displayName}: sensor is awake - retry ${tries + 1} of ${MAX_CFG_RETRIES} for unconfirmed cluster(s) ${missing}"
            sendZigbee(configCmds(missing))
        }
    }
}

private void handleAttribute(String cluster, String attrId, String value) {
    if (cluster == null || attrId == null || value == null || value == "") return
    try {
        switch ("${cluster.toUpperCase()}_${attrId.toUpperCase()}") {
            case "0402_0000":                               // temperature, 0.01 °C
                int t = signed16(value)
                if (t == -32768) return                     // "invalid" marker
                state.rawTempC = t / 100.0
                publishTemperature()
                publishDerived()
                break

            case "0405_0000":                               // humidity, 0.01 %
                int h = Integer.parseInt(value, 16)
                if (h == 0xFFFF) return
                state.rawHumidity = h / 100.0
                publishHumidity()
                publishDerived()
                break

            case "0403_0004":                               // pressure, 0.01 hPa (Sonoff non-standard)
                double hpa = signed32(value) / 100.0
                if (hpa < MIN_PLAUSIBLE_HPA || hpa > MAX_PLAUSIBLE_HPA) {
                    log.warn "${device.displayName}: ignored implausible pressure reading ${hpa} hPa (raw 0x${value})"
                    return
                }
                state.rawHpa = hpa
                state.havePrecisePressure = true
                publishPressure()
                break

            case "0403_0000":                               // standard pressure, whole hPa (fallback only)
                int p = signed16(value)
                if (p == -32768) return
                if (p < MIN_PLAUSIBLE_HPA || p > MAX_PLAUSIBLE_HPA) {
                    log.warn "${device.displayName}: ignored implausible pressure reading ${p} hPa (raw 0x${value})"
                    return
                }
                if (!state.havePrecisePressure) {
                    state.rawHpa = p as double
                    publishPressure()
                }
                break

            case "0001_0021":                               // battery, 0.5 % units
                int b = Integer.parseInt(value, 16)
                if (b == 0xFF) return
                int pct = Math.min(100, Math.round(b / 2.0) as int)
                sendEvent(name: "battery", value: pct, unit: "%", descriptionText: txt("battery is ${pct}%"))
                break

            case "0001_0020":                               // battery voltage, 0.1 V
                logDebug "battery voltage ${Integer.parseInt(value, 16) / 10.0} V"
                break

            default:
                logDebug "ignored attribute cluster ${cluster} attr ${attrId} value ${value}"
        }
    } catch (Exception e) {
        log.warn "${device.displayName}: failed to decode cluster ${cluster} attr ${attrId} value '${value}': ${e}"
    }
}

private void handleCatchall(Map msg) {
    String cl = (msg?.clusterId ?: msg?.cluster) as String
    String cmd = msg?.command as String
    if (cl == null) return
    cl = cl.toUpperCase()

    // 0x07 = Configure Reporting Response
    if (cmd == "07" && cl in CFG_CLUSTERS) {
        String status = (msg.data && msg.data.size() > 0) ? msg.data[0] : "??"
        if (status == "00") {
            List done = (state.cfgConfirmed ?: []) as List
            if (!(cl in done)) done << cl
            state.cfgConfirmed = done
            logDebug "reporting accepted for cluster ${cl}"
            if (done.containsAll(CFG_CLUSTERS) && state.configPending) {
                state.configPending = false
                log.info "${device.displayName}: sensor confirmed its reporting configuration"
            }
        } else if (cl == "0403" && (state.pressureAttr ?: 0x0004) == 0x0004) {
            log.warn "${device.displayName}: sensor rejected reporting on pressure attribute 0x0004 (status ${status}); " +
                     "falling back to standard attribute 0x0000 (whole-hPa resolution) and retrying"
            state.pressureAttr = 0x0000
            state.reportingSig = reportingSignature()
            // Give the fallback attribute a fresh retry budget. Without this,
            // a rejection that arrives late (e.g. on retry 4 of 5) would leave
            // only one attempt to configure the *new* attribute before the
            // retry cap gives up on it entirely.
            state.cfgRetries = 0
            state.lastConfigSend = 0                       // retry at the next wake
        } else {
            log.warn "${device.displayName}: sensor rejected reporting for cluster ${cl} (status ${status})"
        }
        return
    }

    if (cl == "0020" && cmd == "00") {
        logDebug "poll-control check-in received"
        return
    }
    logDebug "catchall ignored: cluster ${cl} command ${cmd} data ${msg?.data}"
}

/* ============================== publishing ============================== */

private void republishAll() {
    if (state.rawTempC != null) publishTemperature()
    if (state.rawHumidity != null) publishHumidity()
    if (state.rawHpa != null) publishPressure()
    publishDerived()
}

/** Calibrated temperature in °C (offset converted from hub scale). */
private Double calTempC() {
    if (state.rawTempC == null) return null
    double off = safeDouble(settings.tempOffset, 0)
    if (location.temperatureScale == "F") off = off / 1.8
    return (state.rawTempC as double) + off
}

private Double calHumidity() {
    if (state.rawHumidity == null) return null
    double h = (state.rawHumidity as double) + safeDouble(settings.humidityOffset, 0)
    return Math.max(0.0d, Math.min(100.0d, h))
}

/** Calibrated station pressure in hPa (offset converted from the display unit). */
private Double calHpa() {
    if (state.rawHpa == null) return null
    double off = safeDouble(settings.pressureOffset, 0)
    switch (settings.pressureUnit ?: "inHg") {
        case "inHg": off = off * HPA_PER_INHG; break
        case "mmHg": off = off / MMHG_PER_HPA; break
    }
    return (state.rawHpa as double) + off
}

private void publishTemperature() {
    Double c = calTempC()
    if (c == null) return
    String scale = location.temperatureScale
    BigDecimal t = round(scale == "F" ? c * 1.8 + 32 : c, 1)
    sendEvent(name: "temperature", value: t, unit: "°${scale}", descriptionText: txt("temperature is ${t}°${scale}"))
}

private void publishHumidity() {
    Double h = calHumidity()
    if (h == null) return
    BigDecimal v = round(h, 1)
    sendEvent(name: "humidity", value: v, unit: "%", descriptionText: txt("humidity is ${v}%"))
}

private void publishPressure() {
    Double hpa = calHpa()
    if (hpa == null) return
    String unit = settings.pressureUnit ?: "inHg"
    BigDecimal v
    switch (unit) {
        case "inHg": v = round(hpa / HPA_PER_INHG, 2); break
        case "mmHg": v = round(hpa * MMHG_PER_HPA, 1); break
        default:     v = round(hpa, 1); unit = "hPa"
    }
    sendEvent(name: "pressure", value: v, unit: unit, descriptionText: txt("pressure is ${v} ${unit}"))

    int elevFt = safeInt(settings.elevationFt, 0)
    if (elevFt > 0) {
        BigDecimal alt = round(altimeterHpa(hpa, elevFt * 0.3048) / HPA_PER_INHG, 2)
        sendEvent(name: "altimeterSetting", value: alt, unit: "inHg", descriptionText: txt("altimeter setting is ${alt} inHg"))
    }
}

/**
 * Altimeter setting from station pressure, NWS formula
 * (Smithsonian Meteorological Tables / ASOS algorithm):
 *   As = (Ps - 0.3) * [1 + (1013.25^n * 0.0065 / 288) * H / (Ps - 0.3)^n]^(1/n),  n = 0.190284
 *   Ps in hPa, H = station elevation in metres.
 */
private double altimeterHpa(double stationHpa, double elevM) {
    double n = 0.190284
    double k = Math.pow(1013.25, n) * 0.0065 / 288.0
    double p = stationHpa - 0.3
    return p * Math.pow(1.0 + k * elevM / Math.pow(p, n), 1.0 / n)
}

/** Dew point and VPD, same Magnus formulation Zigbee2MQTT uses for this sensor. */
private void publishDerived() {
    Double tc = calTempC()
    Double rh = calHumidity()
    if (tc == null || rh == null) return

    double es = 0.61078 * Math.exp((17.27 * tc) / (tc + 237.3))     // saturation vapour pressure, kPa
    double ea = (rh / 100.0) * es
    BigDecimal vpd = round(es - ea, 2)
    sendEvent(name: "vpd", value: vpd, unit: "kPa", descriptionText: txt("VPD is ${vpd} kPa"))

    if (rh > 0) {
        double alpha = (17.27 * tc) / (tc + 237.7) + Math.log(rh / 100.0)
        double dpC = (237.7 * alpha) / (17.27 - alpha)
        String scale = location.temperatureScale
        BigDecimal dp = round(scale == "F" ? dpC * 1.8 + 32 : dpC, 1)
        sendEvent(name: "dewPoint", value: dp, unit: "°${scale}", descriptionText: txt("dew point is ${dp}°${scale}"))
    }
}

/* ============================== health ============================== */

def healthCheck() {
    long limitMs = (safeInt(settings.offlineHours, 3) as long) * 3600000L
    if (state.lastRx != null && (now() - (state.lastRx as long)) > limitMs
            && device.currentValue("healthStatus") != "offline") {
        log.warn "${device.displayName}: no reports for over ${settings.offlineHours ?: 3} hours - marking offline"
        sendEvent(name: "healthStatus", value: "offline", descriptionText: "${device.displayName} is offline")
    }
}

/* ============================== helpers ============================== */

private int signed16(String hex) {
    int v = Integer.parseInt(hex, 16)
    return (v > 0x7FFF) ? v - 0x10000 : v
}

private long signed32(String hex) {
    long v = Long.parseLong(hex, 16)
    return (v > 0x7FFFFFFFL) ? v - 0x100000000L : v
}

/**
 * Round a double to the given number of decimal places for display.
 *
 * Deliberately uses BigDecimal.valueOf(v), NOT `new BigDecimal(v)`. The
 * no-arg constructor builds a BigDecimal from the double's exact binary
 * representation, which is almost never the decimal value it looks like -
 * e.g. 2.675d is actually stored as ...499999999999982236..., so
 * `new BigDecimal(2.675d).setScale(2, HALF_UP)` silently rounds DOWN to
 * 2.67 instead of 2.68. BigDecimal.valueOf(v) goes through Double.toString()
 * first, giving the decimal value a human actually expects to round.
 */
private BigDecimal round(double v, int places) {
    return BigDecimal.valueOf(v).setScale(places, RoundingMode.HALF_UP)
}

private int safeInt(def v, int dflt) {
    try { return (v == null || v.toString() == "") ? dflt : (v as BigDecimal).intValue() } catch (Exception e) { return dflt }
}

private double safeDouble(def v, double dflt) {
    try { return (v == null || v.toString() == "") ? dflt : (v as BigDecimal).doubleValue() } catch (Exception e) { return dflt }
}

private String txt(String s) {
    String d = "${device.displayName} ${s}"
    if (txtEnable) log.info d
    return d
}

private void logDebug(String s) {
    if (logEnable) log.debug "${device.displayName}: ${s}"
}
