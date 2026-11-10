#!/usr/bin/env bash
# modapi.sh <instance>: API surface for runtime-loaded mods. Downloads the top PF_MODAPI_COUNT (default 150) Fabric mods
# for the instance's MC version from Modrinth, scans their bytecode (tools/modapi/ModApiScan.java) and writes
# aot/cfg-modapi/reachability-metadata.json, which keeps every MC / Fabric API / library member they use callable from
# Crema's interpreter. Fabric instances keep the jars in .modapi/mods for ci/hooks.sh (which deletes them).
set -euo pipefail
cd "$(dirname "$0")/.."
source ci/log.sh
INST=${1:?instance}
MC=$(jq -r .mc mc/instances/$INST/launch.json)
ASMD=mc/libraries/org/ow2/asm
ASM=$(ls $ASMD/asm/*/asm-*.jar | head -1):$(ls $ASMD/asm-tree/*/asm-tree-*.jar | head -1)
step "download top ${PF_MODAPI_COUNT:-150} Fabric mods for $MC (Modrinth)"
rm -rf .modapi && mkdir -p .modapi
python3 tools/modapi/fetch-top.py "$MC" "${PF_MODAPI_COUNT:-150}" .modapi/mods | tee logs/modapi-mods.txt | tail -3
step "scan mod bytecode → aot/cfg-modapi"
graalvm/bin/javac -nowarn -cp "$ASM" -d .modapi/b tools/modapi/ModApiScan.java
if [[ $INST == *-fabric-* ]]; then
  CP=$(python3 tools/launch.py "$INST" --flat "baked/$INST" --print | jq -r '. as $c | ($c | index("-cp")) as $i | $c[$i+1]')
else
  CP=$(jq -r '.classpath | join(":")' mc/instances/$INST/launch.json)
fi
graalvm/bin/java -cp "$ASM:.modapi/b" ModApiScan aot/cfg-modapi "$CP" .modapi/mods ${PF_MODAPI_EXTRA:-} | tee -a logs/modapi-mods.txt
cp aot/cfg-modapi/reachability-metadata.json logs/modapi-reachability-metadata.json
[[ $INST == *-fabric-* ]] && rm -rf .modapi/b || rm -rf .modapi
