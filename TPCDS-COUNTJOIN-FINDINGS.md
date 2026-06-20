# CountJoin / Yannakakis Investigation Notes

_Last updated: 2026-06-19._

This is the running log for the CountJoin/Yannakakis correctness and performance work. It records
what was tried, which benchmark queries were used, the observed results, and the current decisions so
we do not have to reconstruct the investigation from terminal history.

## Current Working Tree Summary

Tracked files currently touched by the investigation:

- `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/optimizer/RewriteJoinsAsSemijoins.scala`
- `sql/catalyst/src/main/scala/org/apache/spark/sql/internal/SQLConf.scala`
- `sql/core/src/main/scala/org/apache/spark/sql/execution/SparkStrategies.scala`
- `sql/core/src/main/scala/org/apache/spark/sql/execution/joins/HashCountJoin.scala`
- `sql/core/src/test/scala/org/apache/spark/sql/YannakakisCorrectnessSuite.scala`

Diagnostic / benchmark harnesses currently untracked:

- `sql/core/src/test/scala/org/apache/spark/sql/TPCDSApplicabilitySuite.scala`
- `sql/core/src/test/scala/org/apache/spark/sql/TPCDSBenchmarkSuite.scala`
- `sql/core/src/test/scala/org/apache/spark/sql/TPCDSCountJoinDiagnosticsSuite.scala`
- `sql/core/src/test/scala/org/apache/spark/sql/TPCDSCountJoinPlanDumpSuite.scala`
- `sql/core/src/test/scala/org/apache/spark/sql/TPCDSCountJoinProperBenchmarkSuite.scala`
- `sql/core/src/test/scala/org/apache/spark/sql/TPCDSCrashReproSuite.scala`

Data/harness defaults used in the TPC-DS work:

- SF1 raw data: `/tmp/tpcds-sf1`
- SF5 raw data: `/tmp/tpcds-sf5`
- SF5 parquet cache: `/tmp/tpcds-sf5-parquet`
- dsdgen tool path used earlier: `/home/as/git/spark-eval/benchmark/tpcds-kit/tools`
- Most performance runs used `spark.sql.shuffle.partitions=16`, AQE enabled unless otherwise stated,
  `spark.sql.autoBroadcastJoinThreshold=10MiB`, and whole-stage codegen on.

## Terminology Used In Results

- `base`: Yannakakis disabled; vanilla Spark optimized/executed plan.
- `rewritten` / `forced`: Yannakakis enabled, unguarded enabled, cost gate disabled. This shows the
  rewrite wherever applicable, even if production would skip it.
- `prod` / `cost-gated` / `gated`: Yannakakis enabled, unguarded enabled, cost gate enabled. This is
  the intended production decision path.
- `gate=Y`: the cost-gated plan still contained one or more logical `CountJoin`s.
- `gate=N`: the cost-gated plan contained no logical `CountJoin`s. Note: after adding direct
  non-CountJoin special rewrites, `gate=N` only means no logical `CountJoin`; it does not prove no
  special rewrite happened unless the physical/logical plan is inspected.
- `pwg`: piecewise-guarded. In the earlier discussion we also used `guarded`; these should be read as
  the same class for the tables where both words appeared.
- `unguarded`: the harder class where no single join-tree node contains all aggregate/group attrs.

## Original CountJoin Crash Findings

The physical CountJoin operator exposed crashes on TPC-DS after the logical rewrite emitted
`CountJoin` nodes whose aggregate/group/count specs were ordinary expressions and therefore still
rewritable by later optimizer rules.

Observed failure signatures:

- `MakeDecimal cannot be cast to AggregateExpression` on decimal `sum`/`avg` queries.
- `Literal cannot be cast to NamedExpression` on constant-folded count/group specs, notably q1/q30.

Root cause:

- `CountJoin` carried `aggregatesRight: Seq[AggregateExpression]`, `groupRight: Seq[NamedExpression]`,
  and `countRight: Option[NamedExpression]`.
- Later optimizer rules such as `DecimalAggregates` and `ConstantFolding` rewrote these fields into
  expressions that no longer matched the physical operator's assumptions.
- Plan-change validation surfaced this in tests, but disabling validation showed production-like
  execution still failed inside the physical operator. It was not merely a test-only integrity check.

Important design decision from this phase:

- The legacy non-physical reduction path was not a valid fallback. The physical CountJoin operator is
  the real path and should be the only path.
- The explicit physical-operator config was removed. Physical CountJoin is now the only choice.

## Applicability Snapshot

Empirical all-103 TPC-DS query-file applicability snapshot from the early harness:

- 70 queries fired the rewrite with the rewrite forced.
- 36 survived the cost gate.
- 33 did not fire, commonly due to ROLLUP/CUBE/GROUPING SETS, UNION/INTERSECT/EXCEPT shapes,
  single-relation scalar subqueries, or cyclic joins.
- The largest fan-out candidates such as q17, q24a/b, q25, and q29 are cyclic. The acyclic rewrite
  bails unless the cyclic-bag path can materialize a useful bag decomposition.

## Benchmark Harnesses

`TPCDSCountJoinDiagnosticsSuite`

- Runs one or more queries in `base`, `forced`, and/or `prod` mode.
- Reports wall-clock time, logical CountJoin count, compact physical operator counts, and key metrics
  such as aggregate time/output rows, shuffle bytes/records, sort time, and CountJoin metrics.
- Useful for explaining regressions by plan shape.

`TPCDSCountJoinProperBenchmarkSuite`

- Interleaves `base` and `rewritten` runs after warmup.
- Reports median runtimes and individual runs.
- The default set evolved during the investigation; the broad subset used most recently was:
  `q4,q11,q13,q25,q32,q48,q50,q64,q92,q97`.
- Caveat: this harness compares `base` vs cost-gate-disabled `rewritten`. Its `gate` columns are based
  on logical CountJoin counts and can be misleading once direct non-CountJoin special rewrites exist.

`TPCDSCountJoinPlanDumpSuite`

- Planning-only dump of logical CountJoin blocks, their build/probe inputs, right keys, carried groups,
  carried aggregates, and conditions.
- Useful for identifying whether a query is grouped, count-only, aggregate-carrying, etc.
- It does not explain direct rewrites that do not emit CountJoin.

`TPCDSApplicabilitySuite` / `TPCDSBenchmarkSuite` / `TPCDSCrashReproSuite`

- Earlier broad applicability/correctness/crash harnesses used to establish the initial bug and query
  coverage.

## Broad TPC-DS Performance Snapshot Before Latest q97 Work

Proper benchmark, SF5, 1 warmup / 2 measured repetitions, after the kept HashCountJoin codegen
optimizations and after removing the attempted single-group branch:

| Query | Cost Gate Applies? | Forced CountJoins | Gated CountJoins | Rows | Base Median | Rewritten Median | Speedup | Base Runs | Rewritten Runs |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|
| q4 | Y | 36 | 12 | 100 | 19889 ms | 22033 ms | 0.90x | [19743,19889] | [22033,21182] |
| q11 | Y | 16 | 8 | 100 | 9523 ms | 10330 ms | 0.92x | [9523,8795] | [9704,10330] |
| q13 | N | 5 | 0 | 1 | 1259 ms | 1289 ms | 0.98x | [1259,1213] | [1289,1142] |
| q25 | Y | 7 | 7 | 1 | 14136 ms | 4294 ms | 3.29x | [14136,13995] | [4294,4227] |
| q32 | Y | 4 | 3 | 1 | 551 ms | 1038 ms | 0.53x | [509,551] | [1038,435] |
| q48 | N | 3 | 0 | 1 | 2045 ms | 1686 ms | 1.21x | [2045,1631] | [1686,1635] |
| q50 | Y | 4 | 4 | 27 | 4886 ms | 3199 ms | 1.53x | [4650,4886] | [3199,3106] |
| q64 | Y | 36 | 36 | 19 | 21204 ms | 16290 ms | 1.30x | [21204,20835] | [11346,16290] |
| q92 | Y | 4 | 3 | 1 | 709 ms | 407 ms | 1.74x | [310,709] | [407,353] |
| q97 | N | 1 | 0 | 1 | 4849 ms | 7705 ms | 0.63x | [4849,4132] | [7705,7700] |

Interpretation at that point:

- Clear wins: q25, q50, q64, q92.
- Mild/noisy or modest regressions: q4, q11, q13, q32.
- q97 was the standout forced-regression case, and cost gate skipped it.
- Guarded/piecewise-guarded cases were generally less bad than fully unguarded cases, but not good
  enough to ignore. The next serious work needed to target plan shape / row reduction / aggregate
  fusion, not just narrower applicability.

## q97 Baseline vs Old Forced Rewrite Diagnostics

Before the q97-specific full-outer presence-count work:

| Mode | Runtime | Logical CountJoins | Physical Shape | Key Metrics |
|---|---:|---:|---|---|
| base | 8675 ms | 0 | BHJ=2, SMJ=1, HashAgg=6, Shuffle=3, Broadcast=1, Sort=2, WSCG=7 | aggOut=8331344, shuffleRecords=4181101, sortPeak=528482208 |
| forced | 11785 ms | 1 | BHJ=2, SMJ=2, ShuffledHashCountJoin=1, HashAgg=16, Shuffle=5, Broadcast=1, Sort=4, WSCG=16 | cjOut=523, buildBytes=142606336, aggOut=16631838, sortPeak=1056964416 |

Physical CountJoin line for the old forced q97 shape:

```text
rightKeys=[customer_sk,item_sk] groups=[customer_sk] aggs=[] buildDataSize=142606336,buildTime=1246,numOutputRows=523
```

Interpretation:

- The CountJoin itself reduced the final matched cardinality sharply (`cjOut=523`), but the generic
  outer-join split created a much heavier plan: many extra aggregates, shuffles, sorts, and codegen
  pipelines.
- This confirmed q97 was a plan-shape problem more than a CountJoin loop problem.

## Optimizations Tried And Decisions

### 1. Direct grouped-output read in `HashCountJoin` codegen

What was tried:

- For unique-build grouped CountJoin codegen, read group expressions directly from the matched build
  row where possible instead of projecting the synthetic `groupKey` path.
- Applied in both aggregate-carrying and count-only grouped paths.

Status: kept.

Reason:

- This is a local codegen simplification with low risk and no observed correctness issue.
- It targets overhead inside grouped CountJoin without changing logical applicability.

### 2. Cheaper grouped count-only map value

What was tried:

- Replaced `scala.collection.mutable.MutableLong` with `scala.runtime.LongRef` for grouped count-only
  map values.

Status: kept.

Reason:

- Low-risk object/layout simplification in the hot count-only grouped path.

### 3. Single-group non-unique fast path

What was tried:

- A map-free branch for the single-group count-only case.

Observed result:

- q97 did not materially improve.
- A synthetic regression test became brittle.

Status: removed / not kept.

Reason:

- The q97 bottleneck was not the CountJoin grouping map; it was the generic outer rewrite's plan
  expansion.

### 4. Direct full-outer presence-count rewrite for q97 shape

Target shape:

- Global aggregate over a `FULL OUTER` equi-join.
- Both sides provably unique on the full join key.
- Aggregate slots exactly of the form:
  - `sum(case when left_key is not null and right_key is null then 1 else 0 end)`
  - `sum(case when left_key is null and right_key is not null then 1 else 0 end)`
  - `sum(case when left_key is not null and right_key is not null then 1 else 0 end)`
- This matches TPC-DS q97's `ssci FULL OUTER JOIN csci` presence-count query.

First implementation:

- Compute scalar left count / left non-null presence count.
- Compute scalar right count / right non-null presence count.
- Compute matched count via a `LeftSemi` join, not an inner join and not CountJoin.
- Cross join the scalar rows and derive:
  - `left_only = left_present - matched`
  - `right_only = right_present - matched`
  - `matched = matched`
- Preserve `SUM`-over-empty semantics with a `nonEmptyOuter` guard.
- Look through the immediate projection so q97-style aliases do not prevent matching.
- Preserve original output exprIds.

Correctness:

- Added focused test: `FULL OUTER presence-count sums use scalar counts and match vanilla`.
- Test includes duplicate rows before grouped distinct CTEs and NULL key rows.
- Asserts the direct path fires and emits no CountJoin.

Performance results:

| Variant | Result |
|---|---|
| old forced q97 rewrite | base 4849 ms, rewritten 7705 ms, 0.63x |
| semi-join scalar-count q97 rewrite | base 5401 ms, rewritten 6643 ms, 0.81x |
| diagnostics, single run | base 10315 ms, forced 8527 ms, prod 6919 ms; noisy but showed improved forced shape |

Plan-shape effect of first implementation:

- Old forced q97: `HashAggregateExec=16`, `ShuffleExchangeExec=5`, `SortExec=4`, `CountJoin=1`.
- Direct scalar-count forced q97: `HashAggregateExec=12`, `ShuffleExchangeExec=5`, `SortExec=2`,
  `CountJoin=0`.

Status: kept only for cost-gate-disabled / forced exploration, not enabled for prod.

Reason:

- It improves the old forced q97 path but still loses to warmed baseline in proper repetitions.
- Final code gates `tryRewriteOuter` before this special rewrite, so prod/cost-gated q97 keeps
  vanilla baseline shape.

### 5. Aggregate-fused q97 full-outer presence-count variant

What was tried:

- Replace the three scalar aggregates and scalar cross joins with contribution rows:
  - left branch emits left row / left-present contributions;
  - right branch emits right row / right-present contributions;
  - matched `LeftSemi` branch emits matched contributions;
  - `UNION ALL` the contributions and do one final global aggregate.

Intended benefit:

- Reduce `HashAggregateExec` and `ShuffleExchangeExec` counts by fusing the scalar side counts into a
  single final aggregate.

Observed diagnostics:

- It did reduce plan shape:
  - from `HashAggregateExec=12`, `ShuffleExchangeExec=5`, `BroadcastExchangeExec=3`
  - to `HashAggregateExec=8`, `ShuffleExchangeExec=3`, `BroadcastExchangeExec=1`.

Observed proper benchmark:

| Variant | Result |
|---|---|
| aggregate-fused contribution-union q97 | base 6753 ms, rewritten 11111 ms, 0.61x |

Status: rejected / reverted.

Reason:

- Cleaner operator counts did not translate into runtime. The union/final-aggregate physical shape was
  substantially slower under the proper benchmark.

### 6. Cost-gating the q97 special rewrite

Final q97 diagnostic after reverting fusion and putting the special rewrite behind the existing cost
check:

| Mode | Runtime | Logical CountJoins | Physical Shape |
|---|---:|---:|---|
| base | 10457 ms | 0 | BHJ=2, SMJ=1, HashAgg=6, Shuffle=3, Broadcast=1, Sort=2, WSCG=7 |
| forced | 9552 ms | 0 | BHJ=2, SMJ=1, HashAgg=12, Shuffle=5, Broadcast=3, Sort=2, WSCG=11 |
| prod | 6040 ms | 0 | BHJ=2, SMJ=1, HashAgg=6, Shuffle=3, Broadcast=1, Sort=2, WSCG=7 |

Interpretation:

- Forced uses the direct semi-join scalar-count q97 shape.
- Prod/cost-gated uses the baseline shape.
- This is the correct current decision because the special q97 rewrite is not a reliable prod win yet.

## Cost Gate / Applicability Lessons

What was learned:

- We should avoid broad cost gating solely based on small/broadcastable inputs because other benchmark
  families have catastrophic fan-out cases where baseline can be much worse even when inputs look
  small. The max key degree / fan-out signal matters.
- Conversely, q97 shows that reducing final rows is not sufficient when the rewrite expands the plan
  with extra aggregates, exchanges, sorts, or repeated scans.
- The current cost gate should continue to avoid q97-like cases until there is a physical/plan-shape
  solution that actually beats baseline.

## Codegen / Physical Operator Notes

Confirmed assumptions:

- Performance tests discussed here were using codegen unless explicitly disabled by a correctness
  check comparing codegen on/off.
- Existing correctness tests now force physical paths with join hints and planner configs rather than
  the removed physical-operator selector.

Kept physical/codegen changes:

- grouped-output direct read for unique-build grouped CountJoin codegen;
- `LongRef` for grouped count-only map values;
- physical operator config removed;
- tests adjusted to select shuffled-hash or sort-merge count-join using normal planner knobs/hints.

Rejected physical/codegen changes:

- single-group non-unique count-only fast path, due to no useful q97 improvement and brittle test.

## Query Sets Used

Focused q97 shape:

```sql
WITH ssci AS (
  SELECT ss_customer_sk customer_sk, ss_item_sk item_sk
  FROM store_sales, date_dim
  WHERE ss_sold_date_sk = d_date_sk
    AND d_month_seq BETWEEN 1200 AND 1200 + 11
  GROUP BY ss_customer_sk, ss_item_sk),
csci AS (
  SELECT cs_bill_customer_sk customer_sk, cs_item_sk item_sk
  FROM catalog_sales, date_dim
  WHERE cs_sold_date_sk = d_date_sk
    AND d_month_seq BETWEEN 1200 AND 1200 + 11
  GROUP BY cs_bill_customer_sk, cs_item_sk)
SELECT
  sum(CASE WHEN ssci.customer_sk IS NOT NULL AND csci.customer_sk IS NULL THEN 1 ELSE 0 END),
  sum(CASE WHEN ssci.customer_sk IS NULL AND csci.customer_sk IS NOT NULL THEN 1 ELSE 0 END),
  sum(CASE WHEN ssci.customer_sk IS NOT NULL AND csci.customer_sk IS NOT NULL THEN 1 ELSE 0 END)
FROM ssci FULL OUTER JOIN csci
  ON ssci.customer_sk = csci.customer_sk AND ssci.item_sk = csci.item_sk
LIMIT 100;
```

Broad TPC-DS subset used for proper benchmarks:

- q4
- q11
- q13
- q25
- q32
- q48
- q50
- q64
- q92
- q97

Additional diagnostic/decision queries referenced during the investigation:

- q1 and q30: constant-folding / literal crash signatures.
- q5, q12, q15, q19, q20: decimal aggregate crash/correctness verification set.
- q17, q24a, q24b, q25, q29: cyclic/high-fan-out applicability headroom.
- q3, q78: pure-star cases previously skipped by the gate.

## Current Big Picture

What is clearly good:

- Physical CountJoin correctness/robustness direction: physical path only, no legacy fallback.
- Existing wins remain meaningful: q25, q50, q64, q92.
- Local HashCountJoin codegen simplifications are worth keeping.

What is not solved:

- q4/q11 remain mild forced regressions.
- q97 remains a plan-shape challenge. The direct presence-count rewrite is better than the old forced
  CountJoin outer split, but not enough to beat baseline when warmed.
- Aggregate fusion at the logical `Union` level made operator counts nicer but runtime worse.

Most useful next optimization direction:

- A physical or planner-level full-outer presence-count operator/path for q97-like queries that
  consumes the two distinct key sets once and computes `(left_only, right_only, matched)` directly,
  without materializing full outer rows and without separate scalar aggregate branches.
- More generally, target plan shape / row reduction / aggregate fusion at the physical-plan level,
  not only in logical rewrites.
- Before any broad rerun, validate candidate improvements on a focused set such as q4, q11, q25,
  q50, q64, q92, q97, then rerun the broad applicable TPC-DS set only once a broad solution exists.

## Verification Commands Run Recently

Focused correctness:

```bash
build/sbt "sql/testOnly org.apache.spark.sql.YannakakisCorrectnessSuite -- -z presence-count"
```

Result: passed.

q97 diagnostics:

```bash
TPCDS_DIAG_QUERIES=q97 TPCDS_DIAG_MODES=base,forced,prod \
  build/sbt "sql/testOnly org.apache.spark.sql.TPCDSCountJoinDiagnosticsSuite"
```

Result: passed; final diagnostic shown above.

q97 proper benchmark examples:

```bash
TPCDS_PROPER_QUERIES=q97 TPCDS_PROPER_WARMUPS=1 TPCDS_PROPER_REPETITIONS=3 \
  build/sbt "sql/testOnly org.apache.spark.sql.TPCDSCountJoinProperBenchmarkSuite"
```

Results recorded above for the semi-join scalar-count and aggregate-fused variants.

## Bridge-the-Gap Candidate Investigation

Date: 2026-06-19.

Goal framing:

- We want TPC-DS overall to be at least slightly faster, while making many hard queries substantially faster.
- It is acceptable to add specialized AggJoin/CountJoin optimizations that normal Spark SQL does not have, as long as correctness and plan selection stay defensible.
- TPCH, JOB, and STATS performance is already good enough that TPC-DS should be treated as the stress case, not as evidence that the whole approach is weak.

### Fresh Representative Diagnostics

Command:

```bash
TPCDS_DIAG_QUERIES=q4,q11,q25,q50,q64,q97 TPCDS_DIAG_MODES=base,forced,prod \
  build/sbt "sql/testOnly org.apache.spark.sql.TPCDSCountJoinDiagnosticsSuite"
```

Single-run diagnostics on `/tmp/tpcds-sf5-parquet`:

| query | mode | ms | logical CountJoins | key physical shape | notable metrics |
|---|---:|---:|---:|---|---|
| q4 | base | 35774 | 0 | 11 BHJ, 6 SMJ, 12 agg, 14 exch, 12 sort | SMJ out 48.3M, BHJ out 9.8M, agg 40.8s, sort 1.8s |
| q4 | forced | 30155 | 36 | 6 BHCJ, 6 SHCJ, 12 agg, 14 exch | CJ out 58.0M, build 1.2s, no sort |
| q4 | prod | 28548 | 12 | same physical CJ count as forced | CJ out 58.0M, build 1.2s, no sort |
| q11 | base | 19744 | 0 | 7 BHJ, 4 SMJ, 8 agg, 9 exch, 8 sort | SMJ out 34.1M, BHJ out 6.9M, agg 24.1s |
| q11 | forced | 18193 | 16 | 4 BHCJ, 4 SHCJ, 8 agg, 8 exch | CJ out 40.8M, build 1.0s, no sort |
| q11 | prod | 15657 | 8 | 4 BHCJ, 4 SHCJ, 8 agg, 9 exch | CJ out 40.8M, build 0.9s, no sort |
| q25 | base | 22700 | 0 | 5 BHJ, 2 SMJ, 2 agg, 5 exch, 4 sort | SMJ out 991k, agg 10.6s, sort 18.8s |
| q25 | forced | 6751 | 7 | 5 BHCJ, 2 SHCJ, 2 agg, 5 exch | CJ out 968k, agg 89ms, no sort |
| q25 | prod | 7149 | 7 | same as forced | CJ out 968k, agg 66ms, no sort |
| q50 | base | 8811 | 0 | 3 BHJ, 1 SMJ, 2 agg, 3 exch, 2 sort | join out about 3.0M, agg 7.4s |
| q50 | forced | 4858 | 4 | 3 BHCJ, 1 SHCJ, 2 agg, 3 exch | CJ out 46.5k, agg 3.3s, no sort |
| q50 | prod | 4251 | 4 | same as forced | CJ out 46.5k, agg 2.5s, no sort |
| q64 | base | 33315 | 0 | 33 BHJ, 2 SMJ, 6 agg, 9 exch, 5 sort | join out 10.5M, agg 20.2s, sort 26.6s |
| q64 | forced | 16913 | 36 | 22 BHCJ, 4 SHCJ, 7 agg, 9 exch | CJ out 8.9M, agg 6.2s, no sort |
| q64 | prod | 25051 | 36 | 22 BHCJ, 5 SHCJ, 7 agg, 12 exch | CJ out 10.2M, shuffle bytes 1.5GB |
| q97 | base | 7031 | 0 | 2 BHJ, 1 SMJ, 6 agg, 3 exch, 2 sort | SMJ out 4.1M, agg 13.8s |
| q97 | forced | 8139 | 0 | scalar presence-count rewrite | 12 agg, 5 exch, 3 broadcast exch |
| q97 | prod | 5855 | 0 | baseline shape, special rewrite gated off | same operators as base, faster warm run |

Interpretation:

- q4/q11 are not classical row-reduction wins. CountJoin mostly removes SMJ/sort mechanics but emits roughly the same row scale as the original joins. The remaining work is repeated customer/date summary handling and parent aggregation.
- q25/q50/q64 are the real target class: CountJoin either removes very expensive sort/aggregate work or drastically reduces rows before the parent aggregate.
- q64 exposed a robustness issue: prod introduced extra shuffle/exchange work relative to forced in this single run. That is worth investigating before the next broad benchmark.
- q97 is not a CountJoin inner-loop problem. The logical scalar-count rewrite improves the old full-outer split but adds aggregate/exchange branches. A physical full-outer presence-count path is the right shape.

### Why TPC-DS Is Harder Than TPCH/JOB/STATS Here

TPC-DS has several patterns that are uncommon or less severe in TPCH/JOB/STATS:

- repeated CTE/self-join summaries, especially q4/q11/q64, where the profitable unit is the shared summary/message, not a single binary join edge;
- many dimension attributes carried as grouping payloads, so a count-only edge can still have a wide grouped output;
- outer/presence-count idioms such as q97, where binary full-outer materialization is the wrong abstraction;
- CASE/HAVING/order/limit shapes around aggregates, where the rewrite must preserve final Spark semantics while trying to push aggregation earlier;
- low-selectivity dimension joins where CountJoin avoids sort but does not reduce rows enough to be a major win by itself.

This explains why TPCH/JOB/STATS can look good: they more often reward direct many-to-many reduction. TPC-DS requires shared-message reuse, aggregate fusion, and dedicated physical paths for summary-style queries.

### Research / Other-System Signal

Relevant papers/systems point in the same direction:

- Leapfrog Triejoin and related worst-case optimal join work emphasize multiway processing instead of one binary intermediate at a time: https://arxiv.org/abs/1210.0481
- FAQ/InsideOut frames aggregate queries as variable elimination over compact functions/messages, with the ordering and fused subqueries determining performance: https://arxiv.org/abs/1504.04044
- Juggling Functions Inside a Database describes InsideOut as query rewriting into easier aggregate subqueries that can be evaluated inside a DB engine: https://arxiv.org/abs/1703.03147
- LevelHeaded is useful as a practical warning: making worst-case-optimal ideas work for BI depends on common-case optimizations, not just the asymptotic join algorithm: https://arxiv.org/abs/1708.07859
- Spark SQL tuning docs reinforce that runtime statistics, partitioning, join strategy, and AQE shape matter materially for SQL performance: https://spark.apache.org/docs/latest/sql-performance-tuning.html

The actionable lesson for this codebase: do not chase a generic worst-case-optimal engine. Instead, add targeted Spark-native physical summaries for the TPC-DS shapes we now see.

### Ranked Candidate Optimizations

1. Physical aggregate-carrying CountJoin/AggJoin fusion.

   Target q25/q50/q64 first. These are already the best wins, and the metrics show the largest remaining opportunity is to make the aggregate-carrying path more native. Candidate mechanics:

   - recognize `HashAggregateExec` directly above CountJoin chains where CountJoin already computes `count`/`sum` contributions;
   - emit final aggregate rows from CountJoin into the parent aggregate grouping layout, avoiding an extra row-expansion/projection/aggregate pass when the parent aggregate is a pure merge;
   - keep the existing generic path as fallback.

   Expected payoff: broad hard-query speedups without harming TPCH/JOB/STATS, because it only fires on aggregate-carrying CountJoin shapes.

2. Shared message/materialization reuse for repeated CTE-style reductions.

   Target q4/q11 and the repeated halves of q64. The repeated `year_total`/`cross_sales` shapes rebuild the same customer/date messages several times. Candidate mechanics:

   - canonicalize repeated CountJoin build-side summaries so physical exchange reuse can actually reuse them;
   - introduce an explicit reusable message node for common grouped dimension lookups, e.g. `(customer_sk -> customer attrs)` and `(date_sk -> d_year)` once per query;
   - consider a planner rule that computes a compact CTE summary first, then joins the summaries, rather than pushing CountJoin independently into each CTE consumer.

   Expected payoff: turns q4/q11 from sort avoidance into actual less-work plans.

3. Physical full-outer presence-count operator/path.

   Target q97. Build one path that consumes both distinct key sets once and directly computes `(left_only, right_only, matched)`. Avoid full outer row materialization, three scalar aggregate branches, and cross joins.

   Expected payoff: converts q97 from a logical rewrite experiment into the natural physical algorithm for the query.

4. CountJoin planner-shape robustness.

   Target q64 prod-vs-forced drift. Investigate why prod planned one more shuffled CountJoin and three more exchanges in the diagnostics run. Candidate mechanics:

   - improve canonicalization of CountJoin key order and grouping order;
   - make cost-gated and forced paths preserve the same physical shape when they keep the same logical CountJoin region;
   - add a focused diagnostic assertion for q64-style duplicated subplans.

   Expected payoff: protects existing wins before broad benchmarking.

5. Narrow codegen/data-structure improvements only after shape work.

   Existing inner-loop changes helped but did not explain the remaining TPC-DS gap. Still plausible later:

   - primitive specialized grouped count-only maps for common one/two-column primitive group keys;
   - generated aggregate buffer update code that avoids generic `UnsafeRow` grouping projection when group columns are direct attributes;
   - precomputed ordinal reads for direct grouped-output unique-build paths.

   Expected payoff: incremental. Not likely to massively close the TPC-DS gap alone.

### Proposed Next Implementation Order

1. Investigate and fix q64 prod-vs-forced physical-shape drift first. It is a correctness-neutral planner robustness issue and can immediately protect an existing hard-query win.
2. Prototype aggregate-carrying CountJoin/AggJoin fusion on q25 and q64. Keep only if it improves both, or improves q64 without hurting q25/q50.
3. Prototype a q97 physical presence-count operator/path, guarded by the exact detected shape.
4. After those are stable, rerun the broad applicable TPC-DS set once, then only broaden gates if TPC-DS overall improves without weakening TPCH/JOB/STATS.

## Implemented Optimization: Derived Count Aggregate Fast Path

Date: 2026-06-19.

Change:

- Added a HashCountJoin codegen shortcut for parent-visible aggregate columns that are exactly derivable from the count the operator already maintains.
- Covered forms:
  - `count(non-null literal)` when the build-side count is the leaf `1`;
  - `sum(c)` when `c` is the propagated build-side count column.
- The shortcut reuses the existing count-only paths, but emits the visible aggregate result from the matched-row count or grouped count sum instead of allocating/updating/evaluating generic aggregate buffers.
- Non-derived aggregates, filtered/distinct aggregates, non-Long results, decimals, and value expressions still use the generic aggregate-buffer path.

Verification:

```bash
build/sbt 'sql/testOnly org.apache.spark.sql.YannakakisCorrectnessSuite -- -z "count-only count-join avoids aggregate buffers"'
```

Result: passed, 2 tests.

Focused TPC-DS diagnostic run with the fast path enabled:

```bash
TPCDS_DIAG_QUERIES=q25,q64,q72,q97 TPCDS_DIAG_MODES=base,forced \
  build/sbt 'sql/testOnly org.apache.spark.sql.TPCDSCountJoinDiagnosticsSuite'
```

| query | base ms | forced ms | forced/base | interpretation |
|---|---:|---:|---:|---|
| q25 | 26579 | 7650 | 0.29x | rewritten is much faster; residual aggregate time is tiny |
| q64 | 28555 | 22590 | 0.79x | rewritten is now faster in this run; dominated by derived `count(1)` / `sum(c)` carriers |
| q72 | 44442 | 86753 | 1.95x | still a bad plan-shape regression; only one CountJoin, but the rewrite doubles large join/agg work |
| q97 | 7302 | 7775 | 1.06x | not a CountJoin case; scalar presence-count rewrite remains slightly slower here |

A/B check with only the derived-count fast path temporarily disabled:

```bash
TPCDS_DIAG_QUERIES=q25,q64 TPCDS_DIAG_MODES=forced \
  build/sbt 'sql/testOnly org.apache.spark.sql.TPCDSCountJoinDiagnosticsSuite'
```

| query | enabled forced ms | disabled forced ms | disabled/enabled | conclusion |
|---|---:|---:|---:|---|
| q25 | 7650 | 11815 | 1.54x | fast path is clearly worthwhile |
| q64 | 22590 | 26595 | 1.18x | fast path is worthwhile despite run-to-run noise |

Key metric movement from the A/B:

- q25 enabled: codegen 12.3s, agg 102ms; disabled: codegen 15.3s, agg 126ms.
- q64 enabled: codegen 40.3s, agg 5.4s, CJ build 1.75s; disabled: codegen 46.7s, agg 6.3s, CJ build 2.23s.

Conclusion:

- Keep this optimization. It is narrow, correctness-conservative, and improves the exact repeated TPC-DS carrier shapes seen in q25/q64.
- It does not solve the remaining hard cases. q72 is a plan-shape/applicability problem, not a CountJoin inner-loop problem. q97 needs a physical full-outer presence-count path.
- The next serious optimization should target row-reduction/plan shape: prevent q72-style rewrites that add CountJoin without reducing the dominant large joins, and then revisit shared summary/materialization reuse for q4/q11/q64-style repeated CTE summaries.



## Four-Query Follow-Up: q4/q11/q50/q92

Date: 2026-06-19.

Command:

```bash
TPCDS_DIAG_QUERIES=q4,q11,q50,q92 TPCDS_DIAG_MODES=base,forced,prod \
  build/sbt 'sql/testOnly org.apache.spark.sql.TPCDSCountJoinDiagnosticsSuite'
```

Results:

| query | rows | base ms | forced ms | prod ms | forced/base speedup | prod/base speedup | forced CJs | prod CJs | cost gate behavior |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| q4 | 100 | 33291 | 26663 | 23552 | 1.25x | 1.41x | 36 | 12 | partially kept |
| q11 | 100 | 15229 | 13431 | 13104 | 1.13x | 1.16x | 16 | 8 | partially kept |
| q50 | 27 | 7615 | 4695 | 3845 | 1.62x | 1.98x | 4 | 4 | fully kept |
| q92 | 1 | 1358 | 1150 | 966 | 1.18x | 1.41x | 4 | 3 | partially kept |

Metric summary:

| query | base dominant work | prod dominant work | interpretation |
|---|---|---|---|
| q4 | 48.3M sort-merge rows, 9.8M broadcast-hash rows, 39.6s aggregate time | 58.0M CountJoin rows, 31.4s aggregate time | wins mostly by removing SMJ/sorts/codegen, not by reducing rows |
| q11 | 34.1M sort-merge rows, 6.9M broadcast-hash rows, 18.9s aggregate time | 40.8M CountJoin rows, 17.5s aggregate time | same class as q4, but smaller |
| q50 | 3.0M join rows before aggregation | 46.5K CountJoin rows | strong row-reduction win |
| q92 | 108K broadcast-hash rows in base | prod keeps one BHJ and emits only 4.8K CountJoin rows | hybrid prod plan is better than forced |

Planning observations:

- q4 and q11 are repeated deterministic CTE/self-join summary queries. The `year_total`
  CTE is referenced six times in q4 and four times in q11.
- Forced q4 expands to 36 logical CountJoins. Forced q11 expands to 16. They are all
  count-only (`aggs=[]`) and repeatedly carry wide customer attributes plus `d_year`.
- Prod is better than forced on q4/q11 because the gate keeps only a subset of the
  CountJoins. The remaining plan still rebuilds very similar customer/date reductions.
- q50 and q92 are the healthy class: CountJoin genuinely shrinks the row stream before
  the final aggregate.

CTE/reuse investigation:

- Spark has a built-in path for non-inlined CTE reuse: `ReplaceCTERefWithRepartition`
  inserts repartitions for retained CTE definitions, and `ReuseExchangeAndSubquery`
  can reuse identical exchanges in the physical plan.
- However, `InlineCTE` runs early and inlines deterministic CTEs, while the CountJoin
  rewrite runs later in the `Semijoin Rewrite` batch. q4/q11 therefore reach CountJoin
  as duplicated inlined fragments.
- A crude experiment excluding `InlineCTE` for q4/q11 did not produce a useful better
  shape:

```bash
TPCDS_DIAG_QUERIES=q4,q11 TPCDS_DIAG_MODES=base,prod \
  build/sbt -Dspark.sql.optimizer.excludedRules=org.apache.spark.sql.catalyst.optimizer.InlineCTE \
  'sql/testOnly org.apache.spark.sql.TPCDSCountJoinDiagnosticsSuite'
```

| query | mode | ms | logical CJs | comment |
|---|---|---:|---:|---|
| q4 | base | 41058 | 0 | slower than normal base run |
| q4 | prod | 28265 | 12 | same CountJoin count and operator class as normal prod, slower wall-clock |
| q11 | base | 15654 | 0 | essentially same as normal base |
| q11 | prod | 12891 | 8 | essentially same as normal prod |

Conclusion from this experiment: generic CTE preservation/materialization is not enough.
It may preserve too much of the CTE and trade duplicate filtered work for a larger shared
summary. The promising target is narrower factorization of the repeated summary pattern.

### Next Optimization Candidates

1. Multi-slice summary factorization for q4/q11.

   Instead of materializing the whole `year_total` CTE or independently rewriting each
   reference, recognize the repeated `(sale_type, dyear)` aliases and compute the needed
   two-year summary in one scan per sale channel:

   - store sales: per customer, compute year-2001 and year-2002 totals together;
   - catalog/web analogously;
   - then join the compact per-customer summaries.

   This is a targeted row-reduction/aggregate-fusion rewrite. It should reduce q4/q11
   work much more than another CountJoin inner-loop micro-optimization.

2. Bucketed conditional-aggregate fusion for q50.

   q50's five final aggregates are `sum(CASE WHEN returned_date - sold_date in bucket THEN 1 ELSE 0)`.
   The sales/returns CountJoin already has both date keys at the important join point.
   A physical aggregate-carrying CountJoin path could emit the five bucket counts directly
   per store group instead of producing the 46.5K intermediate rows and aggregating again.

3. Correlated-threshold reuse for q92.

   Prod's partial hybrid is good, but the query scans similar web-sales/date windows for
   the outer query and scalar average subquery. A targeted scalar-subquery decorrelation
   or reuse path may close the remaining gap without changing CountJoin broadly.

4. Keep q72 as a guarded non-target for now.

   A prod-only q72 check produced no logical CountJoins and used the baseline shape:

   ```bash
   TPCDS_DIAG_QUERIES=q72 TPCDS_DIAG_MODES=prod \
     build/sbt 'sql/testOnly org.apache.spark.sql.TPCDSCountJoinDiagnosticsSuite'
   ```

   Result: q72 prod 59553 ms, logical CJs 0. The forced regression is real, but the
   current gate protects production. The next broad optimization should not be tuned
   around q72 unless we add a dedicated outer/presence-count path.


## 2026-06-19: q50 conditional-count carry and broader check

Implemented the first small q50-oriented optimization: `SUM(CASE/IF ... THEN 1 ELSE 0)`
is now treated as a count-like conditional aggregate for product-conflict handling. This
lets q50 carry its five return-lag bucket counts through the CountJoin chain instead of
treating those expressions as conflicting value products.

Correctness:

```bash
build/sbt 'sql/testOnly org.apache.spark.sql.YannakakisCorrectnessSuite -- -z "count"'
```

Result: 64 tests passed.

Broad base/prod diagnostic run:

```bash
TPCDS_DIAG_QUERIES=q4,q11,q25,q50,q64,q72,q74,q92,q97 \
TPCDS_DIAG_MODES=base,prod \
  build/sbt 'sql/testOnly org.apache.spark.sql.TPCDSCountJoinDiagnosticsSuite'
```

| query | class | base ms | prod ms | speedup | prod logical CJs | prod-applied? | notes |
|---|---|---:|---:|---:|---:|---|---|
| q4 | repeated summary / hard | 38384 | 27161 | 1.41x | 12 | yes | moderate win; still 58.0M CountJoin output rows and no carried aggs |
| q11 | repeated summary / hard | 15763 | 12448 | 1.27x | 8 | yes | same shape as q4, smaller |
| q25 | product aggregate | 19780 | 6448 | 3.07x | 7 | yes | strong row/aggregate reduction |
| q50 | conditional buckets | 7201 | 4715 | 1.53x | 4 | yes | bucket counts now carried inside CountJoin |
| q64 | repeated cross-sales | 28145 | 16380 | 1.72x | 36 | yes | strong broad win despite complex shape |
| q72 | guarded non-target | 53119 | 44296 | 1.20x | 0 | no | same logical shape; timing noise/prod config only |
| q74 | repeated summary / gated out | 5444 | 3588 | 1.52x | 0 | no | same logical shape; apparent win is noise, not CountJoin |
| q92 | correlated threshold | 1329 | 1045 | 1.27x | 3 | yes | small hybrid win |
| q97 | full-outer presence | 6598 | 5849 | 1.13x | 0 | no | same logical shape; apparent win is noise |

q50 shape after the change:

- CountJoin output remains 46.5K rows; this is not yet full row reduction to store groups.
- The inner sales/returns shuffled CountJoin now carries five `sum(c)` aggregates grouped by
  `sr_returned_date_sk`.
- The store/date broadcast CountJoins carry five `count(1L)` aggregates.

q50 A/B against a temporary disabled helper:

| variant | q50 base ms | q50 prod ms | prod CountJoin aggs |
|---|---:|---:|---|
| enabled, single-query run | 13503 | 5613 | five carried bucket aggregates |
| enabled, broad run | 7201 | 4715 | five carried bucket aggregates |
| temporarily disabled | 12410 | 5848 | empty aggregate CountJoins |

Conclusion: the q50 conditional-count carry is safe and mildly positive, but it is not the
large missing optimization. It mostly moves bucket-expression work into the CountJoin chain;
it does not collapse the remaining per-date/per-left-row stream.

Planning follow-up:

- q4/q11/q74 are still the main structural gap. They repeatedly build the same deterministic
  `year_total` summary and then self-join specific `(sale_type, year)` slices.
- `InlineCTE` inlines deterministic CTEs before the CountJoin rewrite, so q4/q11/q74 arrive
  as duplicated fragments.
- Excluding `InlineCTE` preserves a broad CTE but did not improve q4/q11; the useful target is
  narrower multi-slice summary factorization, not generic CTE materialization.
- The next serious implementation should recognize/fuse per-channel two-year summaries so each
  sales channel is scanned and aggregated once, producing compact first-year/second-year columns
  per customer before the final channel comparison joins.


## 2026-06-19: stats-aware CountJoin child ordering

Implemented a small plan-shape optimization in the hypertree executor: child subtrees are now
processed in ascending `stats.sizeInBytes`, with the original edge name as deterministic tie-breaker.
The previous edge-name-only order could join a wide dimension before a highly selective filtered
dimension. In q4/q11 this meant customer was often joined before the date slice, so the later
aggregate still carried many more rows than necessary.

Focused diagnostics after the change, SF5, `shuffle.partitions=16`, AQE on:

| query | mode | base ms | rewritten/prod ms | speedup | logical CJs | CountJoin output | shuffle bytes | notes |
|---|---|---:|---:|---:|---:|---:|---:|---|
| q4 | forced | 34475 | 22226 | 1.55x | 36 | 19.3M | 319.7 MB | forced output down from the old ~58M level |
| q4 | prod | 38937 | 20388 | 1.91x | 12 | 19.3M | 319.7 MB | cost gate keeps the useful subset |
| q11 | forced | 22586 | 12460 | 1.81x | 16 | 13.6M | 143.2 MB | forced output down from the old ~40.8M level |
| q11 | prod | 15833 | 9656 | 1.64x | 8 | 13.6M | 143.2 MB | cost gate keeps the useful subset |
| q64 | forced | 28528 | 15609 | 1.83x | 36 | 83.8M | 151.0 MB | remains a strong win |
| q64 | prod | 29106 | 17137 | 1.70x | 36 | 83.8M | 137.4 MB | no regression on already-good hard query |

Decision: keep. This is the first broad structural improvement for the repeated-summary TPC-DS
class. It does not implement full aggregate fusion, but it narrows the gap by making existing
CountJoin reductions happen earlier in the tree.

Remaining gap:

- q4/q11 still build and aggregate each year/channel slice independently.
- A larger multi-slice summary factorization could still scan each sales channel once and emit
  first-year/second-year measures together.
- Build-side pre-aggregation or aggregate-through-unique-dimension rewriting remains the deeper
  route for cases where the best root would otherwise place a large fact-derived subtree on the
  CountJoin build side.


## 2026-06-19: decorating-dimension pre-aggregation

Implemented a logical row-reduction rewrite for aggregate-over-inner-join shapes where one pure
decorating dimension contributes grouping columns but no aggregate inputs. The rewrite computes a
partial aggregate on the non-dimension side grouped by the dimension join key plus the other grouping
keys, joins those compact partial rows to the dimension, then runs a final merge aggregate by the
original grouping expressions.

Important correctness detail:

- The final aggregate is retained even if the dimension key is unique or looks unique. This preserves
  SQL multiplicities when the decorating dimension contains duplicate rows: the post-aggregate join
  duplicates the partial row the same way the original join duplicated every input row, and the final
  merge folds those rows into the original groups.
- The rewrite only handles mergeable `count`, `sum`, `min`, and `max` without DISTINCT/FILTER, only
  for deterministic project/filter/leaf dimension inputs, and it respects
  `spark.sql.yannakakis.unguardedEnabled`.
- It is intentionally not disabled by the CountJoin cost gate: the direct pre-aggregation is the
  production row-reduction mechanism for q4/q11-like shapes, and may leave zero logical CountJoins in
  the optimized plan while still being a Yannakakis-family rewrite.

Plan-shape effect from `TPCDSCountJoinPlanDumpSuite` with the rewrite forced:

| query | old forced logical CountJoins | new forced logical CountJoins | interpretation |
|---|---:|---:|---|
| q4 | 36 | 6 | each sales-channel/year slice now aggregates sales/date by customer before joining customer attrs |
| q11 | 16 | 4 | same customer-decoration reduction for the two-channel version |

Focused correctness:

```bash
build/sbt 'sql/testOnly org.apache.spark.sql.YannakakisCorrectnessSuite -- -z "decorating dimension pre-aggregate"'
```

Result: passed. The test includes duplicate customer-dimension rows to cover the final merge
semantics.

Broader correctness:

```bash
build/sbt 'sql/testOnly org.apache.spark.sql.YannakakisCorrectnessSuite -- -z "count"'
```

Result: passed, 64 tests.

Target/control diagnostics after the change, SF5 parquet, `shuffle.partitions=16`, AQE on:

```bash
TPCDS_DIAG_QUERIES=q4,q11,q25,q50,q64,q92,q72,q97 TPCDS_DIAG_MODES=base,forced,prod \
  build/sbt 'sql/testOnly org.apache.spark.sql.TPCDSCountJoinDiagnosticsSuite'
```

| query | base ms | forced ms | prod ms | prod/base speedup | forced logical CJs | prod logical CJs | notes |
|---|---:|---:|---:|---:|---:|---:|---|
| q4 | 38418 | 19203 | 15043 | 2.55x | 6 | 0 | direct pre-agg removes SMJ/sorts and drops shuffle records from 25.7M to 2.7M |
| q11 | 15895 | 8088 | 6379 | 2.49x | 4 | 0 | same shape; shuffle records drop from 18.1M to 1.6M |
| q25 | 20519 | 3424 | 1953 | 10.51x | 5 | 5 | already-good product aggregate class improves further in this run |
| q50 | 6745 | 5279 | 4651 | 1.45x | 3 | 3 | still positive, but not full bucket fusion; one date CountJoin remains very large |
| q64 | 28501 | 16191 | 15367 | 1.85x | 28 | 28 | remains a strong win; fewer CJs than the earlier 36-CJ shape |
| q92 | 1599 | 1890 | 1350 | 1.18x | 4 | 3 | forced remains noisy/slower; prod hybrid is still protected |
| q72 | 57273 | 107232 | 51252 | 1.12x | 1 | 0 | forced remains a bad non-target; prod keeps baseline shape |
| q97 | 9034 | 8643 | 5922 | 1.53x | 0 | 0 | prod remains baseline-shaped; direct q97 scalar rewrite still forced-only |

Interpretation:

- This is the first optimization that materially bridges the q4/q11 repeated-summary gap rather than
  merely avoiding sort-merge joins. The win comes from row reduction before customer decoration.
- `prod logical CJs = 0` on q4/q11 does not mean no rewrite happened. It means the direct
  pre-aggregation shape won and the recursive reduced sales/date part did not keep CountJoin under
  the cost gate.
- q25 and q64 suggest the pattern generalizes to other hard TPC-DS summaries where a wide dimension
  was being joined before aggregation.
- q50 remains only partly solved. The conditional bucket counts are carried, but the plan still emits
  a large date-expanded stream (`13.3M` CountJoin output rows in this diagnostic). A deeper bucketed
  aggregate fusion would need to collapse those rows directly to store-level buckets. A focused plan
  dump showed the underlying reason: the `store_sales` -> unfiltered `date_dim d1` CountJoin is a
  pure existence/count join with no groups or aggregates, estimated at `250.9M` input rows. The
  selective returned-date side (`d2` filtered to 31 rows) is in a sibling subtree, so the issue is
  delayed pure-existence dimensions or hypertree root/decomposition choice, not CountJoin inner-loop
  overhead.
- q92 is still a correlated-threshold/reuse problem rather than a decorating-dimension problem.
- q72 and q97 remain correctly protected by production gating. q72 is a plan-shape/applicability
  non-target; q97 still wants a dedicated physical full-outer presence-count path.

Updated next candidates after this change:

1. q50 delayed-existence/bucket fusion: defer the pure `store_sales` -> unfiltered `date_dim d1`
   existence join until after the selective returns/date branch, or choose a hypertree root that
   reaches the selective branch first. Then push the return-lag bucket conditions to the point where
   both sold/returned dates are known and emit per-store bucket counters directly, avoiding the
   13M-row date-expanded stream.
2. Physical full-outer presence-count path for q97: one physical algorithm over the two distinct key
   sets should still be better than both vanilla full outer materialization and the scalar-branch
   logical rewrite.
3. Correlated scalar threshold reuse for q92: avoid scanning/aggregating the same web-sales/date
   window separately for the scalar average and outer query.
4. Broader validation: rerun the full applicable TPC-DS set once after any bucket/q97/q92 solution,
   because the current target/control set is now strongly positive but not a full-suite measurement.



## 2026-06-19 handoff: current state, next work, and benchmark commands

This section is meant to make the work movable to another machine without relying on session
history.

Current local state at handoff:

- The latest local optimizer diff adds conservative production guards around the
  decorating-dimension pre-aggregate path in
  `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/optimizer/RewriteJoinsAsSemijoins.scala`.
- The guard is active only when `spark.sql.yannakakis.costGateEnabled=true`; forced mode still
  exposes the old shapes for diagnosis.
- Focused correctness passed:

```bash
build/sbt 'sql/testOnly org.apache.spark.sql.YannakakisCorrectnessSuite -- -z "decorating"'
```

Why the latest guard was added:

- The full pre-guard sweep found many real wins, but also several direct pre-aggregate production
  slowdowns and q24a/q24b timeout-level regressions.
- q4/q11 are the healthy direct-pre-aggregate class: wide customer decoration, large SMJ/sort
  removal, and much lower shuffle records.
- q34/q47/q57/q73/q89 were unhealthy direct-pre-aggregate cases: narrower item/date/store-style
  decoration and large aggregate/shuffle expansion.
- q24a/q24b should be CountJoin wins, not direct-pre-aggregate timeouts.

Latest focused post-guard result:

| query | status after guard | base ms | prod ms | interpretation |
|---|---|---:|---:|---|
| q4 | direct pre-agg preserved | 26914 | 13758 | real speedup |
| q11 | direct pre-agg preserved | 13423 | 6372 | real speedup |
| q24a | routed to CountJoin | 17212 | 9558 | real speedup; old prod timed out |
| q24b | routed to CountJoin | 10997 | 6892 | real speedup; old prod timed out |
| q25 | CountJoin preserved | 24409 | 4254 | real speedup |
| q29 | CountJoin preserved | 19437 | 4672 | real speedup |
| q31 | small shape change | 6657 | 3132 | promising but repeat before claiming |
| q64 | CountJoin preserved | 25604 | 12699 | real speedup |
| q3 | baseline-shaped | 1296 | 703 | old regression removed; timing is noise |
| q34 | baseline-shaped | 2121 | 1356 | old regression removed; timing is noise |
| q47 | baseline-shaped | 7101 | 5660 | old regression removed; timing is noise |
| q52 | baseline-shaped | 1140 | 648 | old direct rewrite skipped; timing is noise |
| q57 | baseline-shaped | 3203 | 2241 | old regression removed; timing is noise |
| q71 | baseline-shaped | 1920 | 1365 | old direct rewrite skipped; timing is noise |
| q73 | baseline-shaped | 1616 | 954 | old regression removed; timing is noise |
| q89 | baseline-shaped | 1920 | 1096 | old regression removed; timing is noise |
| q91 | baseline-shaped | 948 | 706 | old direct rewrite skipped; timing is noise |
| q98 | baseline-shaped | 2088 | 1104 | old direct rewrite skipped; timing is noise |

Do not count same-shape `prod` timings as speedups. In many single-pass diagnostics, `prod` runs
after `base`, so the JVM, data cache, and generated code may be warmer. If the operator multiset and
stable counters match the baseline, classify the row as "no production rewrite" even if `prod` is
faster.

Full pre-guard sweep, after applying that rule:

| scope | speedups | slowdowns | neutral/same-shape | failed |
|---|---:|---:|---:|---:|
| 103 TPC-DS resource variants before latest guard | 26 | 6 | 69 | 2 |

The six old production slowdowns were `q3,q34,q47,q57,q73,q89`. The two failures were q24a/q24b
prod timeout/cancellation. The focused post-guard rerun removed those failures/slowdowns on the
checked set. A new full post-guard production sweep is still needed before quoting final TPC-DS-wide
counts.

### Remaining optimization opportunities

1. q50 delayed-existence and bucket fusion.

   q50 is the most concrete next target. The current plan still emits a large date-expanded stream:
   one date CountJoin contributes about 13M rows even though the selective returned-date branch is
   tiny. The right fix is plan shape, not CountJoin loop tuning:

   - defer the pure `store_sales -> date_dim d1` existence/count step until after the selective
     returns/date side is known; or
   - choose a hypertree root/order that reaches the selective returned-date branch first; then
   - evaluate the sold/returned-date lag buckets where both dates are available and emit compact
     per-store bucket counters directly.

   Success criterion: q50 should improve beyond the current roughly 1.4x-2.0x range and reduce the
   large pure date CountJoin output, without harming q25/q64.

2. Aggregate-carrying CountJoin/AggJoin fusion.

   q25, q29, q50, and q64 are already strong CountJoin wins, but the physical plan still has parent
   aggregate/projection work around CountJoin-carried counts and sums. A dedicated fusion path could
   let CountJoin emit directly into the parent aggregate's grouping layout for pure merge cases.

   Good first targets: q25 and q64. Keep the change only if it helps q64 and does not regress
   q25/q50.

3. q4/q11 repeated-summary factorization.

   Decorating-dimension pre-aggregation made q4/q11 much better, but each sales-channel/year slice
   is still built and aggregated mostly independently. A larger summary-sharing rewrite could scan
   each channel once and produce first-year/second-year measures together.

   This is more invasive than q50 because it crosses sibling summary subqueries.

4. q97 physical full-outer presence-count path.

   Logical q97 rewrites improved operator counts but still failed to beat baseline reliably. The
   promising shape is a physical algorithm over the two distinct key sets that directly computes
   `left_only`, `right_only`, and `matched`, avoiding full outer row materialization and avoiding
   three scalar aggregate branches.

   Keep q97 protected by the cost gate until there is a dedicated physical path.

5. q92 correlated scalar threshold reuse.

   q92 still looks like a reuse/correlation problem: the scalar average and the outer query touch
   the same web-sales/date window. The likely optimization is shared window aggregation or a rewrite
   that computes the threshold and qualifying rows from one compact summary.

6. Broaden the gate only after plan-shape fixes.

   Many forced-only candidates were faster in the old sweep, but several forced shapes were bad
   because they added CountJoin without reducing the dominant large joins. The next gate-widening
   pass should happen after q50/q97/q92-style improvements, not before.

### Benchmark data prerequisites

TPC-DS diagnostic/proper suites use real data if present and create parquet cache directories when
needed.

Reproducible setup: on a fresh machine with no data/toolchain, run `./tpcds-countjoin-setup.sh`.
It installs `flex`/`bison`/OpenJDK 17, clones and builds `databricks/tpcds-kit` dsdgen (patched for
GCC 14+), and generates SF5 `.dat` data into `/tmp/tpcds-sf5`. Override `SCALE`, `TPCDS_DIAG_DATA`,
`TPCDS_DIAG_PARQUET`, etc. via env vars. Then run the full sweep with `./tpcds-countjoin-sweep.sh`
(see "Full TPC-DS production diagnostic sweep" below — the script encodes the same grouping).

Defaults:

- `TPCDS_DIAG_DATA=/tmp/tpcds-sf5`
- `TPCDS_DIAG_PARQUET=/tmp/tpcds-sf5-parquet`
- `TPCDS_PROPER_DATA=/tmp/tpcds-sf5`
- `TPCDS_PROPER_PARQUET=/tmp/tpcds-sf5-parquet`

Override them on the new server if the data lives elsewhere.

The diagnostic suite configuration is fixed in code to:

- `spark.sql.shuffle.partitions=16`
- `spark.sql.autoBroadcastJoinThreshold=10MB`
- AQE enabled unless changed elsewhere
- whole-stage codegen enabled unless changed elsewhere

### Correctness and planning checks

Focused correctness after optimizer changes:

```bash
build/sbt 'sql/testOnly org.apache.spark.sql.YannakakisCorrectnessSuite -- -z "decorating"'
```

Broader count/rewrite correctness:

```bash
build/sbt 'sql/testOnly org.apache.spark.sql.YannakakisCorrectnessSuite -- -z "count"'
```

Planning-only TPC-DS applicability:

```bash
build/sbt 'sql/testOnly org.apache.spark.sql.TPCDSApplicabilitySuite'
```

Caveat: `TPCDSApplicabilitySuite` counts logical `CountJoin`/semijoin markers. It does not fully
capture direct non-CountJoin rewrites such as decorating-dimension pre-aggregation, so use it for
crash/applicability orientation, not for final production coverage.

Plan dump for specific queries:

```bash
TPCDS_DUMP_QUERIES=q4,q11,q24a,q24b,q25,q29,q50,q64,q92,q97 \
  build/sbt 'sql/testOnly org.apache.spark.sql.TPCDSCountJoinPlanDumpSuite'
```

This is planning-only and uses injected stats. It prints CountJoin blocks under cost gate off and
on. It also misses direct non-CountJoin rewrites except through the changed optimized plan shape.

### Focused TPC-DS diagnostics

Use this for quick performance/counter checks after a candidate optimization:

```bash
TPCDS_DIAG_DATA=/tmp/tpcds-sf5 \
TPCDS_DIAG_PARQUET=/tmp/tpcds-sf5-parquet \
TPCDS_DIAG_QUERIES=q4,q11,q24a,q24b,q25,q29,q50,q64,q92,q97 \
TPCDS_DIAG_MODES=base,prod \
  build/sbt 'sql/testOnly org.apache.spark.sql.TPCDSCountJoinDiagnosticsSuite'
```

Add `forced` when investigating cost-gated opportunities:

```bash
TPCDS_DIAG_DATA=/tmp/tpcds-sf5 \
TPCDS_DIAG_PARQUET=/tmp/tpcds-sf5-parquet \
TPCDS_DIAG_QUERIES=q50,q64,q92,q97 \
TPCDS_DIAG_MODES=base,forced,prod \
TPCDS_DIAG_PRINT_PLAN=true \
  build/sbt 'sql/testOnly org.apache.spark.sql.TPCDSCountJoinDiagnosticsSuite'
```

Interpretation rules:

- `base`: Yannakakis disabled.
- `forced`: Yannakakis enabled, unguarded enabled, cost gate disabled.
- `prod`: Yannakakis enabled, unguarded enabled, cost gate enabled.
- Direct pre-aggregate rewrites can have `logicalCountJoins=0`; do not use that field alone to decide
  whether production rewrote the query.
- If `base` and `prod` have the same operator multiset and the same stable counters, classify the
  row as no production rewrite. Faster `prod` in that case is warmup/order noise.
- Useful stable counters: `cjOut`, `cjBuildBytes`, `smjOut`, `bhjOut`, `aggOut`,
  `shuffleRecords`, and `sortPeak`. `shuffleBytes` can jitter slightly across otherwise identical
  plans.

### Full TPC-DS production diagnostic sweep

This is the right next broad measurement for current production behavior. It avoids forced-mode
timeouts and answers "how many queries are faster/slower in production now?"

```bash
OUT=/tmp/tpcds-countjoin-prod-$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUT"

export TPCDS_DIAG_DATA=/tmp/tpcds-sf5
export TPCDS_DIAG_PARQUET=/tmp/tpcds-sf5-parquet
export TPCDS_DIAG_MODES=base,prod

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
  TPCDS_DIAG_QUERIES="$QS" timeout 45m \
    build/sbt 'sql/testOnly org.apache.spark.sql.TPCDSCountJoinDiagnosticsSuite' \
    > "$OUT/$SAFE.log" 2>&1
  STATUS=$?
  echo "DONE $QS status=$STATUS $(date --iso-8601=seconds)" | tee -a "$OUT/manifest.log"
done
```

Summarize raw diagnostic lines:

```bash
rg 'TPCDS-DIAG: .* \| (base|prod) \|' "$OUT"/*.log > "$OUT/results.txt"
rg 'TPCDS-DIAG-COUNTJOIN:' "$OUT"/*.log > "$OUT/countjoins.txt"
```

When making the final table, include:

- query
- whether production changed shape
- whether production contains logical CountJoin
- whether the physical shape contains CountJoin
- base ms
- prod ms
- speedup only if shape changed
- key counters: `cjOut`, `smjOut`, `bhjOut`, `aggOut`, `shuffleRecords`, `sortPeak`

### Full forced/gate exploration sweep

Run this when looking for new candidates or understanding what the gate skips. It is more fragile
because forced mode intentionally exposes bad experimental shapes.

```bash
OUT=/tmp/tpcds-countjoin-forced-$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUT"

export TPCDS_DIAG_DATA=/tmp/tpcds-sf5
export TPCDS_DIAG_PARQUET=/tmp/tpcds-sf5-parquet
export TPCDS_DIAG_MODES=base,forced,prod

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
  TPCDS_DIAG_QUERIES="$QS" timeout 45m \
    build/sbt 'sql/testOnly org.apache.spark.sql.TPCDSCountJoinDiagnosticsSuite' \
    > "$OUT/$SAFE.log" 2>&1
  STATUS=$?
  echo "DONE $QS status=$STATUS $(date --iso-8601=seconds)" | tee -a "$OUT/manifest.log"
done
```

If a forced chunk times out, rerun the later queries from that chunk separately so one bad forced
shape does not hide unrelated results.

### Repeated timing benchmark

The current proper benchmark is useful for forced-mode candidate timing because it warms and
interleaves `base` and `rewritten` runs. It does not time cost-gated `prod`; it only reports the
cost-gated CountJoin count as `gate=Y/N`.

Focused repeated forced timing:

```bash
TPCDS_PROPER_DATA=/tmp/tpcds-sf5 \
TPCDS_PROPER_PARQUET=/tmp/tpcds-sf5-parquet \
TPCDS_PROPER_QUERIES=q4,q11,q24a,q24b,q25,q29,q50,q64,q92,q97 \
TPCDS_PROPER_WARMUPS=1 \
TPCDS_PROPER_REPETITIONS=3 \
TPCDS_PROPER_SEED=20260619 \
  build/sbt 'sql/testOnly org.apache.spark.sql.TPCDSCountJoinProperBenchmarkSuite'
```

Use this to validate a forced-mode optimization before deciding whether the production gate should
keep it. For current production numbers, use `TPCDSCountJoinDiagnosticsSuite` with
`TPCDS_DIAG_MODES=base,prod`, or extend `TPCDSCountJoinProperBenchmarkSuite` to time `cost-gated` as
a third measured mode.

### TPCH and JOB checks

TPC-H benchmark suite:

```bash
TPCH_DIR=/tmp/tpch-sf1-pq \
  build/sbt 'set Test/javaOptions += "-Xmx12g"' \
    'sql/testOnly org.apache.spark.sql.TPCHBenchmarkSuite'
```

TPC-H sweep correctness is included in that suite. It skips if `TPCH_DIR` is absent.

JOB benchmark suite:

```bash
build/sbt 'set Test/javaOptions += "-Xmx12g"' \
  'sql/testOnly org.apache.spark.sql.JOBBenchmarkSuite'
```

The JOB suite currently has hard-coded paths:

- `/home/as/git/Spark-Y/data/parquet/imdb`
- `/home/as/git/Spark-Y/data/job`

On a new server, either put/symlink the data there or parameterize the suite before running it.

No dedicated STATS benchmark harness was found in this checkout under
`sql/core/src/test/scala/org/apache/spark/sql`; use the external STATS harness if it exists on the
benchmark machine.

## 2026-06-19 optimization lead: pre-aggregate through an IN-semijoin (q14a/q14b)

Source: SF5 warm-min gate sweep (`base,forced,prod`, suite warmup=1/iters=2, report MIN ms).
Classification uses plan shape + work counters, not raw ms.

Opportunity: q14a/q14b's per-channel aggregate is structurally a q4/q11-style decorating-dimension
pre-aggregate win, but the rewrite is currently blocked from firing by an `IN (...)` semijoin that
wraps the inner join. Teaching `tryPushAggregatePastDecoratingDimension` to push the per-channel
`sum`/`count` below the `item` join *through* that semijoin could convert q14a/q14b from
gate-skip-correct into wins.

Measured state (warm-min, SF5):

| query | base ms | forced ms | prod ms | prod shape | gate decision |
|---|---:|---:|---:|---|---|
| q14a | 15691 | 23921 (-52%) | 15171 | unchanged (== base) | correctly skipped |
| q14b | 13354 | 21084 (-58%) | 13067 | unchanged (== base) | correctly skipped |

In `forced` (cost gate off) the rule takes the counting/semijoin path (CountJoin + LeftSemi
reductions for the 3-way channel INTERSECT). That path is non-reducing here: `shuffleRecords` is
unchanged (~31M / ~20M) while it adds sort-merge joins and sorts. Counter damage in forced vs base:
`smjOut` 56.8K -> 23.6M (~400x), `sortPeak` 1.17G -> 4.56G (~4x), `aggMs` 7.1K -> 53.8K (~7.6x).
The cost gate correctly rejects this and `prod` stays on the base plan. So the current prod behavior
is right; this is about unlocking a *new* win, not fixing a regression.

Why the per-channel block is pre-agg-eligible (same shape as q4/q11):

```sql
SELECT i_brand_id, i_class_id, i_category_id,
       sum(ss_quantity * ss_list_price), count(*)   -- mergeable sum + count
FROM store_sales, item, date_dim                     -- InnerLike, 3 items
WHERE ss_item_sk IN (SELECT ... FROM cross_items)    -- LeftSemi wrapper (the blocker)
  AND ss_item_sk = i_item_sk AND ss_sold_date_sk = d_date_sk
  AND d_year = 2001 AND d_moy = 11
GROUP BY i_brand_id, i_class_id, i_category_id        -- 3 grouping refs on wide dim `item`
HAVING sum(...) > (SELECT average_sales FROM avg_sales)
```

The aggregate types, the >=3-item inner join, and the wide decorating dimension (`item` carrying
brand/class/category) all match the pre-agg pre-reqs in `tryPushAggregatePastDecoratingDimension`
(RewriteJoinsAsSemijoins.scala:1808-1924). The blocker is the `ss_item_sk IN (cross_items)`
semijoin (and the scalar `HAVING`) between the aggregate and the inner join: it breaks the
`Aggregate -> (Project) -> InnerLike Join of >=3 plain items` match, and `extractInnerJoins`
(scala:1860) does not surface 3 clean items through the LeftSemi.

Caveats / ranking:

- The dominant cost of q14 is the three-way channel INTERSECT (existence / LeftSemi reduction),
  which pre-agg does not touch. So even a successful pre-agg push is an incremental per-channel win,
  not a fix for the whole query. Rank this BELOW q50 (delayed-existence/bucket fusion) and q64
  (aggregate-carrying CountJoin fusion).
- Confidence: HIGH that pre-agg is currently blocked (empirical: `forced` does not produce the
  pre-agg shape, and `forced` runs pre-agg first with no gate guards). MEDIUM on exactly which
  pre-req rejects it (semijoin wrapper vs `extractInnerJoins` item count). Confirming needs a
  debug-logged plan trace of `tryPushAggregatePastDecoratingDimension` on q14a.
- Validation if pursued: q14a/q14b should move from `unchanged`/gate-skip to a CHANGED pre-agg shape
  with reduced `shuffleRecords` and `sortPeak`, without regressing the q4/q11/q24/q25/q29/q64 wins.

## 2026-06-19 gate-tuning candidates: q15 / q58 possible missed opportunities (NEEDS VERIFICATION)

Source: same SF5 warm-min gate sweep (q15 in group 8 light batch 1; q58 in group 7 semijoin
mediums). Both flagged `gate-skip MISSED-OPP`: `forced` was faster than `base` while the cost gate
kept the base plan (prod == base).

| query | base ms | forced ms | prod ms | prod shape | flag | strength |
|---|---:|---:|---:|---|---|---|
| q15 | 1541 | 644 (+58%) | 1470 | unchanged (== base) | gate-skip MISSED-OPP? | clearer (2.4x, beyond noise floor) |
| q69 | 1819 | 1232 (+32%) | 1863 | unchanged (== base) | gate-skip MISSED-OPP? | moderate (above ~22% floor) |
| q58 | 1531 | 1130 (+26%) | 1431 | unchanged (== base) | gate-skip MISSED-OPP? | weak (near noise floor) |

CAVEAT - both are small (~1.5s) queries, so verify before trusting:

- The warm-min noise floor measured on same-shape q1 is ~22% at warmup=1/iters=2. q58's +26% is
  barely above it; q15's +58% (2.4x) is clearly above it and is the stronger lead.
- Work counters in the swept (prod==base) shape are tiny (q15 `shuffleRecords` ~485; q58 ~120.8K),
  so there is not yet counter-level evidence of a structural win - the forced edge must be confirmed
  with the forced-mode counters at higher iterations.

Verification plan (run when the sweep frees the build; do NOT run concurrently with another sbt):

```bash
TPCDS_DIAG_DATA=/tmp/tpcds-sf5 TPCDS_DIAG_PARQUET=/tmp/tpcds-sf5-parquet \
TPCDS_DIAG_QUERIES=q15,q69,q58 TPCDS_DIAG_MODES=base,forced,prod \
TPCDS_DIAG_WARMUP=2 TPCDS_DIAG_ITERS=5 \
  build/sbt 'sql/testOnly org.apache.spark.sql.TPCDSCountJoinDiagnosticsSuite'
```

Treat as a real missed opportunity ONLY if, at warmup=2/iters=5, `forced` stays clearly below `base`
(beyond the ~20% floor) AND `forced` shows reduced `shuffleRecords` vs `base`. Otherwise close as
warmup/order noise. If real, the lead is: relax the cost gate so the (semijoin-reduction) forced
shape is allowed in prod for these queries.

VERDICT (warmup=2/iters=5, 5 iterations each, distributions checked for overlap):

| query | base min | forced min | speedup | distributions overlap? | shufRec base->forced | verdict |
|---|---:|---:|---:|---|---|---|
| q15 | 1607 | 677 | +58% | NO (forced 677-723 vs base 1607-1818) | 485 -> 483470 | CONFIRMED real |
| q69 | 1911 | 1238 | +35% | NO (forced 1238-1309 vs base 1911-2058) | 981398 -> 984133 | CONFIRMED real |
| q58 | 1440 | 1188 | +17% | NO but close (forced max 1316 < base min 1440) | 120775 -> 282086 | borderline (real, small) |

q15 and q69 are CONFIRMED real missed opportunities: the per-iteration distributions do not overlap,
so the speedup is not warmup/order noise - the cost gate is skipping a rewrite that is genuinely
58% / 35% faster.

IMPORTANT correction to the original criterion: the "forced must REDUCE shuffleRecords" filter was
WRONG. q15 forced shuffles ~1000x MORE records (485 -> 483K) yet is robustly 58% faster, and q58
shuffles ~2.3x more yet is faster. So the win does NOT come from shuffle reduction; the forced
(semijoin-reduction) shape avoids some expensive operation in the base broadcast plan despite adding
a shuffle. Use ms distribution non-overlap as the primary real/noise test for small queries, not the
shuffle counter.

Lead for the optimization pass: the cost gate is OVER-CONSERVATIVE for this semijoin-reduction shape
on q15/q69 (and marginally q58) - the mirror image of the forced-slowdown robustness item. Next step
is to capture the q15/q69 forced PLAN (TPCDS_DIAG_PRINT_PLAN=true) to see which base operation the
rewrite avoids, then teach the gate to allow it. Rank q15 first (largest, cleanest signal).

## 2026-06-19 robustness investigation (DEFERRED): make forced (ungated) slowdowns less bad

Motivation: several `forced` (cost-gate OFF) runs are not just "rewrite not worth it" - they are
catastrophic and look pathological (q3 ~9x, q2 ~4x, q72/q95 ~1.8x, q94 ~1.7x slower). Production is
currently safe only because the cost gate vetoes them (all are correctly `gate-skip` in prod). We
want defense in depth: make the rewrite's worst case closer to base so robustness does not rely
solely on the gate's stats being right. If the gate ever mis-estimates, the downside should be mild,
not a 2-9x regression.

Evidence (SF5 warm-min, base vs forced):

| query | SMJ b->f | Sort b->f | smjOut b->f | sortPeak b->f | cjOut (forced) | shufRec b->f | ms b->f |
|---|---|---|---|---|---:|---|---|
| q72 | 1->2 | 2->4 | 390.9M->781.8M | 5.64G->11.27G | 1.5K | 54.0M->54.0M | 48892->87580 |
| q95 | 4->8 | 6->12 | 77.5M->154.9M | 3.72G->6.99G | 302K | 7.2M->10.8M | 17561->31997 |
| q94 | 1->2 | 2->4 | 3.6M->7.2M | 822M->1.64G | 115K | 7.2M->10.8M | 2799->4695 |
| q14a | 2->6 | 4->12 | 56.8K->23.6M | 1.17G->4.56G | 782.9K | 31.3M->31.2M | 15691->23921 |
| q2 | 0->0 | 1->1 | 0->0 | 67M->67M | 10.76M | 3.9K->3.9K | 1688->6598 |
| q3 | 0->0 | 0->0 | 0->0 | 0->0 | 1.70M | 1.5K->14.16M | 690->6056 |

Two suspicious failure modes:

- MODE A - exact work-DOUBLING (q72, q94, q95): forced precisely doubles SMJ count, Sort count,
  `smjOut`, and `sortPeak`. The 2x is the tell: the reducer is added as a DUPLICATE branch that
  re-sorts/re-joins the same large input the base plan already processes, instead of replacing or
  shrinking it. A semijoin reduction should reduce the input before the join; here it appears purely
  additive. (q14a is the same shape but the reduction itself expands -> `smjOut` 416x.)
- MODE B - stream EXPLOSION (q2, q3): forced injects a CountJoin whose output balloons 1000-10000x
  over a tiny base (q3: 1.5K -> 14.2M shuffle records; q2: cjOut 0 -> 10.8M) for queries whose final
  result is only thousands of rows. The count-join fan-out is not reduced.

Gate-independent robustness directions to investigate:

1. Make the rewrite strictly REPLACE, not augment. If a semijoin reducer cannot actually shrink the
   downstream join input (Mode A), do not materialize it as an extra sorted/joined branch - reuse the
   existing scan/build, or skip the reducer. Worst case should then be ~= base, not 2x base.
2. Bound CountJoin construction by estimated fan-out (Mode B): if the count-join's estimated output
   (`cjOut`) vastly exceeds the final aggregate output / base shuffle, the count carry is not paying
   for itself - build it only when the carried count is expected to reduce, not expand, the stream.
   This is a structural guard inside the builder, distinct from the whole-plan cost gate.
3. Avoid double-sorting: where the reducer and the main join both sort the same key, share one sort.

Deferred deep-investigation method (run when the build is free; needs the forced PLAN, which the
sweep did not capture):

```bash
TPCDS_DIAG_DATA=/tmp/tpcds-sf5 TPCDS_DIAG_PARQUET=/tmp/tpcds-sf5-parquet \
TPCDS_DIAG_QUERIES=q3,q2,q72,q95,q94,q14a TPCDS_DIAG_MODES=base,forced \
TPCDS_DIAG_PRINT_PLAN=true TPCDS_DIAG_WARMUP=1 TPCDS_DIAG_ITERS=1 \
  build/sbt 'sql/testOnly org.apache.spark.sql.TPCDSCountJoinDiagnosticsSuite'
```

Then diff base vs forced `TPCDS-DIAG-PLAN` trees to confirm the duplicated-subtree (Mode A) and
fan-out (Mode B) hypotheses, and locate the responsible construction in `buildBottomUpJoins` /
`buildBottomUpJoinsCounting` (RewriteJoinsAsSemijoins.scala). This characterization fans out cleanly
per query and is a good candidate for a Workflow when picked up.

## 2026-06-19 FINAL: full post-guard SF5 sweep (warm-min, shape-classified)

This is the definitive post-guard production measurement that the prior handoff said was still
needed. It supersedes the pre-guard 103-variant counts as the current production picture.

Methodology:

- Data: real TPC-DS SF5 (dsdgen), generated via `tpcds-countjoin-setup.sh`.
- Suite: `TPCDSCountJoinDiagnosticsSuite`, now with warmup+iteration support (warmup=1/iters=2 for
  the sweep; warmup=2/iters=5 for verification). Reported `ms` is the warm MIN. A one-time global
  JVM warmup runs before the measured loop. This was added because single-pass timing was badly
  warmup-biased (same-shape q1 showed base 4191 vs prod 1191 ms; after warm-min, 1193 vs 932, and
  classified neutral because shape/counters are identical).
- Classification uses plan SHAPE + work counters (cjOut, smjOut, shuffleRecords, sortPeak, aggOut),
  NOT raw ms - warm-min still has a ~20% noise floor on small same-shape queries.
- Scope: the 72 structurally-firing queries (from the applicability report) + a 5-query non-firing
  control. The 31 non-firing queries cannot be rewritten and were not executed (cannot regress).

Result tally (72 firing + 5 control = 77 queries): WIN=7, neutral-shape=4, gate-skip=61,
control-neutral=5, REGRESSIONS=0.

Confirmed wins (prod vs base, warm-min, CHANGED shape, counter-corroborated):

| query | base ms | prod ms | speedup | prod shape | key counter |
|---|---:|---:|---:|---|---|
| q25 | 20609 | 2446 | +88% | CountJoin | shuffleRecords x0.04 |
| q29 | 19627 | 3931 | +80% | CountJoin | shuffleRecords x0.20 |
| q11 | 14275 | 4338 | +70% | pre-agg | shuffleRecords x0.09, sortPeak->0 |
| q64 | 25909 | 10067 | +61% | CountJoin | shuffleRecords x0.46 |
| q4 | 26230 | 11714 | +55% | pre-agg | shuffleRecords x0.10, sortPeak->0 |
| q24b | 11566 | 6165 | +47% | CountJoin | shuffleRecords x0.10 |
| q24a | 11763 | 6441 | +45% | CountJoin | shuffleRecords x0.10 |

Neutral rewrites (prod changes shape, no net gain): q50 (+14%, the 13.3M-row stream remains),
q31 (~0%, pre-agg), q32 (~0%), q92 (-5%, the correlated-scalar case - still unsolved).

Gate validation:

- All six pre-guard regressions are now correctly gate-skipped in prod (prod == base): q3, q34, q47,
  q57, q73, q89. Their `forced` (ungated) runs show the old blowups (-526% to -1270%).
- 5/5 non-firing control queries: prod ~= base.
- q24a/q24b prove the gate fires on REAL stats where the injected-stats applicability report said
  cost-gate-SKIPS - so applicability cannot be used to exclude queries; only to find the firing set.

Pre-aggregate census (answers "is pre-agg ever a win"): of the 33 pre-agg-eligible queries, prod
actually executes the pre-agg shape for only THREE: q4 (+55% WIN), q11 (+70% WIN), q31 (neutral).
For all others the gate reroutes to CountJoin (q24a/b, q25, q29, q50, q64 - wins) or to base
(q3/q34/q47/q57/q73/q89 + ~17 more - where forced pre-agg explodes). Net: pre-agg-as-executed is
2 wins, 1 neutral, 0 prod losses; the row-expansion guard (RewriteJoinsAsSemijoins.scala:2008)
prevents the loss cases.

Open leads recorded above, ranked for the optimization pass:

1. q50 delayed-existence / bucket fusion (top target; 13.3M-row stream = shuffle).
2. q64 aggregate-carrying CountJoin fusion (83.8M-row stream + residual sort).
3. Gate over-conservative: q15 (+58%, CONFIRMED) and q69 (+35%, CONFIRMED) are real missed wins the
   gate skips; q58 (+17%) borderline. Win is NOT from shuffle reduction - capture forced plan first.
4. Forced-slowdown robustness (task #9): make worst-case rewrite ~= base (Mode A work-doubling
   q72/q94/q95; Mode B stream explosion q2/q3) so robustness does not rely solely on the gate.
5. q14 pre-agg through the IN-semijoin wrapper (incremental; below q50/q64).
6. q92 correlated-scalar reuse; q97 dedicated full-outer physical path (outer path is off under the
   gate, so q97 stays base in prod).

Reproduce: `tpcds-countjoin-setup.sh` then `tpcds-countjoin-sweep.sh` (or the per-group commands).

## 2026-06-19 OPTIMIZATION PASS: lower slowdowns + bigger speedups

Goal (user): make the rewrite robust so its worst case is close to base (much lower slowdowns), and
ideally capture more speedups. Working autonomously; committing each validated increment.

Risk-ordered plan (from a multi-agent code-map + adversarial design pass over the rule):

1. Mode A guards (forced-only, zero prod-win risk) - tryRewriteOuter + trySplitMixedDistinct.
2. Mode B fan-out guard (count-join builder) behind a new opt-in flag.
3. Gate relaxation for the confirmed missed wins q15/q69 (highest risk; do last, full re-sweep).

Key diagnosis correction (from forced plan dumps, plans_diagnosis.log):

- Mode A (work-DOUBLING, q72/q94/q95) is NOT in the count-join/semijoin reducer chains - those
  correctly REPLACE (thread prevPlan, one join per edge like vanilla). It is the two
  split-and-UNION/recombine pre-passes that BOTH re-join the same large subtree twice:
  - tryRewriteOuter (:1562, q72): `A LEFT JOIN B == (A INNER JOIN B) UNION (A LEFT ANTI JOIN B)`,
    where the inner and anti halves each re-join/re-sort the entire big `left` chain -> exact 2x
    SMJ/Sort/smjOut/sortPeak.
  - trySplitMixedDistinct (:1271, q94/q95): count(DISTINCT)+sum split into two halves over the SAME
    base join, recombined by an Inner join -> exact 2x.
  - BOTH early-return when the cost gate is ON (:1568, :1277), so they run only in forced mode -
    which is exactly why prod has 0 regressions but forced doubles. Guarding them CANNOT regress any
    prod win (those paths are prod-disabled and disjoint from the win paths q4/q11 pre-agg and the
    CountJoin chains).

- Mode B (stream EXPLOSION, q2/q3): the count-join replaces a SELECTIVE BROADCAST join (tiny
  filtered date_dim/item broadcast to store_sales) with a SHUFFLE-based ShuffledHashCountJoin that
  shuffles all ~14M store_sales rows. The count-join forces a shuffle where a broadcast was optimal,
  with no offsetting group collapse.

- q15 missed win (why the gate is wrong): base broadcasts everything then aggregates 7.7M rows
  (aggMs 4412 - the bottleneck); the count-join pre-aggregates (carries counts through broadcast
  count-joins) so the final aggregate sees far fewer rows (aggMs 431, ~10x less). costGateSkips keys
  only on JOIN broadcast-friendliness (baselineBroadcastsAllButLargest && maxKeyDegree<3) and never
  looks at the AGGREGATE, so it skips q15. The fix must make the gate reduction/aggregate-aware.

Decision - Mode A fix (committed): guard each splitter with `canBroadcastBySize(<duplicated
subtree>, conf)` - only split when re-joining the duplicated subtree (`left` for the outer split,
`join` for the mixed-distinct split) is cheap. Rationale: the cost of these splits is the DUPLICATED
JOIN COMPUTE, not the output size, so the design's first-draft rowCount-of-output check was rejected
(q94 is a global aggregate with ~1-row output yet doubles a 7M-row self-join - the output check would
let it through). The broadcast-size check is gate-independent, conservative on missing stats (skip),
and since no measured win uses these paths, the worst case is "forced behaves like base" - exactly
the robustness goal.

Mode A RESULT (verified, SF5 warm-min, YannakakisCorrectnessSuite 118/118 pass):

| query | forced before | forced after | base | note |
|---|---:|---:|---:|---|
| q72 | 87580 (-79%) | 46865 | 48010 | now +2% vs base (was ~1.8x slower) |
| q95 | 31997 (-82%) | 16067 | 16681 | now +4% vs base |
| q94 | 4695 (-68%) | 2972 | 3205 | now +7% vs base |

smjOut and sortPeak now match base exactly (e.g. q94 smjOut 3597882 == base, sortPeak 822083456 ==
base) - the duplicated join/sort branch is gone. The three worst split-doubling forced slowdowns are
eliminated; the rewrite falls back to the base plan (or a non-doubling rewrite) when re-joining the
duplicated subtree is not broadcast-cheap. Prod unchanged (these paths are prod-disabled).

### NEGATIVE result (reverted): gate-independent pre-agg anti-explosion guards do NOT help q34 etc.

Hypothesis tested: make the decorating-pre-aggregate NDV-expansion (:1950) and row/size-expansion
(:2024) guards gate-INDEPENDENT, expecting it to tame the largest forced slowdowns
(q34/q47/q57/q73/q89, -526% to -1270%). Correctness passed (118/118) and the q4/q11 pre-agg wins held
(prod +54%/+71%), but it was a NO-OP on the target slowdowns, so it was REVERTED.

Measured (forced, after the change): q34 15815 (~base 1314 x12), q47 40068, q57 19000, q73 9236,
q89 6935 - essentially unchanged. Why:

- q34 forced stays the pre-aggregate shape (logicalCountJoins=0) yet is slow - so its pre-agg does
  NOT trip the >48x row/NDV expansion guards. Its cost is elsewhere (intrinsic pre-agg work or the
  recursive inner join), not a group blow-up.
- q47/q57/q89 forced take the COUNT-JOIN path (logicalCountJoins=3/3/1), not the pre-agg path at all,
  so the pre-agg guards are irrelevant to them.

Conclusion: the large pre-agg-classed forced slowdowns are NOT a decorating-pre-agg group expansion.
They live in the count-join builder (buildBottomUpJoinsCounting) or the intrinsic pre-agg/inner-join
cost. Those paths RUN IN PROD (they are the q24/q25/q29/q50/q64 and q4/q11 win paths), so a guard
there is NOT zero-risk - it must be validated against every win. Deferred to a reviewed change, not
an autonomous one.

### Scope conclusion for safe autonomous robustness

Only the prod-DISABLED paths (tryRewriteOuter, trySplitMixedDistinct) can be guarded with zero risk
to the production wins; that is Mode A, now committed. The remaining forced slowdowns (pre-agg q34;
count-join q2/q3/q47/q57/q89; semijoin-non-reducing q14a/q14b) originate in paths shared with the
production wins or in physical broadcast-vs-shuffle choices, so reducing them safely needs careful
human-reviewed validation against the full win set, not an unattended change.

### q34 mechanism (the worst pre-agg forced slowdown) - a STATS limitation

q34 forced (plan-dump q34_plan.log): the decorating pre-aggregate DOES explode - aggOut jumps from
34,686 (base) to 13,400,766 (forced), ~386x more rows, with sortPeak 67MB -> 2.08GB and
shuffleRecords 12,685 -> 19.9M. So it is a genuine group expansion. But the row-expansion guard
(:2024, `preAgg.stats.rowCount > original * 48`) does NOT fire on it, because Catalyst's planning-time
stats UNDERESTIMATE the post-pre-agg cardinality: the estimated `preAgg.stats.rowCount` stays under
48x while the runtime cardinality is 386x. This is why making that guard gate-independent was a no-op
for q34.

Implication: this class of forced slowdown is a cardinality-ESTIMATION limitation, not a guard-logic
gap. It cannot be fixed by a smarter static threshold (a lower threshold that caught q34 would also
block the beneficial q4/q11 pre-agg, which also expands but pays off). It needs either better group
NDV estimation for the pre-agg key, or a runtime/AQE adaptive fallback that abandons the pre-agg when
its actual cardinality exceeds the estimate. Same caveat applies to a stats-based gate RELAXATION for
q15/q69: the build-side-size signal that separates q15 (13MB count-join build) from q3 (805MB) /
q2 (10.8M-row build) is the right idea, but it depends on the same planning-time size estimates that
q34 just showed can be far off, so it must be prototyped behind a default-off flag and validated on
the full firing set before trusting it.

### OPTIMIZATION PASS outcome (autonomous session)

Shipped (committed, validated): Mode A - guard the two split-and-UNION rewrites so they do not
re-join a non-broadcast subtree twice. Eliminates the three worst split-doubling forced slowdowns
(q72 -79%->+2%, q95 -82%->+4%, q94 -68%->+7%); correctness 118/118; prod provably unchanged (these
paths are prod-disabled). This is the one clean STRUCTURAL bug (always-dumb duplicated work) in the
slowdown set.

Investigated and deferred to human review (with precise mechanisms above):
1. q15/q69 gate relaxation (the only available PROD speedup, +58%/+35%): needs a build-side-cost
   discriminator at the rewritePlan cost gate (:850, where the built `yannakakisJoins` is available),
   prototyped behind a default-off flag. Stats-dependent; validate on the full set.
2. Mode B count-join broadcast->shuffle (q2/q3) and the count-join-path slowdowns (q47/q57/q89): in
   buildBottomUpJoinsCounting, which is a PROD win path - correctness-sensitive, needs full-win-set
   validation.
3. q34-class pre-agg expansion: a cardinality-estimation problem (needs better NDV or AQE fallback).

Not pursued: a broad gate RELAXATION keyed on join/aggregate stats - the measured data
(q2 aggIn 10.8M > q15 7.7M; q2 collapse-ratio 5800 vs q15 8800) shows join-stat and
aggregate-collapse signals do NOT separate the wins from the slowdowns; only the rewrite's
build-side cost does, and that is stats-fragile (see q34).

### q15/q69 aggregate-aware gate override: PROTOTYPED, validated, REVERTED (negative result)

To capture the confirmed missed wins, I prototyped an aggregate-aware override behind a default-off
flag `spark.sql.yannakakis.aggAwareGateEnabled`: at the rewritePlan cost gate, when the size-only
gate would skip, KEEP the rewrite if every CountJoin's aggregated/build side (`right` child) is
broadcast-cheap (`rewriteHasCheapBuildSides`). Tested via a `prodaa` diagnostics mode
(prod + flag on). Correctness 118/118; row counts matched base/prod/prodaa for every query.

Result (base -> prodaa, warm-min, SF5):

| query | base | prodaa | effect | rewrote? |
|---|---:|---:|---|---|
| q15 | 1576 | 744 | +53% WIN captured | yes (lcj=3) |
| q69 | 1963 | 1155 | +41% WIN captured | yes (lcj=2) |
| q58 | 1599 | 1396 | +13% | yes (lcj=4) |
| q2 | 1726 | 7059 | -309% SLOWDOWN re-admitted | yes (lcj=2) |
| q89 | 1132 | 1622 | -43% slowdown | yes (lcj=3) |
| q47 | 3982 | 5035 | -26% slowdown | yes (lcj=9) |
| q14a | 16765 | 18650 | -11% slowdown | yes (lcj=2) |
| q57 | 1986 | 2166 | -9% slowdown | yes (lcj=9) |
| q3,q33,q56,q34,q16,q14b,q25 | - | - | neutral / win held | - |

Verdict: net-negative when enabled - it captures q15/q69/q58 but re-admits q2 (-309%) and others, so
it was REVERTED (a demonstrably net-negative heuristic should not ship, even default-off). Root cause:
the build-side-cost signal is STATS-FRAGILE. q2 is decisive - its prodaa `shuffleRecords` stays 3938
(no shuffle) yet it is 4x slower, because the count-join builds a ~10.8M-row in-memory hash relation
whose planning-time size estimate is far below the 8x-broadcast-threshold cutoff. The cost that
distinguishes win from slowdown is the rewrite's EXECUTION cost (build/shuffle materialization), which
planning-time stats cannot predict reliably (same lesson as q34's 386x cardinality miss).

Recommendation: the q15/q69 win is real but can only be captured SAFELY with RUNTIME/AQE adaptivity -
decide to keep vs discard the count-join rewrite from ACTUAL materialized cardinalities (or add an AQE
fallback that abandons a rewrite whose build/shuffle exceeds its estimate), not a static cost gate.
This is a larger change than an autonomous session should ship unreviewed. The flag/mode scaffolding
and this measured evidence are preserved here so the AQE approach can build on them.

## OPTIMIZATION PASS - final summary (autonomous session)

- SHIPPED (committed, validated, prod-safe): Mode A - guard the split-and-UNION rewrites
  (tryRewriteOuter, trySplitMixedDistinct) against re-joining a non-broadcast subtree twice.
  Eliminates the three worst split-doubling forced slowdowns (q72 -79%->+2%, q95 -82%->+4%,
  q94 -68%->+7%); correctness 118/118; prod provably unchanged (prod-disabled paths).
- TESTED and REVERTED (negative results, documented with data): (a) gate-independent pre-agg
  anti-explosion guards (no-op: q34's expansion is invisible to stats); (b) aggregate-aware gate
  override for q15/q69 (captures wins but re-admits q2 -309% due to stats-fragile build-cost).
- DEFERRED to human review with precise mechanisms: count-join broadcast->shuffle (q2/q3) and
  count-join-path slowdowns (q47/q57/q89) live in the prod win path (buildBottomUpJoinsCounting);
  q34-class pre-agg expansion and the q15/q69 capture both need AQE/runtime adaptivity, not static
  stats. Net: the one clean STRUCTURAL bug (Mode A) is fixed; the remaining slowdowns/speedups are
  fundamentally cost-estimation problems that require runtime adaptivity, not more static gating.

### Final validation: full base,forced,prod re-sweep on the committed branch

Re-ran the full sweep (72 firing + control, warm-min) on the committed branch (Mode A) to validate
end-to-end. Tally: WIN=7, changed~neutral=4, gate-skip=57, same-shape=9, REGRESSIONS=0 (77 queries).

- All 7 production wins hold: q25 +89%, q29 +79%, q11 +67%, q64 +62%, q4 +55%, q24b +44%, q24a +38%
  (minor warm-min variation vs the pre-optimization sweep; all solidly winning).
- Mode A confirmed for ALL affected queries: q72/q94/q95 forced now == base (+4%/+4%/+1%, reclassified
  from gate-skip-with-slow-forced to neutral/same-shape). The -79%/-82%/-68% forced slowdowns are
  gone.
- 0 production regressions; the only timeouts are the expected q24a/q24b FORCED pre-agg probes (the
  catastrophic shape the prod guard avoids - unchanged, and not a path Mode A touches).

The branch is production-safe: Mode A is a pure robustness improvement (worst-case forced slowdowns
reduced) with zero change to the production win set.

## 2026-06-20 SHIPPED: q50 no-op PK-FK dimension elimination

Implemented optimization #1 from the further-optimizations investigation (the top-ranked item).

What: before hypertree construction, drop a dimension joined only on its provably-unique (PK) key to
a provably non-null fact FK when (a) no dimension attribute is referenced by grouping/aggregates/
projection/other joins and (b) the dimension is UNFILTERED (only isnotnull(key) predicates). Such an
inner join is row-preserving (1:1) and never read - a semantic no-op - so removing it avoids a
gratuitous existence stream. q50's store_sales -> unfiltered date_dim d1 pass (the documented 13.3M-
row stream, cjOut==shuffleRecords) is exactly this shape. Code: eliminateNoOpDimensions in
RewriteJoinsAsSemijoins.scala, called before `new Hypergraph(...)` so both counting and pre-agg
paths benefit. Flag spark.sql.yannakakis.eliminateNoOpDimensionsEnabled (default on).

Correctness safeguard (a bug found and fixed during validation): a FILTERED dimension (e.g. date_dim
WHERE d_year=2001, like q50's d2) is a SELECTIVE semijoin, not a no-op - eliminating it would drop
fact rows. isNonReducingLeaf requires the dimension to be a bare project/filter-over-leaf whose only
predicate is isnotnull(key). So d2 is kept, d1 is removed.

Validation:
- YannakakisCorrectnessSuite 119/119, including a new dedicated test that eliminates the no-op
  dimension AND keeps the filtered one (with ANALYZE'd column stats) and checks results == vanilla.
- TPCDSApplicabilitySuite (injectStats): q50 CountJoin 3 -> 2 - the d1 existence join is dropped;
  0 exceptions across all 103 queries, no other query's classification changed.

Stats dependency (important): the elimination is gated on PROVABLE PK uniqueness, which needs column
NDV stats (hasUniqueKeyStats). The SF5 parquet diagnostic tables have NO ANALYZE'd column stats, so
on that benchmark the elimination is INERT (q50 prod unchanged at ~+14%/13.3M stream). It is
plan-proven to fire and drop the stream when CBO column stats exist (the normal production case). A
wall-clock SF5 demonstration would require ANALYZE'd catalog tables (data rewrite). NOTE: this same
stats gap means ALL NDV-based logic in the rule (e.g. allEquiJoinsHaveUniqueSide arm of the cost
gate) is inert on the current benchmark - the sweep results are driven by size-based stats only.

### #3 (q64 partial-aggregate fusion): assessed REDUNDANT, not implemented

The investigation's rank-3 idea was to insert a partial HashAggregate above the top CountJoin to
collapse q64's 83.8M-row stream before the exchange. Checking q64's actual prod plan (q64_plan.log)
shows this is already done by Spark's automatic partial+final aggregation:

```
final   HashAggregate(... sum ...)            <- final agg
  Exchange hashpartitioning(15 group cols)     <- the shuffle
    partial HashAggregate(... partial_sum ...) <- ALREADY here, directly above the CountJoin
      BroadcastHashCountJoin [ss_item_sk=cs_item_sk] ... [count(1)]   <- top CountJoin
```

The partial aggregate already collapses the 83.8M CountJoin output before the exchange. The residual
10.9M shuffleRecords is the genuine cardinality of q64's 15-column GROUP BY (i_product_name,
i_item_sk, s_store_name, s_zip, 6 address cols, 3 d_year cols, ...), not an un-aggregated stream - an
explicit partial agg cannot reduce it. So #3 as specified would be a no-op, and #2 (its guard) guards
nothing. The only way to shrink q64 further is aggregating INSIDE the count-join (AggJoin operator
surgery - the high-risk rank-5 variant), which is out of scope for this pass. #3 skipped with
evidence.

### "Do #1/#2/#3" outcome

- #1 (q50 no-op dimension elimination): IMPLEMENTED + validated + committed (correct, stats-gated).
- #3 (q64 partial-agg fusion): assessed and SKIPPED - redundant with Spark's automatic partial+final
  aggregation (confirmed in q64's plan).
- #2 (q64 fusion guard): not needed (no #3).

## 2026-06-20 PIVOTAL: stats-equipped measurement overturns the q50 elimination AND flags a win risk

Goal was to MEASURE q50's wall-clock gain from the no-op elimination by giving q50's tables real
column stats (CREATE external catalog tables over the parquet + ANALYZE FOR ALL COLUMNS + CBO).
Added a `q50 stats-equipped` test to TPCDSCountJoinDiagnosticsSuite. Result (warm-min, SF5, full
column stats on all 4 q50 tables):

| mode | ms | logicalCountJoins | join ops | note |
|---|---:|---:|---|---|
| base | 6926 | 0 | SMJ | vanilla |
| prod-noelim | 5353 | 3 | all BroadcastHashCountJoin | +23% WIN (rewrite, elimination OFF) |
| prod-elim | 6777 | 2 | + a ShuffledHashCountJoin | -27% vs prod-noelim (elimination ON) |

Finding 1 - the q50 elimination is COUNTERPRODUCTIVE (overturns optimization #1's premise):
- It DOES fire with stats (logicalCountJoins 3 -> 2; d1 dropped) and results are correct, BUT
- removing the "no-op" d1 made Spark's planner switch a BroadcastHashCountJoin to a
  ShuffledHashCountJoin -> q50 prod 5353 -> 6777 ms (+27% SLOWER).
- The original premise (the prior agent's claim that d1 was the 13.3M-row stream) was WRONG: d1 is a
  BROADCAST existence join; the 13.28M shuffleRecords is store_sales itself and is UNCHANGED by the
  elimination. The "no-op" join was physically load-bearing (kept the count-join chain broadcast).
- ACTION: flipped spark.sql.yannakakis.eliminateNoOpDimensionsEnabled to DEFAULT OFF. The transform
  is correct (YannakakisCorrectnessSuite test still validates it with the flag forced on) but needs a
  cost-aware guard (only eliminate if it does not force a shuffle) before it could be enabled. The
  measurement did its job: caught a regression before it shipped default-on.
- Silver lining: prod-noelim shows the BASE q50 CountJoin rewrite is a genuine +23% WIN under full
  CBO stats (the earlier stats-less +14% was not an artifact; the partial-stats -55% run WAS an
  artifact of incomplete fact stats).

Finding 2 - q4/q11 (2 of the 7 wins) are AT RISK under CBO stats (from the next-opt investigation):
- q4/q11's per-channel pre-agg unit is a clean star (customer JOIN store_sales JOIN date_dim) where
  BOTH edges have a PK-unique side (c_customer_sk=ss_customer_sk, ss_sold_date_sk=d_date_sk). With
  NDV stats, allEquiJoinsHaveUniqueSide (RewriteJoinsAsSemijoins.scala:129-142) returns TRUE, so the
  decorating-pre-agg cost gate (costGateSkips skipNonExpanding=true, ~scala:1975) FIRES and SKIPS the
  pre-agg -> q4/q11 revert to base. On the stats-less benchmark hasUniqueKeyStats is always false, so
  the arm is inert and the wins show - i.e. q4/q11's wins are partly a stats-LESS artifact.
- The 5 CountJoin wins (q25/q29/q24a/q24b/q64) are SAFE under stats: they have fact-to-fact edges
  with no PK side (store_sales<->store_returns<->catalog_sales) so allEquiJoinsHaveUniqueSide stays
  false; several also have maxKeyDegree>=3 which independently forces keep.
- This is the same aggregate-blindness as q15/q69: the gate sees join shape, not the aggregate
  collapse the pre-agg buys (q4 shuffleRecords 25.7M->2.5M, sortPeak 8.2G->0).

Implication: a production system with CBO stats would (a) gain nothing from the no-op elimination
(now off) and (b) LOSE q4/q11 to the pre-agg FK-uniqueness gate. The next concrete work (ranked by
the investigation) is: CONFIRM the q4/q11 flip empirically (stats-injected test), then make the
pre-agg gate aggregate-aware so q4/q11 survive ANALYZE'd stats - the only real threat to the wins.

## 2026-06-20 FIX: pre-agg gate no longer applies the CountJoin unique-side skip

Confirmed the q4/q11 risk empirically: under injectStats (NDV present) the applicability report shows
q4 and q11 as gate=cost-gate-SKIPS. The decorating pre-agg gate (RewriteJoinsAsSemijoins.scala:1973)
reused costGateSkips(..., skipNonExpanding=true), whose arm includes allEquiJoinsHaveUniqueSide - a
CountJoin-specific signal: a non-expanding FK/dimension join means a CountJoin adds no fan-out
reduction, but a DECORATING PRE-AGGREGATE still helps because it aggregates BEFORE the dimension
join. So that arm wrongly skips the q4/q11 pre-agg win once column NDV stats make it fire.

Fix: at the pre-agg gate, use costGateSkips(skipNonExpanding=false) (drops the unique-side arm) plus
dominatedByOneLargeInput explicitly. I.e. drop ONLY the unique-side CountJoin skip; keep the
broadcast-friendliness and dominated-by-one-large-input guards.

Why keep dominated/broadcast (not the full "no gate for pre-agg"): those size-based arms are what
gate-skip q34's BAD pre-agg (386x expansion) on the current benchmark - and the pre-agg's own
row-expansion guard (:2008) cannot catch q34 because planning stats underestimate the expansion. So
dropping them would REGRESS q34. The unique-side arm, by contrast, is inert without NDV (no
regression on the stats-less benchmark; correctness 119/119; applicability skip-count unchanged at
104) and only matters under CBO - exactly where it was harming q4/q11.

Remaining gap (documented, not fully fixed): under CBO stats, q4/q11 could still be skipped by the
broadcast (arm1) or dominated arms if those happen to fire on their star. The principled complete fix
is an AGGREGATE-AWARE pre-agg gate that decides on the pre-agg's estimated ROW REDUCTION (which
separates q4 'reduces 10x' from q34 'expands 386x'), rather than CountJoin join-shape proxies. That
is the same class as the q15/q69 issue and is entangled with q34's stats-underestimated backstop, so
it needs a plan-level test harness under stats (the applicability suite is CountJoin-marker-based and
cannot observe pre-agg firing). This fix removes the one clearly-wrong arm safely; the rest is
deferred.

## 2026-06-20 RESOLVED: all 7 wins hold under real CBO column stats

Built the plan-level under-CBO-stats harness the re-prioritization called for (a `q4/q11 + CountJoin
wins under CBO column stats` test in TPCDSCountJoinDiagnosticsSuite: register all TPC-DS tables as
catalog tables over the parquet, ANALYZE FOR ALL COLUMNS, enable CBO, run base vs prod, assert
results == vanilla). Result (warm-min, SF5, full column stats):

| query | base ms | prod ms | speedup | prod rewrite | rows match |
|---|---:|---:|---:|---|---|
| q4 | 28047 | 11993 | +57% | pre-agg (lcj=0; 0 SMJ/0 Sort vs base 6/12) | yes |
| q11 | 14133 | 4550 | +68% | pre-agg (lcj=0; 0 SMJ/0 Sort) | yes |
| q25 | 21130 | 1919 | +91% | CountJoin (lcj=7) | yes |
| q29 | 21212 | 3670 | +83% | CountJoin (lcj=7) | yes |
| q64 | 43033 | 13403 | +69% | CountJoin (lcj=36) | yes |

The at-risk worry is REFUTED: q4/q11's decorating pre-aggregate FIRES under real CBO column stats
(prod has zero SortMergeJoin/Sort, the SMJ+sort-removal signature of the pre-agg). The broadcast arm
does not skip them either (their 'all but largest' includes customer, which is not broadcast-eligible
at SF5, so baselineBroadcastsAllButLargest is false), and the pre-agg unique-side fix (b7d16ab043)
removed the one arm that would have. So the '7 wins' claim is now MEASURED under production-like CBO
stats, not merely asserted on the stats-less benchmark. All five rewrite under CBO with results
identical to vanilla.

Note the earlier injectStats applicability showing q4/q11 = cost-gate-SKIPS was a false alarm from two
sources: (a) the applicability classifier is CountJoin-marker-based and cannot see pre-agg firing
(pre-agg has lcj=0), and (b) injectStats uses SF1-scaled sizes that differ from real SF5, changing
the size-based arms. Real-execution measurement is the ground truth, and it is positive.

## 2026-06-20 codegen cleanups (from the bytecode review)

- needCopyResult (committed d427be163a): a NON-grouped count join emits at most one row per stream
  input (it aggregates matches into a count rather than fanning out), so the whole-stage boundary
  copy is unnecessary. ShuffledHashCountJoinExec dropped its hard-coded needCopyResult=true ->
  streamedPlan.needCopyResult || groupRight.nonEmpty; BroadcastHashCountJoinExec gated its copy on
  groupRight.nonEmpty && multipleOutputForOneInput. Correctness 119/119; all 6 CountJoin wins'
  row counts identical to base across whole-stage boundaries (no corruption). SF5 wall-clock delta is
  within the noise floor (copies are cheap at this cardinality); the saving scales with count-join
  output volume.
- Dead-code: deleted HashCountJoin.codegenInner (a stale duplicate of the standard HashJoin inner
  codegen, never called - doConsume routes InnerLike to codegenCountInner). The codegenOuter/Semi/
  Anti/Existence branches were KEPT: they are reachable extension scaffolding (gated only by the
  execs' InnerLike require), not pure rot.
- Bytecode review conclusion: the DeclarativeAggregate count-join codegen (the path the 7 wins use)
  is already well-optimized (factorized fan-out + fused aggregate-as-payload, primitive-long count,
  derived-count short-circuit). The remaining codegen items are either low-impact (interpreted/
  imperative-aggregate fallback, which the wins do not use) or large bets (columnar output); see the
  ranked review. No further codegen speedup is realistically on the table for the measured wins.
