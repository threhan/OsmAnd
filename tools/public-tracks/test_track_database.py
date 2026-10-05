import json
from contextlib import closing
from pathlib import Path
import sqlite3
import tempfile
import unittest

import track_database as tool


class TrackDatabaseTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.output = Path(self.folder.name) / 'public-tracks.sqlite'
        self.sample = Path(__file__).parent / 'examples/synthetic-alps.geojson'

    def test_examples_round_trip_names_and_segments(self):
        self.assertEqual({'tracks': 2, 'segments': 3, 'points': 10, 'user_version': 1},
                         tool.build(self.sample, self.output))
        with closing(sqlite3.connect(self.output)) as db, db:
            self.assertEqual(2, db.execute("SELECT count(*) FROM segments WHERE track_id='synthetic-alps-b'").fetchone()[0])
            self.assertIn('Synthetic Alps A', db.execute('SELECT name FROM track_metadata ORDER BY track_id').fetchone()[0])
            blob = db.execute('SELECT geometry FROM segments WHERE id=1').fetchone()[0]
            self.assertEqual(b'\x04\x00\x00\x00', blob[:4])

    def test_existing_output_is_preserved(self):
        self.output.write_bytes(b'existing user data')
        with self.assertRaises(FileExistsError):
            tool.build(self.sample, self.output)
        self.assertEqual(b'existing user data', self.output.read_bytes())

    def test_packaged_database_matches_geojson(self):
        packaged = self.sample.with_suffix('.sqlite')
        expected = tool.build(self.sample, self.output)
        self.assertEqual(expected, tool.validate(packaged))
        with closing(sqlite3.connect(packaged.resolve().as_uri() + '?mode=ro', uri=True)) as saved, \
                closing(sqlite3.connect(self.output)) as generated:
            for table, order in [('segments', 'id'), ('segment_bounds', 'id'), ('track_metadata', 'track_id')]:
                sql = f'SELECT * FROM {table} ORDER BY {order}'
                self.assertEqual(generated.execute(sql).fetchall(), saved.execute(sql).fetchall())

    def test_app_viewport_and_full_track_queries(self):
        tool.build(self.sample, self.output)
        with closing(sqlite3.connect(self.output)) as db:
            ids = db.execute('SELECT b.id FROM segment_bounds b INDEXED BY segment_bounds_cover '
                             'WHERE b.min_x<=? AND b.max_x>=? AND b.min_y<=? AND b.max_y>=? ORDER BY b.id',
                             (8.0011,8.0009,46.5006,46.5004)).fetchall()
            self.assertEqual([(1,), (2,)], ids)
            selected = db.execute('SELECT id FROM segments WHERE track_id=? ORDER BY id',
                                  ('synthetic-alps-b',)).fetchall()
            self.assertEqual([(2,), (3,)], selected)

    def test_missing_spatial_index_is_rejected(self):
        tool.build(self.sample, self.output)
        with closing(sqlite3.connect(self.output)) as db, db:
            db.execute('DROP INDEX segment_bounds_cover')
        with self.assertRaisesRegex(ValueError, 'index'):
            tool.validate(self.output)

    def test_invalid_input_leaves_no_database(self):
        data = json.loads(self.sample.read_text())
        data['features'][0]['geometry']['coordinates'][0] = [8, 100]
        source = Path(self.folder.name) / 'invalid.json'
        source.write_text(json.dumps(data))
        with self.assertRaises(ValueError):
            tool.build(source, self.output)
        self.assertFalse(self.output.exists())

    def test_wrong_bounds_are_rejected(self):
        tool.build(self.sample, self.output)
        with closing(sqlite3.connect(self.output)) as db, db:
            db.execute('UPDATE segment_bounds SET max_x=min_x WHERE id=1')
        with self.assertRaisesRegex(ValueError, 'Bounds omit'):
            tool.validate(self.output)

    def test_truncated_geometry_is_rejected(self):
        tool.build(self.sample, self.output)
        with closing(sqlite3.connect(self.output)) as db, db:
            db.execute("UPDATE segments SET geometry=x'04000000' WHERE id=1")
        with self.assertRaisesRegex(ValueError, 'geometry size'):
            tool.validate(self.output)

    def test_length_units(self):
        self.assertAlmostEqual(111195.08, tool.length_m([(0, 0), (1, 0)]), places=1)


if __name__ == '__main__':
    unittest.main()
