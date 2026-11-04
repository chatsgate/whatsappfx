#!/bin/bash
# Packs the Mac download from a build: whatsappfx-mac.tar.gz (the app, the command line, the connector, install.sh) and its sha256.
# Only build outputs and our own scripts go in; nothing from a home folder, no key, no token.
set -e
OUT="${1:-ci-out/dist}"
rm -rf "$OUT"; mkdir -p "$OUT/whatsappfx-mac"
cp ui/target/whatsappfx.jar cli/target/whatsappcli.jar connector/wa-connector "$OUT/whatsappfx-mac/"
cp ci/mac-install.sh "$OUT/whatsappfx-mac/install.sh"
(cd "$OUT" && tar -czf whatsappfx-mac.tar.gz whatsappfx-mac && shasum -a 256 whatsappfx-mac.tar.gz > whatsappfx-mac.tar.gz.sha256 && rm -rf whatsappfx-mac)
ls -la "$OUT"
