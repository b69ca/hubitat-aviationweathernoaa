// Run from the repository root with Groovy 2.4.21.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

def state = [:]
def settings = [stationId:'KDEN', categoryThreshold:'IFR', windGustThreshold:25, weatherTokens:'TS,FZ,+RA,+SN,FU,VA', notifyRecovery:true]
def readings = [:]
def events = []
def requests = []
def jobs = [:]
def warnings = []
long clock = 1790092800000L
def location = [timeZone:TimeZone.getTimeZone('America/Denver')]

def binding = new Binding([
  state:state, settings:settings, location:location,
  metadata:{ Closure ignored -> }, now:{ -> clock },
  device:[currentValue:{ String name -> readings[name] }, updateSetting:{ String name, Map value -> settings[name] = value.value }],
  log:[warn:{ message -> warnings << message.toString() }, debug:{ ignored -> }, info:{ ignored -> }],
  sendEvent:{ Map event -> events << event; readings[event.name] = event.value },
  runIn:{ seconds, handler, Map options = [:] -> jobs[handler] = [seconds:seconds, options:options] },
  unschedule:{ String handler = null -> if (handler) jobs.remove(handler) else jobs.clear() },
  runEvery1Hour:{ handler -> jobs[handler] = [every:3600] },
  schedule:{ expression, handler -> jobs[handler] = [cron:expression] },
  asynchttpGet:{ callback, params, data -> requests << [callback:callback, params:params, data:data] }
])

def driver = new GroovyShell(binding).parse(new File('AviationWeatherNOAA.groovy'))
driver.run()

def ok = { body -> [hasError:{ -> false }, status:200, getJson:{ -> body }] }
def failed = { int status -> [hasError:{ -> true }, status:status, getJson:{ -> null }] }
def iso = { long time -> new Date(time).format("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", TimeZone.getTimeZone('UTC')) }
def metar = { Map changes = [:] -> [[
  icaoId:'KDEN', obsTime:(changes.time ?: clock).intdiv(1000L), reportTime:iso(changes.time ?: clock),
  temp:changes.containsKey('temp') ? changes.temp : 14.4d, dewp:10d, wdir:changes.wdir ?: 280,
  wspd:changes.containsKey('wspd') ? changes.wspd : 8, wgst:changes.containsKey('wgst') ? changes.wgst : null,
  visib:changes.visib ?: '10+', altim:1022.8d, rawOb:changes.raw ?: 'METAR KDEN TEST', lat:39.8466d,
  lon:-104.6562d, elev:1656d, name:'Denver Intl, CO, US', fltCat:changes.cat ?: 'VFR',
  wxString:changes.wx ?: null, clouds:changes.clouds ?: [[cover:'FEW', base:12000]]
]] }
def taf = { Map changes = [:] -> [[
  icaoId:'KDEN', issueTime:iso(clock), validTimeFrom:clock.intdiv(1000L), validTimeTo:(clock + 86400000L).intdiv(1000L),
  rawTAF:'TAF KDEN TEST', fcsts:changes.periods ?: [[timeFrom:clock.intdiv(1000L), timeTo:(clock + 21600000L).intdiv(1000L),
    wdir:180, wspd:changes.containsKey('wspd') ? changes.wspd : 10, wgst:changes.containsKey('wgst') ? changes.wgst : null,
    visib:changes.visib ?: '6+', wxString:changes.wx ?: null, clouds:changes.clouds ?: [[cover:'SCT', base:5000]]]]
]] }
def complete = { List metarBody, def tafResponse = null ->
  def m = requests[-1]
  assert m.callback == 'metarResponse'
  driver.metarResponse(ok(metarBody), m.data)
  if (settings.fetchTaf != false) {
    def t = requests[-1]
    assert t.callback == 'tafResponse'
    driver.tafResponse(tafResponse ?: ok(taf()), t.data)
  }
}

driver.installed()
assert requests.empty
assert readings.watchStatus == 'setup'
assert readings.numberOfButtons == 3
driver.initialize()
assert jobs.refresh.cron == '0 */15 * ? * *'
assert requests[-1].params.uri == 'https://aviationweather.gov/api/data/metar'
assert requests[-1].params.query.ids == 'KDEN'
assert !requests[-1].params.toString().toLowerCase().contains('apikey')
complete(metar(), ok(taf([clouds:[[cover:'BKN', base:2500]], visib:'5'])))
assert readings.watchStatus == 'ready'
assert readings.stationId == 'KDEN'
assert readings.stationName.startsWith('Denver Intl')
assert readings.flightCategory == 'VFR'
assert readings.forecastWorstCategory == 'MVFR'
assert readings.aviationState == 'normal'
assert readings.windDirection == '280°'
assert readings.visibilityMiles.toString() == '10.0'
assert readings.ceilingFeet == 0
assert readings.tafAvailable == 'yes'
assert !events.any { it.name == 'pushed' }
assert state.hasBaseline
println 'PASS: setup gate, no-key METAR/TAF pipeline, baseline suppression and attributes'

clock += 900000L
driver.refresh()
complete(metar([cat:'IFR', visib:'2', wx:'TSRA', clouds:[[cover:'BKN', base:700]], wgst:30]),
  ok(taf([wx:'TSRA', clouds:[[cover:'BKN', base:800]], visib:'2', wgst:35])))
assert readings.flightCategory == 'IFR'
assert readings.aviationState == 'warning'
assert readings.weather == 'TSRA'
assert readings.ceilingFeet.toString() == '700'
assert readings.forecastWorstCategory == 'IFR'
assert readings.forecastMaxGustKnots.toString() == '35'
assert events.findAll { it.name == 'pushed' }[-1].value == 3
assert readings.notificationType == 'weather'
println 'PASS: significant-weather precedence, IFR parsing and forecast aggregation'

clock += 900000L
driver.refresh()
complete(metar([cat:'LIFR', visib:'1/2', wx:'TSRA', clouds:[[cover:'OVC', base:300]], wgst:30]),
  ok(taf([wx:'TSRA', clouds:[[cover:'OVC', base:300]], visib:'1/2', wgst:35])))
assert readings.flightCategory == 'LIFR'
assert readings.visibilityMiles.toString() == '0.5'
assert events.findAll { it.name == 'pushed' }[-1].value == 1
assert readings.notificationType == 'category'

clock += 900000L
driver.refresh()
complete(metar(), ok(taf()))
assert readings.aviationState == 'normal'
assert events.findAll { it.name == 'pushed' }[-1].value == 1
assert readings.notificationType == 'recovery'
println 'PASS: worsening category, fractional visibility and recovery event'

clock += 900000L
driver.refresh()
complete(metar([wspd:18, wgst:26]), ok(taf([wspd:20, wgst:32])))
assert events.findAll { it.name == 'pushed' }[-1].value == 2
assert readings.notificationType == 'wind'
assert readings.notificationText.contains('gust 32')

clock += 10800000L
driver.refresh()
complete(metar([time:clock - 10800000L]), ok(taf()))
assert readings.aviationState == 'stale'
assert readings.dataAgeMinutes == 180L
assert events.findAll { it.name == 'pushed' }[-1].value == 3
assert readings.notificationType == 'stale'
println 'PASS: current/forecast wind thresholds and stale-data event'

driver.testCategoryEvent()
assert events.findAll { it.name == 'pushed' }[-1].value == 1
driver.testWindEvent()
assert events.findAll { it.name == 'pushed' }[-1].value == 2
driver.testWeatherEvent()
assert events.findAll { it.name == 'pushed' }[-1].value == 3
assert readings.notificationText.startsWith('TEST:')

clock += 900000L
driver.refresh()
complete(metar(), failed(503))
assert readings.watchStatus == 'partial'
assert readings.lastError.contains('HTTP 503')
assert readings.tafAvailable == 'no'

int requestCount = requests.size()
def staleData = requests[-1].data
driver.metarResponse(ok(metar()), staleData)
assert requests.size() == requestCount

settings.stationId = 'bad airport'
clock += 900000L
driver.initialize()
assert readings.watchStatus == 'error'
assert readings.lastError.contains('exactly four')

def restored = new JsonSlurper().parseText(JsonOutput.toJson(state))
assert restored.previous instanceof Map
assert JsonOutput.toJson(state).length() < 15000
println 'PASS: test events, partial TAF failure, validation, stale callbacks and JSON-safe state'
println 'ALL CHECKS PASSED'
