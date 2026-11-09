#!/usr/bin/env bash
# Build runtime/pufferfish-runtime.jar (BakeMain + FlatMain) against the Fabric instance classpath.
set -euo pipefail
cd "$(dirname "$0")/.."
INST=${1:-26.3-fabric-0.20.1}
CP=$(jq -r '.classpath | join(":")' mc/instances/$INST/launch.json)
rm -rf runtime/build && mkdir -p runtime/build
graalvm/bin/javac -nowarn --release 25 -cp "$CP" -d runtime/build $(find runtime/src -name '*.java')
graalvm/bin/jar cf runtime/pufferfish-runtime.jar -C runtime/build .
echo "built runtime/pufferfish-runtime.jar"
