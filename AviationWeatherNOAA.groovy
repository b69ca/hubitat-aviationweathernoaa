import groovy.transform.Field

/*
 * Aviation Weather Watch (NOAA)
 *
 * Monitors METAR observations and TAF forecasts for one ICAO station using
 * NOAA's Aviation Weather Center Data API. No API key or companion service.
 *
 * Author: Jon Wallace
 * Copyright 2026 Jon Wallace
 * License: MIT
 * Version: 1.0.0
 */

metadata {
  definition(
    name: "Aviation Weather Watch (NOAA)",
    namespace: "jonw",
    author: "Jon Wallace",
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/b69ca/hubitat-aviationweathernoaa/main/AviationWeatherNOAA.groovy"
  ) {
    capability "Sensor"
    capability "Refresh"
    capability "Initialize"
    capability "PushableButton"

    command "clearHistory"
    command "testCategoryEvent"
    command "testWindEvent"
    command "testWeatherEvent"

    attribute "watchStatus", "enum", ["setup", "updating", "ready", "partial", "error"]
    attribute "aviationState", "enum", ["unknown", "normal", "advisory", "warning", "stale"]
    attribute "lastChecked", "string"
    attribute "lastSuccessfulCheck", "string"
    attribute "lastError", "string"
    attribute "dataAgeMinutes", "number"

    attribute "stationId", "string"
    attribute "stationName", "string"
    attribute "stationLatitude", "number"
    attribute "stationLongitude", "number"
    attribute "stationElevationMeters", "number"
    attribute "observationTime", "string"
    attribute "observationEpoch", "number"
    attribute "flightCategory", "enum", ["VFR", "MVFR", "IFR", "LIFR", "UNKNOWN"]
    attribute "temperatureC", "number"
    attribute "dewpointC", "number"
    attribute "windDirection", "string"
    attribute "windSpeedKnots", "number"
    attribute "windGustKnots", "number"
    attribute "visibilityMiles", "number"
    attribute "ceilingFeet", "number"
    attribute "altimeterHpa", "number"
    attribute "weather", "string"
    attribute "cloudSummary", "string"
    attribute "rawMetar", "string"

    attribute "tafAvailable", "enum", ["yes", "no"]
    attribute "tafIssueTime", "string"
    attribute "tafValidFrom", "string"
    attribute "tafValidTo", "string"
    attribute "forecastWorstCategory", "enum", ["VFR", "MVFR", "IFR", "LIFR", "UNKNOWN"]
    attribute "forecastMaxWindKnots", "number"
    attribute "forecastMaxGustKnots", "number"
    attribute "forecastWeather", "string"
    attribute "forecastSummary", "string"
    attribute "rawTaf", "string"

    attribute "conditionSummary", "string"
    attribute "notificationText", "string"
    attribute "notificationType", "enum", ["category", "wind", "weather", "stale", "recovery", "test"]
  }

  preferences {
    input name: "setupNotes", type: "paragraph", title: "Aviation Weather Watch 1.0.0",
      description: "Enter a four-letter ICAO airport identifier such as KDEN, KSEA, EGLL or YSSY. Public METAR and TAF data come from aviationweather.gov; no API key is required."
    input name: "stationId", type: "text", title: "ICAO station identifier", required: true
    input name: "pollMinutes", type: "enum", title: "Automatic check interval", options: [
      "10":"10 minutes", "15":"15 minutes", "30":"30 minutes", "60":"1 hour"], defaultValue: "15"
    input name: "staleMinutes", type: "enum", title: "Treat the METAR as stale after", options: [
      "60":"1 hour", "90":"90 minutes", "120":"2 hours", "180":"3 hours", "360":"6 hours"], defaultValue: "120"

    input name: "categoryNotes", type: "paragraph", title: "Flight-category monitoring",
      description: "Button 1 reports a current or forecast category at or below the selected threshold. Categories worsen from VFR to MVFR, IFR and LIFR."
    input name: "categoryThreshold", type: "enum", title: "Flight-category event threshold", options: [
      "MVFR":"MVFR or worse", "IFR":"IFR or worse", "LIFR":"LIFR only"], defaultValue: "IFR"
    input name: "includeForecastCategory", type: "bool", title: "Allow the TAF forecast to trigger category events", defaultValue: true
    input name: "forecastHours", type: "enum", title: "TAF forecast window", options: [
      "6":"6 hours", "12":"12 hours", "24":"24 hours", "36":"36 hours"], defaultValue: "12"

    input name: "windNotes", type: "paragraph", title: "Wind monitoring",
      description: "Button 2 reports current or forecast wind at or above either configured threshold. Leave a threshold empty to ignore it."
    input name: "windSpeedThreshold", type: "number", title: "Sustained-wind threshold (knots, optional)", range: "1..200"
    input name: "windGustThreshold", type: "number", title: "Wind-gust threshold (knots, optional)", range: "1..250", defaultValue: 25
    input name: "includeForecastWind", type: "bool", title: "Allow the TAF forecast to trigger wind events", defaultValue: true

    input name: "weatherNotes", type: "paragraph", title: "Significant weather",
      description: "Button 3 reports matching METAR or TAF weather codes. Tokens are comma-separated, case-insensitive fragments such as TS, FZ, +RA, +SN, FU or VA."
    input name: "notifyWeather", type: "bool", title: "Notify for significant weather codes", defaultValue: true
    input name: "weatherTokens", type: "text", title: "Weather-code tokens", defaultValue: "TS,FZ,+RA,+SN,FU,VA"
    input name: "includeForecastWeather", type: "bool", title: "Allow the TAF forecast to trigger weather events", defaultValue: true
    input name: "notifyStale", type: "bool", title: "Notify when the METAR first becomes stale", defaultValue: true

    input name: "automationNotes", type: "paragraph", title: "Automation behavior",
      description: "Button 1: flight category. Button 2: wind. Button 3: significant weather or stale data. Significant weather wins if several conditions appear together."
    input name: "notifyInitialState", type: "bool", title: "Notify for the most important condition on the first successful check", defaultValue: false
    input name: "notifyOnlyOnChange", type: "bool", title: "Notify only when a condition is new or worsens", defaultValue: true
    input name: "notifyRecovery", type: "bool", title: "Notify on button 1 when all monitored conditions clear", defaultValue: false
    input name: "fetchTaf", type: "bool", title: "Download terminal forecasts", defaultValue: true
    input name: "logEnable", type: "bool", title: "Debug logging (automatically disables after 30 minutes)", defaultValue: false
  }
}

@Field static final String API_ROOT = "https://aviationweather.gov/api/data"
@Field static final Map CATEGORY_RANK = [VFR:0, MVFR:1, IFR:2, LIFR:3, UNKNOWN:-1]

def installed() {
  unschedule()
  state.generation = (state.generation ?: 0L) + 1L
  state.inFlight = null
  state.configured = false
  sendEvent(name:"numberOfButtons", value:3)
  sendChanged("watchStatus", "setup")
}

def updated() {
  state.configured = true
  initialize()
}

def uninstalled() {
  unschedule()
}

def initialize() {
  unschedule()
  state.configured = true
  state.generation = (state.generation ?: 0L) + 1L
  state.inFlight = null
  sendEvent(name:"numberOfButtons", value:3)
  sendChanged("watchStatus", "updating")
  configureSchedule()
  if (settings?.logEnable) runIn(1800, "logsOff")
  refresh()
}

def configureSchedule() {
  int minutes = settingNumber("pollMinutes", 15d, 10d, 60d).intValue()
  if (minutes == 60) runEvery1Hour("refresh")
  else schedule("0 */${minutes} * ? * *", "refresh")
}

def logsOff() {
  device.updateSetting("logEnable", [value:"false", type:"bool"])
}

def refresh() {
  if (state.configured != true) {
    sendChanged("watchStatus", "setup")
    return
  }
  if (state.inFlight) {
    if (now() - ((state.requestStarted ?: 0L) as Long) < 120000L) return
    state.inFlight = null
    recordError("The previous NOAA request did not complete")
  }
  try {
    String station = normalizedStation()
    if (state.stationId != station) {
      state.stationId = station
      state.previous = [:]
      state.hasBaseline = false
    }
    long started = now()
    String token = "${started}-${state.generation}"
    state.inFlight = token
    state.requestStarted = started
    state.batch = [token:token, metar:null, taf:null, errors:[]]
    sendChanged("lastChecked", localText(started))
    sendChanged("watchStatus", "updating")
    if (settings?.logEnable) log.debug "Checking NOAA aviation weather for ${station}"
    request("metarResponse", "${API_ROOT}/metar", [ids:station, format:"json", hours:"3"], token)
  } catch (Exception e) {
    state.inFlight = null
    recordError("Configuration/request: ${e.message}")
  }
}

private void request(String callback, String uri, Map query, String token) {
  try {
    asynchttpGet(callback, [
      uri:uri, query:query, contentType:"application/json", timeout:20,
      headers:["User-Agent":"Hubitat-AviationWeatherWatch/1.0 (github.com/b69ca/hubitat-aviationweathernoaa)"]
    ], [token:token, station:state.stationId])
  } catch (Exception e) {
    state.inFlight = null
    recordError("NOAA request: ${e.message}")
  }
}

def metarResponse(resp, data) {
  if (!validCallback(data)) return
  try {
    List body = responseList(resp, "METAR")
    if (!body) throw new IllegalArgumentException("No METAR was returned for ${data.station}")
    List parsed = body.collect { parseMetar(it) }.findAll { it }
    if (!parsed) throw new IllegalArgumentException("No usable METAR was returned for ${data.station}")
    state.batch.metar = parsed.max { it.time }
  } catch (Exception e) {
    state.batch.errors << "METAR: ${safeText(e.message, 300)}"
  }
  if (settings?.fetchTaf == false) finishBatch(data.token)
  else request("tafResponse", "${API_ROOT}/taf", [ids:data.station, format:"json"], data.token)
}

def tafResponse(resp, data) {
  if (!validCallback(data)) return
  try {
    List body = responseList(resp, "TAF")
    if (body) state.batch.taf = parseTaf(body[0])
  } catch (Exception e) {
    state.batch.errors << "TAF: ${safeText(e.message, 300)}"
  }
  finishBatch(data.token)
}

private void finishBatch(String token) {
  if (token != state.inFlight || token != state.batch?.token) return
  Map batch = state.batch
  Map metar = batch.metar instanceof Map ? batch.metar : null
  Map taf = batch.taf instanceof Map ? batch.taf : null
  List errors = batch.errors instanceof List ? batch.errors : []
  state.inFlight = null
  state.batch = null
  if (!metar) {
    recordError(errors ? errors.join("; ") : "NOAA returned no usable METAR")
    return
  }

  boolean first = state.hasBaseline != true
  Map conditions = evaluateConditions(metar, taf)
  Map previous = state.previous instanceof Map ? state.previous : [:]
  Map event = chooseEvent(conditions, previous, first)
  publishMetar(metar)
  publishTaf(taf)
  publishConditions(conditions, metar, taf)
  state.previous = [categoryRank:conditions.categoryRank, wind:conditions.wind, weather:conditions.weather, stale:conditions.stale,
                    anyAlert:conditions.anyAlert]
  state.hasBaseline = true
  if (event) emitAutomationEvent(event)
  sendChanged("lastSuccessfulCheck", localText(now()))
  sendChanged("lastError", errors ? errors.join("; ").take(500) : "")
  sendChanged("watchStatus", errors ? "partial" : "ready")
}

private Map evaluateConditions(Map metar, Map taf) {
  long ageMinutes = Math.max(0L, now() - (metar.time as Long)).intdiv(60000L)
  boolean stale = ageMinutes > settingNumber("staleMinutes", 120d, 30d, 720d)
  String threshold = settings?.categoryThreshold in ["MVFR", "IFR", "LIFR"] ? settings.categoryThreshold : "IFR"
  int currentRank = categoryRank(metar.category)
  String worstCategory = taf?.worstCategory ?: "UNKNOWN"
  int effectiveCategory = currentRank
  if (settings?.includeForecastCategory != false) effectiveCategory = Math.max(effectiveCategory, categoryRank(worstCategory))
  boolean categoryAlert = effectiveCategory >= categoryRank(threshold)

  Double speedThreshold = optionalNumber(settings?.windSpeedThreshold)
  Double gustThreshold = optionalNumber(settings?.windGustThreshold)
  double maximumSpeed = safeDouble(metar.windSpeed, 0d)
  double maximumGust = safeDouble(metar.windGust, 0d)
  if (settings?.includeForecastWind != false && taf) {
    maximumSpeed = Math.max(maximumSpeed, safeDouble(taf.maxWind, 0d))
    maximumGust = Math.max(maximumGust, safeDouble(taf.maxGust, 0d))
  }
  boolean wind = (speedThreshold != null && maximumSpeed >= speedThreshold) || (gustThreshold != null && maximumGust >= gustThreshold)

  String combinedWeather = [metar.weather, settings?.includeForecastWeather == false ? "" : taf?.weather].findAll { it }.join(" ")
  List tokens = weatherTokenList()
  List matches = tokens.findAll { token -> combinedWeather.toUpperCase().contains(token) }
  boolean weather = settings?.notifyWeather != false && !matches.isEmpty()
  return [stale:stale, ageMinutes:ageMinutes, categoryAlert:categoryAlert, categoryRank:effectiveCategory,
          currentCategory:metar.category, worstCategory:worstCategory, wind:wind, maxWind:maximumSpeed,
          maxGust:maximumGust, weather:weather, weatherMatches:matches, anyAlert:stale || categoryAlert || wind || weather]
}

private Map chooseEvent(Map current, Map previous, boolean first) {
  if (first && settings?.notifyInitialState != true) return null
  boolean changedOnly = settings?.notifyOnlyOnChange != false
  if (current.weather && (!changedOnly || previous.weather != true)) {
    return [button:3, type:"weather", text:"Significant aviation weather at ${state.stationId}: ${current.weatherMatches.join(', ')}; current ${current.currentCategory}, forecast worst ${current.worstCategory}."]
  }
  if (current.stale && settings?.notifyStale != false && (!changedOnly || previous.stale != true)) {
    return [button:3, type:"stale", text:"METAR data for ${state.stationId} is stale (${current.ageMinutes} minutes old)."]
  }
  if (current.categoryAlert && (!changedOnly || previous.categoryRank == null || current.categoryRank > safeInteger(previous.categoryRank, -1))) {
    return [button:1, type:"category", text:"Aviation category alert at ${state.stationId}: current ${current.currentCategory}, forecast worst ${current.worstCategory}."]
  }
  if (current.wind && (!changedOnly || previous.wind != true)) {
    return [button:2, type:"wind", text:"Aviation wind threshold at ${state.stationId}: maximum ${formatNumber(current.maxWind)} kt, gust ${formatNumber(current.maxGust)} kt."]
  }
  if (settings?.notifyRecovery == true && current.anyAlert == false && previous.anyAlert == true) {
    return [button:1, type:"recovery", text:"Monitored aviation conditions cleared at ${state.stationId}; current category ${current.currentCategory}."]
  }
  return null
}

private void publishMetar(Map metar) {
  sendChanged("stationId", state.stationId)
  sendChanged("stationName", metar.name)
  sendChanged("stationLatitude", rounded(safeDouble(metar.latitude, 0d), 4))
  sendChanged("stationLongitude", rounded(safeDouble(metar.longitude, 0d), 4))
  sendChanged("stationElevationMeters", rounded(safeDouble(metar.elevation, 0d), 0))
  sendChanged("observationTime", localText(metar.time as Long))
  sendChanged("observationEpoch", (metar.time as Long).intdiv(1000L))
  sendChanged("flightCategory", metar.category)
  sendChanged("temperatureC", metar.temperature == null ? 0 : rounded(metar.temperature as Double, 1))
  sendChanged("dewpointC", metar.dewpoint == null ? 0 : rounded(metar.dewpoint as Double, 1))
  sendChanged("windDirection", metar.windDirection ?: "")
  sendChanged("windSpeedKnots", metar.windSpeed == null ? 0 : rounded(metar.windSpeed as Double, 0))
  sendChanged("windGustKnots", metar.windGust == null ? 0 : rounded(metar.windGust as Double, 0))
  sendChanged("visibilityMiles", metar.visibility == null ? 0 : rounded(metar.visibility as Double, 1))
  sendChanged("ceilingFeet", metar.ceiling == null ? 0 : rounded(metar.ceiling as Double, 0))
  sendChanged("altimeterHpa", metar.altimeter == null ? 0 : rounded(metar.altimeter as Double, 1))
  sendChanged("weather", metar.weather ?: "")
  sendChanged("cloudSummary", metar.cloudSummary ?: "")
  sendChanged("rawMetar", metar.raw ?: "")
}

private void publishTaf(Map taf) {
  sendChanged("tafAvailable", taf ? "yes" : "no")
  if (!taf) {
    ["tafIssueTime", "tafValidFrom", "tafValidTo", "forecastWeather", "forecastSummary", "rawTaf"].each { sendChanged(it, "") }
    sendChanged("forecastWorstCategory", "UNKNOWN")
    sendChanged("forecastMaxWindKnots", 0)
    sendChanged("forecastMaxGustKnots", 0)
    return
  }
  sendChanged("tafIssueTime", taf.issueTime ? localText(taf.issueTime as Long) : "")
  sendChanged("tafValidFrom", taf.validFrom ? localText(taf.validFrom as Long) : "")
  sendChanged("tafValidTo", taf.validTo ? localText(taf.validTo as Long) : "")
  sendChanged("forecastWorstCategory", taf.worstCategory)
  sendChanged("forecastMaxWindKnots", rounded(safeDouble(taf.maxWind, 0d), 0))
  sendChanged("forecastMaxGustKnots", rounded(safeDouble(taf.maxGust, 0d), 0))
  sendChanged("forecastWeather", taf.weather ?: "")
  sendChanged("forecastSummary", taf.summary)
  sendChanged("rawTaf", taf.raw ?: "")
}

private void publishConditions(Map conditions, Map metar, Map taf) {
  sendChanged("dataAgeMinutes", conditions.ageMinutes)
  String stateValue = "normal"
  if (conditions.stale) stateValue = "stale"
  else if (conditions.weather || categoryRank(metar.category) >= categoryRank("IFR")) stateValue = "warning"
  else if (conditions.categoryAlert || conditions.wind) stateValue = "advisory"
  sendChanged("aviationState", stateValue)
  String text = "${state.stationId} ${metar.category}; wind ${windText(metar)}; visibility ${formatNumber(metar.visibility)} mi"
  if (metar.ceiling != null) text += "; ceiling ${formatNumber(metar.ceiling)} ft"
  if (taf) text += "; forecast worst ${taf.worstCategory}"
  sendChanged("conditionSummary", text.take(500))
}

private Map parseMetar(def raw) {
  if (!(raw instanceof Map)) return null
  Long time = epochSeconds(raw.obsTime) ?: parseTime(raw.reportTime)
  String station = safeText(raw.icaoId, 8).toUpperCase()
  if (time == null || station != state.stationId) return null
  String category = normalizeCategory(raw.fltCat)
  Double visibility = parseVisibility(raw.visib)
  Double ceiling = ceilingFromClouds(raw.clouds, raw.vertVis)
  if (category == "UNKNOWN") category = categoryFromConditions(visibility, ceiling)
  return [time:time, category:category, name:safeText(raw.name, 160), latitude:finiteNumber(raw.lat), longitude:finiteNumber(raw.lon),
          elevation:finiteNumber(raw.elev), temperature:finiteNumber(raw.temp), dewpoint:finiteNumber(raw.dewp),
          windDirection:windDirectionText(raw.wdir), windSpeed:finiteNumber(raw.wspd), windGust:finiteNumber(raw.wgst),
          visibility:visibility, ceiling:ceiling, altimeter:finiteNumber(raw.altim), weather:safeText(raw.wxString, 120),
          cloudSummary:cloudText(raw.clouds), raw:safeText(raw.rawOb, 1000)]
}

private Map parseTaf(def raw) {
  if (!(raw instanceof Map)) return null
  Long from = epochSeconds(raw.validTimeFrom)
  Long to = epochSeconds(raw.validTimeTo)
  long cutoff = now() + settingNumber("forecastHours", 12d, 1d, 48d).longValue() * 3600000L
  List periods = raw.fcsts instanceof List ? raw.fcsts.collect { period -> parseForecastPeriod(period) }.findAll { it } : []
  periods = periods.findAll { it.from <= cutoff && it.to >= now() }
  String worst = periods ? periods.max { categoryRank(it.category) }.category : "UNKNOWN"
  double maxWind = periods ? periods.collect { safeDouble(it.windSpeed, 0d) }.max() : 0d
  double maxGust = periods ? periods.collect { safeDouble(it.windGust, 0d) }.max() : 0d
  String weather = periods.collect { it.weather }.findAll { it }.unique().join(" ")
  String summary = "Worst ${worst}; wind ${formatNumber(maxWind)} kt; gust ${formatNumber(maxGust)} kt"
  if (weather) summary += "; weather ${weather}"
  return [issueTime:parseTime(raw.issueTime), validFrom:from, validTo:to, worstCategory:worst,
          maxWind:maxWind, maxGust:maxGust, weather:weather.take(300), summary:summary.take(500), raw:safeText(raw.rawTAF, 1500)]
}

private Map parseForecastPeriod(def raw) {
  if (!(raw instanceof Map)) return null
  Long from = epochSeconds(raw.timeFrom)
  Long to = epochSeconds(raw.timeTo)
  if (from == null || to == null) return null
  Double visibility = parseVisibility(raw.visib)
  Double ceiling = ceilingFromClouds(raw.clouds, raw.vertVis)
  return [from:from, to:to, category:categoryFromConditions(visibility, ceiling), windSpeed:finiteNumber(raw.wspd),
          windGust:finiteNumber(raw.wgst), weather:safeText(raw.wxString, 120)]
}

private String categoryFromConditions(Double visibility, Double ceiling) {
  if ((ceiling != null && ceiling < 500d) || (visibility != null && visibility < 1d)) return "LIFR"
  if ((ceiling != null && ceiling < 1000d) || (visibility != null && visibility < 3d)) return "IFR"
  if ((ceiling != null && ceiling <= 3000d) || (visibility != null && visibility <= 5d)) return "MVFR"
  if (visibility != null || ceiling != null) return "VFR"
  return "UNKNOWN"
}

private Double ceilingFromClouds(def rawClouds, def rawVertical) {
  Double vertical = finiteNumber(rawVertical)
  if (vertical != null) return vertical
  if (!(rawClouds instanceof List)) return null
  List bases = rawClouds.findAll { cloud -> safeText(cloud?.cover, 10).toUpperCase() in ["BKN", "OVC", "VV"] }
    .collect { cloud -> finiteNumber(cloud?.base) }.findAll { it != null }
  return bases ? bases.min() as Double : null
}

private String cloudText(def rawClouds) {
  if (!(rawClouds instanceof List)) return ""
  return rawClouds.collect { cloud ->
    String cover = safeText(cloud?.cover, 10).toUpperCase()
    Double base = finiteNumber(cloud?.base)
    cover ? "${cover}${base == null ? "" : " ${formatNumber(base)} ft"}" : ""
  }.findAll { it }.join(", ").take(300)
}

private Double parseVisibility(def raw) {
  if (raw == null) return null
  String value = raw.toString().trim().replace("+", "")
  if (!value) return null
  if (value.contains("/")) {
    try {
      List parts = value.split("/")
      return (parts[0] as Double) / (parts[1] as Double)
    } catch (Exception ignored) { return null }
  }
  return finiteNumber(value)
}

private List weatherTokenList() {
  return (settings?.weatherTokens ?: "TS,FZ,+RA,+SN,FU,VA").toString().split(/[,;\s]+/)
    .collect { it.trim().toUpperCase() }.findAll { it }.unique().take(30)
}

private String normalizedStation() {
  String value = settings?.stationId?.toString()?.trim()?.toUpperCase()
  if (!(value ==~ /[A-Z0-9]{4}/)) throw new IllegalArgumentException("ICAO station identifier must contain exactly four letters or digits")
  return value
}

private List responseList(resp, String label) {
  if (resp == null || resp.hasError() || resp.status != 200) throw new IllegalArgumentException("${label} request failed (HTTP ${resp?.status ?: "unknown"})")
  def body = resp.getJson()
  if (!(body instanceof List)) throw new IllegalArgumentException("Unexpected ${label} response")
  return body as List
}

private boolean validCallback(def data) {
  return data?.token && data.token == state.inFlight && data.token == state.batch?.token && data.station == state.stationId
}

private String normalizeCategory(def raw) {
  String value = safeText(raw, 10).toUpperCase()
  return value in ["VFR", "MVFR", "IFR", "LIFR"] ? value : "UNKNOWN"
}

private int categoryRank(def raw) {
  return CATEGORY_RANK[normalizeCategory(raw)] as Integer
}

private String windDirectionText(def raw) {
  if (raw == null) return ""
  String value = raw.toString().trim().toUpperCase()
  if (value == "VRB") return "VRB"
  Double degrees = finiteNumber(value)
  return degrees == null ? safeText(value, 20) : "${Math.round(degrees)}°"
}

private String windText(Map metar) {
  String text = "${metar.windDirection ?: "variable"} ${formatNumber(metar.windSpeed)} kt"
  if (metar.windGust != null) text += " gust ${formatNumber(metar.windGust)}"
  return text
}

private Long epochSeconds(def raw) {
  Double value = finiteNumber(raw)
  return value == null ? null : (value.longValue() * 1000L)
}

private Long parseTime(def raw) {
  String value = raw?.toString()?.trim()
  if (!value) return null
  List formats = ["yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ssXXX"]
  for (String format : formats) {
    try { return Date.parse(format, value, TimeZone.getTimeZone("UTC")).time }
    catch (Exception ignored) { }
  }
  return null
}

private void emitAutomationEvent(Map event) {
  String text = safeText(event.text, 500)
  sendEvent(name:"pushed", value:event.button, isStateChange:true, descriptionText:text)
  sendEvent(name:"notificationText", value:text, isStateChange:true)
  sendEvent(name:"notificationType", value:event.type, isStateChange:true)
}

def clearHistory() {
  state.generation = (state.generation ?: 0L) + 1L
  state.inFlight = null
  state.batch = null
  state.previous = [:]
  state.hasBaseline = false
  refresh()
}

def testCategoryEvent() {
  emitAutomationEvent([button:1, type:"test", text:"TEST: Aviation flight-category event. No real restriction or condition is implied."])
}

def testWindEvent() {
  emitAutomationEvent([button:2, type:"test", text:"TEST: Aviation wind-threshold event. No real wind condition is implied."])
}

def testWeatherEvent() {
  emitAutomationEvent([button:3, type:"test", text:"TEST: Significant aviation-weather event. No real weather condition is implied."])
}

private Double settingNumber(String name, double fallback, double minimum, double maximum) {
  Double value = finiteNumber(settings?.get(name))
  if (value == null) value = fallback
  return Math.max(minimum, Math.min(maximum, value))
}

private Double optionalNumber(def raw) {
  return finiteNumber(raw)
}

private Double finiteNumber(def raw) {
  try {
    if (raw == null || raw.toString().trim() == "") return null
    double value = raw as Double
    return Double.isNaN(value) || Double.isInfinite(value) ? null : value
  } catch (Exception ignored) { return null }
}

private double safeDouble(def raw, double fallback) {
  Double value = finiteNumber(raw)
  return value == null ? fallback : value
}

private int safeInteger(def raw, int fallback) {
  try { return raw == null ? fallback : new BigDecimal(raw.toString()).intValue() }
  catch (Exception ignored) { return fallback }
}

private String safeText(def raw, int maximum) {
  if (raw == null) return ""
  return raw.toString().replaceAll(/[\u0000-\u001F\u007F]/, " ").replaceAll(/\s+/, " ").trim().take(maximum)
}

private String formatNumber(def raw) {
  Double value = finiteNumber(raw)
  return value == null ? "unavailable" : BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}

private def rounded(double value, int places) {
  return new BigDecimal(Double.toString(value)).setScale(places, BigDecimal.ROUND_HALF_UP)
}

private TimeZone hubTimezone() {
  return location?.timeZone ?: TimeZone.getTimeZone("UTC")
}

private String localText(long time) {
  return new Date(time).format("yyyy-MM-dd HH:mm:ss z", hubTimezone())
}

private def sendChanged(String name, value) {
  if (device.currentValue(name)?.toString() != value?.toString()) sendEvent(name:name, value:value)
}

private void recordError(String message) {
  sendChanged("watchStatus", "error")
  sendEvent(name:"lastError", value:safeText(message, 500))
  log.warn "Aviation Weather Watch: ${message}"
}
