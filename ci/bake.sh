#!/usr/bin/env bash
# bake.sh <fabric-instance>: build runtime/pufferfish-runtime.jar, then boot Fabric (Knot + Mixin) once on the JVM and
# write the transformed closed world to baked/<instance>/ (classes/, res/, bake.json). No game window: BakeMain exits
# before the game starts.
set -euo pipefail
cd "$(dirname "$0")/.."
source ci/log.sh
INST=${1:?instance}
step "pufferfish-runtime.jar (BakeMain/FlatMain)"
tools/build-runtime.sh "$INST"
step "bake $INST ($(ls mc/instances/$INST/game/mods | tr '\n' ' '))"
python3 tools/launch.py "$INST" --bake "baked/$INST" > logs/bake.log 2>&1 || { tail -40 logs/bake.log; die "bake failed"; }
grep -E '^\[bake\]' logs/bake.log || { tail -40 logs/bake.log; die "bake printed no summary"; }
log "baked mods: $(paste -sd' ' baked/$INST/res/pufferfish-baked-mods.txt | cut -c1-400)"
