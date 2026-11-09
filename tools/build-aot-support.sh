#!/usr/bin/env bash
# Build aot-support/pufferfish-aot.jar (GraalVM substitutions) against the Fabric instance classpath.
set -euo pipefail
cd "$(dirname "$0")/.."
CP=$(jq -r '.classpath | join(":")' mc/instances/${1:-26.3-fabric-0.20.1}/launch.json)
rm -rf aot-support/build && mkdir -p aot-support/build
graalvm/bin/javac -nowarn -cp "$CP" --add-modules org.graalvm.nativeimage -d aot-support/build $(find aot-support/src -name '*.java')
graalvm/bin/jar cf aot-support/pufferfish-aot.jar -C aot-support/build .
echo "built aot-support/pufferfish-aot.jar"
