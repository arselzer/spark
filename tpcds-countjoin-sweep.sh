#!/usr/bin/env bash
#
# Full post-guard base,prod TPC-DS diagnostics sweep.
#
# Runs TPCDSCountJoinDiagnosticsSuite over all 103 query variants in groups,
# one fresh sbt invocation per group (each capped at 45m), logging to a
# timestamped output dir. Run tpcds-countjoin-setup.sh first to provision the
# toolchain and SF<n> data.
#
# Configurable via env vars (defaults match the suite's built-in defaults):
#   TPCDS_DIAG_DATA=/tmp/tpcds-sf5
#   TPCDS_DIAG_PARQUET=/tmp/tpcds-sf5-parquet
#   TPCDS_DIAG_MODES=base,prod          modes to run per query
#   JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
#   GROUP_TIMEOUT=45m                   per-group wall-clock cap
#
set -u

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:$PATH"

export TPCDS_DIAG_DATA="${TPCDS_DIAG_DATA:-/tmp/tpcds-sf5}"
export TPCDS_DIAG_PARQUET="${TPCDS_DIAG_PARQUET:-/tmp/tpcds-sf5-parquet}"
export TPCDS_DIAG_MODES="${TPCDS_DIAG_MODES:-base,prod}"
GROUP_TIMEOUT="${GROUP_TIMEOUT:-45m}"

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_DIR"

OUT=/tmp/tpcds-countjoin-prod-$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUT"
echo "OUT=$OUT" | tee "$OUT/OUTDIR"
echo "DATA=$TPCDS_DIAG_DATA PARQUET=$TPCDS_DIAG_PARQUET MODES=$TPCDS_DIAG_MODES" | tee -a "$OUT/manifest.log"

# Heavy queries are isolated into their own groups so one slow query cannot
# push a whole group past the timeout.
for QS in \
  q1,q2,q3,q4,q5 \
  q6,q7,q8,q9,q10 \
  q11,q12,q13,q14a,q14b,q15 \
  q16,q17,q18,q19,q20 \
  q21,q22,q23a \
  q23b \
  q24a \
  q24b \
  q25 \
  q26,q27,q28,q29,q30 \
  q31,q32,q33,q34,q35 \
  q36,q37,q38,q39a,q39b,q40 \
  q41,q42,q43,q44,q45 \
  q46,q47,q48,q49,q50 \
  q51,q52,q53,q54,q55 \
  q56,q57,q58,q59,q60 \
  q61,q62,q63,q64,q65 \
  q66,q67,q68,q69,q70 \
  q71,q72,q73,q74,q75 \
  q76,q77,q78,q79,q80 \
  q81,q82,q83,q84,q85 \
  q86,q87,q88,q89,q90 \
  q91,q92,q93,q94,q95 \
  q96,q97,q98,q99
 do
  SAFE=${QS//,/_}
  echo "START $QS $(date --iso-8601=seconds)" | tee -a "$OUT/manifest.log"
  TPCDS_DIAG_QUERIES="$QS" timeout "$GROUP_TIMEOUT" \
    build/sbt 'sql/testOnly org.apache.spark.sql.TPCDSCountJoinDiagnosticsSuite' \
    > "$OUT/$SAFE.log" 2>&1
  STATUS=$?
  echo "DONE $QS status=$STATUS $(date --iso-8601=seconds)" | tee -a "$OUT/manifest.log"
done

echo "SWEEP COMPLETE $(date --iso-8601=seconds)" | tee -a "$OUT/manifest.log"

# Collect the raw diagnostic + countjoin lines for summarization.
rg 'TPCDS-DIAG: .* \| (base|prod) \|' "$OUT"/*.log > "$OUT/results.txt" 2>/dev/null
rg 'TPCDS-DIAG-COUNTJOIN:' "$OUT"/*.log > "$OUT/countjoins.txt" 2>/dev/null
echo "ALL DONE; results in $OUT" | tee -a "$OUT/manifest.log"
