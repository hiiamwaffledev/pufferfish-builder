#!/usr/bin/env bash
# hooks.sh <fabric-instance>: runtime-mixin hooks. HookGen turns every @Inject in the top mods (.modapi/mods, left by
# ci/modapi.sh) into a stand-in mixin baked into Minecraft that calls PfHooks.H[id]; on the phone RuntimeMixins puts the
# runtime mod's handler in that slot. Rebakes baked/<instance> with the hooks, dropping any hook Mixin rejects.
set -euo pipefail
cd "$(dirname "$0")/.."
source ci/log.sh
INST=${1:?instance}
[[ -d .modapi/mods ]] || die "no .modapi/mods (run ci/modapi.sh first)"
step "HookGen + bake with hooks (prune rejected hooks, max 4 rounds)"
rm -rf "baked/$INST-nohooks"; mv "baked/$INST" "baked/$INST-nohooks"
# baked mods (Fabric API) already have their mixins applied: no hooks for them
mkdir -p .modapi/hookmods
for j in .modapi/mods/*.jar; do [[ $(basename "$j") == fabric-api* ]] || ln -sf "$PWD/$j" .modapi/hookmods/; done
PF_HOOKS_WORK=.hooks tools/mixinhooks/hooks.sh "$INST" .modapi/hookmods "baked/$INST" 2>&1 | tee logs/hooks.log
[[ -f baked/$INST/res/pufferfish-hooks.txt ]] || die "bake has no pufferfish-hooks.txt"
log "hooks: $(wc -l < baked/$INST/res/pufferfish-hooks.txt) baked, $(wc -l < .hooks/exclude.txt 2>/dev/null || echo 0) excluded"
cp .hooks/bake-*.log .hooks/exclude.txt logs/ 2>/dev/null || true
rm -rf .modapi .hooks "baked/$INST-nohooks"
