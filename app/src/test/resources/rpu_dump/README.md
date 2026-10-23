# RPU dump fixture

`p81_v29_shots.RPU.bin` is `native/rpu_builder/tests-fixtures/p81_v29_shots.RPU.bin` unchanged (`dovi_tool
generate` 2.3.4, see that README): 7 AUs of Profile 8.1, CM v2.9, in three shots starting at AUs 0, 3 and 5,
so `scene_refresh_flag` is 1 on those three. `RpuDumpFixtureTest` plays it through `RpuDumpTap` and compares
the lines with `scripts/tests/fixtures/rpu_dump_p81_v29.logcat`, which `scripts/tests/test_rpu_dump_to_bin.py`
turns back into this file.
