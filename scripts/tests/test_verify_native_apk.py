import importlib.util
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location("verify_native_apk", Path(__file__).parents[1] / "verify_native_apk.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class NativeApkTest(unittest.TestCase):
    def archive(self, root, abi="arm64-v8a", machine=183, elf_class=2, skip=None, real=True, real_rpu=True):
        path = Path(root) / "test.apk"
        header = bytearray(64)
        header[:4] = b"\x7fELF"
        header[4:6] = bytes((elf_class, 1))
        struct.pack_into("<H", header, 18, machine)
        with zipfile.ZipFile(path, "w") as archive:
            for name in module.REQUIRED_BY_ABI[abi]:
                if name == skip:
                    continue
                if name == module.RPU_BUILDER:
                    # real_rpu: True for both markers, False for none, or the bytes to write.
                    if isinstance(real_rpu, bytes):
                        markers = real_rpu
                    else:
                        markers = b" ".join(module.RPU_BUILDER_MARKERS) if real_rpu else b"stub"
                    payload = header + markers
                else:
                    payload = header + (b"dovi-bridge-libdovi-capi-0.2 dovi_convert_rpu_with_mode" if real else b"stub")
                archive.writestr(f"lib/{abi}/{name}", payload)
        return path

    def test_complete_native_feature_set(self):
        with tempfile.TemporaryDirectory() as root:
            result = module.verify(self.archive(root), "arm64-v8a")
            self.assertEqual(5, len(result["nativeLibraries"]))
            self.assertIn("lib/arm64-v8a/libnuvio_rpu.so", result["nativeLibraries"])

    def test_armeabi_v7a_does_not_need_the_rpu_builder(self):
        with tempfile.TemporaryDirectory() as root:
            apk = self.archive(root, abi="armeabi-v7a", machine=40, elf_class=1)
            result = module.verify(apk, "armeabi-v7a")
            self.assertEqual(4, len(result["nativeLibraries"]))

    def test_missing_bridge_and_stub_are_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaisesRegex(ValueError, "Missing native"):
                module.verify(self.archive(root, skip="libdovi_bridge.so"), "arm64-v8a")
            with self.assertRaisesRegex(ValueError, "real libdovi"):
                module.verify(self.archive(root, real=False), "arm64-v8a")

    def test_missing_and_stub_rpu_builder_are_rejected_on_arm64(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaisesRegex(ValueError, "libnuvio_rpu.so"):
                module.verify(self.archive(root, skip=module.RPU_BUILDER), "arm64-v8a")
            with self.assertRaisesRegex(ValueError, "RPU builder"):
                module.verify(self.archive(root, real_rpu=False), "arm64-v8a")
            # Each marker on its own is not enough.
            for marker in module.RPU_BUILDER_MARKERS:
                with self.assertRaisesRegex(ValueError, "RPU builder"):
                    module.verify(self.archive(root, real_rpu=marker), "arm64-v8a")

    def test_wrong_machine_and_wrong_abi_are_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaisesRegex(ValueError, "architecture"):
                module.verify(self.archive(root, machine=40), "arm64-v8a")
            with self.assertRaisesRegex(ValueError, "Missing native"):
                module.verify(self.archive(root, abi="armeabi-v7a"), "arm64-v8a")

if __name__ == "__main__":
    unittest.main()
