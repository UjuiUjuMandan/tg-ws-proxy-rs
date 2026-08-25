#!/usr/bin/env bash
#
# Run the Android instrumentation suite on the emulator and pull back the APK
# the device was actually given.  Invoked from the `android` directory by the
# `android-emulator` job in .github/workflows/ci.yml.
#
# A file rather than lines in that job's `script:` block, for two reasons.
# reactivecircus/android-emulator-runner hands each line to `sh -c "<line>"`,
# so a line carrying double quotes of its own -- and the `adb pull "$(adb shell
# pm path ...)"` below needs them -- is cut short at the first inner quote and
# reaches sh as an unterminated command substitution.  And keeping both
# commands here puts them under this file's `set -euo pipefail` instead of
# leaving "did the suite actually pass?" resting on how the action chooses to
# execute the block.
set -euo pipefail

# leaveApksInstalledAfterRun: AGP uninstalls both APKs the moment the suite
# ends, and the caller's next step has to see what the device was given.
./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true

# The bytes, not a line out of Gradle's log: the log says which file AGP meant
# to send, the device says which one it got, and telling those two apart is the
# entire point of the check that follows this script.
paths="$(adb shell pm path "$APP_ID" | tr -d '\r' | sed -n 's/^package://p')"
if [[ -z "$paths" ]]; then
    echo "::error::pm path reported no installed APK for $APP_ID -- the suite" \
        "cannot have run against this build"
    exit 1
fi
# More than one path means a split install (base.apk plus configuration APKs),
# which is not what this job builds; pulling an arbitrary one of them would
# make the hash comparison downstream meaningless rather than wrong-looking.
if [[ "$(wc -l <<<"$paths")" -ne 1 ]]; then
    echo "::error::expected exactly one installed APK for $APP_ID, got:"
    echo "$paths"
    exit 1
fi
adb pull "$paths" "$RUNNER_TEMP/installed.apk"
