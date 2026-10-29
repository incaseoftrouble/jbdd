# JBDD — agent guide

Pure-Java (Reduced Ordered) Binary Decision Diagrams and variants: `Bdd`, `Mdd`, `MtBdd` in one package `de.tum.in.jbdd`
(the implementations intertwined, the interfaces the public surface), `de.tum.in.jbdd.collections` (`NatSet`, int maps,
`Cursor`) and `de.tum.in.jbdd.io` beside it. GPLv3, `group = de.tum.in`, version `0.7.0` (`build.gradle.kts`,
`README.md`). Design goals, in this order: **correctness, simplicity, performance, zero runtime dependencies.**

This file holds the agreements, the rules the code does not state, and where things are. The design and the reasons
are in `docs/`; read the one for an area before touching it, since a plausible-looking change there is silently wrong,
and **prefer asking over guessing**.

| doc | what |
|---|---|
| `docs/design.md` | BDD theory as realized here; the two layers and every type's role; the collections; performance habits |
| `docs/memory.md` | reference counts, work stacks, wrappers; collection, growth, the heap cap; what to watch |
| `docs/operations.md` | the recursion skeleton; n-ary, residual product, compose, relational product; the caches and their invalidation; registered operations |
| `docs/enumeration.md` | cursors over path walks, solution counting, the level-to-variable buffer |
| `docs/reordering.md` | levels and variables, sifting and its constants, memory-limited sifting, the order's listeners, statistics |
| `docs/values.md` | numberings (`Values<V>`) and `BddMap<V>`, cross-numbering operations, index recycling |
| `docs/tests.md` | the theories and their variants, scaling, the targeted tests, the benchmarks |
| `README.md`, `CHANGELOG.md`, `TODO.md` | version, features, JVM flags; the release against the previous one; the reasoning behind each `// TODO` |

## 0. Working agreements

- **Do not run the full test suite proactively**: minutes. Compile plus a targeted test class is the inner loop.
- **Spotless, PMD, ErrorProne and NullAway run in `build`** and fail it. No blanket suppressions; where a rule does
  not apply, suppress at the narrowest scope with a reason.
- **Document the status quo only**: no "previously", no rejected alternatives, no plans. Future work is a short
  `// TODO` on the line it concerns; `TODO.md` carries the reasoning behind each one (what it would take, or why it was
  answered in the negative) and is read before acting on a `// TODO`.
- **Keep this file and `docs/` in sync in the same pass**, editing rather than appending: a paragraph a change
  invalidates is wrong for every later session, and what you would have wanted to know beforehand belongs here.
- **The owner edits alongside you.** Re-read a file before editing it; the IDE MCP (`mcp__idea__*`, `.mcp.json`) sees
  the live state and resolves symbols, which matters here because many types are nested in differently named files
  (§8). `reformat_file` is cheaper than `spotlessApply` for one file; spotless stays the authority. Run tests through
  Gradle, not the IDE, so heap and `-ea` are the suite's.
- `CHANGELOG.md` is kept per release against the previous release (what a user of 0.6 meets); `README.md` carries the
  version, the feature list and the recommended JVM flags.

## 1. Build, test, tooling

```bash
./gradlew build            # compile + spotless + errorprone/nullaway + pmd + test
./gradlew test             # JUnit 5, -ea, heap 2g..8g; minutes
./gradlew testSmall        # same suite, theory data scaled to 0.05; ~1 min
./gradlew test -Pjbdd.test.scale=0.2                       # any scale, either task
./gradlew test --tests 'de.tum.in.jbdd.RegressionTests'    # quick loop
./gradlew compileJava compileTestJava -q                   # quickest check
./gradlew spotlessApply    # palantir-java-format, 120 cols; pre-commit hook runs spotlessCheck
./gradlew jmhRandom | jmhSynthetic | jmhDimacs | jmhEnumeration | jmhNary | jmhMtBdd | jmhReorder | jmhRelational | jmhNatSet | jmhCube | jmhChain | jmh
```

- **`--tests` is not a task input**: an unfiltered `test` after a filtered one is UP-TO-DATE without running; `--rerun`
  whenever "the suite passed" is the claim. `-Pjbdd.test.scale` reaches the test JVM as a system property, which
  Gradle tracks.
- **A scaled run is a smoke test, not a filter**: a smaller pool from the same seeds, not a prefix, so a full-scale
  failure need not reproduce. Never report a scaled run as the suite passing.
- **One build at a time**: the test JVM takes gigabytes; two concurrent builds fail `:test` with no failing test.
- **Results are the XML reports** (`build/test-results/<task>/TEST-*.xml`); the HTML report is off.
- **The configuration cache is on**: a new switch for the test JVM is a `-Pjbdd.*` property read in
  `build.gradle.kts`, never a `System.getProperty` in the build script.
- `-Pjbdd.test.java=11` runs the tests on an installed JDK 11 (CI tests on 11 and 21); the build needs JDK 21.
- The reorder stress in `BddTheories`/`MtBddTheories` has no switch, only the scale; it is most of their runtime and
  all of their coverage of a non-identity order.
- Publishing (`-Prelease clean publishToSonatype closeAndReleaseSonatypeStagingRepository`) needs credentials and GPG;
  it is manual. Pushing a tag `vX.Y.Z` creates the GitHub release (`release.yml`): the tag must equal `version`,
  and the notes are `CHANGELOG.md`'s `### X.Y.Z` section.

## 2. The machine in brief

- **A function is an opaque `int`, a node a row in a flat `NodeTable`.** Every table is hash-consed and every
  node-building path enforces both reduction rules, so **id equality is semantic equality**: that is what makes every
  recursive operation cacheable on ids, and what every bug here ultimately violates.
- **Complement edges**: the sign bit of a function id (`positive`, `complement`, `isComplementFunction` in
  `BooleanBase`); `TRUE = Integer.MAX_VALUE`, `FALSE = -TRUE`, `PLACEHOLDER = 0` is "not a node". Canonicity needs
  **the high edge never complemented** (`BddImpl.makeFunction`; `MddImpl` on `children[0]`). `MtBdd` has none: no
  canonical negation of a terminal.

  | | id encoding | branching |
  |---|---|---|
  | `Bdd` | sign = complement bit, `TRUE` a sentinel | binary |
  | `Mdd` | sign = complement bit | n-ary per variable domain |
  | `MtBdd` | positive = table row, **negative = a terminal value** (`value ↔ -value-1`) | binary |

  `nodeFor(function)` is the projection; `NodeBasedDd` is for callers that need the node (reference counts,
  saturation) and must not confuse it with the function.
- **Two layers.** The int layer: manual memory, `BooleanBase` the engine of `BddImpl` and `MddImpl`; `MtBddImpl` is
  not a `BooleanBase`, it composes a `BddImpl` (its variable universe) and owns its own `NodeTable.Binary`: two DAGs,
  one variable numbering, two independent GCs. The object layer: `BddSet`/`BddSetFactory` over a `Bdd`,
  `BddMap<V>`/`Values<V>`/`BddMapFactory` over the MTBDD, wrappers held weakly by `GcReferenceManager`.
- **Construct only through the factories**: `BddFactory.buildBdd()/buildMtBdd()/buildMdd()`, `DdContext.create`
  (the BDD/MTBDD pair over one order; creating a variable is the context's and hands back a BDD function),
  `BinaryFactoryContext.create` (plus `bddSets()`, `bddMaps()`); never `new BddImpl(...)` outside tests.
  `BddConfiguration` holds table sizes, one cache divider, `keepReorderingStructures` and the two GC knobs, nothing
  else: users do not tune caches.
- **Internally everything is by level; everything crossing the API boundary is by variable.** Translate at exactly
  one point per class.
- **Statistics**: a key exists only as a `Statistic` constant next to the counter it reads, with its kind and a
  sentence; structures implement `StatisticsReporter`, the public sources `StatisticsReporter.Source`; a key written
  twice fails an assertion; `DdContext.statistics()` is the one accessor over a pair. JBDD reports nothing at shutdown.

## 3. Rules by area

The rules; the mechanism and the measurements behind each are in the area's doc.

### Memory (`docs/memory.md`)

- Three mechanisms against different things: **reference counts** (14 saturating bits per node; saturated is pinned
  forever, variable nodes and `BddSetFactory.pin` saturate; MTBDD terminals have a parallel `valueReferenceCounts`),
  **the work stacks** (intermediates within one operation; `secondaryWorkStack` for accumulators that outlive a
  nested call's usage) and **`GcReferenceManager`** (the wrappers). **Nothing an operation returns is referenced
  automatically.**
- Work stack discipline: push every fresh child **before** anything that can allocate, pop right after it is
  consumed, in matched pairs; public entry points assert both stacks empty before and after.
- **Which stack a result needs is decided by which table can collect**, not where the value came from: an MTBDD
  operation building BDD nodes (`agreement`, the `*Simplify` domains, `compose`'s mapping) protects on
  `bdd.table()`'s stack.
- A push neither survives the call nor covers a bare terminal value not yet under a node. **`MtBddImpl.of(int)`
  allocates, so it collects**: a bare terminal held across it needs protection.
- `ensureCapacity()` lives once in `NodeTable`, `final`, the tables supply hooks; keep it that way. The mark is
  unconditional, never gated on `approximateDeadNodeCount`. `invalidateUnmarkedNodes()` is valid only right before
  `grow()`. The leaf sweep runs before `reclaimUnmarkedNodes()`, which rebuilds all hash chains (read its comment
  before "fixing" it). Neither a collection nor a growth re-derives the reordering bookkeeping.
- A table that looks corrupted: `NodeTable.check()` first; the failure is usually far from the cause.
- `node_table_work_per_created_node` says whether memory management pays; `node_table_gc_collected_nodes` does not.

### Operations and caches (`docs/operations.md`)

- **Plain recursion** with a large `-Xss`, no explicit stacks. Every `compute*`/`*Recursive`: (1) constant
  short-circuits before any cache lookup; (2) canonicalize operand order only if genuinely commutative; (3) cache
  lookup, keeping the raw hash, `table.step()` on a miss; (4) expand on the smallest top **level**, an operand without
  it passing through; (5) recurse, pushing each result before building the parent; (6) build, pop, `put`, return.
- **Steps are the clients' cost measure** (`DdContext.work()`, `operation_steps`; SemML budgets by it): a recursion
  with a memo of its own counts on the memo's miss, one without on every non-terminal call, marks and folds not at
  all. A new operation that does not count breaks budgets without failing anything.
- The MTBDD combining function is **an opaque lambda with no algebra**: no `f == g` shortcut, no idempotence;
  properties are opted into (`commutative`/`neutral`/`absorbing`, `symmetric`/`reflexive`) as unchecked
  preconditions, spot-checked under `-ea`.
- **Callbacks run inside the traversal**: pure functions of their arguments that start no operation on the diagram
  (the ephemeral caches and the mark bits break; `workStacksEmpty()` trips). Javadoc documents only a deviation; the
  deviations are `splitRelabeled`/`splitBddRelabeled` (the relabeler runs between split and mapping, everything
  referenced) and `Values.adopt` (values mapped before the pass).
- **Caches are memos, not maps**: direct-mapped, a collision overwrites, **a result may vanish and every caller must
  be correct without it**. Keys are non-constant functions (step 1).
- **Validity is reactive**: the prune hook runs on **every** GC of **every** table an entry can reference, before any
  allocation (ids are recycled). A diagram's own hook is `registerStrongly`; a weakly held one is collected, pruning
  stops, wrong answers appear at random.
- Four key flavours: **stable** (ids or the whole context: `ComposeTupleCache`, `RestrictCubeCache`, the support and
  fraction caches); **ephemeral current parameter** (`initX` compares with the previous parameter and invalidates
  wholesale; only for eagerly completing calls, so a lazy cursor is drained before any other query, asserted);
  **per-call scratch** (`split*`, `cartesianProduct`, `residualProduct` number afresh and invalidate at every entry;
  `cartesianProduct` clones its tuple before descending); **implicit global state** (counts range over the variable
  count: `variablesChanged()`).
- Every write goes through `CacheBase.putBin` (lazy, mostly sparse clears; a bypass survives a clear). A store may
  replace a cache's arrays: a parallel array is indexed by the bin `storeKeys` returned, never `values[storeKeys(..)]`.
- **Simplify-fused operations** are one recursion cofactoring the domain on the way down, with a second cache keyed
  plus the domain, invalidated with the first and straddling both tables (registered simplify variants prune on both).
  Widening a domain is always sound; cofactoring it on a variable only where it means the same on both sides
  (`composeSimplify`: `aligned`). `andExistsSimplify` has no low-equals-high shortcut.
- `andExists` inits the `and_exists` and the plain `exists` cache; its registered handle carries both.

### Registered operations (`docs/operations.md`)

- A private cache that survives alternation; the escape from ephemeral invalidation. Every handle extends
  `RegisteredOperation`, whose root declares no abstract method so a handle stays a `@FunctionalInterface`;
  implementations extend `RegisteredOperations.Forwarding`, except `RegisteredApply` (pins constants) and
  `BddMap.Relabeler` (not registered, so no `release()`).
- **A no-op registration is the shared singleton** `RegisteredOperation.identity()` /
  `BddSet.VariableReplacer.identity()` (enums), tested by reference; keep it so when adding a case.
- Constants are pinned at registration, domains stay per-call arguments. Only the compose forms bind nodes
  (`ProtectedOperation`, `ProtectionTracker`). A handle prunes on every table its entries can name; the boolean-valued
  MTBDD operations always straddle both.

### Enumeration (`docs/enumeration.md`)

- **No `Iterator` anywhere.** `Cursor` (`valid`/`current`/`advance`) is positioned on construction and **`current()`
  is the walk's working state**: callers copy. An `Iterator` wrapper's lookahead copy halved throughput; do not
  reintroduce one without measuring. A cursor reading diagram state is drained before any other operation.
- One `PathWalk` per engine, thin `final` cursors over it. **Do not unify the three walks** behind an interface: the
  differences sit in the innermost loop and would make it megamorphic.
- After a reorder a cursor hands out a variable-indexed buffer maintained incrementally: every write to a level set
  goes through `assign`/`pushSupport`/`popSupport` (and `SolutionCursor.increment`); `currentIsConsistent()` checks
  under `-ea`.

### Reordering (`docs/reordering.md`)

- Variables keep their numbers, levels move. `DdVariableOrderImpl` owns the bijection; **reading the order is the
  diagram's, changing it is the order's** (`siftDown`, the reorder forms, `dropReorderStructures`,
  `numberOfVariables`). `explicitOrder == false` is the identity with no arrays allocated; `makeOrderExplicit()`
  precedes the two things that bend it.
- `swapWithNextLevel` rewrites in place and ids survive; `rewriteLevelAfterSwap` hides and unlinks every changing
  node **before building anything**. `siftDown` collects past `MAXIMUM_SIFT_GARBAGE`; a memory-limited table asks
  `swapFits` before an exploring swap.
- **Reordering never runs inside an operation or a callback.** Saturated nodes stay saturated.
- Three shapes that must translate: node construction (`makeFunction(level, ..)` against
  `makeFunctionForVariable`); caller data indexed by variable inside a descent by level (`decisionVariable(node)` for
  the lookup, `levelOfVariable` for the ordering, the cut-off `maxLevel(..)` and never `NatSet.length() - 1`); a mapping
  shorter than the variable count (bounds-check, the rest is unchanged).
- Listeners are **the order's** (`VariableOrderObserver`), dispatched once for both diagrams: an order change drops
  every cache, registered ones included; during a reordering caches are cleared, not maintained; the event fires once
  per reordering (`beginReordering`/`endReordering`), silent per swap. An insertion preserves relative order and shifts
  the one stored level (`MtBddOperations.Compose.maxReplacedLevel`); every variable creation fires it, appends too.

### Values (`docs/values.md`)

- `BddMap<V>` is `(function, Values<V>)`; `V` lives on `Values` alone, values are never null. Maps are canonical on
  `(function, numbering)`, so `equals` is `==`.
- **Same numbering means raw int operations**; cross-numbering is supported in one traversal (`apply(other,
  combiner, destination)`, `where`) and **never adopts implicitly**; a destination numbering is always the caller's.
  `cartesianProductMap`'s tuple is one reused buffer.
- Algebraic declarations translate to raw terminals only within one numbering and are ignored across;
  `neutral`/`absorbing` shortcut only once the value is indexed.
- `Values` and `BddMap` are **invariant**; a supertype view is `createRelabeling(W.class::cast)`; binary operations
  take `BddMap<V>`, not `? extends V` (`where`, `agreement`, `difference` excepted, having a cross-numbering path).
- **Indices are recycled**: `ValuesImpl` observes collections; **whoever hands out an index protects it until a node
  holds it**. `equals` across numberings is meaningless, so a hash structure never mixes maps over two numberings;
  semantic comparison is `agreesWith`/`allMatch`. Factory-identity assertions stay.

### Collections and `Cube` (`docs/design.md`)

- What `NatSet`'s factories and operations return **never changes and is shared** (`copyOf` of such a set is the set
  itself; `union` may return an operand); a `MutableNatSet` is the holder's own. A set equals any `NatSet` of the same
  elements, is not a `Set` (`boxed()` is a view), hashes by its words, sorts by `NatSet.ORDER` (size, then
  lexicographic).
- **Two implementation classes, never more** (`ImmutableNatSet`, `MutableNatSetImpl`), so call sites stay bimorphic;
  only they and `NatSetUtil` read a store. A helper that needs one is written in `collections` and exposed through
  `NatSets`. Walk words bit by bit (`forEach`, `iterator()`, `anyMatch`), never a `nextSetBit` loop.
- `Cube`: `of` copies, `ofUnsafe` takes the sets as they are; `assignment()`/`support()` hand out the cube's own
  sets; a walk's cube is working state, `copy()` one that is kept. It lives in the core package and uses only
  `NatSet`'s public surface.
- Per-call memos keyed by functions, nodes or indices are `IntIntHashMap`/`IntObjectHashMap`, never a boxing map.

## 4. Conventions

- **Java 11** source, target and `--release`: no records, `var`, pattern `instanceof`, text blocks, `Stream.toList()`.
- **Zero runtime dependencies**: `jspecify`, `error_prone_annotations`, `immutables` are `compileOnly`; Guava,
  Hamcrest, JUnit test-only.
- `@NullMarked` packages, NullAway in jspecify mode with `assertsEnabled`: `assert x != null;` narrows, inside the
  branch using the value (a disjunctive precondition at the top narrows nothing). The `*Simplify` caches are where
  this bites and the source of the warnings the build reports.
- Suppressions are narrow with a reason: `NullAway.Init` per JMH `@State` field; `// NOPMD - <reason>` on deliberate
  reference comparisons (factory and numbering identity is the check) and `System.out` in benchmark mains;
  `AssertWithSideEffects` on classes bracketing with the access guard; `PMD.AvoidReassigningParameters` on the three
  implementations (operand order canonicalized in place); `PMD.CouplingBetweenObjects` on `BooleanCache`, `MtBddCache`,
  `MtBddImpl` and a test class that trips it rather than splitting further.
- **Assertions carry real work**: `assert accessGuard.acquire(); .. assert accessGuard.release();` and
  `assert isValidFunction(f)` open a public method; tests run `-ea`. An audit of a whole structure is
  `assert !Assertions.COSTLY_ASSERTIONS || ..`, on only with the system property `JBDD_COSTLY_ASSERTIONS` (the test
  tasks set it; a client's `-ea` run went from 2 s to 700 s without the guard). Validation on hot paths stays in
  assertions, not runtime checks.
- **An exception inside an operation, a callback's included, is fatal**: no `try`/`finally` releasing references;
  `finally` only for `Reference.reachabilityFence`.
- **Not thread-safe.** `ConcurrentAccessGuard` detects under assertions only; pure delegations (`or` calling `and`)
  are unguarded on purpose.
- Names: locals `..Level`/`..Variable`; the ends of the level axis `min`/`max`, never `low`/`high` (the children) or
  `shallow`/`deep`; the plural of leaf is `leaves`, `terminal` the MTBDD's valued leaf.
- **Comments are few and load-bearing.** Javadoc states semantics on the interface (what the caller owes and may not
  assume; heuristics and non-promises named as such), one line normally, no implementation detail, no restating the
  cross-cutting contracts (purity, ownership, cursor validity): only a deviation. Non-public code gets no narration;
  worth a comment is a **why** the code cannot show (a tuned constant's sweep, as `MAXIMUM_SIFT_GARBAGE`) or a
  **meaning** intertwined with the rest (`NodeTable`'s field invariants are the model). A `Statistic`'s sentence says
  what question the number answers.
- **Measure; do not reason.** Several plausible optimizations measured slower and are recorded as such
  (`docs/design.md`); anything performance-motivated is backed by `src/jmh`. The JIT's inlining budget is in bytecode
  size including code never executed: an `assert` in a tiny method (accessor, `current()`, one-line utility) carries
  no message; no assertion another already implies. Treat any edit to a small, hot method as a measurable change.

## 5. Tests (`docs/tests.md`)

- `BddTheories`/`MtBddTheories` are the suite and the runtime: `Generator` fills each diagram with random syntax
  trees and the theories compare against a reference evaluation and re-check table invariants. `BddTheories` runs
  seven variants (three `BddImpl`, three `MtBddAsBinaryDd`: never reordered, reordered, reordered with kept
  bookkeeping; one `MddImpl`); random `siftDown`s hit the reordered ones every `REORDER_EVERY` theories and `@AfterAll`
  asserts the order moved. `infoMap` is a `LinkedHashMap` on purpose (identity hashes).
- `TestProfile` scales the **amount** only; variable counts, dimensions and the variant list are fixed. A scaled count
  never floors to zero (an empty `@MethodSource` fails); `REORDER_EVERY` carries a floor.
- `FailFastExtension` skips a class after its first failure. `UtilityTest` asserts the costly assertions are on.
- **`RegressionTests`: one test per past bug; add there when fixing one.** `BddFuzzTest`/`NatSetFuzzTest` walk
  operation sequences against truth tables and `BitSet`.
- **A new operation needs** theory coverage against the reference evaluation, an invariant check and, if it touches
  ordering, a reordered variant.

## 6. Benchmarks

`src/jmh`, JMH, DIMACS instances in `src/jmh/resources`: regression catchers, each a workload a client runs. A
benchmark written to decide an option is run, its result recorded where the decision is documented, and removed.
**Measure before tuning a constant.**

| task | workload |
|---|---|
| `jmhRandom` | `RandomBenchmark`: 18,000 random operations over a growing pool of functions |
| `jmhSynthetic` | `SyntheticBenchmark` (11 queens, a 1024-bit adder), `SyntheticSetBenchmark` (queens over `BddSet`) |
| `jmhDimacs` | `DimacsBenchmark`: CNF instances conjoined clause by clause |
| `jmhEnumeration` | `EnumerationBenchmark`: solution and path cursors over `EnumerationState`'s shapes, identity order and a translated one |
| `jmhNary` | `NaryBenchmark`: `or(int[])` over guarded cubes, `and(int[])` over clauses, cold |
| `jmhMtBdd` | `MtBddBenchmark`: the object layer's maps as a synthesis tool uses them |
| `jmhReorder` | `ReorderBenchmark`: sifting, sifting in groups and `reorderTo` over 8 queens and a 24-bit adder, with and without kept bookkeeping |
| `jmhRelational` | `RelationalProductBenchmark`: preimages `exists next. R & S` over a counter, a shift register and random transitions, 16 and 32 bits |
| `jmhNatSet` | `NatSetBenchmark`: the set operations a synthesis tool spends its time in, per shape |
| `jmhCube` | `CubeBenchmark`: `contains`, `implies`, `intersects` over cubes in `NatSetBenchmark`'s shapes |

## 7. Where the sharp edges are

Ranked by how much time they cost when got wrong:

1. **Cache invalidation on GC**: silent wrong answers, arbitrarily far from the cause.
2. **Work-stack discipline**: a late push, an early pop or a missed early return is a use-after-free that shows only
   when a collection fires mid-recursion.
3. **Cross-table reasoning**: which table can collect here, which operands live on which side; every `MtBddImpl`
   method touching `bdd` answers it explicitly.
4. **Ephemeral parameters escaping their call** (lazy cursors, retained scratch arrays).
5. **Scratch-array aliasing**: `apply`'s `values[]` and the `DepthPool<int[]>` buffers are rewritten in place across
   the recursion; whatever must remember a leaf's values clones. A `Cursor` hands back its own state the same way
   (`ValuedCursor.value()` is a separate accessor so a step allocates nothing). `DepthPool` is valid only because the
   recursions are strictly depth-first and sequential.
6. **Level versus variable**: invisible to every test that does not reorder, which is why the theories run reordered
   and never-reordered variants side by side.
7. **Assertions are the validation layer**: an inverted or over-eager assertion is a bug no production run catches,
   and an untested method's assertions have never been evaluated.

## 8. Navigation: types not in a file of their own

| Type | Lives in |
|---|---|
| `ValuesImpl`, `BddMapImpl`, `RelabelerImpl`, `RegisteredApply`, `RegisteredReplacer` | `BddMapFactoryImpl.java` |
| `BddSetImpl` | `BddSetFactoryImpl.java` |
| `PathWalk`, `SolutionCursor`, `PathCursor`, `BddTable`, `ComposeAnalysis` | `BddImpl.java` |
| `PathWalk`, `SolutionCursor`, `PathCursor`, `MddTable` | `MddImpl.java` |
| `MtBddTable`, `SplitBijection`, `IntTupleBijection` | `MtBddImpl.java` |
| the registered operations (`Compose`, `Exists`, `Apply`, `Mapper`, ...) | `BddOperations.java`, `MtBddOperations.java` |
| `BddMap.Operator/VariableReplacer/Mapper/Combiner/Selector/Relation/Relabeler` | `BddMap.java` |
| `BddSet.Quantifier`, `BddSet.VariableReplacer` | `BddSet.java` |
| the cache shapes (`UnaryCache`/`BinaryCache`/`TernaryCache` with their `Slot`s, `BinaryToIntCache`, ...) | `BooleanCache.java`, `MtBddCache.java` |
