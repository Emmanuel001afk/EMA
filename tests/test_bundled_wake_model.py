import hashlib
import io
import tempfile
import unittest
import zipfile
from pathlib import Path

from tools.verify_bundled_wake_model import EXPECTED_SHA256, LICENSES, MODEL_ROOT, REQUIRED, verify_apk


class BundledWakeModelTest(unittest.TestCase):
    def apk(self, root, *, missing_model=False, missing_file=False, missing_license=False):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as model:
            for name in REQUIRED[1:] if missing_file else REQUIRED:
                model.writestr(MODEL_ROOT + name, b"test model fixture, not a speech model")
        data = stream.getvalue()
        path = Path(root) / "fixture.apk"
        with zipfile.ZipFile(path, "w") as apk:
            if not missing_model:
                apk.writestr("assets/ema-wake-model.zip", data)
            if not missing_license:
                for name in LICENSES:
                    apk.writestr(name, "Test license notice")
        return path, hashlib.sha256(data).hexdigest()

    def test_complete_bundle_passes(self):
        with tempfile.TemporaryDirectory() as root:
            path, digest = self.apk(root)
            verify_apk(path, digest)

    def test_incorrect_checksum_fails(self):
        with tempfile.TemporaryDirectory() as root:
            path, _ = self.apk(root)
            with self.assertRaisesRegex(ValueError, "checksum"):
                verify_apk(path)

    def test_missing_model_file_fails(self):
        with tempfile.TemporaryDirectory() as root:
            path, digest = self.apk(root, missing_file=True)
            with self.assertRaises(KeyError):
                verify_apk(path, digest)

    def test_no_model_or_license_fails(self):
        for flag in ("missing_model", "missing_license"):
            with self.subTest(flag=flag), tempfile.TemporaryDirectory() as root:
                path, digest = self.apk(root, **{flag: True})
                with self.assertRaises(KeyError):
                    verify_apk(path, digest)

    def test_build_runtime_and_verifier_pin_the_same_model(self):
        root = Path(__file__).resolve().parents[1]
        for name in ("app/build.gradle.kts", "app/src/main/java/com/ema/assistant/EmaWakeModel.kt"):
            self.assertIn(EXPECTED_SHA256, (root / name).read_text())


if __name__ == "__main__":
    unittest.main()
