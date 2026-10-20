from contextlib import closing
from pathlib import Path
import sqlite3
import tempfile
import unittest

from validate_memory_upgrade import compare_archives, historical_shard_seconds


class ArchiveComparisonTest(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.a=Path(self.temporary.name)/'a.mbtiles'
        self.b=Path(self.temporary.name)/'b.mbtiles'
        for path in (self.a,self.b):
            with closing(sqlite3.connect(path)) as db,db:
                db.execute('CREATE TABLE tiles(zoom_level,tile_column,tile_row,tile_data)')
                db.execute('INSERT INTO tiles VALUES(9,1,2,?)',(b'payload',))

    def test_all_raw_keys_and_payloads_match(self):
        self.assertEqual(compare_archives(self.a,self.b)['raw_identical_tiles'],1)

    def test_changed_payload_fails(self):
        with closing(sqlite3.connect(self.b)) as db,db:
            db.execute('UPDATE tiles SET tile_data=?',(b'changed',))
        with self.assertRaisesRegex(ValueError,'mismatch'):
            compare_archives(self.a,self.b)

    def test_missing_row_fails(self):
        with closing(sqlite3.connect(self.b)) as db,db:
            db.execute('DELETE FROM tiles')
        with self.assertRaisesRegex(ValueError,'mismatch'):
            compare_archives(self.a,self.b)

    def test_historical_interval_ignores_unfinished_retry(self):
        root=Path(self.temporary.name)
        monitor=root/'monitor/generation-0001';monitor.mkdir(parents=True)
        (monitor/'run.log').write_text(
            '2026-10-18 09:00:00 INFO Processing shard x45-y25 (21845 tiles)\n'
            '2026-10-18 09:00:02 INFO Processing area of world (21845 tiles)\n'
            '2026-10-18 09:05:58 INFO Completed in 0 days 0 hours 5 minutes\n'
            '2026-10-18 09:06:00 INFO Processing shard x46-y25 (21845 tiles)\n'
            '2026-10-18 09:06:01 INFO Processing area of world (21845 tiles)\n'
            '2026-10-18 09:06:05 INFO Completed in 0 days 0 hours 0 minutes\n'
            '2026-10-18 09:07:00 INFO Processing shard x45-y25 (21845 tiles)\n'
            '2026-10-18 09:07:01 INFO Processing area of world (21845 tiles)\n')
        self.assertEqual(historical_shard_seconds(root,'x45-y25'),356)

    def test_missing_historical_interval_fails(self):
        with self.assertRaisesRegex(ValueError,'No completed'):
            historical_shard_seconds(Path(self.temporary.name),'x45-y25')

if __name__=='__main__':unittest.main()
