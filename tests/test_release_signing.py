import base64
import os
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'tools/sign_release_apk.sh'
WORKFLOW = ROOT / '.github/workflows/android-build.yml'


class ReleaseSigningTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.dir = pathlib.Path(self.temp.name)
        self.input = self.dir / 'input.apk'
        self.input.write_bytes(b'unsigned fixture')
        self.output = self.dir / 'out' / 'signed.apk'
        self.tools = self.dir / 'tools'
        self.tools.mkdir()
        (self.tools / 'zipalign').write_text('''#!/bin/bash
set -e
if [[ "$1" == "-c" ]]; then exit 0; fi
cp "${@: -2:1}" "${@: -1}"
''')
        (self.tools / 'apksigner').write_text('''#!/bin/bash
set -e
if [[ "$1" == "verify" ]]; then
  [[ "${FAIL_VERIFY:-}" != 1 ]] || exit 1
  echo "Signer #1 certificate SHA-256 digest: $(printf '%064d' 0)"
else
  while [[ "$1" != "--out" ]]; do shift; done
  cp "${3}" "${2}"
fi
''')
        for binary in self.tools.iterdir():
            binary.chmod(0o755)
        self.env = os.environ.copy()
        self.env.update(EMA_SIGNING_KEYSTORE_BASE64=base64.b64encode(b'fixture').decode(),
                        EMA_SIGNING_STORE_PASSWORD='private', EMA_SIGNING_KEY_ALIAS='key',
                        EMA_SIGNING_KEY_PASSWORD='private')

    def sign(self):
        return subprocess.run(['bash', str(SCRIPT), str(self.input), str(self.output), str(self.tools)],
                              env=self.env, capture_output=True, text=True)

    def test_success_only_after_verification(self):
        result = self.sign()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.output.read_bytes(), self.input.read_bytes())
        self.assertNotIn('private', result.stdout + result.stderr)

    def test_missing_secret_or_invalid_signature_does_not_produce_output(self):
        del self.env['EMA_SIGNING_KEY_PASSWORD']
        self.assertNotEqual(self.sign().returncode, 0)
        self.assertFalse(self.output.exists())
        self.env['EMA_SIGNING_KEY_PASSWORD'] = 'private'
        self.env['FAIL_VERIFY'] = '1'
        self.assertNotEqual(self.sign().returncode, 0)
        self.assertFalse(self.output.exists())

    def test_rolling_release_only_uploads_verified_signed_outputs(self):
        workflow = WORKFLOW.read_text()
        verify = workflow.split('- name: Verify APK outputs', 1)[1].split('- name: Upload build diagnostics', 1)[0]
        self.assertIn('verify --verbose --print-certs "$DEBUG_APK"', verify)
        self.assertIn('verify --verbose --print-certs "$SIGNED_RELEASE_APK"', verify)
        rolling = workflow.split('- name: Publish APKs to the rolling release', 1)[1]
        self.assertNotIn('aem-publish-github-artifact', workflow)
        self.assertIn('Test release signing with a disposable CI key', workflow)
        self.assertIn('github.event_name != \'pull_request\'', rolling)
        self.assertIn('delete-asset "$tag" app-release-unsigned.apk', rolling)
        self.assertNotIn('gh release upload "$tag" ema-debug.apk app-release-unsigned.apk', rolling)
        self.assertIn('gh release upload "$tag" ema-release-signed.apk', rolling)


if __name__ == '__main__':
    unittest.main()
