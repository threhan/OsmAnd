# Source-aware offline weather cache

`WeatherForecastCacheStore` imports downloaded TIFF SQLite packages into the
native cache without changing the selected weather model. Both native models
are stored independently in `gfs_weather_tiffs.db` and
`ecmwf_weather_tiffs.db`. It runs on `WeatherHelper.cacheExecutor`, the same
executor used for expiry, so app-driven import and expiry are ordered.

The official China package contains overlapping tile coordinates/forecast times
for both `gfs` and `ecmwf`. The previous native `importDbCache` forwarded all rows
to the selected cache without a source predicate. The new importer filters
`source`, maps `forecastdate` to the native `s` key, and preserves a newer existing
`time`. Packages without a `source` column are treated as legacy GFS packages.
Each target has its own SQLite transaction; a failure can be retried idempotently.
No input package is removed. The per-package migration signature is committed
only after both targets finish, and includes the importer version, size and mtime.

The native expiry implementation can unlink every model database when the
selected model becomes empty. Android startup expiry now deletes only rows whose
forecast key predates local midnight, independently in each model's TIFF/raster
database. Database files are kept, including empty ones. Native user-requested
cache clearing remains separate from automatic startup expiry.

Native cache schema and metadata are kept compatible with the packaged native
library: geographic zoom 4, specified tiles `(x,y,z,s)`, image, time and timestamp.
SQLite statements use bound filenames/model names; malformed schemas are rejected
before target writes. Existing native readers use SQLite's transaction locking.

## Regression runner

`OsmAnd/test/java/net/osmand/plus/plugins/weather/WeatherCacheImportRunner.java`
is a standalone Android instrumentation runner targeting `net.osmand.plus`.
Compile against android.jar, package as a test APK signed with the same debug key,
and run its fully qualified class with `adb shell am instrument -w`.
The local build recipe and test manifest are retained under
`D:/Osm/build/weather-cache-fix-20260929/regression`.

It tests the installed importer with actual Android SQLite: model isolation at
the same coordinates/time, idempotent reimport, old/new version ordering, expiry
leaving an empty GFS database and future ECMWF record intact, legacy GFS input,
and malformed input preserving existing records. Scratch databases are confined
to a unique test directory below the app cache directory.

The separate local device runner exercises real China data through native
`ValueRequest.localData=true`, switches models repeatedly, and checks both cache
counts. It intentionally does not reimport data, so repeating it after process
restart tests persistence rather than concealing a lost cache.

## Read-only packages and device verification

Read-only input packages (including files pushed by adb) are staged once into a
private temporary file for SQLite attachment and deleted in finally. Input schema
is validated before staging. Equal timestamps are skipped; only strictly newer
records replace an existing tile, avoiding unnecessary large writes. Import and
prune entry points are synchronized as well as using the app cache executor.

On 2026-09-29 the installed ARM64 build passed all 13 Android SQLite regression
assertions, including read-only input. The actual China package retained 680 GFS
and 488 ECMWF records through repeated native localData-only requests and a new
process, without the probe reimporting data. The actual weather UI displayed
24.1 C GFS and 23.1 C ECMWF, matching native offline samples. GFS was restored.
Evidence: D:/Osm/build/weather-cache-fix-20260929/verification.json and
D:/Osm/logs/weather-cache-device-restart-20260929.log. This verifies China caches,
not completion of the World archive or offline support for ICON/GEM.
