#!/usr/bin/env bash
# Start/stop the CarDroid car-display helper (runs as shell UID via app_process from the installed APK).
# Usage: tools/car-display-helper.sh start|stop|status|logs   (ADB_SERIAL=<serial> to pick a device)
set -euo pipefail
PKG=com.cardroid.app
PORT=${HELPER_PORT:-27186}
LOG=/data/local/tmp/cardroid-helper.log
ADB=(adb); [ -n "${ADB_SERIAL:-}" ] && ADB=(adb -s "$ADB_SERIAL")
sh() { "${ADB[@]}" shell "$@" | tr -d '\r'; }

pid() { sh "pidof cardroid-helper" || true; }

case "${1:-}" in
  start)
    if [ -n "$(pid)" ]; then echo "helper already running (pid $(pid))"; exit 0; fi
    apk=$(sh "pm path $PKG" | sed -n 's/^package://p' | grep 'base\.apk$' | head -n1)
    [ -n "$apk" ] || { echo "$PKG not installed (pm path returned no base.apk)" >&2; exit 1; }
    uid=$(sh "cmd package list packages -U $PKG" | sed -n 's/.* uid:\([0-9]*\).*/\1/p' | head -n1)
    [ -n "$uid" ] || { echo "cannot resolve uid of $PKG" >&2; exit 1; }
    "${ADB[@]}" shell "CLASSPATH=$apk setsid nohup app_process /system/bin --nice-name=cardroid-helper com.cardroid.app.helper.HelperMain --port=$PORT --app-uid=$uid >$LOG 2>&1 </dev/null &"
    for _ in 1 2 3 4 5 6 7 8 9 10; do [ -n "$(pid)" ] && break; sleep 0.5; done
    sleep 1
    if [ -n "$(pid)" ]; then echo "helper started (pid $(pid), app uid $uid, apk $apk)"; else echo "helper failed to start:" >&2; sh "cat $LOG" >&2; exit 1; fi ;;
  stop)
    sh "pkill -f cardroid-helper" || true
    sleep 1
    if [ -n "$(pid)" ]; then echo "helper still running (pid $(pid))" >&2; exit 1; else echo "helper stopped"; fi ;;
  status)
    if [ -n "$(pid)" ]; then echo "running (pid $(pid))"; else echo "not running"; exit 1; fi ;;
  logs) sh "cat $LOG" ;;
  *) echo "usage: $0 start|stop|status|logs" >&2; exit 2 ;;
esac
