#!/usr/bin/env bash
# Dev loop for the push-notification pipeline.
#
#   scripts/testpush.sh install   # build + install debug, grant notif permission, launch
#   scripts/testpush.sh logs      # tail filtered logcat (kit + app push logs)
#   scripts/testpush.sh fire      # simulate a silent push (drain + notify) via adb broadcast
#   scripts/testpush.sh cold      # force-stop, then fire — proves cold-start wake
#
# The "fire"/"cold" paths need NO Firebase/Play services — they exercise the exact same
# processPendingMessages() + PushNotifier code that real FCM runs. For real FCM end-to-end,
# use a Google Play emulator image (or a device) and send from the Firebase console.
set -euo pipefail

APP_ID="com.obscuraapp.android"
RECEIVER="$APP_ID/com.obscura.app.TestPushReceiver"
ACTION="com.obscura.app.TEST_PUSH"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"

cd "$(dirname "$0")/.."

case "${1:-}" in
  install)
    ./gradlew :app:installDebug
    "$ADB" shell pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS || true
    "$ADB" shell am start -n "$APP_ID/com.obscura.app.MainActivity"
    echo "Installed + launched. Log in, add a friend, then: scripts/testpush.sh fire"
    ;;
  logs)
    "$ADB" logcat -c
    "$ADB" logcat -s ObscuraKit:V ObscuraApp:V
    ;;
  fire)
    "$ADB" shell am broadcast -n "$RECEIVER" -a "$ACTION"
    ;;
  cold)
    echo "Force-stopping $APP_ID to prove cold-start wake…"
    "$ADB" shell am force-stop "$APP_ID"
    sleep 1
    "$ADB" shell am broadcast -n "$RECEIVER" -a "$ACTION"
    ;;
  *)
    echo "usage: scripts/testpush.sh {install|logs|fire|cold}"
    exit 1
    ;;
esac
