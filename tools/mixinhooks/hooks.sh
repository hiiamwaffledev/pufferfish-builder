#!/usr/bin/env bash
# hooks.sh <instance> <scan-mods-dir> <baked-out>: HookGen → bake → prune rejected hooks → repeat (max 4 rounds) until
# the bake has no hook failures. Leaves the final pufferfish-hooks.jar in the instance's mods/ and the bake in <baked-out>.
# Expects: graalvm/, mc/, runtime/pufferfish-runtime.jar, tools/launch.py; run from the repo root.
set -euo pipefail
INST=$1 SCAN=$2 OUT=$3
M=mc/instances/$INST/game/mods
ASM=$(ls mc/libraries/org/ow2/asm/asm/*/asm-*.jar | head -1):$(ls mc/libraries/org/ow2/asm/asm-tree/*/asm-tree-*.jar | head -1)
W=${PF_HOOKS_WORK:-.hooks}; mkdir -p "$W"; rm -f "$W/exclude.txt" "$M/pufferfish-hooks.jar"
graalvm/bin/javac -nowarn -cp "$ASM" -d "$W/cls" tools/mixinhooks/HookGen.java
# HookGen resolves against the flat classpath of a hook-less bake (MC + baked Fabric API)
[[ -f $OUT-nohooks/bake.json ]] || python3 tools/launch.py "$INST" --bake "$OUT-nohooks" > "$W/bake-nohooks.log" 2>&1
CP=$(python3 tools/launch.py "$INST" --flat "$OUT-nohooks" --print | jq -r '. as $c | ($c | index("-cp")) as $i | $c[$i+1]')
for round in 1 2 3 4; do
  graalvm/bin/java -cp "$ASM:$W/cls" HookGen "$W/pufferfish-hooks.jar" "$CP" "$SCAN" "$W/exclude.txt"
  cp "$W/pufferfish-hooks.jar" "$M/"
  python3 tools/launch.py "$INST" --bake "$OUT" > "$W/bake-$round.log" 2>&1 || { tail -30 "$W/bake-$round.log"; exit 1; }
  grep -E '^\[bake\]' "$W/bake-$round.log"
  r=$(python3 tools/mixinhooks/prune.py "$W/bake-$round.log" "$W/pufferfish-hooks.jar" "$W/exclude.txt"); echo "$r"
  [[ $r == *" 0 newly excluded"* ]] && break
done
