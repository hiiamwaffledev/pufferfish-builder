#!/usr/bin/env bash
# build-mc.sh <instance> <mode>: cross-compile Minecraft → ios-aarch64 relocatable object (out/<name>/tmp/**/<name>.o),
# the input of Pufferfish's local tools/ios-mc-app.sh (linking needs the iOS SDK and stays on the user's PC).
#   mode vanilla    plain AOT build (same flags as Pufferfish tools/ios-build-mc.sh)
#   mode crema      + -H:+RuntimeClassLoading (mods run in the interpreter)
#   mode crema-jit  + Ristretto (-H:+GraalJITCompileAtRuntime) + the sealed-JIT substitution (jit/subst); needs points-to
# Env: PF_HEAP (builder -Xmx, default 12g), PF_PAR (compile threads, default 4), PF_ANALYSIS (rta|pointsto),
#      PF_OPT (2|b), PF_GC (parallel|serial|g1), PF_EXTRA (more native-image args).
set -euo pipefail
cd "$(dirname "$0")/.."
source ci/log.sh
INST=${1:?instance} MODE=${2:?mode}
case $MODE in vanilla) NAME=minecraft;; crema) NAME=minecraft-crema;; crema-jit) NAME=minecraft-crema-jit;; *) die "unknown mode $MODE";; esac
ANALYSIS=${PF_ANALYSIS:-$([[ $MODE == crema-jit ]] && echo pointsto || echo rta)}
[[ $MODE == crema-jit && $ANALYSIS == rta ]] && die "crema-jit needs PF_ANALYSIS=pointsto (RTA has no runtime-compilation variants)"
CFG=$PWD/aot/$([[ $INST == *fabric* ]] && echo cfg-fabric || echo cfg-vanilla)
L=$PWD/logs; mkdir -p "$L"
ASMD=mc/libraries/org/ow2/asm
ASM=$(ls $ASMD/asm/*/asm-*.jar | head -1):$(ls $ASMD/asm-commons/*/asm-commons-*.jar | head -1)
BJ=$(ls graalvm-cross/lib/svm/builder/*.jar | tr '\n' ':')

step "aot-support jar (substitutions)"
tools/build-aot-support.sh "$(ls -d mc/instances/*fabric* | head -1 | xargs basename)"

step "Darwin JDK substitutions (GenJdkSubst)"
rm -rf aot/cross/build aot/cross/subst-build
graalvm/bin/javac -nowarn -cp "$ASM" -d aot/cross/build tools/cross-src/GenJdkSubst.java
graalvm/bin/java -cp "$ASM:aot/cross/build" GenJdkSubst aot/cross/darwin-java.base aot/cross/linux-java.base \
  aot/cross/darwin-only/java.base aot/cross/jdk-subst.txt aot/cross/jdk-subst.jar aot/cross/jdk-exports | tail -3
graalvm/bin/javac -nowarn --add-exports java.base/sun.nio.fs=ALL-UNNAMED -cp "$BJ" -d aot/cross/subst-build $(find tools/cross-src/subst -name '*.java')
rm -f aot/cross/cross-subst.jar; graalvm/bin/jar cf aot/cross/cross-subst.jar -C aot/cross/subst-build .
EXTRA_CP=$PWD/aot/cross/jdk-subst.jar:$PWD/aot/cross/cross-subst.jar

MODE_ARGS=()
if [[ $MODE == crema* ]]; then MODE_ARGS+=(-H:+RuntimeClassLoading); fi
if [[ $MODE == crema-jit ]]; then
  step "sealed-JIT substitution jar"
  rm -rf jit/build; graalvm/bin/javac -nowarn -cp "$BJ" -d jit/build $(find jit/subst -name '*.java')
  graalvm/bin/jar cf jit/jit-subst.jar -C jit/build .
  EXTRA_CP+=:$PWD/jit/jit-subst.jar
  MODE_ARGS+=(-H:+GraalJITCompileAtRuntime)
fi
AN_ARGS=(); [[ $ANALYSIS == rta ]] && AN_ARGS=(-H:+UseExperimentalReachabilityAnalysis -H:-AOTTrivialInline)
case ${PF_GC:-parallel} in
  serial) GC_ARGS=(-J-XX:-UseParallelGC -J-XX:+UseSerialGC);; g1) GC_ARGS=(-J-XX:-UseParallelGC -J-XX:+UseG1GC);; *) GC_ARGS=(-J-XX:+UseParallelGC);;
esac

step "classpath"
CP=$(tools/aot-cp.sh "$INST")
MAIN=$(jq -r .mainClass mc/instances/$INST/launch.json)
log "main $MAIN, $(tr ':' '\n' <<<"$CP" | wc -l) jars"
tr ':' '\n' <<<"$CP" | sed 's|^'"$PWD"'/||' > "$L/classpath.txt"

OUT=$PWD/out/$NAME; rm -rf "$OUT"; mkdir -p "$OUT/tmp"
RI=$(paste -sd, aot/cross/jdk-runtime-init.txt)
mapfile -t PA < <(cat aot/cross/patch-args aot/cross/jdk-exports)
ARGS=(
  -J-Xmx${PF_HEAP:-12g} -J-XX:+UseCompactObjectHeaders "${GC_ARGS[@]}"
  -J-Xlog:gc:file=$L/gc.log:uptime,level -J-XX:+HeapDumpOnOutOfMemoryError -J-XX:HeapDumpPath=$L/oom.hprof
  -J-Dsvm.targetArch=aarch64 -J-XX:+UnlockDiagnosticVMOptions -J-XX:-BytecodeVerificationRemote "${PA[@]}"
  --parallelism=${PF_PAR:-4} --target=ios-aarch64 -H:+UnlockExperimentalVMOptions -H:-ForeignAPISupport
  -H:+UseCAPCache -H:CAPCacheDir=$PWD/aot/cross/cap-ios -H:+ExitAfterRelocatableImageWrite
  -R:ReservedAddressSpaceSize=${PF_IOS_RESERVE:-1g} -H:TempDirectory=$OUT/tmp --future-defaults=run-time-initialize-jdk
  "--initialize-at-run-time=$RI" -cp "$CP:$EXTRA_CP" -o "$OUT/$NAME"
  -O${PF_OPT:-2} -march=armv8.1-a -H:MaxSurvivorSpaces=3 "${AN_ARGS[@]}" -H:+ReportExceptionStackTraces
  "-H:ConfigurationFileDirectories=$CFG" --exclude-config '.*netty.*\.jar' 'META-INF/native-image/.*'
  --enable-native-access=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED
  -H:BuildOutputJSONFile=$L/build-output.json "${MODE_ARGS[@]}" ${PF_EXTRA:-} "$MAIN"
)
printf '%s\n' "${ARGS[@]}" | sed "s|$PWD/||g" > "$L/native-image-args.txt"
log "mode=$MODE analysis=$ANALYSIS heap=${PF_HEAP:-12g} par=${PF_PAR:-4} gc=${PF_GC:-parallel} opt=${PF_OPT:-2}"

step "native-image → ios-aarch64 ($MODE)"
ci/memlog.sh "$L" & MEMPID=$!
set +e
# line-buffered tee: the Actions log shows progress live, the full log is kept for the artifact
stdbuf -oL graalvm/bin/native-image "${ARGS[@]}" 2>&1 | tee "$L/native-image.log"
rc=${PIPESTATUS[0]}
set -e
kill $MEMPID 2>/dev/null || true
if (( rc != 0 )); then
  grep -v '^\s*at ' "$L/native-image.log" | grep -iE '^Error|error:|exception|Caused' | head -12 | cut -c1-300 | sed 's/^/::error::/'
  die "native-image failed (exit $rc)"
fi
OBJ=$(find "$OUT/tmp" -name "$NAME.o" | head -1); [[ -f $OBJ ]] || die "no $NAME.o produced"
log "object: ${OBJ#$PWD/} ($(du -h "$OBJ" | cut -f1))"
echo "$OBJ" > "$L/object-path.txt"
