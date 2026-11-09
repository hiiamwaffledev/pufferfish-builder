#!/usr/bin/env bash
# summary.sh: render the run's stage table, native-image stage timings, memory peak and errors as Markdown
# (→ $GITHUB_STEP_SUMMARY on Actions, stdout locally). Never fails the job.
cd "$(dirname "$0")/.."
L=logs
{
  echo "## Pufferfish builder: ${PF_TARGET:-ios} / ${PF_MODE:-?} / ${PF_INSTANCE:-?}"
  echo
  echo "| stage | time | result |"; echo "|---|---|---|"
  [[ -f $L/stages.tsv ]] && while IFS=$'\t' read -r s t r; do
    printf '| %s | %dm%02ds | %s |\n' "$s" $((t/60)) $((t%60)) "$r"; done < $L/stages.tsv
  if [[ -f $L/native-image.log ]]; then
    echo; echo "### native-image stages"; echo '```'
    grep -E '^\[[0-9]/[0-9]\]|Finished generating|Peak RSS|GC time|Error|Exception' $L/native-image.log | grep -v '^\s*at ' | cut -c1-200 | head -40
    echo '```'
  fi
  if [[ -f $L/memlog.tsv ]]; then
    echo; echo "### memory"
    awk -F'\t' 'NR>1 { if ($4>r) r=$4; if ($3>u) u=$3; if (min==""||$2<min) min=$2 } END {
      printf "builder peak RSS **%.1f GB**, system peak used %.1f GB, min available %.1f GB\n", r/1024, u/1024, min/1024 }' $L/memlog.tsv
  fi
  [[ -f $L/gc.log ]] && { echo; echo "GC: $(grep -c 'Pause Full' $L/gc.log) full GCs; last: $(grep 'Pause Full' $L/gc.log | tail -1 | cut -c1-160)"; }
  [[ -f out/artifact.txt ]] && { echo; echo "### artifact"; echo '```'; cat out/artifact.txt; echo '```'; }
} | if [[ -n ${GITHUB_STEP_SUMMARY:-} ]]; then tee -a "$GITHUB_STEP_SUMMARY"; else cat; fi
exit 0
