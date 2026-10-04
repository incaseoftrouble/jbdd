# TODO

The in-source `// TODO`s, grouped by what each would take. Each has an identifier, `[LIKE-THIS]`, that the
source comment carries too, so grep for it; identifiers are names, not numbers, and never change. A TODO that turns out to be answered is deleted from the source rather than
carried; if the answer is worth keeping, it belongs next to the code it is about. The larger open items -
native domain-restricted enumeration for MDDs, frozen numberings, `invert`'s array bound - live in
`CLAUDE.md` §15.

## Blocked on a measurement

Each of these trades an allocation or a copy against a cache hit rate, which only a representative workload
can settle; `CLAUDE.md` §11 is explicit that reasoning about these paths has repeatedly measured backwards.

- **[KEY-COPY] `MtBddImpl#cartesianProductRecursive` and `#computeNaryApply` - do the memos pay for the key copy?**
  Both recursions rewrite their operand array in place, so a cache key has to be a clone (§6). Whether the
  hit rate covers an allocation per node needs a benchmark with many-operand products and applies;
  `MtBddBenchmark.unionAll` is one for the apply.
- **[NARY-APPLY] `MtBddImpl#apply(int[], MtBddNaryOperator)` - slower than the pairwise fold it stands for.**
  `MtBddBenchmark`: uniting 256 edge trees under a registered union is 23 ms as a pairwise fold and 66 ms as one
  n-ary application (the result has 61,000 nodes and 39,000 distinct values; the fold's order, left, balanced or
  deepest first, moves it by a few ms at most). The n-ary recursion carries the whole tuple to every node - a
  cofactor array and a cloned cache key of 256 ints per step - where the fold's steps each see two operands and
  the shared apply cache. The BDD `andAll` shrinks its tuple along the path and falls back to pairwise where
  operands do not share top variables (§5); the MTBDD apply does neither. Whether a neutral-dropping, shrinking
  tuple or a plain pairwise fold inside `apply(int[], ...)` wins is the measurement to make before touching it.
- **[NARY-SPLIT] `BddImpl#computeAndAll` - when a step switches to pairwise.** Every step goes pairwise for its
  whole subtree once its operands do not outnumber their distinct top levels four to one. Measured against making
  that choice at the entry only (2026-10-06, ms per conjunction): operands sharing only their first variable over
  independent rest (`x0 ∨ gᵢ`) 0.216 → 0.018 at 200 operands and 0.019 → 0.004 at 50; independent operands 0.029 →
  0.020; guarded cubes unchanged; random 3-clauses over 22 variables 0.035 → 0.041 (`NaryBenchmark`, 200 clauses) and
  0.013 → 0.016 on another instance. The clauses lose because their deep tuples, a few operands at distinct
  levels, would still have shrunk; switching only tuples of eight operands and more recovered the second instance
  (0.014) but not the first (0.041). A ratio relative to the levels left below the tuple, rather than a flat four,
  is the next thing to try - against more than two random instances.
- **[COMPOSE-GATHER] `BddImpl#computeComposeRecursive` - gather the decided replacements and project once.** A replacement
  the path made constant is substituted immediately, each substitution recomputing the support and the
  projection; collecting them first would do that once per node. On a compose workload (relabelings and
  general mappings over 24 variables) the whole descent, allocations included, is a
  minor part of the profile, so this waits for a workload where compose dominates.
- **[COMPOSE-POOL] `BddImpl#computeComposeBranch` - a depth pool for the restricted replacements.** Same workload, same
  verdict: the arrays are a few percent at most. The pool is straightforward (one array per depth, the
  low branch's reused by the high branch) but every reader of `replacements` would have to carry an
  explicit length.
- **[SIFT-BOUND] `DdVariableOrderImpl#sift` - bound a direction by the current best times a factor** rather than by
  the starting size. Changes which positions sifting explores; needs the reordering benchmarks.
- **[RESTRICT-PREFIX] `MtBddImpl#restrict` - is the prefix walk worth it?** The literals fixing the topmost decisions are
  walked without caching so that restrictions differing only there share entries. Whether that happens
  often enough depends on the caller's restriction shapes.

## Worth doing

- **[AND-EXISTS] `BinaryDecisionDiagram` - `AndExistsSimplify` and the other fused forms.** The and-exists itself is
  native (`BddImpl#andExists`, §6). Open: `andExistsSimplify` (the and-exists cofactoring a domain on the way
  down, `andSimplify`'s way: a ternary cache next to the and-exists one, invalidated with it), quantification
  fused with other operators (`xorExists`, a quantifying MTBDD `apply`), and a native `MddImpl` and-exists - an
  MDD takes the interface's default, which builds the conjunction. Each wants a workload that quantifies a
  combination other than the conjunction first; none of JBDD's clients has one yet.

## Needs design

- **[COMBINE-SHARED] `BddMapFactoryImpl#registerCombine` - specialise when all three numberings coincide.** With one
  numbering everywhere a raw terminal means the same on both sides, so a declared `BddMapBinaryOperator`'s
  laws would translate (§10) - but `registerCombine` takes a plain `BiFunction`, so honouring them means
  detecting the operator by type, and then also pinning `neutral`/`absorbing` the way `RegisteredApply`
  does (§7). Without the laws the specialisation saves two field loads. Decide whether the typed detection
  is wanted before writing it.
- **[PRODUCT-NATIVE] `BddMapFactoryImpl#cartesianProduct` - use the native cartesian product.** The native one's cache is
  per-call scratch state keyed on a bijection built fresh each call (§6), and the merging `map` must never
  index the tuples (§10) - which is why the current code routes through `apply`. Pairs with the key-copy
  measurement above.
- **[RESTRICT-REGISTER] `BddOperations` - a registered `restrict`.** `restrict`'s cache is stable and keyed on the whole cube,
  so a handle would only save resolving the cube per call; the question is whether anything calls one
  restriction often enough to want it.
