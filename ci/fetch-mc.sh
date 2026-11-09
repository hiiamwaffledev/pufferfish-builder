#!/usr/bin/env bash
# fetch-mc.sh <mc-version> [fabric-loader-version]: download the official Minecraft client + libraries from Mojang
# (and Fabric's from maven.fabricmc.net) into mc/. Assets (sounds/textures) are skipped: the build only needs jars.
# Fabric instances also get the mods in PF_MODS (space-separated URLs; default Fabric API) in game/mods.
# Nothing here is committed or redistributed.
set -euo pipefail
cd "$(dirname "$0")/.."
source ci/log.sh
MC=${1:?mc version} FAB=${2:-}
FAPI=${PF_FABRIC_API:-0.165.2+26.3}
step "fetch Minecraft $MC${FAB:+ + Fabric $FAB} (jars only)"
PF_NO_ASSETS=1 python3 tools/fetch.py "$MC" ${FAB:+--fabric "$FAB"}
# ASM for the builder patches + aot-support: always from the Fabric instance (vanilla has no ASM)
if [[ -z $FAB ]]; then PF_NO_ASSETS=1 python3 tools/fetch.py "$MC" --fabric "${PF_ASM_FABRIC:-0.20.1}"; fi
if [[ -n $FAB ]]; then
  step "mods for $MC-fabric-$FAB"
  M=mc/instances/$MC-fabric-$FAB/game/mods; mkdir -p "$M"
  for u in ${PF_MODS:-https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$FAPI/fabric-api-$FAPI.jar}; do
    curl -fsSL --retry 4 -o "$M/$(basename "$u")" "$u" || die "download failed: $u"
    log "$(basename "$u") $(du -h "$M/$(basename "$u")" | cut -f1) sha256 $(sha256sum "$M/$(basename "$u")" | cut -c1-16)"
  done
fi
for i in mc/instances/*/launch.json; do
  log "$i: main=$(jq -r .mainClass "$i") jars=$(jq '.classpath|length' "$i")"
done
log "mc/: $(du -sh mc | cut -f1)"
