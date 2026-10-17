"""Small reference-completeness regressions; run with Python unittest."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import osmium

from prepare_contour_osm import validate


class PreparationTest(unittest.TestCase):
    def test_nested_glacier_references_and_peak_tags(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output = root / "source.osm", root / "features.osm.pbf"
            source.write_text('''<osm version="0.6">
<node id="1" lat="1" lon="1"/><node id="2" lat="1" lon="2"/>
<node id="3" lat="2" lon="2"/><node id="4" lat="2" lon="1"/>
<node id="5" lat="1.5" lon="1.5"><tag k="natural" v="peak"/><tag k="ele" v="123 m"/><tag k="name" v="Test"/></node>
<node id="6" lat="0" lon="0"><tag k="amenity" v="bench"/></node>
<way id="10"><nd ref="1"/><nd ref="2"/><nd ref="3"/><nd ref="4"/><nd ref="1"/></way>
<relation id="20"><member type="way" ref="10" role="outer"/><tag k="type" v="multipolygon"/></relation>
<relation id="21"><member type="relation" ref="20" role="outer"/><tag k="natural" v="glacier"/></relation>
</osm>''')
            subprocess.run([sys.executable, str(Path(__file__).with_name("prepare_contour_osm.py")),
                            str(source), str(output)], check=True, capture_output=True)
            self.assertEqual(validate(output)["objects"], {"n": 5, "w": 1, "r": 2})
            tags = [(obj.tags.get("name"), obj.tags.get("ele")) for obj in osmium.FileProcessor(output)
                    if obj.is_node() and obj.id == 5]
            self.assertEqual(tags, [("Test", "123 m")])
            refused = subprocess.run([sys.executable, str(Path(__file__).with_name("prepare_contour_osm.py")),
                                      str(source), str(output)], capture_output=True)
            self.assertNotEqual(refused.returncode, 0)

    def test_missing_reference_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "incomplete.osm"
            source.write_text('<osm version="0.6"><way id="1"><nd ref="999"/></way></osm>')
            with self.assertRaisesRegex(ValueError, "Incomplete references"):
                validate(source)


if __name__ == "__main__":
    unittest.main()
