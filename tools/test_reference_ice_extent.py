"""Regression for RBT layers with different coordinate extents."""
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).parent / "rbt_reference"))
from compare import glacier_geometry, ice_agreement


def polygon_layer(extent):
    return {"extent": extent, "features": [{"geometry": {"type": "Polygon", "coordinates": [
        [[0, 0], [extent / 2, 0], [extent / 2, extent], [0, extent], [0, 0]]]
    }}]}


class ReferenceIceExtentTest(unittest.TestCase):
    def test_independent_polygon_extent_has_identical_ice_agreement(self):
        lines = {"extent": 4096, "features": [
            {"geometry": {"type": "LineString", "coordinates": [[1024, 100], [1024, 4000]]},
             "properties": {"ele": 100, "is_ice": 1}},
            {"geometry": {"type": "LineString", "coordinates": [[3072, 100], [3072, 4000]]},
             "properties": {"ele": 100, "is_ice": 0}}]}
        baseline = ice_agreement({"glacier_polygon": polygon_layer(4096)}, lines)
        high_extent = ice_agreement({"glacier_polygon": polygon_layer(16384)}, lines)
        self.assertEqual(baseline, high_extent)
        self.assertEqual(high_extent, [3900., 3900., 3900., 3900.])

    def test_invalid_extent_is_rejected(self):
        with self.assertRaises(ValueError):
            glacier_geometry(polygon_layer(0))


if __name__ == "__main__":
    unittest.main()
