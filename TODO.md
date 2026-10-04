# TODO

The in-source `// TODO`s, grouped by what each would actually take. The larger open work — native
domain-restricted enumeration, frozen/interned numberings, `invert`'s array bound —
lives in `CLAUDE.md` §15 and is not repeated here.

Entries name the enclosing method rather than a line, so grep for `TODO` in that file to find the line
itself. A TODO that turns out to be answered is deleted from the source rather than carried; if the answer
is worth keeping, it belongs next to the code it is about.

## Blocked on a benchmark

A measurement question, not a design one. There is no representative workload in `src/jmh` for it, so it
cannot be settled honestly today — and `CLAUDE.md` §11 is explicit that plausible reasoning about these
paths has repeatedly measured backwards. What would unblock it is a way to replay real load; the synthetic
and DIMACS instances here do not stand in for it.

- **`MtBddImpl#cartesianProductRecursive` — does caching `cartesianProduct` pay for the copy it forces?**
  The recursion rewrites its operand array in place, so the cache key has to be a clone (§6). The question
  is whether the hit rate covers the allocation per node. Needs a cartesian-product benchmark, which does
  not exist; `invert`'s `INVERT_ARRAY_DOMAIN_THRESHOLD` would want the same harness.

## Worth doing

- **`BinaryDecisionDiagram` — `AndExistsSimplify` and the other fused forms.** The classic and-exists, plus
  quantify+apply+simplify. This is the one with real payoff: a fused operation never visits a branch the
  quantifier collapses, the way `andSimplify` never visits one the domain excludes (§6). It is also the most
  work — a new recursion, a second domain-carrying cache, ephemeral-parameter invalidation for the
  quantified set, and theory coverage against the reference evaluation in both orders (§12).
- **`BddImpl#composeSimplify` — a native restrict-and-simplify.** `BddImpl.composeSimplify` still materialises
  `restrict` then `simplify` when the mapping is a restriction; one recursion carrying the domain through the
  restriction, as `andSimplify` carries it through the conjunction (§6), would never build the intermediate.

## Needs design

- **`BddMapFactoryImpl#registerCombine` — specialise when all three numberings coincide.** Less easy than it
  reads. With one numbering everywhere a raw terminal means the same on both sides, so a declared
  `BddMapBinaryOperator`'s laws would translate (§10) — but `registerCombine` takes a plain `BiFunction`, so
  honouring them means detecting the operator by type, and then also pinning `neutral`/`absorbing` the way
  `RegisteredApply` does (§7), which `RegisteredCombiner` does not. Without the laws the specialisation
  saves two field loads and nothing else. Decide whether the typed detection is wanted before writing it.
- **`BddMapFactoryImpl#cartesianProduct` — use the native cartesian product.** Cross-layer:
  `cartesianProduct`'s cache is per-call scratch state keyed on a bijection built fresh each call (§6), and
  the merging `map` must never index the tuples (§10) — which is why the current code routes through `apply`
  instead. Pairs with the benchmark question above; there is no point optimising this path before one
  exists.
- **`BddImpl#computeAndAll` — a true n-ary conjunction.** Today `and(int[])`/`or(int[])` (and the set
  layer's `intersection`/`union` on top of them) are a left fold of binary `and`s, deepest top level first,
  which only keeps each intermediate small.
  One recursion over all operands at once builds no intermediate diagram at all, and takes the order
  question with it. What it would take: a Shannon step over k operands (cofactor those on the top level,
  drop `TRUE`s, stop on `FALSE` or a complementary pair), and a cache keyed on the operand tuple — sorted,
  so the key is canonical, and cloned before descending like `cartesianProduct`'s (§6). Worth it only if a
  benchmark with many operands per call shows the
  fold's intermediates dominating.
