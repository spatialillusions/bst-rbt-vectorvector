import contextlib
import io
from pathlib import Path
import sqlite3
import tempfile
import unittest

from verify_shard_merge import verify


@contextlib.contextmanager
def connection(path):
    with contextlib.closing(sqlite3.connect(path)) as c:
        with c:
            yield c


class ShardMergeVerificationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.work = self.root / '.shards'
        (self.work / 'contours').mkdir(parents=True)
        self.rows = [(9, 4, 3, b'first'), (9, 2, 1, b'second')]
        for i, row in enumerate(self.rows):
            path = self.work / 'contours' / f'x{i}-y0.mbtiles'
            self.database(path, [row])
            stat = path.stat()
            (self.work / f'x{i}-y0.done').write_text(f'contours:{stat.st_size}:{stat.st_mtime_ns // 1000000}')
        self.database(self.root / 'contours.mbtiles', self.rows)

    def database(self, path, rows):
        with connection(path) as c:
            c.execute('CREATE TABLE tiles(zoom_level,tile_column,tile_row,tile_data)')
            c.execute('CREATE TABLE metadata(name,value)')
            c.execute('CREATE UNIQUE INDEX tile_index ON tiles(zoom_level,tile_column,tile_row)')
            c.executemany('INSERT INTO tiles VALUES(?,?,?,?)', rows)

    def check(self, expected=2):
        with contextlib.redirect_stdout(io.StringIO()):
            return verify(self.root, expected)

    def test_compares_all_payloads_in_copy_order(self):
        self.assertEqual(self.check()['raw_tiles_verified'], 2)

    def test_rejects_missing_completion_marker(self):
        (self.work / 'x0-y0.done').unlink()
        with self.assertRaisesRegex(ValueError, 'markers'):
            self.check()

    def test_rejects_changed_payload(self):
        with connection(self.root / 'contours.mbtiles') as c:
            c.execute("UPDATE tiles SET tile_data=? WHERE tile_column=2", (b'corrupt',))
        with self.assertRaisesRegex(ValueError, 'mismatch'):
            self.check()

    def test_rejects_extra_destination_row(self):
        with connection(self.root / 'contours.mbtiles') as c:
            c.execute('INSERT INTO tiles VALUES(9,8,7,?)', (b'extra',))
        with self.assertRaisesRegex(ValueError, 'extra'):
            self.check()

    def test_rejects_unexpected_shard_count(self):
        with self.assertRaisesRegex(ValueError, 'count'):
            self.check(3)


if __name__ == '__main__':
    unittest.main()
