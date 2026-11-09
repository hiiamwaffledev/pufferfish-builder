# log.sh: shared logging for ci/*.sh (sourced). Timestamps + elapsed time, GitHub log groups, error annotations,
# and a stage table (ci/stages.tsv) that ci/summary.sh renders into the job summary.
PF_T0=${PF_T0:-$(date +%s)}; export PF_T0
PF_STAGES=${PF_STAGES:-$PWD/logs/stages.tsv}; export PF_STAGES
mkdir -p "$(dirname "$PF_STAGES")"
_pf_ts() { local e=$(( $(date +%s) - PF_T0 )); printf '%s +%02d:%02d:%02d' "$(date -u +%H:%M:%S)" $((e/3600)) $((e%3600/60)) $((e%60)); }
_pf_mem() { awk '/MemAvailable/ {a=$2} /MemTotal/ {t=$2} END {printf "mem %.1f/%.1f GB free", a/1048576, t/1048576}' /proc/meminfo; }
log()  { echo "[$(_pf_ts)] $*"; }
warn() { echo "::warning::$*"; log "WARN: $*"; }
_pf_close() {
  if [[ -n ${PF_STEP:-} ]]; then
    printf '%s\t%s\t%s\n' "$PF_STEP" "$(( $(date +%s) - PF_STEP_T ))" "${1:-ok}" >> "$PF_STAGES"
    [[ -n ${GITHUB_ACTIONS:-} ]] && echo "::endgroup::"
    PF_STEP=
  fi
}
step() {
  _pf_close ok
  PF_STEP=$*; PF_STEP_T=$(date +%s)
  [[ -n ${GITHUB_ACTIONS:-} ]] && echo "::group::$*"
  echo "[$(_pf_ts)] ==== $* ($(_pf_mem))"
}
die() { echo "::error::$*"; log "FATAL: $*"; _pf_close FAILED; exit 1; }
trap 'rc=$?; if (( rc != 0 )); then _pf_close "FAILED (exit $rc)"; else _pf_close ok; fi' EXIT
