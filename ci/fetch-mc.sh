#!/usr/bin/env bash
# fetch-mc.sh <mc-version> [fabric-loader-version]: download the official Minecraft client + libraries from Mojang
# (and Fabric's from maven.fabricmc.net) into mc/, then build aot-support/pufferfish-aot.jar.
# Assets (sounds/textures) are skipped: the build only needs the jars. Nothing here is committed or redistributed.
set -euo pipefail
cd "$(dirname "$0")/.."
source ci/log.sh
MC=${1:?mc version} FAB=${2:-}
step "fetch Minecraft $MC${FAB:+ + Fabric $FAB} (jars only)"
PF_NO_ASSETS=1 python3 tools/fetch.py "$MC" ${FAB:+--fabric "$FAB"}
# ASM for the builder patches + aot-support: always from the Fabric instance (vanilla has no ASM)
if [[ -z $FAB ]]; then PF_NO_ASSETS=1 python3 tools/fetch.py "$MC" --fabric "${PF_ASM_FABRIC:-0.20.1}"; fi
for i in mc/instances/*/launch.json; do
  log "$i: main=$(jq -r .mainClass "$i") jars=$(jq '.classpath|length' "$i")"
done
log "mc/: $(du -sh mc | cut -f1)"
