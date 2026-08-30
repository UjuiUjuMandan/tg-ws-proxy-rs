#!/usr/bin/env bash
#
# Run the Android instrumentation suite on the emulator. Invoked from the
# `android` directory by the `android-emulator` job in .github/workflows/ci.yml.
#
# A file rather than lines in that job's `script:` block, because
# reactivecircus/android-emulator-runner hands each line to `sh -c "<line>"`,
# and keeping commands here puts them under this file's `set -euo pipefail`
# instead of leaving "did the suite actually pass?" resting on how the action
# chooses to execute the block.
set -euo pipefail

# The emulator dies with the action's step, so anything the device knows has to
# be collected here -- a later workflow step has no adb to talk to. An
# instrumentation process that crashes natively leaves no assertion text in the
# Gradle report at all ("Instrumentation run failed due to Process crashed"),
# so without this the only evidence of a JNI-level fault is that it happened.
dump_device_state() {
    local dest="$RUNNER_TEMP/device-logs"
    mkdir -p "$dest"
    adb logcat -d -b crash -v threadtime > "$dest/logcat-crash.txt" 2>&1 || true
    adb logcat -d -b main -v threadtime > "$dest/logcat-main.txt" 2>&1 || true
    # Root is available on the emulator images this job uses; a tombstone
    # carries the faulting frame that logcat's crash buffer can truncate.
    if adb root > /dev/null 2>&1; then
        adb shell 'ls /data/tombstones 2>/dev/null' > "$dest/tombstone-list.txt" 2>&1 || true
        adb shell 'cat /data/tombstones/* 2>/dev/null' > "$dest/tombstones.txt" 2>&1 || true
    fi
    # Echo the crash buffer into the job log too: the artifact is the full
    # story, but a red job should say why without a download.
    if [[ -s "$dest/logcat-crash.txt" ]]; then
        echo "----- logcat -b crash (tail) -----"
        tail -n 120 "$dest/logcat-crash.txt"
        echo "----- end logcat -----"
    fi
    # Native aborts from the shim land in the main buffer under our own tag or
    # as an ART fatal signal; surface those lines specifically.
    grep -aE 'tg-ws-proxy|libtg_ws_proxy_jni|Fatal signal|art::|JNI DETECTED|dalvikvm' \
        "$dest/logcat-main.txt" | tail -n 80 || true
}

status=0
./gradlew :app:connectedDebugAndroidTest || status=$?

dump_device_state

if [[ "$status" -ne 0 ]]; then
    exit "$status"
fi
