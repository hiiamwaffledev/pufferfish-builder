#!/usr/bin/env bash
# build-mc.sh <instance> <mode>: native-image Minecraft.
#   PF_TARGET=ios (default)  ios-aarch64 relocatable object out/<name>/tmp/**/<name>.o, linked later on the user's PC by
#                            Pufferfish tools/ios-mc-app.sh (needs the iOS SDK)
#   PF_TARGET=linux          linked linux-amd64 executable out/<name>/<name> (fast desktop test loop)
#   mode vanilla    plain AOT build
#   mode crema      + -H:+RuntimeClassLoading (mods added later run in the interpreter)
#   mode crema-jit  + Ristretto (-H:+GraalJITCompileAtRuntime) + the sealed-JIT substitution (jit/subst)
# Fabric instances (<mc>-fabric-<loader>) build the baked closed world from ci/bake.sh with FlatMain (no Knot/Mixin).
# Env: PF_HEAP (builder -Xmx, default 12g), PF_PAR (default 4), PF_ANALYSIS (pointsto|rta), PF_OPT (2|b),
#      PF_GC (parallel|serial|g1), PF_EXTRA (more native-image args).
set -euo pipefail
cd "$(dirname "$0")/.."
source ci/log.sh
INST=${1:?instance} MODE=${2:?mode} TARGET=${PF_TARGET:-ios}
FABRIC=; [[ $INST == *-fabric-* ]] && FABRIC=1
case $MODE in vanilla) SUF=;; crema) SUF=-crema;; crema-jit) SUF=-crema-jit;; *) die "unknown mode $MODE";; esac
NAME=minecraft${FABRIC:+-fabric}$SUF$([[ $TARGET == linux ]] && echo -linux)
ANALYSIS=${PF_ANALYSIS:-pointsto}
[[ $MODE == crema* && $ANALYSIS == rta ]] && die "crema needs PF_ANALYSIS=pointsto (RTA leaves virtual calls in pre-isolate code → SIGSEGV at launch; Ristretto needs points-to anyway)"
CFG=$PWD/aot/$([[ $FABRIC ]] && echo cfg-fabric || echo cfg-vanilla)
L=$PWD/logs; mkdir -p "$L"
ASMD=mc/libraries/org/ow2/asm
ASM=$(ls $ASMD/asm/*/asm-*.jar | head -1):$(ls $ASMD/asm-commons/*/asm-commons-*.jar | head -1)
BJ=$(ls graalvm-cross/lib/svm/builder/*.jar | tr '\n' ':')

step "aot-support jar (substitutions)"
tools/build-aot-support.sh "$(ls -d mc/instances/*fabric* | head -1 | xargs basename)"

EXTRA_CP=; TARGET_ARGS=()
if [[ $TARGET == ios ]]; then
  step "Darwin JDK substitutions (GenJdkSubst)"
  rm -rf aot/cross/build aot/cross/subst-build
  graalvm/bin/javac -nowarn -cp "$ASM" -d aot/cross/build tools/cross-src/GenJdkSubst.java
  graalvm/bin/java -cp "$ASM:aot/cross/build" GenJdkSubst aot/cross/darwin-java.base aot/cross/linux-java.base \
    aot/cross/darwin-only/java.base aot/cross/jdk-subst.txt aot/cross/jdk-subst.jar aot/cross/jdk-exports | tail -3
  graalvm/bin/javac -nowarn --add-exports java.base/sun.nio.fs=ALL-UNNAMED -cp "$BJ" -d aot/cross/subst-build $(find tools/cross-src/subst -name '*.java')
  rm -f aot/cross/cross-subst.jar; graalvm/bin/jar cf aot/cross/cross-subst.jar -C aot/cross/subst-build .
  EXTRA_CP=:$PWD/aot/cross/jdk-subst.jar:$PWD/aot/cross/cross-subst.jar
  RI=$(paste -sd, aot/cross/jdk-runtime-init.txt)
  mapfile -t PA < <(cat aot/cross/patch-args aot/cross/jdk-exports)
  TARGET_ARGS=(-J-Dsvm.targetArch=aarch64 "${PA[@]}" --target=ios-aarch64 -H:+UseCAPCache -H:CAPCacheDir=$PWD/aot/cross/cap-ios
    -H:+ExitAfterRelocatableImageWrite -R:ReservedAddressSpaceSize=${PF_IOS_RESERVE:-1g} --future-defaults=run-time-initialize-jdk
    "--initialize-at-run-time=$RI" -march=armv8.1-a)
fi

MODE_ARGS=()
[[ $MODE == crema* ]] && MODE_ARGS+=(-H:+RuntimeClassLoading)
if [[ $MODE == crema-jit ]]; then
  step "sealed-JIT substitution jar"
  rm -rf jit/build; graalvm/bin/javac -nowarn -cp "$BJ" -d jit/build $(find jit/subst -name '*.java')
  graalvm/bin/jar cf jit/jit-subst.jar -C jit/build .
  EXTRA_CP+=:$PWD/jit/jit-subst.jar
  MODE_ARGS+=(-H:+GraalJITCompileAtRuntime)
fi
# -H:-AOTTrivialInline: Graal bug "unexpected encoding: true true" in FrameState (hits RTA and points-to)
AN_ARGS=(-H:-AOTTrivialInline); [[ $ANALYSIS == rta ]] && AN_ARGS+=(-H:+UseExperimentalReachabilityAnalysis)
case ${PF_GC:-parallel} in
  serial) GC_ARGS=(-J-XX:-UseParallelGC -J-XX:+UseSerialGC);; g1) GC_ARGS=(-J-XX:-UseParallelGC -J-XX:+UseG1GC);; *) GC_ARGS=(-J-XX:+UseParallelGC);;
esac

step "classpath"
FLAT_ARGS=()
if [[ $FABRIC ]]; then
  [[ -f baked/$INST/bake.json ]] || die "no bake for $INST (run ci/bake.sh)"
  export PUFFERFISH_CP=$(python3 tools/launch.py "$INST" --flat "baked/$INST" --print | jq -r '. as $c | ($c | index("-cp")) as $i | $c[$i+1]')
  MAIN=dev.pufferfish.runtime.FlatMain
  # Baked world: Mixin is never bootstrapped at runtime, so keep its service providers out of the image.
  FLAT_ARGS=(-H:ServiceLoaderFeatureExcludeServices=org.spongepowered.asm.service.IMixinService,org.spongepowered.asm.service.IMixinServiceBootstrap,org.spongepowered.asm.service.IGlobalPropertyService
    -H:IncludeResourceBundles=net.fabricmc.loader.Messages '-H:IncludeResources=pufferfish-baked-mods\.txt')
else
  MAIN=$(jq -r .mainClass mc/instances/$INST/launch.json)
fi
CP=$(tools/aot-cp.sh "$INST")
log "main $MAIN, $(tr ':' '\n' <<<"$CP" | wc -l) classpath entries"
# Crema: interpreted mod code calling a method that analysis pruned is FATAL (no fallback), so keep the API surface mods
# use. Default: the JDK core (indy string concat, lambdas, records, collections). PF_PRESERVE_LIBS adds whole jars
# (basename prefixes, e.g. "gson- brigadier-"), PF_PRESERVE=none disables.
PRESERVE_ARGS=()
if [[ $MODE == crema* && ${PF_PRESERVE:-} != none ]]; then
  for p in java.lang java.lang.invoke java.lang.reflect java.lang.runtime java.lang.ref java.lang.constant java.util java.util.function \
           java.util.stream java.util.concurrent java.util.concurrent.atomic java.util.concurrent.locks java.util.regex java.io \
           java.nio java.nio.file java.nio.charset java.math java.text java.time java.net; do
    PRESERVE_ARGS+=(-H:Preserve=package=$p); done
  IFS=: read -ra ENTRIES <<<"$CP"
  for e in "${ENTRIES[@]}"; do
    b=$(basename "$e")
    case $b in
      # whole-jar Preserve of MC (+ libs) thrashed a 12g builder heap in analysis (run 42696653613) → opt-in only
      *) for x in ${PF_PRESERVE_LIBS:-}; do [[ $b == $x* ]] && PRESERVE_ARGS+=(-H:Preserve=path=$e); done;;
    esac
  done
  log "preserve: $(printf '%s\n' "${PRESERVE_ARGS[@]}" | sed 's|.*/||; s|-H:Preserve=package=||' | tr '\n' ' ')"
fi
tr ':' '\n' <<<"$CP" | sed 's|^'"$PWD"'/||' > "$L/classpath.txt"

OUT=$PWD/out/$NAME; rm -rf "$OUT"; mkdir -p "$OUT/tmp"
ARGS=(
  -J-Xmx${PF_HEAP:-12g} -J-XX:+UseCompactObjectHeaders "${GC_ARGS[@]}"
  -J-Xlog:gc:file=$L/gc.log:uptime,level -J-XX:+HeapDumpOnOutOfMemoryError -J-XX:HeapDumpPath=$L/oom.hprof
  -J-XX:+UnlockDiagnosticVMOptions -J-XX:-BytecodeVerificationRemote
  --parallelism=${PF_PAR:-4} -H:+UnlockExperimentalVMOptions -H:-ForeignAPISupport "${TARGET_ARGS[@]}"
  -H:TempDirectory=$OUT/tmp -cp "$CP$EXTRA_CP" -o "$OUT/$NAME"
  -O${PF_OPT:-2} -H:MaxSurvivorSpaces=3 "${AN_ARGS[@]}" -H:+ReportExceptionStackTraces
  "-H:ConfigurationFileDirectories=$CFG$([[ $MODE == crema* && -f aot/cfg-modapi/reachability-metadata.json ]] && echo ,$PWD/aot/cfg-modapi)" --exclude-config '.*netty.*\.jar' 'META-INF/native-image/.*'
  --enable-native-access=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED "${FLAT_ARGS[@]}"
  -H:BuildOutputJSONFile=$L/build-output.json "${MODE_ARGS[@]}" "${PRESERVE_ARGS[@]}" ${PF_EXTRA:-} "$MAIN"
)
printf '%s\n' "${ARGS[@]}" | sed "s|$PWD/||g" > "$L/native-image-args.txt"
log "name=$NAME target=$TARGET mode=$MODE analysis=$ANALYSIS heap=${PF_HEAP:-12g} par=${PF_PAR:-4} gc=${PF_GC:-parallel} opt=${PF_OPT:-2}"
echo "$NAME" > "$L/name.txt"

step "native-image → $TARGET ($MODE)"
ci/memlog.sh "$L" & MEMPID=$!
set +e
stdbuf -oL graalvm/bin/native-image "${ARGS[@]}" 2>&1 | tee "$L/native-image.log"
rc=${PIPESTATUS[0]}
set -e
kill $MEMPID 2>/dev/null || true
if (( rc != 0 )); then
  grep -v '^\s*at ' "$L/native-image.log" | grep -iE '^Error|error:|exception|Caused' | head -12 | cut -c1-300 | sed 's/^/::error::/'
  die "native-image failed (exit $rc)"
fi
if [[ $TARGET == ios ]]; then OBJ=$(find "$OUT/tmp" -name "$NAME.o" | head -1); else OBJ=$OUT/$NAME; fi
[[ -f $OBJ ]] || die "no output for $NAME"
log "output: ${OBJ#$PWD/} ($(du -h "$OBJ" | cut -f1))"
echo "$OBJ" > "$L/object-path.txt"
