#!/bin/bash
# Mac smoke test, run by .github/workflows/mac.yml on an Apple-silicon runner after the build. It starts the real app on a scratch home with the
# test approval hook (App.testApprove: only with a scratch WHATSAPPFX_HOME and WHATSAPPFX_TEST_APPROVE=1), looks at it with the real
# `whatsappcli` through the real caller check, and takes screenshots. Every check prints "ok" or "FAIL"; the script ends non-zero if any failed.
# Output (logs, screenshots) goes to ci-out/ for the workflow to upload.
set +e
OUT="$PWD/ci-out"; mkdir -p "$OUT"
export WHATSAPPFX_HOME="${RUNNER_TEMP:-/tmp}/wa-smoke-home"
rm -rf "$WHATSAPPFX_HOME"; mkdir -p "$WHATSAPPFX_HOME/bin"
cp connector/wa-connector "$WHATSAPPFX_HOME/bin/wa-connector"
# the connector as a DOWNLOADED file would be: quarantined and not executable. The app must put both right (Connector.prepareForMac).
chmod -x "$WHATSAPPFX_HOME/bin/wa-connector"; xattr -w com.apple.quarantine "0083;5f000000;Safari;" "$WHATSAPPFX_HOME/bin/wa-connector" 2>/dev/null
echo '[{"id":"default","title":"Personal","colour":"#21c063"},{"id":"work","title":"Work","colour":"#58a6ff"}]' > "$WHATSAPPFX_HOME/accounts.json"
export WHATSAPPFX_TEST_APPROVE=1
FAILS=0
check() { if [ "$2" = "0" ]; then echo "ok    $1"; else echo "FAIL  $1"; FAILS=$((FAILS+1)); fi; }
shot() { screencapture -x "$OUT/$1.png" 2>>"$OUT/screencapture.err"; [ -s "$OUT/$1.png" ]; check "screenshot $1" $?; }
CLI() { java -jar cli/target/whatsappcli.jar "$@"; }

system_profiler SPSoftwareDataType SPDisplaysDataType 2>/dev/null | head -40 > "$OUT/system.txt"
sw_vers >> "$OUT/system.txt"; java -version 2>> "$OUT/system.txt"

# the runner is a VM whose paravirtual GPU lacks a Metal call JavaFX uses (NSInvalidArgumentException newArgumentEncoderWithLayout): software rendering
java --enable-native-access=ALL-UNNAMED -Dprism.order=sw -jar ui/target/whatsappfx.jar --background > "$OUT/app.out" 2>&1 &
APP=$!
for i in $(seq 1 60); do CLI accounts > "$OUT/accounts.json" 2>>"$OUT/cli.err" && break; sleep 1; done
grep -q '"account":"work"' "$OUT/accounts.json"; check "the app starts and answers whatsappcli accounts (two numbers)" $?
kill -0 $APP 2>/dev/null; check "the app process is still alive" $?
shot 01-started

# the tray / menu bar: the app logs why when there is none
cp "$WHATSAPPFX_HOME/wa.log" "$OUT/wa-after-start.log" 2>/dev/null
if grep -q "tray unavailable\|no system tray" "$WHATSAPPFX_HOME/wa.log"; then echo "info  TRAY: ABSENT or failed: $(grep -h 'tray' "$WHATSAPPFX_HOME/wa.log" | head -2)"; else echo "info  TRAY: no complaint in the log (the menu-bar icon itself is in the screenshots)"; fi
grep -q "Dock integration not set up" "$WHATSAPPFX_HOME/wa.log"; [ $? -ne 0 ]; check "Dock integration set up without a warning" $?
grep -q "TEST MODE" "$WHATSAPPFX_HOME/wa.log"; check "test approval hook active (scratch home)" $?
CNT=$(pgrep -f wa-connector | wc -l | tr -d ' '); [ "$CNT" -ge 2 ]; check "two connector processes run (the quarantined, non-executable file was fixed): $CNT" $?

# the real caller check (TcpPeer on macOS): status and a read go through the gate, the answer names the right number
CLI --account default status > "$OUT/status-default.json" 2>>"$OUT/cli.err"; grep -q '"account":"default"' "$OUT/status-default.json"; check "status through the caller check: default" $?
CLI --account work status > "$OUT/status-work.json" 2>>"$OUT/cli.err"; grep -q '"account":"work"' "$OUT/status-work.json"; check "status through the caller check: work" $?
CLI chats > "$OUT/chats-noflag.txt" 2>&1; grep -q "has 2 numbers" "$OUT/chats-noflag.txt"; check "no --account with two numbers is refused and lists them" $?
CLI --account work chats > "$OUT/chats-work.txt" 2>&1; ! grep -qi "cannot tell which program" "$OUT/chats-work.txt"; check "the caller was identified (no 'cannot tell which program')" $?
cat "$WHATSAPPFX_HOME/run/access.log" > "$OUT/access.log" 2>/dev/null; grep -q "number" "$OUT/access.log"; check "access.log names the number on each line" $?

# windows
CLI show --all > /dev/null 2>&1; sleep 4; shot 02-chats-windows
CLI --account work show --window settings > /dev/null 2>&1; sleep 3; shot 03-settings
sleep 22; shot 04-link-windows
CLI notify-test > /dev/null 2>&1; sleep 3; shot 05-notification-card
CLI show > "$OUT/show-chooser.json" 2>&1; sleep 2; shot 06-which-number-chooser
CLI --account work account-set --colour red > "$OUT/recolour.json" 2>&1; grep -q "e5534b" "$OUT/recolour.json"; check "recolour a number" $?
sleep 2; shot 07-after-recolour

# start at logon
CLI --account default autostart --set on > "$OUT/autostart.json" 2>&1
PL="$HOME/Library/LaunchAgents/io.github.chatsgate.whatsappfx.plist"
[ -f "$PL" ]; check "LaunchAgent plist written" $?
plutil -lint "$PL" > "$OUT/plutil.txt" 2>&1; check "LaunchAgent plist is valid" $?
cp "$PL" "$OUT/launchagent.plist" 2>/dev/null
CLI --account default autostart --set off > /dev/null 2>&1

cp "$WHATSAPPFX_HOME/wa.log" "$OUT/wa-final.log" 2>/dev/null
CLI quit > /dev/null 2>&1; sleep 12
kill -0 $APP 2>/dev/null; [ $? -ne 0 ]; check "quit stops the app" $?
echo "---- $FAILS check(s) failed"
exit $FAILS
