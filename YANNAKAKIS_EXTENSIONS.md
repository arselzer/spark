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

**AVG.** Supported when the average's output is `DoubleType` (byte/short/int/long/float/double
inputs). Each half carries two partials `[sum(x), count(x)]` — the matched half through the
count-join, which fan-out-weights both — recombined in the merge as `SUM(sums)/SUM(counts)` and cast
to the average's type. This mirrors Spark's own `Average`, which for `DoubleType` output is exactly
`Divide(sum.cast(double), count.cast(double))`, so values and schema match. DECIMAL and interval
averages keep the safe fallback (their `Average` uses a specific decimal/interval division whose
precision the simple `SUM/SUM` reconstruction would not match bit-for-bit).

**Limitations.** Falls back (correct, unaccelerated) for DECIMAL/interval AVG, DISTINCT, percentile,
and other non-mergeable aggregates, and when the matched half does not accelerate (avoids
multiplying the join for no benefit). DISTINCT and percentile are genuinely non-decomposable across
the union.

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

**Core (real data — full pass 2026-06-16, cost gate off to force the rewrite).** The speedup tracks
the join's fan-out: dramatic where intermediates blow up, modest where they don't, always correct.

- **STATS-CEB** (146 COUNT(*) join queries over the Stack-Exchange dataset): the rewrite fires on
  **146/146**, **0 mismatches**, and **finishes all 146 while vanilla Spark does-not-finish 7 within
  15 s** — those 7 are fan-out stars (e.g. a 5-way join on `UserId`) whose intermediates are
  intractable to materialize but cheap to count (rewrite 0.25–2.8 s, i.e. **>5×…>60×**). Geomean
  **1.21×** over the 139 both-finished (mostly tiny joins where the count-join's overhead shows —
  the production cost gate skips those). This is the count-join's signature regime.
- **Join-Order Benchmark** (real IMDB): all 8 sampled queries apply and match; speedup **up to
  3.42×** (16a 60 s→17 s, 33a 23 s→7 s, 17a 2.88×), with count-join whole-stage codegen beating both
  interpreted and vanilla (1a **4.3×** off→codegen).
- **TPC-H sf1 & sf3**: all 22 match vanilla at both scales (15 apply, 7 fall back); codegen beats
  interpreted on every query, and beats vanilla on the grouping-heavy count-join queries, growing
  with scale (sf3 q7 **1.64×**, q10 **1.53×**), near-parity elsewhere (cost-gate territory).

**Where the win is: the core fan-out reduction (synthetic scaling sweep, single machine, median of
K runs).** The count-join's speedup comes from *not materializing fan-out*. On `SUM(f.fm)` over a
fan-out star `F ⋈ d₁ ⋈ d₂` (no spanning predicate), the rewrite computes `SUM(fm·cnt₁·cnt₂)` over the
reduced dims while vanilla materializes the `~n³/card²` blow-up. The rewrite stays flat as fan-out
grows; vanilla does not, so the speedup grows with fan-out:

| fan-out | intermediate ≈ | vanilla | rewrite | speedup |
| --- | --- | --- | --- | --- |
| 10 | 1 M  | 193 ms |  187 ms | 1.03× |
| 25 | 16 M | 158 ms |  123 ms | 1.28× |
| 45 | 91 M | 345 ms |  127 ms | 2.72× |
| 70 | 343 M | 1279 ms | 166 ms | **7.70×** |

This is the same mechanism behind the real-data numbers above (TPC-H/JOB), shown cleanly in isolation.

**The extensions are generality wins at parity, not perf wins.** Scaling sweeps (same harness) over
the cross-relation predicate, LEFT/FULL outer, and cyclic cases hold at **parity within run-to-run
variance** as scale grows, with the rewrite matching vanilla at every point:

- *Cross-relation predicate* — the predicate `d₁.x + d₂.y > c` spans the two dims, so evaluating it
  needs the `(x,y)` pairs, i.e. exactly the cross-product the count-join would otherwise avoid. Hence
  parity, not reduction. (An earlier single-shot reading suggested ~1.3×; the scaling sweep shows
  that was noise.)
- *LEFT/FULL outer* — the matched half is reduced, but the anti half(s) add work; net parity.
- *Cyclic* — the bag *is* vanilla's inner join, so parity by construction (a few percent of
  count-machinery overhead, no meaningful regression).

So the extensions' value is **generality + correctness**, consistent with TPC-H/JOB being acyclic
inner-join workloads on which they are inert; the count-join's *performance* contribution is the core
fan-out reduction, demonstrated by the curve above and the real-data results.

## Limitations and future work

- **DECIMAL/interval AVG, DISTINCT, percentile over outer joins.** Double-output AVG is supported
  (`sum`+`count` carry). DECIMAL/interval AVG falls back pending exact precision parity with Spark's
  `Average`; DISTINCT and percentile do not decompose across the union.
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
