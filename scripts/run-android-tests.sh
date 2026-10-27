#!/usr/bin/env bash
# Run the real Room integration/migration suite against Android's SQLite engine.
set -euo pipefail

: "${ANDROID_HOME:?The Android SDK must be installed first.}"
sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
avdmanager="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
adb="$ANDROID_HOME/platform-tools/adb"
image='system-images;android-35;google_apis;x86_64'
report_dir='app/build/reports/android-emulator'
mkdir -p "$report_dir"

if [[ ! -e /dev/kvm ]]; then
    echo 'Android device tests cannot run: this runner does not expose /dev/kvm.' >&2
    echo 'Use a GitHub-hosted x64 Linux runner with KVM; device tests were not skipped or marked passed.' >&2
    exit 1
fi
if [[ ! -r /dev/kvm || ! -w /dev/kvm ]]; then
    sudo chown "$USER:$(id -gn)" /dev/kvm
    sudo chmod 660 /dev/kvm
fi
if [[ ! -r /dev/kvm || ! -w /dev/kvm ]]; then
    echo 'Android device tests cannot run: the runner user cannot access KVM.' >&2
    exit 1
fi

python3 - "$sdkmanager" "$ANDROID_HOME" "$image" <<'PY'
import subprocess
import sys
subprocess.run([sys.argv[1], '--sdk_root=' + sys.argv[2], 'emulator', sys.argv[3]],
               input='y\n' * 100, text=True, check=True, timeout=600)
PY

export ANDROID_AVD_HOME="${RUNNER_TEMP:-/tmp}/offer-point-avds"
export ANDROID_SERIAL='emulator-5554'
mkdir -p "$ANDROID_AVD_HOME"
printf 'no\n' | "$avdmanager" create avd --force --name offer-point-ci --package "$image" --device pixel_2

emulator_pid=''
capture_pid=''
cleanup() {
    if [[ -n "$capture_pid" ]]; then
        kill "$capture_pid" >/dev/null 2>&1 || true
        wait "$capture_pid" >/dev/null 2>&1 || true
    fi
    mkdir -p "$report_dir/customization-previews"
    timeout 10 "$adb" -s "$ANDROID_SERIAL" pull /sdcard/Android/data/com.offerpoint.pos/files/customization-previews/. "$report_dir/customization-previews/" >/dev/null 2>&1 || true
    timeout 10 "$adb" -s "$ANDROID_SERIAL" logcat -d > "$report_dir/logcat.txt" 2>&1 || true
    timeout 10 "$adb" -s "$ANDROID_SERIAL" emu kill >/dev/null 2>&1 || true
    if [[ -n "$emulator_pid" ]]; then
        kill "$emulator_pid" >/dev/null 2>&1 || true
        for ((attempt=0; attempt<10; attempt++)); do
            kill -0 "$emulator_pid" 2>/dev/null || break
            sleep 0.2
        done
        kill -9 "$emulator_pid" >/dev/null 2>&1 || true
        wait "$emulator_pid" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

"$ANDROID_HOME/emulator/emulator" -avd offer-point-ci -port 5554 \
    -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect \
    -accel on -cores 2 -memory 1536 -camera-back none -camera-front none \
    > "$report_dir/emulator.log" 2>&1 &
emulator_pid=$!
"$adb" start-server
if ! timeout 120 "$adb" -s "$ANDROID_SERIAL" wait-for-device; then
    tail -n 100 "$report_dir/emulator.log" >&2
    echo 'Android emulator was not detected by adb within 120 seconds.' >&2
    exit 1
fi

booted=false
for ((attempt=0; attempt<180; attempt++)); do
    if ! kill -0 "$emulator_pid" 2>/dev/null; then
        tail -n 100 "$report_dir/emulator.log" >&2
        echo 'Android emulator exited before boot completed.' >&2
        exit 1
    fi
    boot_state=$(timeout 5 "$adb" -s "$ANDROID_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') || true
    if [[ "$boot_state" == 1 ]]; then
        booted=true
        break
    fi
    sleep 2
done
if [[ "$booted" != true ]]; then
    tail -n 100 "$report_dir/emulator.log" >&2
    echo 'Android emulator did not complete boot within the bounded startup period.' >&2
    exit 1
fi

"$adb" -s "$ANDROID_SERIAL" shell input keyevent 82
"$adb" -s "$ANDROID_SERIAL" shell settings put global window_animation_scale 0
"$adb" -s "$ANDROID_SERIAL" shell settings put global transition_animation_scale 0
"$adb" -s "$ANDROID_SERIAL" shell settings put global animator_duration_scale 0
"$adb" -s "$ANDROID_SERIAL" logcat -c

# UTP removes the tested app at suite teardown. Copy synthetic UI captures while
# it is installed, using its debug UID rather than relying on shell storage access.
mkdir -p "$report_dir/customization-previews"
python3 - "$adb" "$ANDROID_SERIAL" "$report_dir/customization-previews" > "$report_dir/customization-collector.log" 2>&1 <<'PY' &
import subprocess
import sys
import time
from pathlib import Path

adb, serial, destination = sys.argv[1:]
folder = Path(destination)
names = ('dashboard-grid.png', 'checkout-dark.png')
while True:
    for name in names:
        output = folder / name
        if output.exists():
            continue
        try:
            result = subprocess.run([adb, '-s', serial, 'exec-out', 'run-as', 'com.offerpoint.pos', 'cat',
                '/sdcard/Android/data/com.offerpoint.pos/files/customization-previews/' + name],
                capture_output=True, timeout=8)
            image = result.stdout
            if result.returncode == 0 and image.startswith(b'\x89PNG\r\n\x1a\n') and image.endswith(b'\x00\x00\x00\x00IEND\xaeB`\x82'):
                output.write_bytes(image)
                print('Captured synthetic UI:', name, flush=True)
        except subprocess.TimeoutExpired:
            pass
    if all((folder / name).is_file() for name in names):
        break
    time.sleep(2)
PY
capture_pid=$!

# Application/test APKs were already compiled. Keep the test runner's heap bounded
# while the emulator is active on a standard hosted runner.
./gradlew --no-daemon --stacktrace --max-workers=2 \
    -Dorg.gradle.jvmargs='-Xmx1536m -Dfile.encoding=UTF-8' \
    :app:connectedLiveDebugAndroidTest

test -s "$report_dir/customization-previews/dashboard-grid.png"
test -s "$report_dir/customization-previews/checkout-dark.png"

python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
reports = list(Path('app/build/outputs/androidTest-results/connected').glob('**/TEST-*.xml'))
executed = 0
for report in reports:
    for suite in ET.parse(report).getroot().iter('testsuite'):
        if list(suite.iter('testsuite'))[1:]:
            continue
        executed += int(suite.get('tests', '0')) - int(suite.get('skipped', '0'))
if executed <= 0:
    raise SystemExit('No executed Android device tests were reported; this is not a passing device verification.')
print(f'Executed Android device tests: {executed}', flush=True)
PY
