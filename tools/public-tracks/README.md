# Build a public hiking-track database

This describes the **selectable public-track layer in this fork**. It is not
OsmAnd's standard `.obf` map format, a routing graph, a raster tile database, or
the weather cache. The reader is
[`PublicTracksStore.kt`](../../OsmAnd/src/net/osmand/plus/plugins/publictracks/PublicTracksStore.kt).

## Quick start

Python 3.9 or newer, with the standard library only. From the repository root:

```sh
python tools/public-tracks/track_database.py build tools/public-tracks/examples/synthetic-alps.geojson public-tracks.sqlite
python tools/public-tracks/track_database.py validate public-tracks.sqlite
python -m unittest discover -s tools/public-tracks -p "test_*.py"
```

Expected: **2 tracks, 3 segments, 10 points, user_version 1**. Output creation is
exclusive: an existing database is never overwritten. Change the destination
filename when rebuilding. Generated databases are local artifacts; do not add
your real map/track database to this repository.

## Two non-sensitive examples outside China

[`examples/synthetic-alps.geojson`](examples/synthetic-alps.geojson) contains
invented coordinates in the Swiss Alps region, around **46.5 N, 8.0 E**:

| ID | Geometry | Purpose |
| --- | --- | --- |
| `synthetic-alps-a` | One LineString, 4 points | A named continuous track |
| `synthetic-alps-b` | Two LineStrings, 3 points each | One named track with a real gap between its segments |

These are **format fixtures, not verified hiking routes**. They were constructed
for this repository, not extracted from a phone, account, forum or commercial
track database. They contain no personal journeys, dates, device identifiers or
third-party route names. Do not navigate them. Repository licensing applies.

## SQLite contract (version 1)

Use a normal SQLite 3 file named `public-tracks.sqlite`, with
`PRAGMA user_version = 1`. See the complete recommended DDL in
[`schema.sql`](schema.sql). Ordinary B-tree tables/indexes work on Android builds
without SQLite's optional R-tree module.

| Table / column | SQLite type | Meaning |
| --- | --- | --- |
| `segments.id` | INTEGER PRIMARY KEY | Unique segment ID; ascending order defines segment order within a track |
| `segments.track_id` | TEXT NOT NULL | Stable logical track ID; all parts of a track share this ID |
| `segments.geometry` | BLOB NOT NULL | Binary longitude/latitude points, described below |
| `segments.length_m` | REAL NOT NULL | Finite, non-negative segment length in meters |
| `segment_bounds.id` | INTEGER PRIMARY KEY | Exactly the corresponding segment's ID |
| `segment_bounds.min_x`, `max_x` | REAL NOT NULL | Minimum/maximum longitude over all points |
| `segment_bounds.min_y`, `max_y` | REAL NOT NULL | Minimum/maximum latitude over all points |
| `track_metadata.track_id` | TEXT PRIMARY KEY | One optional metadata record per track |
| `track_metadata.name` | TEXT | UTF-8 display name; null/blank names fall back to the track ID |

**Required named index:** `segment_bounds_cover(min_x,max_x,min_y,max_y,id)`.
The reader explicitly uses `INDEXED BY segment_bounds_cover`, so the name must
match. Also provide `segments(track_id,id)` for efficient full-track selection.
Every segment needs one bounds row. Bounds must contain every point; undersized
bounds make visible routes disappear from queries. Extra orphan rows are invalid.

The `track_metadata` table may be omitted for legacy unnamed databases. If
present, its IDs must be unique; duplicate metadata would duplicate joined rows.
Other importer bookkeeping tables are not needed by the layer. No timestamps,
elevations, user profiles or source credentials are stored in this format.

Use file-safe IDs matching `[A-Za-z0-9_.-]{1,120}`. This creator/validator enforces
that profile because the app includes track IDs in exported GPX filenames.
Names may contain non-ASCII text. Each input feature needs a unique track ID;
represent disconnected parts with MultiLineString, not repeated features.

## Geometry BLOB

All numbers are **little-endian**, with no padding or header beyond the count:

| Offset | Size | Value |
| --- | --- | --- |
| 0 | 4 bytes | Signed 32-bit point count `N`, from 2 to 1,000,000 |
| 4 + 16*i | 8 bytes | IEEE-754 float64 longitude of point i |
| 12 + 16*i | 8 bytes | IEEE-754 float64 latitude of point i |

Exact byte length: `4 + 16*N`. Coordinates are WGS84 decimal degrees,
**longitude first**, with longitude in [-180,180] and latitude in [-90,90].
NaN/infinity are invalid. Do not store GCJ-02/BD-09, tile indices, Web Mercator
meters, float32, WKB, WKT or a GeoJSON string in this field. Do not concatenate
separate segments: that invents a line across the gap. Split routes at the
antimeridian for this reader's ordinary longitude bounds.

Encoding a two-point segment in Python:

```python
import struct
points = [(8.0, 46.5), (8.001, 46.5005)]
blob = struct.pack('<i', len(points))
blob += b''.join(struct.pack('<dd', lon, lat) for lon, lat in points)
assert len(blob) == 36
```

The creator computes length using spherical great-circle distances (meters).
It accepts only two-dimensional GeoJSON LineString/MultiLineString coordinates.
Convert other formats/coordinate systems before using it. It is a reference
creator for small/medium inputs, not a streaming multi-gigabyte import service.
The validator checks schema version, integrity, the required spatial index,
binary point counts/coordinates, lengths and bounds; it cannot establish trail
access, traversability or accuracy. It permits legacy databases without names.

## Load into this fork

The layer reads **one active file** at
`<configured OsmAnd data directory>/public-tracks/public-tracks.sqlite`.
A common default for package `net.osmand.plus` is
`/sdcard/Android/data/net.osmand.plus/files/public-tracks/public-tracks.sqlite`;
other packages or storage settings use different roots. Copying a second file
under a different name does not add another layer.

Close/stop the app before replacing a live database, back up the existing file,
and copy the validated new database to the active filename. For this default
path, with an already authorized development phone:

```sh
adb shell am force-stop net.osmand.plus
adb pull /sdcard/Android/data/net.osmand.plus/files/public-tracks/public-tracks.sqlite public-tracks.backup.sqlite
adb shell mkdir -p /sdcard/Android/data/net.osmand.plus/files/public-tracks
adb push public-tracks.sqlite /sdcard/Android/data/net.osmand.plus/files/public-tracks/public-tracks.sqlite
```

Use a fresh backup filename and confirm the backup succeeded before replacement;
if no active file exists, no backup is needed. Close the database writer before
copying; checkpoint and close any WAL writer so the database is self-contained.
The included creator uses SQLite's normal rollback journal and closes its file.

Reopen this fork, enable the public-track plugin, center near 46.501 N, 8.002 E
and zoom to **11 or above**. Tap near 46.5005 N, 8.001 E to test overlapping names.
Rendering is thinned, while selection queries all matching tracks. Selecting B
loads both original segments; route planning offers a segment choice. Database
segments are not automatically merged into OsmAnd's routable road graph.

The fixtures should be used on a spare test installation. The commands above
replace the active layer, not merge it. To combine datasets, rebuild a single
database with unique segment IDs and collision-free track IDs.
