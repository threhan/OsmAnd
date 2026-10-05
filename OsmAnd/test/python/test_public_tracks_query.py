"""SQLite integration checks using the public-track viewport SQL from Kotlin."""
from pathlib import Path
import re
import sqlite3
import unittest

SOURCE = (Path(__file__).resolve().parents[2] / 'src/net/osmand/plus/plugins/publictracks/PublicTracksStore.kt').read_text(encoding='utf-8')
ID_TEMPLATE = re.search(r'idSql = "(SELECT [^\n]+?FROM segment_bounds[^\n]+?)"', SOURCE).group(1)
EXCLUSION = re.search(r'val exclusion = [^\n]+?else "([^"]+)"', SOURCE).group(1)
ROW_TEMPLATE = re.search(r'val sql = "(SELECT [^\n]+?WHERE s.id IN[^\n]+?)"', SOURCE).group(1)

class PublicTrackQueryTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.executescript('''
        CREATE TABLE segments(id INTEGER PRIMARY KEY, track_id TEXT, geometry BLOB, length_m REAL);
        CREATE TABLE segment_bounds(id INTEGER PRIMARY KEY,min_x REAL,max_x REAL,min_y REAL,max_y REAL);
        CREATE INDEX segment_bounds_cover ON segment_bounds(min_x,max_x,min_y,max_y,id);
        CREATE TABLE track_metadata(track_id TEXT PRIMARY KEY,name TEXT);
        ''')
        for i in range(1, 2106):
            self.db.execute('INSERT INTO segments VALUES(?,?,?,?)',(i,str(i),b'geometry',100))
            self.db.execute('INSERT INTO segment_bounds VALUES(?,?,?,?,?)',(i,115,115.001,39,39.001))
            self.db.execute('INSERT INTO track_metadata VALUES(?,?)',(str(i),'Trail '+str(i)))

    def tearDown(self):
        self.db.close()

    def query(self, limit=None, names=True, bounds=(115.0006,115.0004,39.0006,39.0004), exclude=None):
        sql = ID_TEMPLATE + (EXCLUSION if exclude else '') + ' ORDER BY b.id'
        if limit is not None: sql += ' LIMIT ' + str(limit)
        ids = [row[0] for row in self.db.execute(sql, bounds + (exclude or ()))]
        result = []
        for start in range(0, len(ids), 64):
            batch = ids[start:start + 64]
            rows = ROW_TEMPLATE.replace('$nameColumn', 'm.name' if names else 'NULL').replace('$metadataJoin', ' LEFT JOIN track_metadata m ON m.track_id=s.track_id' if names else '').replace('$placeholders', ','.join('?' for _ in batch))
            result.extend(self.db.execute(rows, batch).fetchall())
        return result

    def test_batch_boundaries_preserve_all_original_rows_in_order(self):
        expected = self.db.execute('SELECT s.id,s.track_id,s.geometry,s.length_m,m.name FROM segments s LEFT JOIN track_metadata m ON m.track_id=s.track_id ORDER BY s.id').fetchall()
        self.assertEqual(expected, self.query())
        for limit in (1, 63, 64, 65, 128, 129, 2105, 2106):
            self.assertEqual(expected[:limit], self.query(limit=limit))

    def test_dense_viewport_and_hidden_candidates(self):
        drawing=self.query(limit=26)
        self.assertEqual(26,len(drawing))  # One extra row detects truncation.
        candidates=self.query()
        self.assertEqual(2105,len(candidates))  # Neither the 25 nor old 2000 drawing cap.
        self.assertEqual('Trail 2105',candidates[-1][4])
        self.assertNotIn(candidates[-1][0],{row[0] for row in drawing[:25]})
        self.assertEqual(drawing,self.query(limit=26))

    def test_legacy_database_without_names(self):
        self.db.execute('DROP TABLE track_metadata')
        result=self.query(names=False)
        self.assertEqual(2105,len(result))
        self.assertTrue(all(row[4] is None for row in result))

    def test_empty_location_does_not_match(self):
        self.assertEqual([],self.query(bounds=(116,115.9,40,39.9)))

    def test_prefetch_excludes_visible_candidates_without_losing_boundary_routes(self):
        for i, west, east in ((2106,115.02,115.03),(2107,115.001,115.03)):
            self.db.execute('INSERT INTO segments VALUES(?,?,?,?)',(i,str(i),b'geometry',100))
            self.db.execute('INSERT INTO segment_bounds VALUES(?,?,?,?,?)',(i,west,east,39,39.001))
        visible=(115.001,114.99,39.001,38.99)
        expanded=(115.05,114.9,39.05,38.9)
        first={r[0] for r in self.query(bounds=visible)}
        margin={r[0] for r in self.query(bounds=expanded,exclude=visible)}
        all_rows={r[0] for r in self.query(bounds=expanded)}
        self.assertIn(2107,first)
        self.assertEqual({2106},margin)
        self.assertFalse(first & margin)
        self.assertEqual(all_rows,first | margin)

if __name__ == '__main__': unittest.main()
