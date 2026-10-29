# Tests and benchmarks

The design and the why; the rules a session must hold are `CLAUDE.md` §3.

## Tests

`src/test/java/de/tum/in/jbdd`, JUnit 5 + Hamcrest.

- **`BddTheories` / `MtBddTheories`** — the main suite and the bulk of the runtime. `Generator` fills each
  diagram once with random syntax trees, producing unary/binary/ternary data points fed to parameterized
  theories that compare the diagram against a reference `SyntaxTree`/`IntSyntaxTree` evaluation and
  re-check table invariants.
- **`BddTheories` runs seven diagram variants**: three `BddImpl` and three `MtBddAsBinaryDd` — never
  reordered, reordered with the bookkeeping rebuilt per reorder, reordered with
  `keepReorderingStructures(true)` — plus one `MddImpl`. An `@AfterEach` hook applies random `siftDown`s
  to the four reordered ones every `REORDER_EVERY` theories; the never-reordered ones are the control.
  `MtBddTheories` mirrors this with three `Context`s (its data points carry the context they were built
  in). `@AfterAll` asserts the order actually moved, so the stress cannot silently degrade into a no-op.
  `infoMap` is a `LinkedHashMap` on purpose: it keys on diagrams, whose hash is their identity hash, so a
  plain `HashMap` iterates differently per JVM run and a failure would move or vanish between runs of the
  same code. Each diagram is built from a named `BddConfiguration` whose `toString()` reports that name —
  which is how a failing data point says which variant it came from.
- **`MddAsBinaryDd`, `MtBddAsBinaryDd`** — adapters letting the theories run the same assertions against
  BDD, MDD and MTBDD. All three are driven as `BinaryDd` (`docs/design.md`), which `BddImpl` is natively, so only the
  other two need an adapter; one that can reorder implements `ReorderableDd` on top (`MddAsBinaryDd` does
  not — MDDs do not reorder, and `byLevel` keys on exactly that).
- **`FailFastExtension`** skips the rest of a class once one test fails (the shared structure is then
  suspect).
- **`TestProfile`** scales how much data those two suites generate (`jbdd.test.scale`, `CLAUDE.md` `CLAUDE.md` §1). Only the
  *amount* scales — variable counts, tree dimensions, assignment bounds and the variant list are fixed,
  because they decide which code paths exist at all, and shrinking them would make a fast run exercise a
  structurally different library. Two counts are not plain data and are scaled deliberately:
  `SKIP_CHECK_RANDOM_BOUND` is a per-theory probability, so it scales to keep the expected *number* of
  invariant checks rather than their frequency; `REORDER_EVERY` counts theories and carries a floor,
  without which a small run finishes before ever reordering and `@AfterAll` fails with "never left the
  identity order" — a red build that says nothing about the library. A scaled count must never floor to
  zero: an empty `@MethodSource` is a failing test method, not a skipped one.
  `SyntheticTest` reads the same scale but as a *bound*, not a factor: its boards grow exponentially, so
  the largest alone dominates the test and it drops from 9 queens to 8 below 0.9 and to 7 below 0.5.
- Targeted tests: `BddTest`, `MtBddTest`, `BddMapTest`, `BddSetTest`, `ValuesTest`, `ReorderTest`, `HashTest`,
  `UtilityTest`, `DimacsReaderTest`, `StatisticsTest` (a context's and an MDD's keys and descriptions agree, every
  ratio's parts are in the snapshot); in `collections`, `NatSetTest` (every operation against `java.util.BitSet`,
  over spans that keep a set in the array, move it to words, or mix both; one set in every representation equal
  to itself, hash code and order included), `NatSetFuzzTest` (random operation sequences against `BitSet`,
  every query after each step, the set re-read through each class and representation, the combinations against
  sets of every shape), `NatSetsTest` and `IntHashMapTest`; `CubeTest` beside the core tests. `BddFuzzTest` is the
  BDD's counterpart: the n-ary
  operations, quantification and the relational product, composition over restrictions and general mappings,
  the domain operations and restriction by a path cursor's cubes, all against truth tables on a tiny table with
  collections, reorderings and garbage between the operations - the theories compare per operation against syntax
  trees, this walks their combinations over one diagram. Both scale their rounds with `TestProfile` (public, so the
  `collections` tests see it) and run in seconds at full scale. `UtilityTest` also asserts
  that the costly assertions are on (`Assertions`), so a test task that forgot the property fails. `SyntheticTest` — n-queens counts as an end-to-end sanity check.
  **`RegressionTests` — one test per past bug; add here when fixing one.**
- `ValuesTest` holds the numbering-level tests: split residuals, a merging cartesian product and a
  supertype view via `createRelabeling`. `RegisteredOperationsTest` holds the object layer's handles —
  that each agrees with the plain operation it stands for, warm cache included (the `applyIn`/`replaceIn`
  ones pinned to their contract via `agreement(…).containsAll`). Test classes are split by subject
  (sets in `BddSetTest`, maps in `BddMapTest`); when one trips PMD's coupling limit anyway, suppress
  `PMD.CouplingBetweenObjects` on it rather than splitting it further.
- `MtBddTheories`' residual-product theories check the operation against its definition (the syntax trees, `restrict`
  and `support`), against the chain it denotes, and each generalization as an identity: the cartesian product, the
  monoid n-ary apply, vector composition, and the split of the composition for slots outside the operands' supports.
- **New operations need:** theory coverage against the reference evaluation, an invariant check, and — if
  it touches ordering — a reordered variant.

## Benchmarks

`src/jmh/java/...`, JMH, DIMACS instances in `src/jmh/resources`. They exist to catch performance regressions, so
each is a workload a client runs, not a comparison between implementation options: a benchmark written to decide
an option is run, its result recorded where the decision is documented, and removed. Performance claims in
comments and the changelog are expected to be backed by these; **measure before tuning a constant.**

`NatSetBenchmark` sits in `collections` and times each operation over a pool of 1024 random sets of one shape
(singletons, tiny sets, 2 to 32 elements below 64, sparse ones below 4096), so a time is per set, over sets
that differ. The representation (`NatSetUtil.MAX_ARRAY_SIZE`, `WORD_COST`) is fixed; the layouts it was chosen
against were measured once and are recorded in `docs/design.md`.
