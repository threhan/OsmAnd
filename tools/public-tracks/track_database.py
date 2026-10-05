"""Build and validate this fork's public-track SQLite format (Python 3.9+)."""
import argparse
import json
import math
from pathlib import Path
import re
import sqlite3
import struct

TRACK_ID = re.compile(r'[A-Za-z0-9_.-]{1,120}\Z')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def coordinates(points):
    require(isinstance(points, (list, tuple)) and 2 <= len(points) <= 1_000_000,
            'Each segment needs 2..1000000 points')
    for p in points:
        require(isinstance(p, (list, tuple)) and len(p) == 2, 'Use exactly [longitude, latitude]')
        require(all(type(v) in (int, float) and math.isfinite(v) for v in p), 'Non-finite coordinate')
        require(-180 <= p[0] <= 180 and -90 <= p[1] <= 90, 'Coordinate out of range')
    return points


def length_m(points):
    total = 0.0
    for (x1, y1), (x2, y2) in zip(points, points[1:]):
        p1, p2 = math.radians(y1), math.radians(y2)
        h = math.sin((p2-p1)/2)**2 + math.cos(p1)*math.cos(p2)*math.sin(math.radians(x2-x1)/2)**2
        total += 2 * 6371008.8 * math.asin(math.sqrt(min(1.0, max(0.0, h))))
    return total


def build(source, destination):
    data = json.loads(Path(source).read_text(encoding='utf-8'))
    require(data.get('type') == 'FeatureCollection', 'Expected a GeoJSON FeatureCollection')
    features = data.get('features')
    require(isinstance(features, list) and features, 'No features supplied')
    destination = Path(destination)
    # Exclusive creation protects existing databases, including a live user database.
    with destination.open('xb'):
        pass
    try:
        db = sqlite3.connect(destination)
        try:
            db.executescript(Path(__file__).with_name('schema.sql').read_text(encoding='utf-8'))
            row_id = 0
            with db:
                for feature in features:
                    require(feature.get('type') == 'Feature', 'Expected Feature')
                    props, geometry = feature['properties'], feature['geometry']
                    track_id, name = props['track_id'], props['name']
                    require(isinstance(track_id, str) and TRACK_ID.fullmatch(track_id), 'Unsafe or empty track_id')
                    require(isinstance(name, str) and name.strip(), 'Missing track name')
                    db.execute('INSERT INTO track_metadata VALUES(?,?)', (track_id, name.strip()))
                    kind = geometry['type']
                    require(kind in ('LineString', 'MultiLineString'), 'Only LineString/MultiLineString supported')
                    parts = [geometry['coordinates']] if kind == 'LineString' else geometry['coordinates']
                    require(isinstance(parts, list) and parts, 'Empty route')
                    for points in parts:
                        coordinates(points)
                        row_id += 1
                        blob = struct.pack('<i', len(points)) + b''.join(struct.pack('<dd', *p) for p in points)
                        xs, ys = zip(*points)
                        db.execute('INSERT INTO segments VALUES(?,?,?,?)', (row_id, track_id, blob, length_m(points)))
                        db.execute('INSERT INTO segment_bounds VALUES(?,?,?,?,?)',
                                   (row_id, min(xs), max(xs), min(ys), max(ys)))
        finally:
            db.close()
        return validate(destination)
    except Exception:
        destination.unlink(missing_ok=True)
        raise


def validate(path):
    db = sqlite3.connect(Path(path).resolve().as_uri() + '?mode=ro', uri=True)
    try:
        require(db.execute('PRAGMA user_version').fetchone()[0] == 1, 'user_version must be 1')
        require(db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok', 'SQLite integrity check failed')
        index = [r[2] for r in db.execute('PRAGMA index_info(segment_bounds_cover)')]
        require(index == ['min_x', 'max_x', 'min_y', 'max_y', 'id'], 'Missing or wrong segment_bounds_cover index')
        tracks, rows, point_count = set(), 0, 0
        sql = ('SELECT s.id,s.track_id,s.geometry,s.length_m,b.min_x,b.max_x,b.min_y,b.max_y '
               'FROM segments s LEFT JOIN segment_bounds b ON b.id=s.id ORDER BY s.id')
        for row_id, track, blob, distance, west, east, south, north in db.execute(sql):
            require(isinstance(track, str) and TRACK_ID.fullmatch(track), f'Unsafe track ID at row {row_id}')
            require(isinstance(blob, bytes) and len(blob) >= 4, f'Missing geometry at row {row_id}')
            count = struct.unpack_from('<i', blob)[0]
            require(2 <= count <= 1_000_000 and len(blob) == 4 + 16*count, f'Invalid geometry size at row {row_id}')
            points = coordinates(list(struct.iter_unpack('<dd', blob[4:])))
            require(distance is not None and math.isfinite(distance) and distance >= 0, f'Invalid length at row {row_id}')
            require(all(v is not None and math.isfinite(v) for v in (west,east,south,north)), f'Missing bounds at row {row_id}')
            require(all(west <= x <= east and south <= y <= north for x,y in points), f'Bounds omit points at row {row_id}')
            tracks.add(track)
            rows += 1
            point_count += count
        require(not db.execute('SELECT 1 FROM segment_bounds b LEFT JOIN segments s ON s.id=b.id WHERE s.id IS NULL LIMIT 1').fetchone(), 'Orphan bounds')
        if db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='track_metadata'").fetchone():
            require(not db.execute('SELECT track_id FROM track_metadata GROUP BY track_id HAVING count(*)>1 LIMIT 1').fetchone(), 'Duplicate metadata IDs')
        return {'tracks': len(tracks), 'segments': rows, 'points': point_count, 'user_version': 1}
    finally:
        db.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    create = commands.add_parser('build')
    create.add_argument('geojson', type=Path)
    create.add_argument('output', type=Path)
    check = commands.add_parser('validate')
    check.add_argument('database', type=Path)
    args = parser.parse_args()
    try:
        result = build(args.geojson, args.output) if args.command == 'build' else validate(args.database)
        print(json.dumps(result, sort_keys=True))
    except (ValueError, KeyError, TypeError, OSError, sqlite3.Error) as error:
        parser.exit(1, f'Error: {error}\n')


if __name__ == '__main__':
    main()
