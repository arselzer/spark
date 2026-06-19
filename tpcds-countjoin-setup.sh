#!/usr/bin/env bash
#
# Reproduce the TPC-DS CountJoin benchmark environment from scratch.
#
# Idempotent: re-running skips work that is already done. Brings a fresh
# machine to the point where the diagnostics/benchmark suites in
# TPCDS-COUNTJOIN-FINDINGS.md can run against real SF<n> data.
#
# Steps:
#   1. Install build deps (flex, bison) and OpenJDK 17.
#   2. Clone + build databricks/tpcds-kit dsdgen (patched for GCC 14+).
#   3. Generate SF<scale> .dat data into $TPCDS_DIAG_DATA.
#   4. Optional smoke test (RUN_SMOKE=1) that loads the data and runs q1.
#
# Configurable via env vars (defaults match the suites' built-in defaults):
#   SCALE=5                                  TPC-DS scale factor (GB)
#   TPCDS_DIAG_DATA=/tmp/tpcds-sf5           where .dat files are written
#   TPCDS_DIAG_PARQUET=/tmp/tpcds-sf5-parquet  parquet cache (filled lazily by the suite)
#   KIT_DIR=/tmp/tpcds-kit                   tpcds-kit checkout/build dir
#   JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
#   RUN_SMOKE=0                              set to 1 to run the q1 smoke test
#
set -euo pipefail

SCALE="${SCALE:-5}"
TPCDS_DIAG_DATA="${TPCDS_DIAG_DATA:-/tmp/tpcds-sf${SCALE}}"
TPCDS_DIAG_PARQUET="${TPCDS_DIAG_PARQUET:-/tmp/tpcds-sf${SCALE}-parquet}"
KIT_DIR="${KIT_DIR:-/tmp/tpcds-kit}"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
RUN_SMOKE="${RUN_SMOKE:-0}"

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

log() { echo "[setup] $*"; }

# --- 1. System packages -----------------------------------------------------
# flex/bison are required to build dsdgen; openjdk-17 is required by the build
# (pom.xml: <java.version>17</java.version>).
need_pkgs=()
command -v flex  >/dev/null 2>&1 || need_pkgs+=(flex)
command -v bison >/dev/null 2>&1 || need_pkgs+=(bison)
[ -x "$JAVA_HOME/bin/java" ] || need_pkgs+=(openjdk-17-jdk-headless)

if [ "${#need_pkgs[@]}" -gt 0 ]; then
  log "installing: ${need_pkgs[*]}"
  sudo apt-get update -qq
  sudo apt-get install -y "${need_pkgs[@]}"
else
  log "system packages already present"
fi

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
log "JAVA_HOME=$JAVA_HOME ($(java -version 2>&1 | head -1))"

# --- 2. Build dsdgen --------------------------------------------------------
# GCC 14 promotes implicit-int / implicit-function-declaration / etc. to hard
# errors; the (old) tpcds-kit C sources trip these, so we demote them back to
# warnings and point lex/yacc at flex/bison.
if [ ! -x "$KIT_DIR/tools/dsdgen" ]; then
  log "cloning tpcds-kit into $KIT_DIR"
  rm -rf "$KIT_DIR"
  git clone --depth 1 https://github.com/databricks/tpcds-kit.git "$KIT_DIR"

  log "building dsdgen"
  make -C "$KIT_DIR/tools" clean >/dev/null 2>&1 || true
  make -C "$KIT_DIR/tools" OS=LINUX \
    LINUX_LEX=flex LINUX_YACC="bison -y" \
    LINUX_CFLAGS="-g -O2 -Wall -fcommon \
      -Wno-error=implicit-int -Wno-error=implicit-function-declaration \
      -Wno-error=int-conversion -Wno-error=incompatible-pointer-types \
      -Wno-error=return-mismatch \
      -Wno-implicit-int -Wno-implicit-function-declaration"
else
  log "dsdgen already built at $KIT_DIR/tools/dsdgen"
fi

# --- 3. Generate data -------------------------------------------------------
# dsdgen produces one <table>.dat (pipe-delimited) per table, which the
# diagnostics suite reads and converts to parquet on first use.
expected_tables=24  # 24 TPC-DS tables (+ dbgen_version, ignored by the suite)
have=$(ls "$TPCDS_DIAG_DATA"/*.dat 2>/dev/null | wc -l)
if [ "$have" -lt "$expected_tables" ]; then
  log "generating SF${SCALE} data into $TPCDS_DIAG_DATA"
  mkdir -p "$TPCDS_DIAG_DATA"
  ( cd "$KIT_DIR/tools" && ./dsdgen -scale "$SCALE" -dir "$TPCDS_DIAG_DATA" -terminate N -force Y )
else
  log "SF${SCALE} data already present ($have .dat files in $TPCDS_DIAG_DATA)"
fi
log "data: $(ls "$TPCDS_DIAG_DATA"/*.dat | wc -l) tables, $(du -sh "$TPCDS_DIAG_DATA" | cut -f1) in $TPCDS_DIAG_DATA"

# --- 4. Optional smoke test -------------------------------------------------
if [ "$RUN_SMOKE" = "1" ]; then
  log "running q1 smoke test (first build compiles Spark; this is slow)"
  cd "$REPO_DIR"
  TPCDS_DIAG_DATA="$TPCDS_DIAG_DATA" \
  TPCDS_DIAG_PARQUET="$TPCDS_DIAG_PARQUET" \
  TPCDS_DIAG_QUERIES=q1 \
  TPCDS_DIAG_MODES=base,prod \
    build/sbt 'sql/testOnly org.apache.spark.sql.TPCDSCountJoinDiagnosticsSuite'
fi

log "done."
log "next: run the full sweep with"
log "  TPCDS_DIAG_DATA=$TPCDS_DIAG_DATA TPCDS_DIAG_PARQUET=$TPCDS_DIAG_PARQUET $REPO_DIR/tpcds-countjoin-sweep.sh"
