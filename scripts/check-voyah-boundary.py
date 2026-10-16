#!/usr/bin/env python3
"""Verify that Voyah's vehicle integration leaves the upstream CarPlay core intact."""
from pathlib import Path
import subprocess
import hashlib

BASE = 'e06a2502'
REVIEWED_VOYAH_INTEGRATION = {
    'shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt': '344a25a6e0fa7a62764a1ffeeb83343b9ae4d69be78d6f458ce834bdc0abf208',
    'shared/src/main/java/com/shilapi/xcertplay/media/MicrophoneUplink.kt': '9c6dce3e0cf8e3593bc86f47587468d850b041d7b9819a88ce6a17aac9ab0c8d',
    'shared/src/main/java/com/shilapi/xcertplay/media/MicrophoneCaptureStats.kt': '3656ffd4a4600b5e89da14c82e0cdde7b1b3da434d767c2b35da30c1056eee16',
    'shared/src/main/java/com/shilapi/xcertplay/media/VoyahAudioRouting.kt': 'c61a6d71d9a14431ff13ac63c0b3ab07e11ad6fbcb4402e974b9914d1d93d6a7',
    'shared/src/main/java/com/shilapi/xcertplay/media/NavigationAudioFocus.kt': '0743c451cd82477e24f86acdef6df5f1135c011af6c976bd541b46b1993217fe',
    'shared/src/test/java/com/shilapi/xcertplay/media/AudioRebufferCapacityTest.kt': '4ac73da84c5a8261bb3937f0adb8963416ec35b87daddb1abf3ce29bcde5be68',
    'shared/src/test/java/com/shilapi/xcertplay/media/VoyahAudioRoutingTest.kt': 'eb59d3290a6af28dd44f40afa14ead3cf0bd92c8f3a134320506d90fa0d69a05',
    'shared/src/test/java/com/shilapi/xcertplay/media/NavigationAudioFocusTest.kt': '282f30d9a54aff0913fb6c3fb0406747a21cc404b19a3dd962da9fd30b7877f4',
    'shared/src/main/java/com/shilapi/xcertplay/transport/VoyahVehicleSpeedSource.kt': '6656c7df60cf1c391dcc727e0673ad279cc7dc42eff449ffe1331ab64657b19d',
    'shared/src/test/java/com/shilapi/xcertplay/transport/VoyahVehicleSpeedSourceTest.kt': 'f68dfb3b88cce02943d8a299a224050eaf21efbe47ead8dd90ec9c0e2c1e4eeb',
    'shared/src/main/java/com/shilapi/xcertplay/adb/LocalAdb.kt': '9b88425703b38891093958cbbcd4c4ea4d20c90b817c6774afe09b82c2cba4c5',
    'shared/src/main/java/com/shilapi/xcertplay/media/AmbientMusicController.kt': '1d597b9efc1810dfe8689f077b3b0d26662946c448687691aabadcbaa025eb47',
    'shared/src/main/java/com/shilapi/xcertplay/media/AmbientMusicSettings.kt': '0c66ac930dc473c5a2a4bad5fc3e0cb511082cca842690b49748d20d19289c64',
    'shared/src/main/java/com/shilapi/xcertplay/network/WifiScanPause.kt': '1c230e4098920487f5cb5dc7fd6ad644af514fdede82051571aa88766ccd7998',
}

REVIEWED_BYD_GUARDS = {
    'common/src/main/java/com/shilapi/xcertplay/AdbClusterRouter.kt': '7db3dc2442d4ae0f06a527bdf49ebaf79da14cbcbfa629b51c9c44fd4d461b5d',
    'common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt': '9a0fb3d07bc5cef11d8c0d700251cebbca88c17da8aa9a5b3541ab0800f9d09d',
    'common/src/main/java/com/shilapi/xcertplay/ClusterMapPresentation.kt': '6134157a8cd074eb74525cd1a1b67d63e5de191f9f3c5d97eabef93e9ecd600b',
    'common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt': '8a4e24bf6084d12d0ca33d0ec74dca681364a35c5fa133517cf0b1d5c3a94ef0',
    'common/src/main/java/com/shilapi/xcertplay/NavigationWheelSettings.kt': 'f398cd52e963e59b804e6b18c0e2c3adf64acaacc619961e3b5faba06f2fb401',
    'shared/src/main/java/com/shilapi/xcertplay/VoyahPlatformPolicy.kt': '841d4d68c8dbe3f3db0a56584f509bbc099707e6e36be97dccc8e43f8ddfeeb5',
    'shared/src/main/java/com/shilapi/xcertplay/hud/BydBluetoothSuspend.kt': '0f6e607509a3822fa13cda73a77f9203d751ff9c9b73355915edd6cc2a6e7a17',
    'shared/src/main/java/com/shilapi/xcertplay/hud/BydBootStartRepair.kt': '8e3d17b2d890e180e228b54f204475fe131b7988410574bc7547562932eac7ed',
    'shared/src/main/java/com/shilapi/xcertplay/hud/BydCallPopupGuard.kt': '85faff49785b102429167cac7371e86d393e5be6b7fe75a5e67937d8890c1547',
    'shared/src/main/java/com/shilapi/xcertplay/hud/BydNavigationOutputs.kt': 'bc739def2120ba1974c91d6d1d98c75fe3a9b24d1b9c851684005ffad9949a06',
    'shared/src/main/java/com/shilapi/xcertplay/hud/BydOutputSettings.kt': 'bbb7686ddd3870009a65daaefcfa05851143f42c61c98af5fc31f1982e2df016',
    'shared/src/main/java/com/shilapi/xcertplay/hud/BydStandaloneHudOutput.kt': '40a6ed15ce1f57d2a012bbbd5bb2bf9784fe8d12122d907cf3e566963fb536d0',
    'shared/src/test/java/com/shilapi/xcertplay/hud/VoyahBydProtectionTest.kt': 'dc14746a6d806d5a6c0a3ef6bfeaeaa0a58d161e62e9f02dcfcf064d87305637',
}

ROOT = Path(__file__).resolve().parents[1]
PROTECTED = ['airplay', 'compat', 'iap2', 'location', 'media', 'mfi', 'network', 'orchestration', 'transport', 'telecom']

def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)

failures = []
count = 0
for kind in ['main', 'test']:
    for module in PROTECTED:
        prefix = f'shared/src/{kind}/java/com/shilapi/xcertplay/{module}/'
        baseline = set(git('ls-tree', '-r', '--name-only', BASE, '--', prefix).decode().splitlines())
        current = {str(p.relative_to(ROOT)) for p in (ROOT/prefix).rglob('*') if p.is_file()}
        allowed = {file for file in REVIEWED_VOYAH_INTEGRATION if file.startswith(prefix)}
        if (current - baseline) - allowed or baseline - current:
            failures.append(f'{prefix}: file set differs from upstream')
        for file in baseline | allowed:
            p = ROOT/file
            if file in REVIEWED_VOYAH_INTEGRATION:
                matches = p.is_file() and hashlib.sha256(p.read_bytes()).hexdigest() == REVIEWED_VOYAH_INTEGRATION[file]
            else:
                matches = p.is_file() and p.read_bytes() == git('show', f'{BASE}:{file}')
            if not matches:
                failures.append(f'{file}: differs from upstream')
            count += 1
for file, expected in REVIEWED_BYD_GUARDS.items():
    p = ROOT/file
    if not p.is_file() or hashlib.sha256(p.read_bytes()).hexdigest() != expected:
        failures.append(f"{file}: BYD guard differs from reviewed source")
for name in ['VoyahDebugTools', 'VoyahTestMode', 'VoyahRouteCapture']:
    for module in ['common', 'shared']:
        for p in (ROOT/module/'src').rglob('*'):
            if p.suffix in {'.kt', '.java'} and name in p.read_text():
                failures.append(f'{p.relative_to(ROOT)}: obsolete reference {name}')
if failures:
    raise SystemExit('\n'.join(failures))
print(f'PASS: {count - len(REVIEWED_VOYAH_INTEGRATION)} upstream files unchanged; {len(REVIEWED_VOYAH_INTEGRATION)} reviewed Voyah source/test files match; no obsolete references.')
