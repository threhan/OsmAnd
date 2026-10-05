# Point weather alerts

Entry points: the weather plugin adds **Point weather alerts** to the map context menu and main drawer. A new rule uses the selected point or current map center. Existing rules can be edited, disabled, re-enabled, or deleted. Rules are independent of navigation and do not require device location permission.

The first version supports temperature at or below a threshold (Celsius), wind speed at or above a threshold (m/s), hourly liquid rain including showers (mm), and hourly snowfall (cm). Any selected condition can trigger a notification ahead of the selected time interval. The editor uses device-local dates and whole hours, stores UTC epoch milliseconds, and limits the end to seven days ahead. Up to 20 rules are retained, including expired rules.

## Data and scheduling

The background provider is the Open-Meteo Forecast API, best-match model selection: https://open-meteo.com/en/docs . Requests send only the selected latitude/longitude and forecast options, over HTTPS; no account, device identifier, track, or current location is sent. The editor discloses the provider and coordinate transfer. Attribution is displayed in the UI (Open-Meteo, CC BY 4.0). This may differ from OsmAnd's GFS/ECMWF weather overlay. The existing native overlay's value manager depends on renderer initialization, so it is not used by this job.

Android JobScheduler requests network-connected checks approximately hourly, with a separate immediate job for Save / Check now. Periodic jobs persist across reboot. Execution timing remains subject to Android background restrictions and force-stop. There is no exact alarm or permanent foreground service. No requests are scheduled without an enabled, unexpired rule.

Rules and status live in private `weather_point_alerts` SharedPreferences. Rule revisions prevent a response from notifying after the rule has been edited, disabled, or deleted. Each condition notifies at most once per rule revision; editing and re-enabling reset this state. Conditions are only marked notified after notifications are permitted and posting succeeds. A notification opens its point through the existing GeoIntentActivity.

Hourly rain/snow amounts describe the preceding hour, so the bucket ending at the interval's start is excluded. The core evaluator checks boundaries, skips non-finite values, excludes past forecast hours, and checks coverage before reporting no match. Missing data, HTTP failure, and blocked notifications have distinct states. The last successful retrieval time is preserved after failures, with an age warning after six hours. Retrieval time is deliberately not described as model generation time: `generationtime_ms` is API computation duration, not forecast age. This version cannot independently establish upstream model freshness.

## Validation

`WeatherAlertEvaluatorTest` covers inclusive thresholds, UTC interval boundaries, past-hour exclusion, rain bucket boundaries, missing values, incomplete coverage, and intervals across midnight. It can run as a focused JUnit 4 test independently of Android. For device validation, use a clearly named temporary rule with a threshold expected to match a real forecast, verify the persisted status and notification destination, then delete the test rule. A second check must not repost an already-notified condition.

No existing public-track, map, terrain, or offline-weather database is migrated by this feature.
