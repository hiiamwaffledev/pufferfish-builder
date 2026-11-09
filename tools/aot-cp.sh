#!/usr/bin/env bash
# aot-cp.sh <instance-id>  → prints the AOT-ready classpath (sanitized jars + aot-support substitutions)
#  - joml: legacy package-info.class (pre-Java5 classfiles) breaks the image builder
#  - lwjgl core: drop Java 25 FFM overlay (generates hidden classes at runtime) -> plain JNI path
set -euo pipefail
cd "$(dirname "$0")/.."
INST=$1
CP=${PUFFERFISH_CP:-$(jq -r '.classpath | join(":")' mc/instances/$INST/launch.json)}
mkdir -p aot/jars
sanitize() { # jar -> echo path to use
  local j=$1 b; b=$(basename "$j")
  local s=$PWD/aot/jars/$b
  case "$b" in
    joml-*.jar) [[ -f $s ]] || { cp "$j" "$s"; chmod u+w "$s"; zip -qd "$s" '*package-info.class' >&2; }; echo "$s";;
    lwjgl-3*.jar) [[ $b == *natives* ]] && { echo "$j"; return; }
      [[ -f $s ]] || { cp "$j" "$s"; chmod u+w "$s"; zip -qd "$s" 'META-INF/versions/*' >&2
        (cd aot/jars && unzip -p "$b" META-INF/MANIFEST.MF | grep -v '^Multi-Release' > MANIFEST.MF.tmp && mkdir -p m/META-INF && mv MANIFEST.MF.tmp m/META-INF/MANIFEST.MF && (cd m && zip -q "../$b" META-INF/MANIFEST.MF) && rm -r m) >&2; }
      echo "$s";;
    *) echo "$j";;
  esac
}
NEWCP=()
IFS=: read -ra PARTS <<< "$CP"
for j in "${PARTS[@]}"; do NEWCP+=("$(sanitize "$j")"); done
echo "$(IFS=:; echo "${NEWCP[*]}"):$PWD/aot-support/pufferfish-aot.jar"
