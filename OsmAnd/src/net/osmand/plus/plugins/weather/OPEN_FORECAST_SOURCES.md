# ICON and GEM weather sources

The source selector adds DWD ICON Global (`icon_global`) and ECCC GEM Global
(`gem_global`). Data is requested explicitly by model from Open-Meteo. Neither
option silently falls back to GFS, ECMWF, or best-match forecasts.

## Behavior

- Map overlays sample a latitude/longitude grid covering the visible area, with
  at most 49 coordinates per request. Minimum latitude spacing is 0.125 degrees
  for ICON and 0.15 degrees for GEM; zooming out increases spacing. The caption
  states the approximate north/south spacing. This is a sampled visualization,
  not a replacement for full-resolution meteorological raster tiles.
- Temperature, sea-level pressure, wind speed, cloud cover and hourly total
  precipitation use colored cells and numeric legends. Contours interpolate
  cell edges. The wind animation switch displays static wind-direction arrows
  for these sources, not native particle animation. Forecast values follow the
  existing time slider. Missing values are not treated as zero or extrapolated.
- A tap on the map while the weather forecast panel is open, a weather widget
  tap, or the map-context action **Point weather forecast** opens hourly values
  at the selected coordinate. Times use the device timezone. Point details use
  explicit Celsius, m/s and mm units; widgets honor existing unit preferences.
- Source descriptions disclose coordinate transmission, internet requirements
  and non-commercial use. Attribution is also shown in map and point views.
- These sources use their own one-hour cache. Existing country/world forecast
  archives do not contain ICON/GEM. The offline card explains this distinction.
- Existing point-weather notification rules retain their original best-match
  Open-Meteo provider. Changing the map model does not edit saved alert rules.

## Saved forecast places

The drawer action **Saved forecast places** is available regardless of the
currently selected map model. Users can add the map center, or save a point from
its forecast dialog. Each of up to 100 places stores an independent UUID, name
(up to 80 characters), exact coordinate and ICON/GEM source. The editor supports
renaming, coordinate editing and source changes; deletion requires confirmation.
Renaming an existing place retains its ID and list position. Leaving coordinates
unchanged retains their original precision. Duplicate coordinates are allowed,
including differently named entries for model comparisons.

Data is stored in private SharedPreferences `saved_forecast_places`, separate
from notification rules. The manager loads names and coordinates without making
network requests; only opening a forecast fetches its data. A parsing/storage
failure is reported instead of silently discarding the saved list.

`SavedForecastPlacesTest` checks JSON round trips with names containing Unicode
and escaping, preservation of independent models at the same coordinate,
non-mutating rename/upsert semantics and invalid-coordinate rejection.

## Concurrency and resource bounds

`OpenForecastData` is owned by `WeatherHelper` for the application lifetime.
One worker owns memory/disk cache and request budgets; at most 12 tasks queue.
All callbacks return on the main thread. Map generations and widget request
tokens discard obsolete results after panning, time changes or model switches.
Map requests debounce for 1.2 seconds and widget requests for 1 second.
Timeouts are 15 seconds to connect and 20 seconds to read. HTTP 429 backs off
for five minutes. The app persists conservative budgets of 150 coordinates per
minute and 3,000 per UTC day, counting failed requests as well. Memory and disk
each retain at most 256 point forecasts. Responses are capped at 6 million chars.

## Validation

The comparison dialog (`ForecastComparisonUi`) requests all four explicit models
through Open-Meteo: `gfs_global`, `ecmwf_ifs025`, `icon_global`, `gem_global`.
This does not change native GFS/ECMWF map providers or map source settings.
It is available in the drawer, point actions, and saved-place actions. One shared
hourly epoch axis aligns four fixed model rows, with temperature, precipitation,
wind speed, cloud cover and sea-level pressure selectors. Missing values stay
blank (em dash), failed models show their own error, and retry uses the existing
bounded client/cache. Relative cell colors use a common scale across models.
The time zone, retrieval times, provider attribution, interpolation caveat and
possible differences from downloaded map forecast runs are disclosed in the UI.
Closing the dialog invalidates callbacks. Existing map selection and saved-place
records are unchanged. Device UI testing is pending while the global weather
download remains active.

`OpenForecastDataTest` exercises missing-vs-zero values, time boundaries,
truncated arrays, misaligned hours and unexpected units. It can be run on the
JVM using compiled Kotlin classes, Android stubs and the ordinary org.json jar.
Live batch probes for Beijing and Kathmandu returned 168 hours for each source
and the six requested fields. Diagnostic fixtures are local under
`D:/Osm/build/weather-source-probes`; they are not production bundled data.

Device validation on 2026-09-28 confirmed ICON/GEM colored map overlays, source
switching, day selection refreshing values, named point forecasts, and saved-place
create/rename/restart/delete behavior. Close-zoom cells must not be rejected based
on projected pixel width: a valid model cell can exceed the viewport. Seam checks
use center-relative longitude discontinuities instead. Explicit offline mode and
plugin disable/re-enable remain untested on the device.

## Provider references

- https://open-meteo.com/en/docs/dwd-api
- https://open-meteo.com/en/docs/gem-api
- https://open-meteo.com/en/pricing
- https://eccc-msc.github.io/open-data/msc-data/nwp_gdps/readme_gdps_en/

The hosted free API is for non-commercial use, requires attribution and has
rate limits. Commercial distribution needs an appropriate service arrangement.

## Saved-place seven-day overview (2026-09-29)

Selecting a place opens DailyForecastUi with its name, coordinates, model,
edit/delete actions, hourly forecast, four-model comparison, and seven daily
sections. Saved places now accept all four models; existing IDs and coordinates
remain intact. Deletion still requires confirmation.

OpenForecastData.requestDaily shares the hourly worker, bounded queue, and API
budget. It requests explicit daily temperature minima/maxima, rain, showers,
precipitation, maximum gust, and mean 10 m wind in the destination timezone.
Daily mean wind is converted to Beaufort force; gust remains distinct. Missing
data is unknown, never zero. Rain includes showers; total precipitation includes
snow. Seven calendar dates start at destination-local today, including across
daylight-saving changes; unavailable later dates are shown as unknown.

The separate daily-weather-v1 disk cache retains at most 400 snapshots. Same-day
snapshots under one hour old serve without a request; Refresh forces a request.
Network or budget errors retain snapshots up to seven days old, label their
retrieval time and the failure, and never repeat expired dates. This cache is
separate from GFS/ECMWF TIFF archives. First use requires network or an imported
matching point snapshot. The UI discloses attribution and coordinate submission.

DailyForecastTest covers units, array lengths, destination dates including DST,
missing data, rain versus snow, and wind-force boundaries. Local reproduction:
python D:/Osm/build/weather-daily-20260929/run_tests.py .
Daily aggregate definitions: https://open-meteo.com/en/docs .

Daily snowfall is requested as snowfall_sum (cm) and displayed separately from
rain and total precipitation (water equivalent). Positive amounts under 0.1 cm
are shown as less than 0.1 cm, never zero. Rain and snow on one date do not imply
simultaneous mixed precipitation. Missing snow fields in legacy snapshots remain
unknown; these snapshots refresh even within the ordinary freshness interval,
but remain usable for their other fields on request failure. A null snow value
is also unknown, distinct from a returned zero. Snowfall is not ground snow depth.

## Model meteogram comparison

ForecastComparisonUi uses a dark full-width sheet with native Basic/Clouds-Rain
and 1-hour/3-hour/daily controls, per-model hide/pin controls, and ForecastComparisonChart
panels. A single offset synchronizes horizontal drags across all panels, survives
mode/duration changes, and is clamped when shortening the range. Vertical scrolling
remains available. Model visibility/pinning uses independent preferences, never
the map source or saved-place records. A time-column tap opens all model values.

OpenForecastData.requestComparison has distinct compare7_<encoded-device-timezone>_ cache keys and requests
seven days in the device time zone; the display is limited to those seven dates.
Ordinary map/hourly requests remain seven days. Additional validated fields are
weather_code, is_day, snowfall, gusts and high/middle/low cloud cover. All models
retain their explicit API mapping; missing horizons stay missing. No other model
fills gaps. Four times the coordinate request budget is reserved for this larger
query. The parser accepts up to 384 aligned hours while retaining old snapshots.

Temperature and wind are point values. Precipitation and snowfall are sums of the
three hourly amounts ending at each column; gust is the maximum over those hours.
Any missing hour makes the aggregate unknown. Snow is cm, precipitation is mm
water equivalent; positive snow below 0.1 stays visible. Wind arrows point downwind.
Absolute temperature/color scales are shared by every panel. Cloud bands encode
coverage in broad model layers, not measured cloud heights, with a sea-level
pressure line. Wave controls are disabled because these sources do not supply
marine forecasts. Actual model resolutions are shown, not copied from Windy.

Windy screenshots were used as a visual reference; data remains Open-Meteo.
Reproduction scripts, reference screenshots, actual model responses and UI test
runner are local under D:/Osm/build/weather-windy-20260929. Tests include strict
three-hour aggregation, absent horizons and comparison field unit validation.


### Seven-day interval selector (2026-09-29)

The comparison display now covers seven local calendar dates including today,
with 1-hour, 3-hour (default) and daily columns instead of 5/15-day controls.
Hourly columns begin at the current interval boundary; daily columns begin at
local midnight. Calendar arithmetic handles DST. Switching interval resets the
shared horizontal position. Daily columns show max/min temperature, summed
precipitation/snow, mean wind and maximum gust; clouds/pressure use daily means.
Missing hourly data invalidates the aggregate instead of fabricating zero.
Daily weather icons use the highest supplied weather code and omit wind arrows
since a scalar mean direction is misleading. Old UTC comparison snapshots are isolated by the new cache prefix to avoid
missing the early hours of the first local day. Twenty-one targeted JVM tests pass, including daily aggregates and DST.


### Saved-place seven-day chart page

DailyForecastUi now opens the same chart sheet in single-source mode, defaulting
to daily columns and retaining edit/delete/comparison actions. DailyForecast
provider aggregates (including offline snapshots and retrieval labels) supply
the daily temperature range, precipitation/snow, mean wind and maximum gust.
Hourly comparison data supplies 1/3-hour modes and cloud/icon fields. Missing
hourly data does not erase daily aggregates. The detailed seven-day text view
is retained behind a daily-column tap for explicit rain/snow and Beaufort details.
Comparison hidden/pinned preferences do not affect single-source visibility.

### Native offline package cadence (2026-10-05)

Downloaded GFS/ECMWF packages can have coarser tail intervals than the native
core's fixed 1/3-hour alignment. CachedWeatherTimes indexes actual per-model,
per-zoom-4-tile timestamps on WeatherHelper's serialized cache executor. The
immutable index is republished after import/expiry and on entering offline mode;
its revision invalidates map providers and point widgets.

While disconnected, requests within a known interval of at most six hours use
the preceding available sample. The map labels the actual sample timestamp and
widgets append that timestamp when it differs from the selected hour. This is
an explicitly dated snapshot, not interpolation or a new forecast for that hour.
Requests outside known coverage, or across a larger missing interval, return no
data. Map/contour providers and widgets use local-only native requests offline;
online lookup retains the original requested time. Late widget responses for a
different source or selected time are discarded.

Map time selection uses the center tile's schedule; surrounding uncached tiles
remain unavailable. Network availability follows the application's connectivity
check, so a connected but unusable proxy is not treated as offline automatically.
This does not extend a package's forecast horizon, refresh an old package, or
change the independent Open-Meteo caches used by saved-place comparison pages.
