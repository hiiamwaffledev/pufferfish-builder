#!/usr/bin/env bash
# package.sh <name>: zstd + AES-256 (PBKDF2) encrypt the built object with secret PF_ARTIFACT_KEY → out/<name>.tar.zst.enc.
# The object contains compiled Minecraft code, so it is NEVER uploaded unencrypted (public repo artifacts are
# downloadable by any signed-in GitHub user). Decrypt locally with Pufferfish tools/ci-fetch.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
source ci/log.sh
NAME=${1:?name}
[[ -n ${PF_ARTIFACT_KEY:-} ]] || die "secret PF_ARTIFACT_KEY is not set; refusing to upload an unencrypted object"
step "package + encrypt $NAME"
OBJ=$(cat logs/object-path.txt)
REL=${OBJ#$PWD/out/$NAME/}
{
  echo "name=$NAME"; echo "object=$REL"; echo "commit=${GITHUB_SHA:-local}"; echo "run=${GITHUB_RUN_ID:-local}"
  echo "built=$(date -u +%FT%TZ)"; echo "object_sha256=$(sha256sum "$OBJ" | cut -d' ' -f1)"
} > out/$NAME/build-info.txt
# linux: the executable plus the JDK shared libs native-image writes next to it (libawt.so, ...)
FILES=("$REL"); [[ $NAME == *-linux ]] && mapfile -t FILES < <(cd out/$NAME && find . -maxdepth 1 -type f ! -name build-info.txt -printf '%P\n')
# runtime-mixin hooks: ids baked into the image must match what the phone bundles, so ship this build's exact
# hooks mod (generated stand-ins only) and id table with it
INST=${PF_INSTANCE:-}
if [[ -n $INST && -f baked/$INST/res/pufferfish-hooks.txt ]]; then
  mkdir -p out/$NAME/hooks
  cp baked/$INST/res/pufferfish-hooks.txt mc/instances/$INST/game/mods/pufferfish-hooks.jar out/$NAME/hooks/
  FILES+=(hooks/pufferfish-hooks.txt hooks/pufferfish-hooks.jar)
fi
tar -C out/$NAME -cf - build-info.txt "${FILES[@]}" | zstd -T0 -12 -q |
  openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt -pass env:PF_ARTIFACT_KEY -out out/$NAME.tar.zst.enc
rm -f "$OBJ" out/$NAME/*.so  # nothing unencrypted lingers in the workspace
{ cat out/$NAME/build-info.txt; echo "encrypted=$NAME.tar.zst.enc $(du -h out/$NAME.tar.zst.enc | cut -f1) sha256=$(sha256sum out/$NAME.tar.zst.enc | cut -d' ' -f1)"; } | tee out/artifact.txt
