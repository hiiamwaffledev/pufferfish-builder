#!/usr/bin/env bash
# memlog.sh <logdir>: background monitor. Every 10 s: system memory + builder RSS/CPU → memlog.tsv;
# every 5 min: builder heap histogram (top 40) → histo-<n>.txt. Lets us see where the heap goes if a build thrashes.
set -u
D=${1:?logdir}; mkdir -p "$D"
J=${JAVA_HOME:-$PWD/graalvm}/bin
echo -e "time\tavail_mb\tused_mb\tbuilder_rss_mb\tbuilder_cpu%\tload1" > "$D/memlog.tsv"
n=0; last=0
while sleep 10; do
  pid=$(pgrep -f 'bin/java @.*driverRoot' | head -1)
  read -r rss cpu <<<"$( [[ -n $pid ]] && ps -o rss=,pcpu= -p "$pid" || echo '0 0')"
  awk -v t="$(date -u +%H:%M:%S)" -v r="$rss" -v c="$cpu" -v l="$(cut -d' ' -f1 /proc/loadavg)" \
    '/MemTotal/ {tt=$2} /MemAvailable/ {a=$2} END {printf "%s\t%d\t%d\t%d\t%s\t%s\n", t, a/1024, (tt-a)/1024, r/1024, c, l}' \
    /proc/meminfo >> "$D/memlog.tsv"
  now=$(date +%s)
  if [[ -n $pid ]] && (( now - last >= 300 )); then
    last=$now; n=$((n+1))
    timeout 120 "$J/jmap" -histo "$pid" 2>&1 | head -45 > "$D/histo-$(printf %02d $n).txt" || true
  fi
done
