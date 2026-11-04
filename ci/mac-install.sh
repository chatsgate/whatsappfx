#!/bin/sh
# Installs WhatsappFx for the current user on a Mac: no sudo, nothing outside the home folder. Run from the unpacked folder:  sh install.sh
# Needs Java 25 (a JDK or JRE; check with: /usr/libexec/java_home -v 25).
set -e
D="$(cd "$(dirname "$0")" && pwd)"
APP="$HOME/.local/share/whatsappfx"
BIN="$HOME/.local/bin"
DATA="$HOME/chatsgate/whatsappfx"
/usr/libexec/java_home -v 25 >/dev/null 2>&1 || { echo "Java 25 is not installed. Install a Temurin 25 JDK into your own folder or from adoptium.net, then run this again."; exit 1; }
mkdir -p "$APP" "$BIN" "$DATA/bin"
cp "$D/whatsappfx.jar" "$D/whatsappcli.jar" "$APP/"
cp "$D/wa-connector" "$DATA/bin/wa-connector"
chmod +x "$DATA/bin/wa-connector"
# a file that came through curl has no quarantine flag; one that came through a browser has, and Gatekeeper would refuse it
xattr -dr com.apple.quarantine "$APP" "$DATA/bin" 2>/dev/null || true
cat > "$BIN/whatsappfx" <<'LAUNCH'
#!/bin/sh
exec "$(/usr/libexec/java_home -v 25)/bin/java" --enable-native-access=ALL-UNNAMED -Xdock:name=WhatsappFx -Dapple.awt.application.name=WhatsappFx -jar "$HOME/.local/share/whatsappfx/whatsappfx.jar" "$@"
LAUNCH
cat > "$BIN/whatsappcli" <<'LAUNCH'
#!/bin/sh
exec "$(/usr/libexec/java_home -v 25)/bin/java" --enable-native-access=ALL-UNNAMED -jar "$HOME/.local/share/whatsappfx/whatsappcli.jar" "$@"
LAUNCH
chmod +x "$BIN/whatsappfx" "$BIN/whatsappcli"
echo "Installed. Start it with:  $BIN/whatsappfx --background"
case ":$PATH:" in *":$BIN:"*) ;; *) echo "(add $BIN to your PATH to type just 'whatsappfx')" ;; esac
