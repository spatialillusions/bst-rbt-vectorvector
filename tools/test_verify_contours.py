"""Self-contained validation regressions; no reference or elevation downloads."""
from contextlib import closing
import gzip
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

import mapbox_vector_tile
from verify_contours import verify


class VerifyContoursTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'test.mbtiles'
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('CREATE TABLE metadata(name,value)')
            db.executemany('INSERT INTO metadata VALUES (?,?)', [('format','pbf'),('minzoom','9'),('maxzoom','9')])
            db.execute('CREATE TABLE tiles(zoom_level,tile_column,tile_row,tile_data)')
        self.store()

    def store(self, **properties):
        p = dict(ele=200,index=200,is_ice=0)
        p.update(properties)
        tile = mapbox_vector_tile.encode({'name':'contours','features':[{'geometry':{'type':'LineString','coordinates':[[0,0],[4096,4096]]},'properties':p}]})
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('DELETE FROM tiles')
            db.execute('INSERT INTO tiles VALUES (9,0,0,?)',(gzip.compress(tile),))

    def test_valid_compressed_tile(self):
        self.assertEqual(verify(self.path)['counts'], {'tiles':1,'contours':1})

    def test_invalid_index(self):
        self.store(index=20)
        with self.assertRaisesRegex(ValueError,'index'):
            verify(self.path)

    def test_invalid_interval(self):
        self.store(ele=100,index=100)
        with self.assertRaisesRegex(ValueError,'interval'):
            verify(self.path)

    def test_invalid_ice_flag(self):
        self.store(is_ice=2)
        with self.assertRaisesRegex(ValueError,'ice flag'):
            verify(self.path)

    def test_empty_archive(self):
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('DELETE FROM tiles')
        with self.assertRaisesRegex(ValueError,'No contour output'):
            verify(self.path)

    def test_duplicate_key(self):
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('INSERT INTO tiles SELECT * FROM tiles')
        with self.assertRaisesRegex(ValueError,'Duplicate'):
            verify(self.path)

    def test_out_of_range_key(self):
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('UPDATE tiles SET tile_column=512')
        with self.assertRaisesRegex(ValueError,'tile key'):
            verify(self.path)

    def test_example_style_has_no_external_assets(self):
        root = Path(__file__).resolve().parents[1]
        style = json.loads((root/'styles/contours/style.json').read_text())
        self.assertEqual(set(style['sources']), {'CONTOURS'})
        self.assertFalse({'sprite','glyphs'} & style.keys())
        self.assertTrue(all(layer.get('source','CONTOURS') == 'CONTOURS' for layer in style['layers']))
        config = json.loads((root/'styles/config.json').read_text())
        self.assertEqual(set(config['data']), {'CONTOURS'})

if __name__ == '__main__':
    unittest.main()
