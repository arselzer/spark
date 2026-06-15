# Count-Join (Yannakakis) Rewrite — Journal-Version Extensions

This document summarizes the journal-version extensions to the count-join rewrite, their
correctness arguments, limitations, and evaluation. It is written to be lifted into the paper.

## Background

The base rewrite turns an aggregate over an **acyclic** equi-join into a Yannakakis-style bottom-up
semijoin reduction that carries a fan-out **count** column, so that `SUM(x)` becomes `SUM(x·count)`,
`COUNT(*)` becomes `SUM(count)`, and so on. The reduction never materializes the full join: each
relation is reduced against its neighbours and the per-tuple multiplicity is propagated as the
count. A logical `CountJoin` operator and physical broadcast/shuffled-hash/sort-merge count-join
operators (with whole-stage codegen) realize this. The base rewrite is provably correct for acyclic
join+aggregate queries and is where the measured speedups live (see *Evaluation*).

The three extensions below widen applicability along orthogonal axes — **predicates**, **join
semantics**, and **topology** — while preserving the base machinery. All are validated by a
deterministic correctness suite (`YannakakisCorrectnessSuite`, 100 tests) and a property-based fuzz
oracle (`YannakakisFuzzSuite`) that compares the rewrite against vanilla Spark on both **values and
output schema**.

## Extension 1 — Cross-relation predicates (on by default)

**Mechanism.** A θ-predicate spanning ≥2 relations (e.g. `r.x + t.y > c`) cannot be pushed to a
single relation. The hypergraph extracts such predicates as deferred computations, carries their
referenced attributes up the semijoin reduction as count-join grouping keys, and applies the
predicate at the lowest node where all its attributes co-occur, folded into the `CountJoin`
condition. A fully-filtered group emits nothing (the phantom-count-0 guard in the operators).

**Correctness.** The carried attributes inner-join exactly the subtrees that contain them, so the
predicate sees the same tuples it would in the materialized join; intermediate dedup on the carry is
lossless because the carried attributes become grouping keys. A defensive `subsetOf` guard falls
back if a predicate's attributes cannot all be carried to a common node (does not fire for connected
queries).

**Limitations.** None functionally; a single-relation residual `Filter` above the join is
intentionally **not** folded back (predicate pushdown has already run, so folding could drop a
single-relation predicate the hypergraph does not model).

## Extension 2 — Outer joins (on by default, conservative)

**Mechanism.** LEFT/RIGHT/FULL OUTER via the decomposition

```
A ⟕ B  ≡  (A ⋈ B)              ⊎  (A ▷ B, B-cols → NULL)
A ⟗ B  ≡  (A ⋈ B)              ⊎  (A ▷ B, B-cols → NULL)  ⊎  (B ▷ A, A-cols → NULL)
```

The matched half (inner join) is routed through the base count-join rewrite; each unmatched half is
a plain aggregate over a LeftAnti join (no fan-out). The per-group partial results are UNION-ed and
re-aggregated per group, merging `count`/`sum → SUM`, `min → MIN`, `max → MAX`, cast back to the
original type. RIGHT is normalized to LEFT by swapping; FULL adds the symmetric B-only half.
(`tryRewriteOuter` in `RewriteJoinsAsSemijoins.scala`.)

**Correctness.** Three points: (1) the anti half(s) keep exactly the rows the outer join
NULL-extends, so the branches partition the outer join's rows; (2) in each unmatched half every
column of the null-extended side is projected to a typed NULL literal preserving exprId, so
`count(x)=0`, `sum/min/max(x)=NULL`, `count(*)` still counts the row, and a NULL-extended grouping
key collapses to the NULL group — exactly the outer semantics; the matched half re-points references
to the inner join's output (`toInner`) to recover real nullability (else `count(b.x)` would fold
away). (3) The merge casts each result back to the original aggregate type, preserving output
exprIds and schema. A `missingInput` guard falls back if the split drops a required attribute.

**Limitations.** Falls back (correct, unaccelerated) for AVG/DISTINCT/percentile and other
non-mergeable aggregates, and when the matched half does not accelerate (avoids multiplying the join
for no benefit). AVG over an outer join is mergeable in principle (carry `sum` and `count` partials,
divide in the merge) but is **deferred**: reconstructing AVG as `SUM(sum)/SUM(count)` risks
decimal-precision divergence from Spark's `Average`, which requires exact Average-semantics
mirroring. DISTINCT and percentile are genuinely non-decomposable across the union.

## Extension 3 — Cyclic joins (off by default; flag `spark.sql.yannakakis.cyclicBagsEnabled`)

**Mechanism.** A generalized hypertree decomposition. GYO ear-removal reduces the acyclic part;
when it stalls on a mutually-irreducible cyclic residual, that residual is materialized as an
ordinary inner-join **bag** (applying bag-internal equi-conditions and any cross-relation filter
contained in the bag), reinserted as one derived hyperedge, and GYO continues. The count machinery
then runs over the acyclic tree-of-bags unchanged: the bag is a derived relation whose rows equal
the cyclic sub-query. A whole-query bag (e.g. a bare triangle) applies the aggregate directly to the
materialized bag.

**Cost gate.** Because the bag is materialized eagerly with binary joins, a size-based cost gate
(when enabled) only forms the bag when it is *broadcast-bounded* — every residual relation except
the largest is broadcast-eligible — so the bag is a chain of broadcast joins that cannot blow up;
otherwise it declines and falls back to vanilla, whose join reordering may be better for two large
relations.

**Correctness.** The bag's rows are exactly the cyclic sub-query's join output, so substituting it
as a derived relation leaves the count propagation correct. Determinism required stabilizing the
per-vertex attribute choice (a bag edge exposes multiple attributes per vertex; the map is sorted by
exprId). The decomposition declines (clean fallback) on shapes it does not handle.

**Limitations.** The bag is materialized with **binary joins**, i.e. it equals what vanilla would
compute, so cyclic support is a **generality** contribution, not a performance win on bare cyclic
queries. Two cyclic extensions were investigated and deliberately **not** implemented (see *Future
work*): multiple disjoint cyclic components (near-degenerate in connected queries), and bag
join-order robustness (unreachable — Spark's `ReorderJoin` delivers a connected order).

## Evaluation

### Correctness (primary evidence)

- **`YannakakisCorrectnessSuite`** — 100 deterministic oracle tests (acyclic count/sum/min/max/avg,
  mixed-distinct, cross-relation predicates, LEFT/RIGHT/FULL OUTER, cyclic triangles/4-cycles),
  each asserting the rewrite matches vanilla and (where relevant) that the intended path fired.
- **`YannakakisFuzzSuite`** — a property-based oracle generating random star/chain queries (with
  cross-relation filters and LEFT/RIGHT/**FULL** OUTER joins) and random triangles/4-cycles, each
  compared to vanilla on **values and schema**. Acyclic: 400/400 clean; cyclic: 300/300 clean. The
  fuzzer has historically found real bugs (a count/sum-of-grouping-key fan-out bug; a cyclic
  per-vertex-attribute non-determinism), all fixed.

### Generality

The extensions add the following query classes over the base acyclic count/sum/min/max rewrite:
multi-relation θ-predicates; LEFT/RIGHT/FULL outer joins over the reduced (matched) join; and cyclic
join topologies (triangles, k-cycles, and acyclic-with-a-cyclic-island) via GHD bags.

### Performance

**Core (real data, established).** On TPC-H sf1 the count-join codegen is 1.03–2.69× over
interpreted across all 22 queries (15 rewrite, 7 fall back; all 22 results match vanilla). On the
Join-Order Benchmark (real IMDB) the rewrite is faster on 7/8 sampled queries, up to ~2.6× (e.g.
17a 34s→13s, 16a 60s→28s); grouped-path codegen is ~1.7–1.9× over interpreted and ~3.5× over vanilla
on JOB 1a.

**Extensions (synthetic micro-benchmark, `YannakakisExtensionsBenchmarkSuite`, indicative).**
Small-scale, single-machine medians; the rewrite matches vanilla in every case:

| Scenario | vanilla | rewrite | speedup |
| --- | --- | --- | --- |
| cross-relation predicate over a fan-out chain | 207 ms | 157 ms | **1.32×** |
| LEFT OUTER with fan-out + grouped aggregate | 296 ms | 283 ms | 1.05× |
| FULL OUTER (three-branch split) | 664 ms | 661 ms | 1.00× |
| cyclic triangle `count(*)` | 80 ms | 84 ms | 0.95× |

These confirm the expected shape: the predicate path inherits the base reduction win (it grows with
the chain's intermediate size); outer joins are perf-neutral generality wins; the cyclic bag is
vanilla's join, so it is parity (a few percent of count-machinery overhead, no meaningful
regression). The extensions' value is generality + correctness, not new speedups — consistent with
the fact that TPC-H and JOB are acyclic inner-join workloads on which the extensions are inert.

## Limitations and future work

- **AVG/DISTINCT/percentile over outer joins.** AVG is mergeable via a `sum`+`count` carry; deferred
  pending exact decimal-precision parity with Spark's `Average`. DISTINCT and percentile do not
  decompose across the union.
- **Cyclic is generality-only.** The bag uses binary joins. A genuine performance contribution on
  cyclic queries requires a worst-case-optimal join *that aggregates*. Concretely: because the bag
  is **counted** and never enumerated, the relevant cost is `N^fhtw` and, on irreducible chordless
  ≥4-cycle / inequality-bearing residuals, submodular width `#subw < fhtw`, so a (#)PANDA /
  PANDAExpress-style aggregating bag would close the gap a binary-join bag forfeits. This is left as
  future work, with two honest caveats: counting in submodular width for the non-idempotent
  COUNT/SUM semiring is an open problem (the proven width is the weaker, non-tight `#subw`), and no
  submodular-width algorithm has yet been implemented in any system (PANDAExpress closes the
  asymptotic/polylog gap in the proof, not the engineering gap). For the canonical triangle
  `subw = fhtw = ρ* = 3/2`, so it yields nothing there.
