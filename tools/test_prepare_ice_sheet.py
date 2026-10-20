"""Synthetic projection/hole regressions; no network or real datasets required."""
import io
from pathlib import Path
import tempfile
import unittest
import zipfile

import numpy as np
import shapefile
from shapely import get_srid
from shapely.geometry import Point

from prepare_ice_sheet import convert, project


class IceSheetPreparationTest(unittest.TestCase):
    def fixture(self, path, projection='PROJCS["Pseudo_Mercator",AUTHORITY["EPSG","3857"]]'):
        shp, shx, dbf = io.BytesIO(), io.BytesIO(), io.BytesIO()
        y = -6378137 * 2.4
        with shapefile.Writer(shp=shp, shx=shx, dbf=dbf) as writer:
            writer.field("id", "N")
            writer.poly([[(0,y),(0,y+100000),(100000,y+100000),(100000,y),(0,y)],
                         [(20000,y+20000),(80000,y+20000),(80000,y+80000),(20000,y+80000),(20000,y+20000)]])
            writer.record(1)
        with zipfile.ZipFile(path, "w") as archive:
            for extension, stream in (("shp", shp), ("shx", shx), ("dbf", dbf)):
                archive.writestr("ice." + extension, stream.getvalue())
            archive.writestr("ice.prj", projection)
        return y

    def test_projection_keeps_rock_holes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "ice.zip"
            y = self.fixture(path)
            geometry = convert(path)
            self.assertEqual(get_srid(geometry), 4326)
            self.assertEqual(len(geometry.geoms), 1)
            self.assertEqual(len(geometry.geoms[0].interiors), 1)
            x1, y1 = project(np.array([10000.]), np.array([y + 10000]))
            x2, y2 = project(np.array([50000.]), np.array([y + 50000]))
            self.assertTrue(geometry.covers(Point(x1[0], y1[0])))
            self.assertFalse(geometry.covers(Point(x2[0], y2[0])))
            self.assertAlmostEqual(geometry.bounds[2], 0.8983152841195214, places=10)

    def test_wrong_projection_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "wrong.zip"
            self.fixture(path, 'GEOGCS["WGS84"]')
            with self.assertRaisesRegex(ValueError, "3857"):
                convert(path)


if __name__ == "__main__":
    unittest.main()
