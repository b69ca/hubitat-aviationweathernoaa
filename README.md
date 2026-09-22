# Aviation Weather Watch (NOAA)

A Hubitat driver that monitors METAR observations and TAF forecasts for one airport through NOAA's Aviation Weather Center Data API. It exposes flight category, ceiling, visibility, winds, significant weather and forecast conditions to dashboards and Rule Machine.

There is no account, API key, subscription, location request, companion server or additional Hubitat library. Coverage is worldwide where NOAA's Aviation Weather Center has METAR or TAF data.

## Features

- Configures by four-character ICAO identifier, such as `KDEN`, `KSEA`, `EGLL` or `YSSY`.
- Reports the latest VFR, MVFR, IFR or LIFR category.
- Publishes temperature, dewpoint, visibility, ceiling, wind, gusts, altimeter, clouds and raw METAR.
- Decodes the selected TAF window to find its worst category, maximum wind, maximum gust and weather codes.
- Supports category, wind and significant-weather automation events.
- Detects stale METARs and optional recovery to normal conditions.
- Suppresses existing conditions on the first successful update by default.
- Keeps current METAR data usable when the TAF endpoint temporarily fails.

This driver is for situational awareness and home automation. It is not approved aviation weather equipment and must not be used for flight planning or operational decisions. Pilots must use authorized briefings and official sources.

## Install

1. In Hubitat, open **Drivers Code → New Driver**.
2. Import the [raw driver](https://raw.githubusercontent.com/b69ca/hubitat-aviationweathernoaa/main/AviationWeatherNOAA.groovy) and click **Save**.
3. Open **Devices → Add Device → Virtual**.
4. Select **Aviation Weather Watch (NOAA)** and name the device.
5. Enter a four-character ICAO station identifier.
6. Review the thresholds and click **Save Preferences**.

The device remains in `setup` and sends no request until preferences are saved or **Initialize** is deliberately pressed. The first successful update normally establishes a silent baseline.

## Preferences

| Preference | Default | Purpose |
| --- | --- | --- |
| ICAO station identifier | Required | Four letters or digits identifying the airport. |
| Check interval | 15 minutes | 10, 15, 30 or 60 minutes. |
| METAR stale after | 2 hours | Changes `aviationState` to `stale`. |
| Category threshold | IFR or worse | Button 1 threshold: MVFR, IFR or LIFR. |
| Forecast may trigger category | On | Includes the worst TAF category. |
| TAF forecast window | 12 hours | Considers forecast periods in the next 6–36 hours. |
| Sustained-wind threshold | Empty | Optional button 2 threshold in knots. |
| Gust threshold | 25 knots | Button 2 threshold for current or forecast gusts. |
| Forecast may trigger wind | On | Includes maximum TAF wind and gust. |
| Significant-weather codes | On | Enables button 3 for matching weather tokens. |
| Weather tokens | `TS,FZ,+RA,+SN,FU,VA` | Comma-separated, case-insensitive METAR/TAF fragments. |
| Forecast may trigger weather | On | Checks weather in TAF periods. |
| Notify stale | On | Uses button 3 when METAR age first exceeds the limit. |
| Notify initial state | Off | Optionally announces a qualifying initial condition. |
| Notify only on change | On | Suppresses repeated events until a condition appears or worsens. |
| Notify recovery | Off | Optionally uses button 1 when all monitored conditions clear. |
| Download TAF | On | Can be disabled for observation-only use. |

Weather tokens are fragments. `TS` matches thunderstorm codes such as `TSRA`; `FZ` matches freezing precipitation; `+RA` and `+SN` match heavy rain and snow; `FU` and `VA` match smoke and volcanic ash.

## Automation buttons

| Button | Meaning |
| --- | --- |
| 1 | Current or forecast flight-category threshold, or optional recovery. |
| 2 | Current or forecast sustained wind or gust threshold. |
| 3 | Matching significant weather or stale METAR data. |

One event is emitted per update. Significant weather takes precedence, followed by stale data, category deterioration and wind. `notificationText` explains the chosen event and `notificationType` identifies it.

In Rule Machine, trigger on a button and send the `notificationText` attribute to a notification device. The test commands emit marked `TEST:` events without changing real conditions or history.

**Clear History** invalidates any current request and establishes a fresh baseline on the next successful update.

## Observation attributes

| Attribute | Meaning |
| --- | --- |
| `watchStatus` | `setup`, `updating`, `ready`, `partial` or `error`. |
| `aviationState` | `unknown`, `normal`, `advisory`, `warning` or `stale`. |
| `stationId`, `stationName` | ICAO identifier and station name supplied by NOAA. |
| `stationLatitude`, `stationLongitude`, `stationElevationMeters` | Public airport metadata. |
| `observationTime`, `observationEpoch`, `dataAgeMinutes` | METAR timing and freshness. |
| `flightCategory` | `VFR`, `MVFR`, `IFR`, `LIFR` or `UNKNOWN`. |
| `temperatureC`, `dewpointC` | Air and dewpoint temperatures. |
| `windDirection`, `windSpeedKnots`, `windGustKnots` | Surface wind. |
| `visibilityMiles`, `ceilingFeet` | Decoded visibility and lowest BKN, OVC or vertical-visibility layer. |
| `altimeterHpa` | Altimeter setting as provided by the API. |
| `weather`, `cloudSummary`, `rawMetar` | Weather code, readable clouds and original report. |

## Forecast attributes

| Attribute | Meaning |
| --- | --- |
| `tafAvailable` | Whether a usable TAF was returned. |
| `tafIssueTime`, `tafValidFrom`, `tafValidTo` | Forecast timing. |
| `forecastWorstCategory` | Worst category calculated from forecast visibility and ceiling. |
| `forecastMaxWindKnots`, `forecastMaxGustKnots` | Maximums in the configured forecast window. |
| `forecastWeather` | Unique forecast weather strings in the window. |
| `forecastSummary`, `rawTaf` | Compact decoded summary and original TAF. |
| `conditionSummary` | Current category, wind, visibility, ceiling and forecast worst category. |

## Decoding notes

The driver uses the standard category boundaries:

| Category | Ceiling | Visibility |
| --- | --- | --- |
| LIFR | Below 500 ft | Below 1 statute mile |
| IFR | 500 to below 1,000 ft | 1 to below 3 miles |
| MVFR | 1,000 through 3,000 ft | 3 through 5 miles |
| VFR | Above those limits | Above 5 miles |

The worse of ceiling and visibility determines a calculated TAF category. NOAA's supplied METAR `fltCat` is used when present. `PROB`, `TEMPO` and other TAF periods inside the selected time window are included; the driver reports worst-case conditions rather than a time-sequenced flight briefing.

## Network and privacy

Requests send the public ICAO station identifier, result format and a narrow time window to `aviationweather.gov`. They include no home location, account, API key, Hubitat identifier, device name or automation data. NOAA receives the hub's public IP address and driver user-agent as part of normal HTTPS operation.

The API asks clients to keep requests limited in scope and frequency. This driver requests one station only and defaults to every 15 minutes.

## Troubleshooting

- `watchStatus = error`: no usable METAR was returned. Confirm the ICAO identifier and internet access.
- `watchStatus = partial`: the METAR is usable but TAF retrieval or parsing failed.
- `tafAvailable = no`: the station may not issue a TAF, TAF collection may be disabled, or the service may be temporarily unavailable.
- No initial automation event: existing conditions are intentionally used as the baseline by default.
- Category differs from another display: report timing, ceiling selection and forecast period handling can differ. Refer to the original METAR/TAF and official tools.

## Development

Run the simulated Hubitat suite with Groovy 2.4.21:

```sh
groovy tests/AviationWeatherNOAATest.groovy
```

Tests cover setup gating, request construction, first-run suppression, METAR and TAF decoding, event precedence, IFR/LIFR categories, fractional visibility, recovery, winds, stale data, partial TAF failure, validation, stale callbacks and JSON-safe state. GitHub Actions runs the same suite.

Data courtesy of the [NOAA Aviation Weather Center Data API](https://aviationweather.gov/data/api/). Source code is MIT licensed. Aviation Weather Watch is not affiliated with or endorsed by NOAA, FAA or Hubitat.
