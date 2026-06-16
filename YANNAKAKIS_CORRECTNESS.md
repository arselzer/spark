# Correctness of the Count-Join Rewrite and its Extensions

This is the formal correctness companion to `YANNAKAKIS_EXTENSIONS.md`: precise statements and
proof sketches for the base acyclic rewrite and each extension. Proofs are at the level of a paper's
correctness section. The model is **annotated (bag) relations**, which makes the "count" column a
first-class object and the aggregate pushdown a statement about a single recurrence.

## 1. Preliminaries

**Annotated relations.** Following the provenance-semiring view (Green–Karvounarakis–Tannen), fix the
bag semiring `K = (ℕ, +, ·, 0, 1)`. A relation over attribute set `U` is a finite-support function
`R : Tup(U) → ℕ`, where `R(t)` is the multiplicity of tuple `t`. The relational operators are:

- **Join:** `(R₁ ⋈ R₂)(t) = R₁(π_{U₁}t) · R₂(π_{U₂}t)` for `t` over `U₁ ∪ U₂` whose projections lie
  in each operand (and `0` otherwise).
- **Projection:** `(π_V R)(t) = Σ_{t' : π_V t' = t} R(t')`.
- **Selection:** `(σ_θ R)(t) = R(t)` if `θ(t)` holds, else `0`.

A base table is its multiplicity function (`1` per distinct row for a set, general `ℕ` for a bag).
Write `J = R₁ ⋈ ⋯ ⋈ Rₙ` for the full join; by associativity/commutativity of `⋈`,
`J(t) = ∏_i Rᵢ(π_{Uᵢ}t)`.

**Aggregates as functions of the annotation.** For a relation `R` over `U` and attribute `x ∈ U`
(`x(t)` its value, with SQL `NULL` semantics):

- `COUNT(*) (R) = Σ_t R(t)`
- `SUM(x) (R)   = Σ_{t : x(t) ≠ NULL} x(t) · R(t)`
- `COUNT(x) (R) = Σ_{t : x(t) ≠ NULL} R(t)`
- `MIN(x)/MAX(x) (R) = min / max { x(t) : R(t) > 0, x(t) ≠ NULL }`  — depends only on `supp(R)`.
- `AVG(x) (R) = SUM(x)(R) / COUNT(x)(R)`.

The target query is `Q = γ_{G; 𝒜}(J)` — group by attribute set `G`, aggregates `𝒜` — evaluated over
the annotated full join `J`. We show the rewrite computes `γ_{G;𝒜}(J)` without materializing `J`.

**Join trees / acyclicity.** The query hypergraph has a vertex per (equivalence class of equi-joined)
attribute and an edge per relation. It is α-acyclic iff GYO ear-removal empties it, iff a *join tree*
`T` exists: a tree over the relations satisfying the running-intersection property (for any attribute
`a`, the nodes whose relation contains `a` form a connected subtree).

## 2. Core: semijoin reduction with count propagation

Root `T` at a node `ρ`. The rewrite makes one **bottom-up** pass.

**Lemma 1 (semijoin support preservation — Yannakakis 1981).** A full bottom-up + top-down semijoin
reduction replaces each `Rᵢ` by `π_{Uᵢ}(J)` on its support: every *dangling* tuple (one in no full-join
witness) is removed, and no surviving tuple is duplicated or dropped.
*Proof.* Standard; the running-intersection property guarantees a semijoin against a neighbour removes
exactly the tuples that cannot extend to that neighbour's subtree, and acyclicity lets these local
checks compose to the global join. ∎

**Lemma 2 (count = fan-out multiplicity).** Define the **count column** by the bottom-up recurrence:
for a tuple `t` at node `u` with children `c₁,…,c_k`,
```
count_u(t) = R_u(t) · ∏_j ( Σ_{s ∈ c_j , s ⋈ t} count_{c_j}(s) ).
```
Then for the root, `count_ρ(t) = (π_{U_ρ} J)(t)` — the number of full-join witnesses projecting to `t`.
*Proof.* Induction on `T`. *Base:* a leaf has `count_u(t) = R_u(t)`, the multiplicity of `t` in the
"subtree join" `R_u`. *Step:* the subtree at `u` is `R_u ⋈ (⋈_j subtree(c_j))`. The number of subtree
witnesses projecting to `t` is `R_u(t)` times, for each child `c_j`, the number of `c_j`-subtree
witnesses that join with `t`. By the IH that number is `Σ_{s ⋈ t} count_{c_j}(s)`. The product over
`j` is valid because, by the running-intersection property, distinct child subtrees share attributes
with the rest only through `u`; fixing `t` makes their witness sets independent, so their counts
multiply rather than interact. Hence `count_u(t)` equals the subtree-join multiplicity of `t`, and at
`u = ρ` the subtree is all of `J`. ∎

A tuple has `count_ρ(t) > 0` iff it has a full-join witness, so the root after the bottom-up pass
carries exactly `supp(π_{U_ρ} J)` with the correct multiplicities (no separate top-down pass is needed
when the aggregate sits at `ρ`).

**Theorem 1 (aggregate correctness).** Let every attribute referenced by `G` and `𝒜` be *available at
`ρ`* — i.e. carried up the tree as an additional grouping key when it originates in a non-root relation
(so it becomes part of `U_ρ` and Lemma 2 applies verbatim). Then, per group `g ∈ G`:

1. `COUNT(*)(J)|_g = Σ_{t : π_G t = g} count_ρ(t)` — i.e. `COUNT(*) ↦ SUM(count)`.
2. `SUM(x)(J)|_g  = Σ_{t : π_G t = g, x(t)≠NULL} x(t)·count_ρ(t)` — i.e. `SUM(x) ↦ SUM(x·count)`.
3. `COUNT(x)(J)|_g = Σ x(t)·count_ρ(t)` restricted to `x(t)≠NULL` — i.e. `COUNT(x) ↦ SUM([x≠NULL]·count)`.
4. `MIN(x)/MAX(x)(J)|_g = min/max{ x(t) : count_ρ(t) > 0 }` — count-insensitive.
5. `AVG(x)(J)|_g = SUM(x)(J)|_g / COUNT(x)(J)|_g`, computed from (2) and (3).

*Proof.* By Lemma 2, `count_ρ` reproduces the multiplicity of each `t ∈ π_{U_ρ}J`; cases (1)–(3) are
the definitions of the aggregates evaluated on the annotated `π_{U_ρ}J`, which equals the aggregate on
`J` because projecting onto a superset of `G ∪ refs(𝒜)` is loss-free for these aggregates. (4) needs
only `supp`, preserved by Lemma 2. (5) is the quotient. ∎

**Duplicate-insensitive ("0MA") path.** When all aggregates are duplicate-insensitive
(`MIN`,`MAX`,`COUNT(DISTINCT)`,`SUM(DISTINCT)`,…), fan-out is irrelevant: only `supp(π J)` matters. The
rewrite then uses a pure `LeftSemi` semijoin reduction (no count column), correct by Lemma 1. The
classifier must route fan-out-sensitive aggregates (`COUNT`,`SUM`,`AVG`) to the counting path even
when their argument is a grouping key — `count(k) GROUP BY k` still depends on fan-out.

## 3. Cross-relation predicates

**Theorem 2.** Let `θ` reference attributes spanning ≥2 relations, and let `u` be the lowest tree node
whose subtree contains all of `refs(θ)` (carried up as grouping keys to `u`). Then evaluating `σ_θ` at
`u` before the count propagation yields `γ_{G;𝒜}(σ_θ(J))`.
*Proof.* Every join on the path from `u` to `ρ` is on attributes disjoint from `refs(θ)` (else a lower
node would contain `refs(θ)`), and joins only add attributes and multiply annotations. Hence `σ_θ`
commutes upward: `σ_θ(J) = (σ_θ at u) ⋈ (rest)`. Applying `θ` at `u` removes exactly the witnesses
`θ` would remove from `J`, so the counts computed above `u` are the fan-outs *within* `σ_θ(J)`. A group
all of whose witnesses are filtered has total count `0` and is suppressed (the phantom-count-0 guard),
matching `γ(σ_θ(J))`. ∎

## 4. Outer joins

**Theorem 3 (decomposition).** As bags,
```
A ⟕ B  =  (A ⋈ B)  ⊎  ν_B(A ▷ B)
A ⟗ B  =  (A ⋈ B)  ⊎  ν_B(A ▷ B)  ⊎  ν_A(B ▷ A)
```
where `A ▷ B` is the anti-join (tuples of `A` with no `B`-match), `B ▷ A` symmetric, and `ν_S(·)`
sets the columns of side `S` to typed `NULL`.
*Proof.* Each tuple `a ∈ A` either has ≥1 join partner in `B` — contributing its matches to `A ⋈ B` —
or none — contributing one `B`-NULL-extended copy. The two cases partition `A`, hence partition the
multiset `A ⟕ B`. FULL adds the symmetric `B`-only tuples, partitioned identically. ∎

**Theorem 3′ (merge).** For a group `g` and an aggregate with a monoid combine `⊕` (`SUM` for
`COUNT`/`SUM`; `MIN`/`MAX` for themselves),
`agg(X ⊎ Y)|_g = agg(X)|_g ⊕ agg(Y)|_g`. Re-aggregating the union of per-branch partials with `⊕`
therefore yields `agg` over the whole outer join.
*Proof.* `γ` partitions rows by group; since `X ⊎ Y` is a disjoint bag union, each group's row-bag is
the union of its row-bags in `X` and `Y`, and `COUNT`/`SUM`/`MIN`/`MAX` are monoid homomorphisms from
bag-union to `+`/`+`/`min`/`max`. ∎

**NULL correctness.** In `ν_B(A ▷ B)` every `B`-column is typed `NULL`, so `COUNT(b.x)=0`,
`SUM/MIN/MAX(b.x)=NULL` (ignored by the merge's monoid), `COUNT(*)` still counts the row, and a `B`
grouping key collapses to the `NULL` group — exactly the outer-join NULL extension. The matched half
is re-pointed to the inner join's output to recover the non-`NULL` nullability of `B`, so its
aggregates are not folded to `NULL`. RIGHT is LEFT after swapping; the matched half is accelerated by
Theorem 1, the anti halves are fan-out-free plain aggregates.

**Proposition 4 (AVG over an outer join, `DoubleType` output).** For inputs whose `AVG` output type is
`DoubleType`, Spark's `Average(x)` is `Divide(sum(x).cast(double), count(x).cast(double))`. Carrying
the two additive partials `sum(x)` and `count(x)` through each branch (Theorem 3′ with `⊕ = SUM`) and
forming `Divide(Σ sum, Σ count)` cast to `double` equals `Average(x)` over the union, since
`Σ_branches sum(x) = SUM(x)(X⊎Y)` and `Σ_branches count(x) = COUNT(x)(X⊎Y)`.
*Scope.* `DECIMAL`/interval `AVG` is **not** claimed: Spark's `Average` there uses a type-specific
division whose precision the `SUM/SUM` reconstruction need not match bit-for-bit, so the rewrite
declines (fallback), preserving correctness by deferring to vanilla. ∎

## 5. Cyclic joins via GHD bags

**Theorem 5 (bag substitution).** Let GYO reduce the acyclic part and stall on an irreducible cyclic
component `C ⊆ {R₁,…,Rₙ}`. Materialize the **bag** `B = σ_{Θ_C}(⋈_{R∈C} R)`, where `Θ_C` are the
cross-relation predicates whose references lie entirely within `C`. Replacing the relations of `C` by
the single derived relation `B` (whose connecting attributes are the vertices of `C` shared with the
rest) yields an **acyclic** hypergraph `H'` with `⋈(H') = J`, and Theorem 1 applies to `H'` with
`B(t) = (⋈_{R∈C} R)(t)`.
*Proof.* `B`'s tuples are by construction exactly those of the sub-join `⋈_C` (with in-bag predicates
applied, which by Theorem 2 commute out of the surrounding joins). Substituting a relation equal to a
sub-join preserves the overall join by associativity/commutativity, so `⋈(H') = J`. `H'` is acyclic:
`C` collapses to one edge whose vertices are precisely `C`'s external connection points, removing the
cycle while preserving all external incidences, so GYO now completes. The count machinery treats `B`
as a base relation; its multiplicity is the materialized join's multiplicity, as Theorem 1 requires. ∎

A bare cyclic query (the whole query is one bag) applies `γ_{G;𝒜}` directly to `B`, trivially correct.
The cost gate only changes *whether* the bag is formed (size-bounded), never the result: declining
falls back to vanilla.

## 6. Scope (correctness by fallback)

The rewrite is **partial**: outside the cases above it returns the original plan, which is correct by
definition. It declines (and thus stays correct) for: non-acyclic queries without the cyclic flag;
`DECIMAL`/interval `AVG`, `DISTINCT`, and percentile over outer joins; aggregates it cannot classify;
a cyclic residual that is not broadcast-bounded (cost gate) or not connected; and any rewrite whose
output would drop a required attribute (`missingInput` guard). Every acceptance path above is covered
by `YannakakisCorrectnessSuite` (deterministic oracle) and `YannakakisFuzzSuite` (property-based, on
values **and** schema); see `YANNAKAKIS_EXTENSIONS.md` §Evaluation.
