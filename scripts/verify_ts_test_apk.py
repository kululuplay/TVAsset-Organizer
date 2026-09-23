"""Validate an isolated test APK; never publish or replace a production asset."""
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

app = Path('IptvPlayer/app')
build_config = (app/'build.gradle.kts').read_text()
version_match = re.search(r'versionName = "([0-9]+\.[0-9]+\.[0-9]+)"', build_config)
min_sdk_match = re.search(r'minSdk\s*=\s*(\d+)', build_config)
assert version_match, 'Missing release version'
assert min_sdk_match, 'Missing minimum Android SDK'
version = version_match.group(1) + '-preview1'
artifact_name = 'KululuIPTV-' + version
apk = app / 'build/outputs/apk/debug/app-debug.apk'
build_tools = Path(os.environ['ANDROID_HOME']) / 'build-tools'
sdk = max(
    (p for p in build_tools.iterdir() if p.is_dir() and re.fullmatch(r'\d+\.\d+\.\d+', p.name)),
    key=lambda p: tuple(map(int, p.name.split('.'))),
)
badging = subprocess.check_output([str(sdk/'aapt'), 'dump', 'badging', str(apk)], text=True)
assert "name='com.iptv.player.preview'" in badging
assert f"versionName='{version}'" in badging
assert f"sdkVersion:'{min_sdk_match.group(1)}'" in badging
assert "application-label:'Kululu IPTV Preview'" in badging
signature = subprocess.check_output([str(sdk/'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
assert 'Verified using v1 scheme (JAR signing): true' in signature
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature
subprocess.run([str(sdk/'zipalign'), '-c', '4', str(apk)], check=True)
with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None
    names = archive.namelist()
    abis = sorted({n.split('/')[1] for n in names if n.startswith('lib/') and n.endswith('.so')})
    assert {'armeabi-v7a', 'arm64-v8a'} <= set(abis)
    dex = b''.join(archive.read(n) for n in names if re.fullmatch(r'classes\d*\.dex', n))
    assert b'Landroidx/media3/exoplayer/hls/' not in dex
    assert b'Lcom/iptv/player/player/HlsLeaseRecovery;' not in dex
    assert b':demux=ts,none' in dex
    assert b':demux=mp4,mkv,avi,asf,ogg,flac,mpgv,mpga,ts,es,wav,aiff,au,rawdv,none' in dex
    assert 'javazoom/jl/decoder/sfd.ser' in names
suites = [ET.parse(p).getroot() for p in (app/'build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
counts = {key: sum(int(s.get(key, '0')) for s in suites) for key in ('tests', 'failures', 'errors', 'skipped')}
assert counts['tests'] > 0 and counts['failures'] == counts['errors'] == 0, counts
lint = ET.parse(app/'build/reports/lint-results-debug.xml').getroot()
lint_counts = {severity: sum(i.get('severity') == severity for i in lint.findall('issue')) for severity in ('Error', 'Fatal', 'Warning')}
assert not lint_counts['Error'] and not lint_counts['Fatal'], lint_counts
out = Path('ts-test-artifacts')
out.mkdir(exist_ok=True)
result = out/(artifact_name + '.apk')
shutil.copyfile(apk, result)
digest = hashlib.sha256(result.read_bytes()).hexdigest()
(out/'SHA256SUMS.txt').write_text(f'{digest}  {result.name}\n')
(out/'apk-badging.txt').write_text(badging)
(out/'apk-imza.txt').write_text(signature)
metadata = {'apk':result.name, 'bytes':result.stat().st_size, 'sha256':digest, 'package':'com.iptv.player.preview', 'version':version, 'abis':abis, 'tests':counts, 'test_classes':len(suites), 'lint':lint_counts, 'media3_hls_classes':False, 'commit':os.environ.get('GITHUB_SHA'), 'real_device_test':'Pending user Android stick test'}
(out/'dogrulama.json').write_text(json.dumps(metadata, indent=2)+'\n')
print(json.dumps(metadata))
if os.environ.get('GITHUB_OUTPUT'):
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as outputs:
        outputs.write(f'artifact_name={artifact_name}\n')
