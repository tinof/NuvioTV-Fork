import contextlib
import importlib.util
import io
import shutil
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("rpu_l1_compare", Path(__file__).parents[1] / "rpu_l1_compare.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

FIXTURES = Path(__file__).parents[2] / "native" / "rpu_builder" / "tests-fixtures"


def rpu(l1, refresh, l6=1000):
    """The parts of a `dovi_tool export` RPU the comparison reads."""
    return {
        "header": {"rpu_type": 2},
        "vdr_dm_data": {
            "scene_refresh_flag": refresh,
            "cmv29_metadata": {"ext_metadata_blocks": [
                {"Level1": {"min_pq": l1[0], "avg_pq": l1[1], "max_pq": l1[2]}},
                {"Level6": {"max_display_mastering_luminance": l6}},
            ]},
        },
        "rpu_data_crc32": refresh * 7 + l1[1],
    }


class RpuL1CompareTest(unittest.TestCase):
    def test_equal_l1_and_rest_pass_while_refresh_differences_are_only_reported(self):
        reference = [rpu((0, 1000, 3000), 1), rpu((0, 1000, 3000), 0), rpu((0, 1200, 3000), 1)]
        ours = [rpu((0, 1000, 3000), 1), rpu((0, 1000, 3000), 1), rpu((0, 1200, 3000), 1)]
        compared, l1, rest, refresh = module.compare(ours, reference)
        self.assertEqual((3, [], []), (compared, l1, rest))
        self.assertEqual([(1, 1, 0)], refresh)

    def test_l1_and_rest_differences_are_found_at_the_offset(self):
        reference = [rpu((0, 900, 3000), 1), rpu((0, 1000, 3000), 0), rpu((0, 1000, 3000), 0)]
        ours = [rpu((0, 1001, 3000), 0), rpu((0, 1000, 3000), 0, l6=4000)]
        compared, l1, rest, _ = module.compare(ours, reference, offset=1)
        self.assertEqual(2, compared)
        self.assertEqual([(1, (0, 1001, 3000), (0, 1000, 3000))], l1)
        self.assertEqual([2], [frame for frame, _, _ in rest])

    @unittest.skipUnless(shutil.which("dovi_tool"), "dovi_tool is not on PATH")
    def test_dovi_tool_files(self):
        same = FIXTURES / "profile_a.histogram.v40.RPU.bin"
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(0, module.main([str(same), str(same)]))
            # Another peak source: the same RPUs apart from L1 max.
            self.assertEqual(1, module.main([str(FIXTURES / "profile_a.max-scl.v40.RPU.bin"), str(same)]))


if __name__ == "__main__":
    unittest.main()
