# Value numbering: `Values<V>` and `BddMap<V>`

The design and the why; the rules a session must hold are `CLAUDE.md` §3.

An MTBDD terminal is a raw `int`, so it means something only relative to a numbering. That numbering is a
first-class, explicitly created object.

```java
BddMapFactory maps = ctx.bddMaps();      // one per BinaryFactoryContext, non-generic
Values<String> strings = maps.create();  // stage 1: a numbering
BddMap<String> m = strings.of("lo").update(x0, "hi");   // stage 2: maps over it
```

`BddMap<V>` is exactly `(function, Values<V>)`; `V` lives on `Values` alone, which owns every value-typed
entry point (`of`, `ifThenElse`, `cartesianProduct`, `createRelabeling`, `relabelInto`, `adopt`,
`registerApply`). Values may not be null.

- **Scope is the caller's knob.** One numbering shared by many maps gives subtree sharing between them and
  makes every operation between them raw and cheap; a narrow numbering per map is denser but makes every
  operation cross-numbering. Neither is imposed.
- **Same numbering ⇒ raw int operations.** `agreement` is raw terminal equality on its own cache;
  `ifThenElse`, `update`, `where`, `domainOf` never unwrap a value. Maps are canonicalized on
  `(function, values)` (the numbering's key space in the high half of the key, `docs/design.md`), so `equals` is `==` and
  O(1), and
  semantically equal maps over one numbering *are* the same object.
- **Cross-numbering is supported, not forbidden.** `apply(other, combiner, destination)` and
  `where(other, BiPredicate)` resolve each side through its own numbering in a single traversal — no
  `adopt` pass first, and the two sides need not share a value type. Operations that must *produce* a map
  over one numbering go through `Values.adopt` explicitly; there is no implicit adoption, which would
  permanently append the other map's values and hide an O(|other|) traversal behind an O(1)-looking call.
- **A destination numbering is always caller-supplied**, never auto-created — `apply`, `split`,
  `cartesianProductMap` all take one, so chained cross-numbering operations cannot silently proliferate
  numberings. `cartesianProductMap` additionally takes a `map` over each tuple — not for the mapping (the
  caller can map the finished product) but so a merging `map` never has to index the tuples, nor have a
  numbering over `List<V>` exist. The tuple `map` receives is **one reused buffer**, rewritten between
  calls like a `Cursor`'s `current()`: reading it is fine, keeping it or handing it back is not. Plain
  `cartesianProduct` is the identity case and copies for the caller via
  `ValuesImpl#getOrAssignIndex(value, copy)` — copy-on-insert, so only a genuinely new tuple allocates.
- **Algebraic declarations need a shared numbering.** `BddMapBinaryOperator` (commutative/neutral/
  absorbing) and `BddMapBinaryPredicate` (symmetric/reflexive) translate down to raw terminals only when
  both maps share a numbering; across numberings a raw terminal means different things on each side, so
  the declaration is quietly ignored rather than believed. `neutral`/`absorbing` additionally become a raw
  shortcut only if that value has actually been indexed (`ValuesImpl#peek`). Claimed laws are verified
  under assertions (`BddMapBinaryOperator#checkLaws`).
- **Injective transforms cost O(1) per map.** `Values#createRelabeling` copies the numbering verbatim, so
  every map's function id carries over unchanged (`RelabelerImpl`). `relabelInto`/`adopt` are the
  re-encoding counterparts for the non-injective/merging case, and traverse. That is also the answer to
  variance: `Values` and `BddMap` are **invariant**, and a view over a supertype `W` is
  `createRelabeling(W.class::cast)` — injective, one pass over the (typically small) co-domain, nothing
  per map. Binary operations are correspondingly invariant in their operand (`apply`,
  `Values#ifThenElse`, `cartesianProduct` take `BddMap<V>`, not `BddMap<? extends V>`): every map a
  `Values<V>` hands out is statically `BddMap<V>`, so a statically `BddMap<Sub>` argument necessarily
  comes from a *different* numbering, and widening would make exactly the calls compile that then trip the
  numbering assertion. `where(BddMap, BddMapBinaryPredicate)` (as `<W extends V>`, keeping the two `where`
  overloads unambiguous), `agreement` and `difference` are the exceptions, and only because they do have a
  cross-numbering path at runtime.
- **Indices are recycled, not append-only.** `ValuesImpl` is a `NodeTableObserver`: on a collection
  (or the growth one turned into) it forgets entries whose raw terminal was *globally* reclaimed. The raw
  space is shared with every other numbering and with a split's intermediate indices, and a handed-out
  index - fresh or existing - can name a terminal that nothing live holds yet. So **whoever hands out an
  index protects it until a node holds it**: inside a traversal the leaf is built on the spot, and the two
  splits, which relabel every residual up front, keep each relabeled terminal on the work stack until
  `computeMap` has built it in (`RegressionTests`). It then walks its high-water mark (`biggestAliveIndex`)
  back and rebuilds `freeGaps` by one ascending scan, so fresh values pack in from the bottom. The mapping
  is a bijection at all times and an index's meaning is stable while it is alive — which is what lets a
  cached raw result stay valid. `afterGc` returns immediately unless something below `biggestAliveIndex`
  was reclaimed; the walk back reads `toValue`, not `reclaimedValues`, because a slot is vacant either
  because it was just reclaimed or because it was already a gap, and the mark must step over both.
  Reclamation is conservative: with several numberings sharing the raw int space, a reclaimed value cannot
  be attributed to one, so only globally dead entries are dropped.
- **No canonicalization, freezing or interning of numberings.** Two consequences to design around:
  structure sharing is insertion-order dependent, and `equals` (with the function id as hash) is only
  meaningful within one numbering - a hash structure never mixes maps over two numberings, since those
  would not share values either. Semantic comparison across numberings is `agreesWith`/`allMatch`,
  backed by `MtBdd.allMatch`: `applyBoolean`'s traversal answering a bit, which builds nothing and stops
  at the first valuation that fails. Within one numbering `agreesWith` is the O(1) id comparison.
- Underneath, `MtBddImpl.applyBoolean(f1, f2, MtBddBinaryPredicate)` is the boolean-valued counterpart of
  `apply`: it folds two terminals into a bit, so the result is a BDD. `reflexive` lets identical operand
  nodes answer true without descending, `symmetric` lets a pair and its mirror share a cache entry; both
  are checked under assertions and both are claims about the *raw* predicate, so `BddMapImpl` drops them
  when the numberings differ (there, `test(i, i)` is not a diagonal). No irreflexive counterpart exists —
  negating the result is one complement edge.
- Factory-identity assertions (`this == that.factory`) stay throughout: they catch functions from a
  different `MtBddImpl` being mixed in, which would otherwise corrupt silently.
