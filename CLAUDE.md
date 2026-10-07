# JBDD — agent guide

Pure-Java (Reduced Ordered) Binary Decision Diagrams and variants. Single Gradle module; the diagrams in one
package `de.tum.in.jbdd` (the implementations are intertwined, the interfaces mark the public surface), with
`de.tum.in.jbdd.collections` (§3) and `de.tum.in.jbdd.io` beside it. GPLv3, `group = de.tum.in`, version `0.7.0` (in `build.gradle.kts` + `README.md`).
Design goals, in this order: **correctness, simplicity, performance, zero runtime dependencies.**

This file is the whole design reference. Read the relevant
section before touching memory management, caches, enumeration, reordering or the value numbering — those are the
areas where a plausible-looking change is silently wrong. Decision diagrams are hard to get right: **prefer
asking for clarification over guessing.**

## 0. Working agreements

- **Do not run the full test suite proactively** — it takes minutes. Ask the user to run it, or run a
  filtered subset. Compile + a targeted test class is the normal inner loop.
- **Spotless / PMD / ErrorProne / NullAway failures are the owner's to fix.** Do not silence them with
  blanket suppressions, and do not treat them as your blocker. If a rule genuinely does not apply,
  suppress at the narrowest scope with a reason.
- **Document the status quo only.** No "previously we did X", no "considered but rejected" narration in
  source or docs, no plan/proposal text. Genuine future work is a short `// TODO` on the line it concerns;
  `TODO.md` carries the reasoning behind each one — what it would take, or why it was answered in the
  negative — and is the place to look before acting on a `// TODO`.
- **Keep this file in sync, in the same pass as the change.** A change that invalidates a paragraph here
  invalidates it for every future session. The same goes for anything that turns out to be a
  "I would have wanted to know this beforehand" — that belongs here, not in a commit message. But **edit,
  do not append**: sharpen or replace the paragraph that was wrong. This is a working reference, not a
  log, and a file that grows without being cut stops being read.
- **The owner edits and reviews alongside you.** Files changing under you mid-session is normal, not a
  sign something broke. Re-read a file before editing it rather than trusting an earlier read, and prefer
  the IDEA MCP (§1) for the live state — that is largely why it is wired up.
- `CHANGELOG.md` is kept current per release (`0.7.0` in progress), against the previous release: what a user of
  0.6 meets, not the steps in between. `README.md` carries the version, the feature list and the recommended JVM
  flags.

## 1. Build, test, tooling

```bash
./gradlew build            # compile + spotless + errorprone/nullaway + pmd + test
./gradlew test             # JUnit 5, -ea, heap 2g..8g; minutes
./gradlew testSmall        # same suite, theory data scaled to 0.05; ~1 min
./gradlew test -Pjbdd.test.scale=0.2                       # any scale, either task
./gradlew test --tests 'de.tum.in.jbdd.RegressionTests'    # quick loop
./gradlew compileJava compileTestJava -q                   # quickest check
./gradlew spotlessApply    # palantir-java-format, 120 cols; pre-commit hook runs spotlessCheck
./gradlew jmhRandom | jmhSynthetic | jmhDimacs | jmhEnumeration | jmhNary | jmhMtBdd | jmhReorder | jmhRelational | jmhNatSet | jmhCube | jmh
```

Gotchas that have cost real time:

- **`--tests` is not a task input.** An unfiltered `test` right after a filtered one is reported
  UP-TO-DATE without running anything. Use `--rerun` whenever "the full suite passed" is the claim.
  `-Pjbdd.test.scale` is the opposite — it reaches the test JVM as a system property, which Gradle does
  track, so changing it re-runs on its own.
- **A scaled run is a smoke test, not a filter** (`TestProfile`, §12). It generates a smaller pool from
  the same seeds rather than a prefix of the full one, so a failure at full scale need not reproduce under
  it. Never report a scaled run as the suite passing.
- **One build at a time.** The test JVM alone takes gigabytes; two concurrent builds fail `:test` with no
  failing test in the report.
- **The configuration cache is on** (`gradle.properties`). A `-Pjbdd.*` property read at configuration
  time is one of its inputs, so changing it re-configures. Forward a new switch to the test JVM the same
  way, as a `-P` property read in `build.gradle.kts`, never as a `System.getProperty` in the build script.
- `-Pjbdd.test.java=11` runs `test`/`testSmall` on that JDK (a Gradle toolchain; it must be installed, nothing
  is downloaded) while the build itself needs JDK 21. CI tests on 11, the baseline, and 21.
- The reorder stress in `BddTheories`/`MtBddTheories` is unconditional; there is no switch, only the
  scale. It is most of those suites' runtime and all of their coverage of a non-identity variable order.
- Publishing (`-Prelease clean publishToSonatype closeAndReleaseSonatypeStagingRepository`) needs Sonatype
  credentials and GPG — not relevant for normal development.

**IntelliJ MCP (`mcp__idea__*`, configured in `.mcp.json`, server at `127.0.0.1:64342`).** It talks to a
live IDE, so it is only available when the user has the project open; fall back to Bash/Read/Grep
otherwise. Where it genuinely beats the shell:

- `get_file_problems` — ErrorProne/NullAway/PMD-class diagnostics for a single file without a Gradle run.
- `search_symbol` / `get_symbol_info` / `analyze_calls` — resolved, not textual. Worth a lot here, because
  many types live as nested classes in a differently named file (§3).
- `rename_refactoring` — safe renames across the package; the codebase renames a lot (`...Level` vs
  `...Variable` naming is load-bearing).
- `reformat_file` — cheaper than `spotlessApply` for one file, though spotless is still the authority.
- `execute_run_configuration` / `build_project` — avoid for tests; prefer the Gradle commands above so
  heap and `-ea` settings are the ones the suite expects.

## 2. BDD theory, as this library realizes it

Enough to reason about the code; not a textbook.

A **BDD** represents a boolean function over ordered variables as a DAG. Each internal node tests one
variable and has a `low` (variable = 0) and `high` (variable = 1) child; leaves are constants. *Ordered*
means every root-to-leaf path visits variables in one global order. *Reduced* means two rules hold
everywhere: **no redundant test** (a node whose children are identical is replaced by that child) and **no
duplicate subgraph** (structurally identical nodes are shared). A ROBDD over a fixed order is a canonical
form — hence the single invariant below.

**The one idea everything follows from.** A *function* is an opaque `int`. A *node* is a row in a flat
`NodeTable`. Every table is hash-consed and every node-building path enforces both reduction rules on
construction, so **id equality is semantic equality**. That is what makes every recursive operation
cacheable on its argument ids, and it is what every bug in this codebase ultimately violates.

**Shannon expansion** is the engine of every operation: `f = (¬x ∧ f|x=0) ∨ (x ∧ f|x=1)`. A binary
operation recurses on the topmost variable of its operands, cofactoring each operand that carries it and
passing the others through unchanged, then rebuilds a node — the classic `apply`/`ite` algorithm, memoized
on operand ids.

**Complement edges.** The sign bit of a function id is a complement flag, so `f` and `¬f` share one node
and negation is free: `positive(f) = |f|`, `complement(f) = -f`, `isComplementFunction(f) = f < 0`
(`BooleanBase`). `TRUE = Integer.MAX_VALUE`, `FALSE = -TRUE`, both sentinels outside the table;
`PLACEHOLDER = 0` is "not a node" (arrays start zeroed, so this makes reallocation cheap). Complement
edges need a canonicalization rule or canonicity is lost — here, **the high edge is never complemented**:
`BddImpl.makeFunction` pushes the complement outwards. `MddImpl.makeFunction` does the same on
`children[0]`. Both `BddImpl` and `MddImpl` use complement edges; `MtBdd` does not, because there is no
canonical way to negate an arbitrary terminal.

Function ids are *not* node ids, and each diagram encodes differently:

| | id encoding | branching |
|---|---|---|
| `Bdd` | sign = complement bit, `TRUE` a sentinel | binary |
| `Mdd` | sign = complement bit | n-ary per variable domain |
| `MtBdd` | positive = table row, **negative = a terminal value** (`value ↔ -value-1`) | binary |

`nodeFor(function)` is the projection. `NodeBasedDd` exists precisely so callers needing the node (ref
counts, saturation) do not confuse it with the function.

**Variable order decides everything.** BDD size is exponentially sensitive to it; finding the optimum is
NP-hard, so the practical answer is *dynamic reordering* by **sifting**: repeatedly pick a variable, sweep
it through the order by adjacent swaps, keep the position minimizing live node count. An adjacent-level
swap is local — it rewrites only nodes at those two levels — which is why `siftDown` is the primitive
(§8).

**Variants.** An **MDD** generalizes the domain (variables take one of *n* values, nodes have *n*
children); an **MTBDD** (ADD) generalizes the codomain (leaves carry values, not just `true`/`false`).
`Bdd` is the intersection of both generalizations. An MTBDD has no `and`/`or`/`xor`; it has
`apply`/`map` with a caller-supplied combining function instead, which is why it cannot reuse the boolean
engine.

## 3. Architecture

Two layers, deliberately separated.

### Int layer — a function is an opaque `int`, memory is manual

```
DecisionDiagram                     ids, ref counting, support, statistics, ReferenceGuard
├─ NodeBasedDd                      nodes: nodeFor / nodeReferenceCount / nodeCount / size / gc,
│                                   plus check / treeToString / invalidateCache
├─ BooleanTerminalDecisionDiagram   codomain fixed to bool, domain generic  → Bdd, Mdd
├─ BooleanDecisionDiagram           domain fixed to binary (highOf/lowOf)   → Bdd, MtBdd
└─ ReorderableDd                    structural: variableOrder, and the two level queries through it
```

- The `...Dd` suffix marks the two **implementation-side** interfaces; `...DecisionDiagram` marks the
  **denotational** ones. `NodeBasedDd` is public because it is useful, not because it is stable — it
  describes how this implementation happens to represent functions.
- `DecisionDiagram` carries the ownership contract only and names no node at all. Everything counting
  nodes — `size(function)`, `nodeCount`, `gc()` — sits on `NodeBasedDd`. `gc()` is a *hint*: the caller
  says "now is a good time", the implementation may decline, and it is a semantic no-op either way.
  Every statistics value is a number (`Integer`, `Long` or `Double`), never a string.
  `NodeBasedDd` also carries the introspection every implementation can answer: `check()` (§4),
  `treeToString`, `invalidateCache`. All three are semantic no-ops or read-only, and all three are the
  kind of thing a caller debugging its own corruption has no other way to reach.
- `ReorderableDd` says only that this diagram *has* an order that can move, and hands it out:
  `variableOrder()`, plus `levelOfVariable`/`variableAtLevel` defaulted through it. Only implementations
  that actually reorder have it, so `Mdd` does not. **Reading the order is the diagram's, changing it is
  the order's** — an order change moves every diagram over those variables at once, so it is not any one
  diagram's to offer.
- Functional interfaces: `BinaryDecisionDiagram`, `MultiValuedDecisionDiagram`,
  `MultiTerminalDecisionDiagram`. Public facades: `Bdd` (= `BinaryDd` + reorderable), `MtBdd`, `Mdd` (no
  reordering). `BinaryDd` is binary + node-based without the reordering promise — the shape `Mdd` has, and
  what the test adapters implement so BDD, MDD and MTBDD can be driven through one type (§12). The split is
  visible on `bdd()`: the denotational `MultiTerminalDecisionDiagram.bdd()` promises only a
  `BinaryDecisionDiagram`, and `MtBdd` narrows it covariantly to `Bdd`, so only the facade hands out the
  implementation-side surface.
- `BooleanTerminalDecisionDiagram<S, P>` is the whole boolean-valued logical API over assignment type `S`
  and path type `P` (`NatSet`/`Cube` for BDDs, `int[]`/`int[]` for MDDs): `and`, `andNot`, `exists`,
  `forall`, `ifThenElse`, `simplify`, solution and path cursors, the `xyIn` / `xySimplify` variants.
  `constrain`, the generalized cofactor, is `BinaryDecisionDiagram`'s and `MultiTerminalDecisionDiagram`'s only:
  it needs a "nearest" domain-satisfying value, which an n-valued variable does not have, so `MddImpl` offers
  `simplify` alone. So is the relational product `andExists` (with its dual `orForall` and `registerAndExists`):
  the interface's defaults build the conjunction, `BddImpl` fuses (§6).

Implementations (public, like most of the package - visibility here is deliberately open, so a client can build
on `GcReferenceManager` or `NodeTable`; still, construct the diagrams only through the factories):

- `BooleanBase<S, P>` — shared engine for `BddImpl` and `MddImpl`.
- `MtBddImpl` — **not** a `BooleanBase`. It *composes* a `BddImpl` (its variable universe) and owns its
  own `NodeTable.Binary`: two disjoint DAGs, one variable numbering, two independent GCs.
- `NodeTable` — the manual-memory heart: flat primitive arrays, hash-consed unique table, packed metadata
  `<VAR:17><REF:14><MARK:1>` (asserted to fill an `int`), free list, mark-and-sweep GC, growth, optional
  reordering bookkeeping. `NodeTable.Binary` (parallel `low[]`/`high[]`) and `NodeTable.Multi` (jagged
  `int[][]`); each diagram supplies a nested `Table` filling in the abstract hooks.
- `DdContextImpl` — the BDD and its MTBDD, and the creation of the variables they share. Deliberately
  shallow: it constructs the order first, then both diagrams eagerly (BDD first — the MTBDD hangs its
  caches and observers off it). Each diagram keeps only its own table, cache and `rewriteLevelAfterSwap`
  half of a swap.
- `DdVariableOrderImpl` — the order those variables are laid out in and everything that moves it: the
  bijection, the entire sifting engine, the `reorder_*` statistics. Both diagrams hold it **directly**
  rather than reaching through the context, because `levelOfVariable`/`variableAtLevel` sit on the hot
  path of every node operation. It reaches the diagrams the other way, through the context, which is what
  keeps construction acyclic — nothing it does at construction touches them.
- `ConcurrentAccessGuard` — thread-identity, reentrant, assertion-only. A field on `BooleanBase` and
  separately on `MtBddImpl`. Every public write entry point brackets its body with
  `assert guard.acquire(); … assert guard.release();`. Pure-delegation methods (`or` calling `and`) are
  deliberately unguarded — they do no write of their own and would conflict with what they delegate to.
  It only *detects*; synchronization remains the caller's job.

Entry points — never `new BddImpl(...)` outside tests:

- `BddFactory.buildBdd() / buildMtBdd() / buildMdd()` (each optionally with a `BddConfiguration`).
- `DdContext.create(...)` — the BDD/MTBDD pair over **one** variable order (`bdd()`, `mtBdd()`,
  `variableOrder()`), the two `createVariable*AtLevel` forms, and **the** `statistics()`: both diagrams,
  their tables and caches, and the order, in one map. It is the only public accessor over a pair — neither
  `Bdd` nor `MtBdd` reports its own, since a partial view of one key space is what made the numbers hard to
  find. The accessor is the public `StatisticsSource` (`statistics(StatisticsDetail)`, `describeStatistics()`),
  which `DdContext` and `Mdd` extend; `Mdd` keeps one of its own: it is its own variable universe, with no
  context above it. **A key exists only as a `Statistic` constant** next to the counter it reads - name, kind
  (`COUNTER` summed, `GAUGE` the current value, `MAXIMUM`, `RATIO` recomputed from its parts, never averaged) and
  a sentence; a `Statistic.Ratio` names its parts and is computed from them, never handed a value. Every
  structure writing statistics (tables, caches, the order, the wrapper managers) implements the package-private
  `StatisticsReporter`, whose one `report(report, detail)` writes into a `StatisticsReport`; the public sources
  implement `StatisticsReporter.Source`, which reads both the snapshot and the descriptions from one such report,
  so a key cannot go undescribed and a ratio cannot disagree with its parts. `StatisticsReport` is public only
  because those classes are, and opaque outside the package. Scopes carry the prefixes - the configuration's `name()`, the
  diagram's `bdd_`/`mtbdd_`/`mdd_` (`statisticsPrefix()`) over its table and its caches, `cache_<name>_` within
  it, the order's keys unprefixed (the order is the context's), `set_`/`map_` - and the name `{name}`
  in a sentence stands for; a key written twice fails an assertion. `Statistics.formatStatistics` renders a
  snapshot. `statistics(StatisticsDetail)` reads to a detail: `COUNTERS` are the fields the
  structures keep as they run (no pass, no write, no access guard - the one level another thread may read,
  best effort), `FULL` (what `statistics()` reads) adds a read-only pass over each table (valid, referenced
  and saturated nodes, the nodes below the referenced ones, counted with a visited `NatSet` of its own, never
  the mark bits; the hash chains). JBDD reports nothing on its own at shutdown: a client wanting the
  statistics of a run that is being stopped reads `statistics(COUNTERS)` from a shutdown hook of its own and
  writes them wherever it likes
  (logging from a hook is unreliable - `java.util.logging` resets its handlers in a hook of its own). Creating a variable is the context's
  because it is about the *universe*, and it hands back a **BDD** function whichever diagram the caller came
  from.
- `BinaryFactoryContext.create(...)` — a context plus the object-layer factories `bddSets()`, `bddMaps()`
  (one each per context).
- `BddConfiguration` — an `org.immutables` `@Value.Immutable` generating `ImmutableBddConfiguration`:
  the initial table sizes (`initialSize()` for the BDD, `mtbddInitialSize()` defaulting to it), one
  `cacheSizeDivider()` for every operation cache (a binary, ternary or ephemeral cache is `table / divider`
  bins, a unary one half that, a registered operation's an eighth - `CacheBase.REGISTERED_OPERATION_DIVIDER`),
  `keepReorderingStructures()`, and the two GC knobs `useGarbageCollection()` / `gcLiveNodeThreshold()`.
  Deliberately nothing else: users do not tune caches, tables double, and a cache always keeps what is
  still valid across a collection. It extends `NodeTableConfiguration`, which is all `NodeTable` ever sees.

### Object layer — wrappers with automatic reference management

- `BddSet`/`BddSetFactory` over a `Bdd`; `BddMap<V>`/`Values<V>`/`BddMapFactory` over the MTBDD.
- `GcReferenceManager`: wrappers are held by `WeakReference` and a `ReferenceQueue` drives
  `reference`/`dereference` — no `finalize()`, which carries a hefty penalty. `protect(container)` is
  canonical per the `long` `canonicalKey()`: the function's 32 bits by default; a `BddMap` puts its
  numbering's key space (a per-`ValuesImpl` counter) in the high half, since a function id alone is
  ambiguous across numberings. The wrappers sit in one open-addressing table per manager (linear probing,
  backward-shift deletion, key `0` empty because `PLACEHOLDER` is never a function), so a lookup allocates
  nothing. **Never split it per key space**: the table is what keeps the weak references reachable, and a
  `Reference` that is itself unreachable is never enqueued - its function would stay referenced for good.
  A wrapper collected but not yet queued is replaced in place, the new one inheriting its reference. The queue is
  drained on every miss and before every collection of the owner's table (`drainBeforeGc`, registered by both
  factories), so a queued wrapper's nodes are not counted live; `BinaryFactoryContext.statistics()` reports
  `set_`/`map_` `wrapper_count`, `wrapper_drained_count` and `wrapper_drained_before_gc_count`. The table
  grows at 2/3 load and shrinks when a drain leaves it below 1/8.
- A caller wrapping sets in its own type binds it with `BinaryFactoryContext.attachToSets` (an
  `Attachment<BddSet, A>`) - one slot per `BddSetImpl`, built lazily, living exactly as long as the set -
  rather than keeping a second canonical map of its own. It stays off `BddSet`/`BddSetFactory`, which are
  the semantic interfaces.
- `BddSetFactory.of(expression, ExpressionStructure)` builds a set from a caller's own propositional expression
  type, read through the structure (a pure callback, like every other); `known` lets the caller supply sets it
  already has for subexpressions. One memoized build, every intermediate referenced until its end.
- A caller converting a diagram into something of its own (an expression, a hash, an encoding, the values in
  order) folds over it: `BddSet.fold`, `BddSetFactory.fold(roots, folder)`, `BddMap.fold`. One int-keyed walk, no
  wrappers, the folder called per node, children first and high before low, memoized per node (`FoldMemo`, a null
  result included). The result type is nullness-parametric (`R extends @Nullable Object`): a folder is handed only
  results it returned, so one over a non-null `R` never checks for null and one returning null declares
  `@Nullable R`, and the IDE and NullAway hold each to its choice. The two folders differ on purpose: a set's
  diagram shares a node between a function and its complement, so `BddSet.Folder` has `trueValue()` and an abstract
  `complement`, computed once per node a complement edge or root needs it - a folder forgetting it does not compile;
  `BddMap.Folder` has a value and no complement.
  The folder runs between the walk's reads; the roots stay referenced and nothing moves, so it may build on the
  factory. There is no snapshot type: a caller of the int layer walks `highOf`/`lowOf` with an `IntObjectHashMap`.
- A set or map crosses contexts only by `BddSetFactory.adopt(set, variableMapping)` /
  `Values.adopt(map, variableMapping, valueMapping)`, over the int layer's `adopt`. Both create the mapped
  variables first; the map version maps each distinct value before the traversal (so the mapping may build
  anything) and keeps those terminals referenced until the end, so no collection frees their indices. The int
  layer's `adopt` is native in `BddImpl` (complement edges shared) and `MtBddImpl`: a primitive memo, each
  rebuilt node on the work stack until the end, and a node made directly where the mapped variable lies above
  both rebuilt children. A source of another implementation goes through `BddUtil.adopt`.
- `BddUtil` holds what is computed from a `BinaryDecisionDiagram`'s public operations alone and has no native
  counterpart: `implicants`, `primeImplicants`, `shortestPath`, and the generic `adopt`.
- `BddSet` deliberately exposes nothing assuming a fixed variable universe — callers always name the
  support they mean.
- `Cube` is the one type for a conjunction of literals - equivalently a partial assignment:
  path walks and `BddUtil`'s `implicants` / `primeImplicants` / `shortestPath` hand them out, `restrict` and
  `BddSetFactory.of` take them, `of(Cube)` builds one's function. Its operations return new cubes; a walk's cube
  is working state (§8). `of` copies what it is given and `ofUnsafe` takes the sets as they are (checking the
  assignment against the support by assertion only); the accessors `assignment()` / `support()` hand out the
  cube's own sets, never copies, so a caller keeping one across a walk's step (or JBDD keeping one as a cache
  key - `restrict` does) takes `copy()`, a no-op over sets that never change.
  It sits in the core package, not in `collections`: it is a logic notion over the diagrams' variables, and from
  there it can only use `NatSet`'s public surface - its `contains`, `intersects` and `implies` are `NatSets.equalOn`
  / `equalOnIntersection` and a `containsAll`.
- `io.DimacsReader` parses DIMACS CNF (benchmarks/tests).

### `de.tum.in.jbdd.collections`

Collections independent of decision diagrams, public for users too; nothing here depends on the core package.

- `NatSet` / `MutableNatSet`: a finite set of naturals, primitives only, not a `Set<Integer>` (`boxed()` is that,
  a view). What `NatSet`'s factories and operations return never changes and is shared, not copied (`copyOf` of
  such a set is the set itself; `union` and friends may return an operand that never changes); a
  `MutableNatSet` is the holder's own - what a method returning a copy returns, and what `MutableNatSet.copyOf` makes.
  A set equals any `NatSet` of the same elements and nothing else; its hash code mixes its non-zero words with
  their indices (murmur3's finalizer), built on the fly in array mode, so both classes and representations
  agree - `Set`'s sum of the elements would put all subsets of `[0, 21)` into 211 buckets. `toString` is
  `Set`'s; `NatSet.ORDER` orders by size, then lexicographically. `subSet(from, to)` is the elements in a range,
  `slice(from, to)` the same re-based at 0 (`BitSet.get(from, to)`), `rank(element)` the number of elements below
  one without building that subset (a binary search of the array; the words' popcounts from the nearer end, the far
  side being the size less the rest): 1 to 6 ns and nothing allocated on `jmhNatSet`'s shapes, where
  `subSet(0, element).size()` takes 6.5 to 16.5 ns and 12 to 53 bytes; `MutableNatSet.freezeAndClear()` hands a built set's
  store to a set that never changes and leaves the mutable one empty. `shifted(amount)` and
  `MutableNatSet.shift(amount)` move every element, dropping those that would turn negative (a word-wise shift
  with carry, or an add per array element; in place, the array stays an array). `MutableNatSet` has
  `java.util.BitSet`'s mutators under their names; arguments are checked by assertion only. JBDD's API speaks it throughout (a fresh result such as `supportAt` or `anyAssignment` is a
  `MutableNatSet`); `copyOf(BitSet)`, `toBitSet()` and `copyInto(BitSet)` convert.
- **Two implementation classes, never more**, so a call site stays at most bimorphic: `ImmutableNatSet` (an
  exact ascending array or words, in one `final` `Object` field told apart by `instanceof` - 24 bytes rather than 32
  for two typed fields; its hash code computed once, on construction; the empty set and the singletons below 128
  shared) and `MutableNatSetImpl` (at most 16 elements as a sorted `int[]`, words otherwise; an insertion may move it
  to words, only `optimize()` moves it back, so removing never changes the representation). Words are chosen when
  they take no more memory than the array, counting the JVM's padding of arrays to 8 bytes (`NatSetUtil.useWords`):
  a singleton below 64 is a word, as `int[1]` and `long[1]` both take 24 bytes, and arrays are left for sets far
  apart. Words-only was measured and gains nothing (a synthesis tool end to end, `jmhEnumeration`/`jmhRandom`), while sparse sets
  as words take 2 to 10 times as long and up to `element / 64` words. Neither class is a `Set`: `boxed()` wraps the
  set in a `BoxedNatSet`, with `Set`'s equality and hash code, whose mutators always throw over an immutable set.
- The read algorithms over either store are static functions in `NatSetUtil`, shared by both. Nothing outside the
  two implementation classes and `NatSetUtil` reads a store (`wordsOf`, `elementsOf` and friends; `NatSetTest`
  checks the representation chosen), and the package makes that a compile-time rule for everything outside
  `collections`: a helper that needs a store is written there and exposed through `NatSets`, as `equalOn` is.
  Operands of one
  representation meet word against word or array against array (`union`/`intersection`/`difference` of immutable
  sets build the result's store directly, and so do a mutable set's over two arrays - `ImmutableNatSet.unionOfArrays`
  and friends; over words, copying the mutable set and combining in place measured faster - and a factory picks the
  representation before it copies anything; `NatSet.ORDER` compares with `Arrays.mismatch`/`Arrays.compare`), and
  `containsAll`/`intersects` of words against an array test the array's elements in the words (mostly a tiny set
  against a larger one) - in the loop rather than a helper, as a call site C2 does not find hot inlines 35
  bytes at most. An immutable set's representation follows from its elements and its store is exact, so two are equal
  exactly if their stores are; otherwise equal sizes and words equal up to the shorter's end (a mutable set's words
  may run on), or equal array prefixes, decide. A mutable set's bulk and range operations count the change in the
  words they touch, not every word (asserted). `NatSets.equalOn` (two sets holding the same elements of a scope) and
  `equalOnIntersection` (of two scopes' intersection) are one pass over the words, else a walk of the (smaller)
  scope. A fused single pass for `Cube.implies` (five stores read instead of a `containsAll` and an `equalOn`)
  measured 1.2 to 1.7 times slower.
- **Words are walked bit by bit**, one trailing-zero count and one clear per element: `forEach`, `anyMatch`/
  `allMatch`/`noneMatch`, and the primitive `iterator()` as a cursor on a word. Walking run by run, and choosing by
  sampling the runs first, was measured once (a benchmark since removed): runs won 13 to 24% only with runs of twelve elements and more
  below 1024 bits and lost up to fivefold above, and the sampling cost more than either walk. These, not a
  `nextSetBit` loop, are how to walk a set: in array mode `nextSetBit` searches the array.
- `NatSets`: helpers over `NatSet` - mapped copies and views, `int` encodings, `difference` into a target, equality on a
  scope (`equalOn`, `equalOnIntersection`),
  `increment` (a set as a binary counter over given positions: a contiguous one carries with `nextClearBit`),
  `forEachWithIndex`, and `powerSet`, a `Cursor` handing out that counter.
- `Cursor` (the enumeration shape of §8), `IntIntHashMap` / `IntObjectHashMap`.

### Navigation: types that are not in a file of their own

Many important types are nested. Searching for `ValuesImpl.java` will fail.

| Type | Lives in |
|---|---|
| `ValuesImpl`, `BddMapImpl`, `RelabelerImpl`, `RegisteredApply`, `RegisteredReplacer` | `BddMapFactoryImpl.java` |
| `BddSetImpl` | `BddSetFactoryImpl.java` |
| `PathWalk`, `SolutionCursor`, `PathCursor`, `BddTable`, `ComposeAnalysis` | `BddImpl.java` |
| `PathWalk`, `SolutionCursor`, `PathCursor`, `MddTable` | `MddImpl.java` |
| `MtBddTable`, `SplitBijection`, `IntTupleBijection` | `MtBddImpl.java` |
| the registered operations (`Compose`, `Exists`, `Apply`, `Mapper`, …) | `BddOperations.java`, `MtBddOperations.java` |
| `BddMap.Operator/VariableReplacer/Mapper/Combiner/Selector/Relation/Relabeler` | `BddMap.java` |
| `BddSet.Quantifier`, `BddSet.VariableReplacer` | `BddSet.java` |
| the concrete cache shapes (`UnaryCache`/`BinaryCache`/`TernaryCache` with their `Slot`s, `BinaryToIntCache`, …) | `BooleanCache.java`, `MtBddCache.java` |

## 4. Memory management: three overlapping mechanisms

Manual, and the three protect against different things. Confusing them is the classic bug.

1. **Reference counts.** Per node, 14 saturating bits in the metadata word. Saturated = pinned forever,
   ref/deref become no-ops (variable nodes are saturated at creation; `BddSetFactory.pin` saturates a
   set's root, so there is no unpin and pinning twice is free). `consume`/`updateWith`/
   `ReferenceGuard` are the rebalancing helpers. MTBDD *terminals* get a parallel `short[]
   valueReferenceCounts` indexed by value — same saturating scheme, separate array.
   **Nothing returned by any operation is automatically referenced.** Chaining two constructing calls
   across statements requires explicit bookkeeping in between.
2. **The work stack** (`NodeTable`, plus an independent `secondaryWorkStack`). Protects *intermediate*
   results within one operation, cheaply. Discipline: push every freshly computed child **before**
   anything that can allocate, pop right after it is consumed, in matched pairs — allocation →
   `ensureCapacity()` → GC/grow, which sweeps anything neither referenced nor on a work stack. Public
   entry points assert the stacks are empty before *and* after. The secondary stack exists for
   accumulators that must outlive a nested call's stack usage (e.g. `split`'s bijection) without breaking
   the primary stack's LIFO counting.
3. **`GcReferenceManager`** — the object layer (§3).

**Which stack a result needs is determined by which table can GC, not by where the value came from.**
`MtBddImpl.agreement` builds *BDD* nodes, so it pushes to `bdd.table()`'s stack, not its own. An MTBDD
operation touching only MTBDD nodes needs no BDD-side protection, and vice versa; any MTBDD operation
allocating BDD nodes must protect its BDD operands the way `BddImpl` would.

The `*Simplify` family is the sharpest instance: narrowing a domain quantifies a variable out via
`bdd.computeOr`, which allocates BDD nodes, so every domain (the caller's and each widened one, for the
duration of its subtree) lives on the BDD work stack. The BDD `compose` pushes its mapping for the whole call
(a registered composer references it instead).

Two things a work-stack push does **not** do: survive past the call, and cover a *bare terminal value* the
caller referenced but has not embedded under any node — the mark phase only reaches leaves transitively.
Hence `MtBddTable`'s reclaim spares any value with a nonzero refcount even if unmarked.

### GC and growth

**`MtBddImpl.of(int)` is the second trigger, and the only one outside the table.** A value is an
allocation the node table never hears about, so a workload producing many of them while building few nodes
never reaches `ensureCapacity` and nothing ever reclaims a dead one. `of` therefore counts genuinely new
values since the last collection and forces one when that count passes `valueCollectionThreshold` *and*
exceeds the nodes created in the same span — where nodes are being made, the table's own trigger is
already doing the job. The threshold doubles (to `MAXIMUM_VALUE_COLLECTION_THRESHOLD`) whenever a forced
collection frees no value, so a workload legitimately holding many of them does not pay a mark every few
thousand allocations, and resets the moment one does free something. `mtbdd_value_triggered_collections`
counts how often it fired. The consequence for callers: **`of(int)` allocates, so it collects** — a bare
terminal held across it needs the same protection as anything else, which for the in-recursion callers is
the work stack (`doSetMarkBelow` marks a leaf pushed there, which is why `collectForValues` pushes the
value it is about to hand out).

`ensureCapacity()` runs from `makeNode` when free nodes fall to ≤ 25%. It lives **once**, in `NodeTable`,
and is `final`; each table supplies hooks (`configuration`, `notifyBeforeGc`/`notifyAfterGc`/
`notifyAfterTableGrowth`, `clearUnreferencedLeaves`, `checkOwner`, `bytesPerSlot`). Keep it that way — a
per-table copy is how one table silently loses a step (draining `ProtectionTracker` before marking, say).

```
drain phantom refs and queued wrappers (notifyBeforeGc), then
mark everything reachable from referenced|saturated nodes and both work stacks
  ├─ live ≤ gcLiveNodeThreshold (default 0.5) → reclaim the rest, notify afterGc
  └─ else                                     → invalidate the unmarked, grow, notify afterTableGrowth
```

Three load-bearing properties:

- **The threshold is hysteresis, not a "worth it" test.** Collection triggers at 75% full, so threshold
  *t* leaves `1-t` free: the next full mark+sweep is due after allocating `0.75-t` of the table. The
  default 0.5 buys 25%; raising it toward 0.75 shrinks that window quadratically in cost (0.7 buys 5%,
  i.e. tens of random node visits amortized *per created node*). Trading memory for that window is the
  point of the knob.
- **The mark is unconditional** (given `useGarbageCollection()`). It must not be gated on
  `approximateDeadNodeCount`, which counts only refcount 1→0 transitions — garbage never referenced (every
  abandoned intermediate) is invisible to it, so a workload that never dereferences would grow forever.
  Marking is O(live) random visits, at most the order of the `grow` it may avoid, and the unmarked nodes
  are dropped before growing either way (rebuilding their chain entries is the expensive part of growing).
- **Growth is capped by the heap** (`nextSize`/`bytesPerSlot` against `Runtime.maxMemory()` minus used,
  with `MEMORY_SAFETY_FACTOR = 1.25`). `bytesPerSlot` is the subclass's `structuralBytesPerSlot` plus
  `parentCount` while live — ignoring it sizes a slot at 20 bytes when it costs 24. Per-variable node
  lists are deliberately *not* in there: they hold valid nodes, not slots, and a grow does not touch them.
  Used memory includes uncollected JVM garbage, and garbage-inflated readings made a table collect every few
  percent of allocations instead of growing (22 against 3.4 work per created node, with 600 MB of garbage in a
  1 GB heap). So `nextSize`, where the reading falls short, asks the JVM for a collection
  (`collectedAvailableMemory`, `Runtime.gc()`) and reads again - at most once per table size, so a really full
  heap does not pay one per table collection. The heap is read at most once per `ensureCapacity`, and only where
  it decides: memory can only raise the live threshold, so after the mark it is asked only when more than the
  configured share is live, and otherwise only before a growth.
  No reading sees through the garbage without a collection: the memory pools' current usage sums to the
  `Runtime` reading, their usage after the last collection misses everything that reached the old generation
  since (a freshly grown table included, so growing on it risks a real `OutOfMemoryError`), and counting eden as
  free needs the collector's pool roles. Allocating and catching the `OutOfMemoryError` would be exact, but trips
  `-XX:+ExitOnOutOfMemoryError` and heap dumps. The case above is the only evidence for the collection: under the
  Parallel collector (queens 12, `-Xmx1g`, live ballast and churn between operations) it never helped and once
  hurt (8.4M slots at 8.7 work per created node against 10.0M at 7.75 without it). When the larger table would
  not fit, the live threshold rises to 0.9
  (`MEMORY_PRESSURE_LIVE_NODE_THRESHOLD`) — a dense table beats being unable to allocate. A growth smaller
  than a sixteenth of the table (`MINIMUM_GROWTH_DIVISOR`) is not taken: it would buy a few allocations per
  full mark. A table that cannot grow collects instead, and then collects again only once half of what that
  left free is used (`denseFreeThreshold`) - without that, every allocation below the quarter-free trigger
  was a full mark, and a synthesis workload ran for hours. If a collection leaves under a thirty-second free
  (`MINIMUM_FREE_DIVISOR`), or a table that could not collect (inside a rewrite, or with collection off) has no
  free node left, `ensureCapacity` throws `OutOfMemoryError`. Whether the table can grow is decided
  *before* choosing between reclaiming and the futile path, since the futile path's invalidation is valid only
  right before `grow()`.

`biggestReferencedNode` is an upper bound, exact after every sweep (both sweeps pass every node and record
the topmost referenced one) and lax in between: a dereference never searches downwards for the next
referenced node, which cost as many slot visits as lay between the two - the whole table, for a fresh result
referenced over a few operations and released again (measured at 300 µs per reference/dereference pair
with a million dead slots below it). It bounds the loops over referenced nodes: `markAllReferencedNodes` (every
collection), `referencedNodeCount()` (and so the statistics) and `check()`.

`invalidateUnmarkedNodes()` deliberately does *not* fix the free list or counts — it is valid only
immediately before `grow()`, which rebuilds both. It does reset the dead-node counter (like
`reclaimUnmarkedNodes()` it removes every dead node) and drops the reordering bookkeeping, since the
half-broken table it leaves offers nothing to patch up.

**Neither a collection nor a growth re-derives the reordering bookkeeping.** Growing moves no node, so
node-indexed parent counts and the per-variable lists stay valid; only hash chains depend on table size. A
collection reclaims exactly what nothing reaches — precisely the nodes the parent counts already call dead
— so every survivor keeps its count and `deadNodeCount` drops by what was collected. The lists are
filtered by `compactVariableLists`, one streaming pass. Re-deriving instead (mark from roots plus a relink
of every node) was ~20% of every reordering workload. Appending from inside the sweep looks cheaper (the
sweep already holds the survivor's variable) and measured **12% slower**: it turns a sequential stream into
a dependent load of `variableChains[variable]` per node.

`reclaimUnmarkedNodes()` rebuilds *all* hash chains from scratch rather than unlinking only dead nodes.
Read the comment there before "fixing" it: a node has one chain slot shared with the free list, so dead
nodes must be unlinked precisely; that means walking chains (random access) instead of streaming the table
(sequential), it cannot produce the ascending free list `check()` asserts, and the only winning variant —
repairing just the buckets containing a dead node — pays off only when few nodes died, which the threshold
above avoids by growing instead.

`MtBddTable` additionally sweeps terminal values in the *same* mark pass via the managed-leaf hooks
(`markLeafNodeIfManaged`, `anyManagedLeafMarked`, `recurse*`). Leaf marks live in a separate `MutableNatSet
markedValues`, so **step order matters**: the leaf sweep must run before `reclaimUnmarkedNodes`, whose
closing `assert isNoneMarked()` also checks leaf marks. The `includeLeaves` flag threaded through the
marking family distinguishes a full GC-style mark (liveness depends on leaves) from a dedup-only walk
(`nodeCountBelow`, `forEachVariable`) that must not touch leaf state; `BddTable`/`MddTable` ignore it.

`NodeTable.check()` is a large invariant verifier every table gets for free (canonicity, hash chains, free
list, ref/mark consistency, child variable ordering) and transitively validates MTBDD terminal allocation
through the `isValidLeafNode` hook. **Reach for it first** — a corrupted table usually fails far from the
corrupting call.

**Watching this in production.** `node_table_work_per_created_node` — (marked + swept + rehashed) over
created nodes — is the one number capturing whether memory management pays for itself.
`node_table_gc_collected_nodes` does *not*: a table collected twice as often collects **more** nodes, not
fewer. Read it with `node_table_slots_per_live_node` (the memory being spent to keep it low) and
`node_table_gc_yield`. `node_table_futile_gc_count` counts marks thrown away by a subsequent grow;
`node_table_memory_limited_grow_count` says the heap, not the configuration, is picking the table size;
`node_table_jvm_gc_request_count` counts the JVM collections asked for before deciding that.
All O(1) per collection; nothing on the hot path.

## 5. The recursive-operation skeleton

Core operations are **plain recursion** over the diagram; deep structures are handled with a large `-Xss`,
not hand-rolled explicit stacks (an iterative implementation measured ~10% slower on some benchmarks and is
tedious to maintain). Keep new operations in that shape.

Every `compute*` / `*Recursive` follows six steps; deviations are where bugs live:

1. Trivial/constant short-circuits — **before** any cache lookup, always.
2. Canonicalize operand order, *only* if the operation is genuinely commutative.
3. Cache lookup; keep the raw pre-modulo hash (`lookupHash()`).
4. Shannon-expand on the smallest top **level**; an operand not carrying that variable passes through
   unchanged on both branches. `decisionLevelOrMax` gives a constant `Integer.MAX_VALUE`, below every
   level, so the minimum over the operands picks the step and `lowIf`/`highIf` pass a constant through -
   no per-operand constant branches.
5. Recurse, pushing each result to the work stack before building the parent.
6. Build, pop, `put(hash, …)`, return.

The stashed hash stays valid across the recursion because the keys it came from stay alive (work stack).
If the table grew meanwhile, the hash still lands in a legal bin — a wasted slot, not corruption.

**`and(int[])` / `or(int[])` are one n-ary recursion**, not a fold: the operand tuple is canonicalized (no
constants, no duplicates, sorted by top level, then node, then sign - one `long` key per operand, the level in the
high word - so a complementary pair is adjacent and means false, the operands deciding the step lead and the deepest
close the tuple). The step expands on the first operand's level; the rest pass into both cofactors unchanged and in
order, so each cofactored tuple is the deciding operands' children, sorted, merged into that rest - an operand that
became true drops out and the tuple shrinks along the path. Sorting each whole cofactored tuple instead cost twice
as much on `jmhNary`'s clauses. Results are cached on the tuple (`OperandTupleCache`, keyed and pruned like the
compose tuple cache). On unions of thousands of cubes over the same variables it creates a quarter to an eighth of
the nodes a pairwise fold does. It pays only where the tuple shrinks along the path, i.e. where operands
share their top variables (cubes over the same variables, clauses); operands over distinct variables (a
formula's conjuncts) stay in the tuple to the bottom and cost their number per node, so a step whose operands do
not outnumber their distinct top levels four to one (`NARY_MINIMUM_OPERANDS_PER_TOP_LEVEL`; a client's formula
build 6.9 → 12.0 s n-ary, 6.4 s with the choice) finishes its tuple pairwise, folding it from its end (deepest top
level first). The choice is made at every step, not only at the entry: operands sharing only their first variables
(`x0 ∨ gᵢ`, the `gᵢ` independent) look shared at the entry and are 5 to 12 times slower n-ary below it. A switch is
for the whole subtree, which never comes back to the n-ary; it costs up to a fifth on dense random clauses, whose
deep tuples would still have shrunk (`TODO.md` [NARY-SPLIT]).
`NaryBenchmark` (`jmhNary`) times both shapes.

**MTBDD-specific constraint:** the combining function is an **opaque caller lambda with no assumed
algebra**. There is no `f == g ⇒ f` shortcut and no idempotence — `apply(f, f, op)` must fully recurse.
Callers opt into properties explicitly via `MtBddBinaryOperator`/`MtBddNaryOperator`
(`commutative`/`neutral`/`absorbing`), which are *unchecked preconditions* spot-checked at leaves by
`checkLaws` under `-ea`. `commutative` enables step 2, `neutral` returns the other operand unchanged,
`absorbing` prunes both subtrees.

**Callbacks run inside the traversal.** An `apply` operator, a `map` function, a `where` predicate and the
like are invoked with the work stacks loaded, so each must be a pure function of its arguments and must
not start another operation on the diagram — one that does trips the `workStacksEmpty()` assertion. The
work stack itself would survive a nested, balanced operation; what would not are the ephemeral caches (a
nested `apply` with another operator re-initialises the cache the outer one is still filling, so its
results land under the wrong operator) and the mark bits a value walk (`forEachValue`, `allValuesMatch`)
holds while a nested collection marks and clears everything. Purity is the general contract; a method's
javadoc should document only a *deviation* from it. The deviations: `splitRelabeled` /
`splitBddRelabeled` (and so `BddMap.splitMap`, `BddSet.split`) run their relabeler *between* the split and
the mapping, with the meta-function and every residual referenced rather than on a stack, so it may build
functions, and `Values.adopt` maps its values before the pass.

## 6. Operation caches

`CacheBase` is the shared engine: flat `int[]` table, fixed stride per bin, prime sizes, lazy resize,
**one candidate bin per key** — direct-mapped (`mod(hash, size)`, no probing), so a collision *overwrites*.
These are memos, not maps: **a cached result may vanish and every caller must be correct without it.**
Two key flavours: `CacheBase.IntKeys` packs `int` keys into the bin (`keyCount` leading slots the key,
the rest the result) and does every lookup and put (`findBin`/`storeKeys` by key count, `resultIn`/`storeResult`
for a result kept in the bin); a concrete cache adds only where its result lives and what makes an entry stale.
`BooleanCache.IntCache` asserts every key is a *non-constant* function, since step 1 of §5 short-circuits
constants before any lookup. The MTBDD int caches are one class per key count, told apart by their `Slot`s
(`MTBDD`, `BDD`, `PLAIN` per key and result), from which the per-table validity checks follow. A boolean result
is a bit (`CacheBase.Bits`), not a slot. `CacheBase.ObjectKeys<V>` keys on an object
(`cartesianProduct`'s operand tuple).
Resizing lives in the base (`rehashInto`, hashing the stored key exactly as `lookup` did); a cache whose
result sits in a *parallel* array overrides `growInto` and passes a `BinRelocation`. `BooleanCache` (one
table) and `MtBddCache` (entries can straddle *two* tables) subclass it separately — each concrete cache
knows, per slot, which table it belongs to (`isValidBdd`/`isValidMtbdd`).

**Validity is reactive, never proactive.** A cached result is not referenced or pinned. When a table GCs,
a hook re-checks or wholesale invalidates the affected slots; between GCs entries are assumed good. Two
consequences that are easy to get wrong:

- The prune hook must run on **every** GC of **every** table an entry can reference, **before any new
  allocation** — node ids are recycled, so a stale entry can silently become "valid" again while naming a
  different function. Hooks are `NodeTableObserver`s; `ObserverGroup` holds *caller-owned*
  observers weakly (`register`) and *owned* ones strongly (`registerStrongly`). A diagram's own
  hook in the weak list is eventually collected, after which pruning silently stops and wrong answers
  appear at random — **always `registerStrongly` for those.**
- Anything a key *implicitly* depends on but does not encode must invalidate the whole cache when it
  changes. Four flavours:
  - **Stable** — keys are plain ids (`agreement`, `ite`, `update`, `simplify`, `constrain`), or carry the
    whole context: the BDD `compose` (`ComposeTupleCache`, see below), `restrict` (`RestrictCubeCache`, the
    node and the cube of the literals below the prefix it walks without the cache, so restrictions differing
    in that prefix share it), the support (`supportCache`, ascending variables per node; filled by composition only, which grows it on
    usage - support queries walk the diagram and take an entry where they meet one) and the satisfying
    fraction (`FractionCache`: per regular node its own fraction and its complement's, interleaved, so that
    neither is ever derived as `1 - x`, which would round a small complement to 0; a fraction depends on
    neither the variable count nor the order; `FractionInCache` is the same pair per node and domain, both
    scaled by one binary exponent that keeps the larger in `[1, 2)`, so a domain far below `2^-1074` of all
    assignments neither underflows nor needs exact counts; `satisfyingFractionIn` divides by their sum, where
    the exponent cancels; `influences` sums, over the nodes of a variable, the probability that a random
    assignment reaches the node - computed top-down in reverse post-order with a per-call map, the caches
    being lossy - times the fraction where its children differ, from `DifferenceCache`: per pair of regular
    nodes, the smaller first, the fractions where they differ and where they agree, a complement on either side
    swapping the two, so every term stays a sum of non-negatives). Nothing extra.
  - **Ephemeral "current parameter"** — the MTBDD `compose` (`int[]` mapping) and `restrict` (a `Cube`),
    `exists` and `andExists` (a `NatSet` each), `apply`/`map`/`mapBoolean`/`applyBoolean` and the n-ary `apply` (an opaque
    operator compared by identity; the paired `*Simplify` cache is invalidated by the same `initX`; the
    n-ary one keys on the cloned operand tuple, as `cartesianProduct` does), `count` (a predicate),
    `canReachMatch` (a predicate), `allMatch` (a predicate, like `applyBoolean`). `initX(...)`
    compares against the previous call's parameter and invalidates wholesale on change. **This works only
    for eagerly-completing calls** — the lazy cursor from `assignmentCursor` outlives its own `initX` and
    must be drained before any other query runs; an assertion enforces it.
  - **Per-call scratch state** — `split`/`splitCombine`, `splitBdd`/`splitBddCombine` and
    `cartesianProduct` produce *indices into a bijection built fresh per call*, so an older entry names a
    numbering that no longer exists. `initSplit()`/`initSplitBdd()`/`initCartesianProduct()` therefore
    invalidate unconditionally at every entry. `splitBdd`'s residuals are BDD functions, interned on the
    BDD's secondary work stack, and its first cache is keyed on a BDD node (a `UnaryCache` of `BDD` to `MTBDD`). Within one
    call they are sound because interning is idempotent. Note `cartesianProduct`'s key is the whole
    operand tuple and its recursion rewrites that array in place — it must be cloned before descending,
    which is also what the cache stores.
  - **Implicit global state** — satisfaction/assignment counts range over `[decisionVariable,
    numberOfVariables)`, so creating a variable invalidates them (`variablesChanged()`) though
    `numberOfVariables()` appears in no key. Both caches are `VariableOrderObserver`s and hear it as
    `variablesInserted`; `MddImpl` has no order, so it calls `variablesChanged()` on its cache itself.

**An invalidation is cleared lazily, and usually sparsely.** `invalidate()` only marks the cache; its next use
clears it. Every write goes through `CacheBase.putBin`, which records the bin while fewer than size / 32 were
written since the last clear, so the clear resets just those (`sparse_clear_count`); past that, or after a growth
that rehashed entries, it fills the whole array. The ephemeral and per-call caches above need this: they keep
the size of their largest call, and most later calls write a handful of entries (a synthesis workload clears the MTBDD
`map` cache 1.3M times, ~16,000 bins for ~38 entries each; full fills were 3% of its run time). A write
bypassing `putBin` would survive a clear; `ensureValid` asserts the cache empty after every clear.

**Simplify-fused operations.** `andSimplify` (BDD) and `applySimplify`/`mapSimplify`/
`composeSimplify` (MTBDD) are *one* recursion with the plain operation as the `domain == TRUE` special
case, not a wrapper around `simplify(op(...))` — the domain is cofactored on the way down, so a branch the
domain excludes is never visited. Each carries a second cache keyed on the same arguments plus the domain,
invalidated together with the first. That domain-carrying cache straddles both tables, which is why the
*registered* simplify variants register their prune hook with the BDD as well as the MTBDD; a plain
registered `apply`/`compose` only needs the MTBDD's.

Two invariants easily lost: widening a domain (`domainVar < variable` → `or` of the cofactors) is always
sound because a *larger* domain constrains more; cofactoring the domain on a variable is sound only when
that variable means the same on both sides — for the MTBDD's `composeSimplify` only when it maps to itself
(`aligned`), since the domain speaks about *post*-substitution variables.

**The relational product is one recursion** (`BddImpl#andExists`, CUDD's `bddAndAbstract`): on the pair, by
level, as `computeAnd`; at a quantified level the result is the `or` of the two cofactor results, low first, and
the high one is skipped when the low one is true or equals a high cofactor (a result has no quantified variable, so
that cofactor has none either and bounds the high result) - low first like every other recursion here, which on
`jmhRelational` took a quarter to a third less time than CUDD's high first on the 16-bit relations and the same
within noise on the 32-bit ones; below the last quantified level it is `computeAnd`, on
the `and` cache; a constant or repeated operand leaves `existsRecursive`. That last exit is why `andExists` inits
both the `and_exists` cache (binary, ordered operands) and the plain `exists` one for its set, and why the
registered handle carries both. The conjunction is never built: on the preimages of `jmhRelational` (§13) the
fused form creates 2.3 to 4.7 times fewer nodes and takes 0.4 to 0.85 of the time of `and` then `exists` (the
random transitions gaining least).
`orForall` is its complement. `BddTheories.testAndExists` pins it, both operand orders, registered and dual, to
the composition and the syntax trees.

**The BDD compose is a joint descent** (`computeCompose`, also `composeSimplify` and both registered forms). Its
state is the function and the domain restricted to the path, with each replaced variable the function still
reads and that replacement restricted to the path. That tuple is the whole context, so results go to the
stable `ComposeTupleCache`, keyed `[F, D, v_1, ..., v_k, R_1, ..., R_k]` (the variables are part of the key: the
same replacement for another variable means something else). A replacement the path made constant is
substituted into `F` right away, so a decided disjunct or conjunct ends the descent. Otherwise the step splits
on `F`'s top variable. Left alone, it is a path literal: the domain is restricted by it, and so is each
replacement the child still reads (`restrictLiteral`, one prebuilt cube per literal, through `RestrictCubeCache`);
a branch outside the domain is skipped, its replacements never restricted, and the children are reassembled over
it - a node per level where they lie below. Replaced, it is an if-then-else over its replacement, taking one
branch directly where the domain decides it. Carrying the path is what keeps compose from building a branch for both values of a variable its replacements read and
discarding one half - exponential where the replaced variables sit below those
(`testComposeCarriesThePathToTheReplacements`); keying on content shares a state reached along paths that
restrict alike. Results agree with the composition wherever the domain holds.

## 7. Registered operations

The escape hatch from ephemeral invalidation: bind the parameter once, get a **private cache** that
survives alternation between operations. A plain call hands the diagram a freshly built translation on
every invocation; the diagram reads that as a different operator and drops the shared ephemeral cache, so
repeating one operation never reuses an entry.

Int layer: `registerCompose`/`registerComposeSimplify` on both `Bdd` and `MtBdd`, `Bdd#registerExists` and
`registerAndExists` (one `BddOperations.Exists`, unary the exists, binary the and-exists, each with its private
cache; an unused cache is never allocated, `CacheBase` sizing lazily), and `MtBdd#registerApply`/`registerApplySimplify`/`registerMap`/`registerMapSimplify`/`registerMapBoolean`/
`registerApplyBoolean`, returning `RegisteredOperation.Unary`/`Binary`/`Ternary`. The BDD compose forms hold
no cache: the composition's is keyed on its whole context (§6), so they only resolve and protect the mapping
once. Only the compose forms bind *nodes*, and those own them: `ProtectedOperation` + `ProtectionTracker` reference the operands on
construction and drop them via a `PhantomReference` when released or unreachable. The rest bind a lambda or
a `NatSet` and hold nothing. Their private caches grow on usage (`growOnUsage`) rather than tracking table
size, and each registers its prune hook with *every* table its entries can name — the two boolean-valued
MTBDD operations always straddle both, since their results are BDD functions.

Object layer: handle types bound once and applied repeatedly — `BddMap.Operator<V>` (plus `applyIn`),
`BddMap.VariableReplacer` (plus `replaceIn`), `BddMap.Mapper<V, O>`, `BddMap.Combiner<V, W, O>`,
`BddMap.Selector<V>`, `BddMap.Relation<V>`, `BddSet.Quantifier`, `BddSet.RelationalProduct`, `BddSet.VariableReplacer`.

- **Each extends `RegisteredOperation`**, which is where `release()` and the one statement of what
  binding means both live. The root declares no abstract method, so a handle that also extends a
  `java.util.function` type (`BinaryOperator`, `UnaryOperator`, `Function`, `BiFunction`) stays a valid
  `@FunctionalInterface` — keep it that way when adding one. The implementations get `release()` by
  extending `RegisteredOperations.Forwarding<V>`, which holds the int-layer operation as `operation` and
  forwards to it; `RegisteredApply` is the one that does not, because it also pins
  constants. `BddMap.Relabeler` deliberately does not:
  it is created by `createRelabeling`, not registered, and holds a destination numbering that outlives
  it, so a `release()` on it would mean something else.
- `BddMap.VariableReplacer` is the exception to the `java.util.function` part: replacing variables never
  looks at a terminal, so one instance serves maps over *any* numbering and each result stays over its
  operand's — a generic method, which no lambda can implement, hence its explicit
  `@SuppressWarnings("PMD.ImplicitFunctionalInterface")`.
- **A registration that turns out to be a no-op is a shared singleton.** `registerCompose` over a mapping
  that replaces nothing, and `registerExists` over no variable, return exactly
  `RegisteredOperation.identity()` (`registerAndExists` over no variable is a plain `and`, not a no-op);
  `BddSetFactory`'s two variable replacements recognise that and return
  `BddSet.VariableReplacer.identity()` rather than wrapping a call that would do nothing. The point is the
  reference: a caller can test for it and skip the operation entirely, which no freshly built lambda
  allows. Keep it a singleton (both are enums) when adding a case.
- **All of them are backed** by an int-layer registered operation. `BddSetFactory`'s two variable
  replacements take the set of variables they replace, because a handle is built before it sees a set:
  the resolved substitution is absolute, so naming the variables is all it takes to resolve it once
  instead of per call.
- **Constants are pinned at registration, domains are not.** `RegisteredApply` resolves `neutral` and
  `absorbing` to raw terminals once and holds each as a `BddMap` constant field for the handle's
  lifetime, keeping that leaf referenced so its index cannot be recycled into another value. A
  simplification domain stays a per-call argument on `applyIn`/`replaceIn`: the recursion narrows it while
  descending, so it is part of every cache key below the root whatever the outermost domain was, and
  pinning it would restrict what the handle accepts without making a single key smaller.

## 8. Enumeration: cursors, not iterators

There is no `Iterator` anywhere in the API. `Cursor<E>` — `valid()` / `current()` / `advance()` — is the
only shape. It is positioned on construction (no step before the first element; an empty enumeration
starts invalid), and **`current()` is the walk's own working state**: defined exactly while `valid()`
holds, and only until the next `advance()`. Callers must copy. Some cursors consult diagram-internal state
and must be drained before any other operation on the diagram.

The reason is `hasNext()`: answering it needs the walk a step ahead of the caller, and answering it
*without disturbing the last result* forces every element through a copy. That copy was the dominant cost
of solution enumeration — removing it roughly halves ns/solution. Do not reintroduce an `Iterator` wrapper
without measuring. `forEachRemaining` is the one borrowing from `Iterator`: it needs no lookahead, so it
is free.

Each engine has one **walk** plus thin cursors over it:

```
BddImpl    PathWalk(function, domain) → SolutionCursor, PathCursor
MddImpl    PathWalk(function, domain) → SolutionCursor, PathCursor
MtBddImpl  PathWalk(function, values) → AssignmentCursor, PathCursor   (both ValuedCursor)
```

`PathWalk` is *not* a `Cursor` — it hands nothing out, it only moves; the cursors differ only in what they
make of its state. Each is a `final` class in a field of its own type, so nothing in the walk dispatches
virtually. **Do not unify the three behind a shared interface:** the differences (branching arity,
complement edges, what counts as a dead branch) sit in the innermost loop and would make those call sites
megamorphic. Near-duplicate walks are the right trade here.

**A solution enumeration is a path enumeration plus a counter.** A path fixes only the variables the walk
decides on; everything else in the support is free, and every combination extends that path to a solution.
The free set is computed once per path (`NatSets.difference`) and counted off with `NatSets.increment`.
`forEachSolutionInRecursive` applies the same idea: when neither side branches at a level it records the
variable and descends *once*, and the leaf runs the accumulated free variables off as a counter — without
that, k free variables cost 2^k identical descents.

`BddImpl.PathWalk` carries a **pair** (function, domain), which is what makes `solutionCursorIn` a real
operation rather than `solutionCursor(and(f, d))`. One property does not survive: within a single diagram
any non-`FALSE` node has a path to `TRUE`, so a descent avoiding `FALSE` always arrives — but two
non-`FALSE` nodes can be jointly unsatisfiable, so the descent can **dead end** and `advance()` must
retract and carry on, including on the very first descent. With the domain at `TRUE` that never happens;
carrying it costs ~5 ns/path, amortizing to nothing on solutions.

Order is lexicographic ascending **by level**: the variables the walk decides on vary slowest, the free
ones are counted underneath, the lowest level fastest. `forEachSolution` and the cursors agree on it: the BDD's
recursion counts the free variables the same way, and the MDD's `forEachSolution` is the interface's default
over its cursor (MDDs see little use, and the cursors are on par with a recursion).

Everything above is by level; `current()` is by variable. On a diagram that has never reordered the two
coincide and the cursor hands out the walk's own sets directly; after a reorder it hands out a
variable-indexed buffer, distinguished by a `@Nullable translated` field (no buffer = no translation).
**That buffer is maintained incrementally, never rebuilt.** A step changes a handful of bits while the
sets hold the whole path, so every writer mirrors its own flips: `PathWalk` routes all four of its writes
through `assign`/`pushSupport`/`popSupport`, and `SolutionCursor.increment` does the same for the free
levels. The walk snapshots `levelToVariable` at construction, so a mirrored write is one array load and
cannot be invalidated by a variable creation resizing the context's array. Measured once against rebuilding per
step (a benchmark variant since removed): solutions 4.3x with many free variables, 1.65x with none, paths 1.8x–2.6x;
on the identity order the mirroring is a null check that never fires. Two rules keep it correct: every
write to either level set goes through one of the four writers, and `currentIsConsistent()` rebuilds and
compares under `-ea`, so a writer that forgot to mirror fails at the cursor, not downstream.

Rough costs per element (18 variables, ~50k solutions, identity order) against the native recursions:
solutions ≈ 6 ns vs 3 ns; solutions in a domain ≈ 6 ns vs 5 ns; paths ≈ 40 ns vs 17 ns. The path gap is
the explicit stack against the JVM's call stack and is close to irreducible in this shape — caching high
edges in the stack and a positive-node + `lookingFor` representation were both measured; neither helped.

## 9. Reordering

Variables keep their numbers; only their **level** moves. `DdVariableOrderImpl` owns the bijection
(`variableToLevel`/`levelToVariable`) and both diagrams read it, so a shared variable universe cannot
drift apart.

`explicitOrder` (asked through `isExplicitOrder()`) is a fast-path switch *and* the arrays' liveness
flag: while false the order is the identity and is **not stored at all** — both arrays are
`EMPTY_INT_ARRAY`, `levelOfVariable`/`variableAtLevel` answer `v`, and
a workload that never reorders never allocates them. `makeOrderExplicit()` writes out the identity ahead
of the only two things that bend it (`siftDown`, and `createVariableAtLevel` anywhere but the bottom); it
leaves `explicitOrder` set although the order is at that instant still the identity, which is sound only
because both callers make it not be within the same critical section. `makeImplicitIfIdentity()` is the other
direction. That flag is worth more than an array load — it also decides whether enumeration writes
straight into a variable-indexed set or translates through a buffer (§8). It is O(variables), so it is
asked only after `reorder`, `reorderTo` and `reorderToIdentity`, never per swap;
`reorder_identity_reverts` counts how often it fires.

`swapWithNextLevel(level)` is the primitive: exchange the variables at `level` and `level + 1`, rewriting
exactly the nodes that must change, **in place**. Node ids survive, so every `int` a caller holds keeps
denoting the same function. `siftDown(level)` is that one swap as a complete reordering — the bracket plus
the primitive — and it sits on `DdVariableOrder`, not the diagrams, because it moves both; it is the only
*operational* method in the reordering surface.

Everything that changes the order lives on `DdVariableOrder`: `siftDown`, the four reorder forms,
`dropReorderStructures`, and `numberOfVariables` so that `variableAtLevel` has a bound without another
type. `bdd.variableOrder() == mtBdd.variableOrder()` is also the way to ask whether two diagrams share a
variable universe. `reorder()` returns nodes saved **across every diagram over the order**, which is why
it belongs there and not on a `Bdd` that would appear to be answering for itself.

`rewriteLevelAfterSwap` (one per diagram, same recursion, `MtBddImpl`'s without complement edges) decides
which nodes change and `rewriteHideAndUnlink`s all of them out of the unique table **before building anything**:
until a node is rewritten it still carries the old variable, so a `makeNode` below could hand it out as a
fresh child and it would then be rewritten out from under that parent. The nodes that do *not* change stay
in the table on purpose — they are genuine nodes of the variable moving down, and a new child matching one
of them must find it rather than duplicate it.

- `reorder()` — sifting: heaviest variable first, each swept to its best position within its group,
  abandoning a direction once it has grown past `MAXIMUM_SIFT_GROWTH`. Candidate positions are measured by
  exact live node count, which `parentCount` (live-parent counts, maintained with cascade and
  resurrection) gives for free.
- `reorder(List<NatSet> groups)` — restrict movement to within already-contiguous blocks; a variable in no
  group stays put.
- `reorderTo(List<NatSet> blocks)` — *put the order into this shape*: block `i` entirely above block
  `i + 1`, each block a contiguous run. That is the whole specification — a variable in no block is a
  don't-care and is **left alone**. It aims at nothing (the result may be bigger), and its postcondition is
  exactly `reorder(blocks)`'s precondition, so "shape it, then optimise within the shape" is two calls
  with the same argument. Realized as one sort key per variable, selected from the top: a don't-care at
  level `l` keys on `2l + 1` (keeping relative order and rough position); every block member keys on `2s`,
  `s` being the median of the levels its members would be pulled to, pushed past the block before it. The
  even key makes a block one contiguous run sorting ahead of a don't-care wanting the same spot. **The
  median is what makes this an edit rather than a replacement**: for an already-consecutive block it
  returns where that block already is, so a satisfied request swaps nothing. Heuristic, not
  minimum-inversion, but it never moves a variable the request does not force.
- `reorderToIdentity()` — every variable back at its own number; lands on the implicit order by
  construction (asserted).
- `createVariableAtLevel` is cheap by construction: no existing node mentions a variable that did not
  exist, so neither node table is touched.

**`siftDown` itself collects**, past `MAXIMUM_SIFT_GARBAGE = 0.40`, before it opens the rewrite bracket.
It has to be there rather than in the sifting loop: `siftDown` is what `reorderTo` and any caller-written
policy drive directly, and neither gets other cleanup; inside the bracket the table grows instead of
collecting, so orphans accumulate and a table grown large enough never falls back under
`ensureCapacity`'s 25%-free trigger — the garbage then stays for good and every later swap walks all of
it. Collecting at *every* swap costs more than it saves, hence the threshold. Swept over the reordering
benchmarks it is a shallow basin flat from 0.30 to 0.45, ~8% worse at 0.25, degrading above 0.45 where the
garbage a swap walks outweighs the collections saved and the table starts doubling (0.60 doubles it; with
no bound the run dies of memory). 0.40 sits mid-basin.

**A table that cannot grow stops sifting short of a swap it could not hold.** Inside the rewrite bracket a table
grows instead of collecting, and one the heap no longer lets grow (`NodeTable.isMemoryLimited()`, set by the first
growth that found no room) runs full: the swaps' garbage fills it and `ensureCapacity` throws `OutOfMemoryError`
from inside `rewriteLevelAfterSwap`, with both diagrams half rewritten (seen on a synthesis workload, 5 GB heap,
an explicit `reorder()`). So `sift` asks `swapFits` before each exploring swap while either table is memory
limited: a swap creates at most two nodes per node of the lower variable, so twice that count free is enough;
otherwise both diagrams collect first (the swaps' garbage, which no `ensureCapacity` inside the bracket reclaims)
and, if that does not make room, the direction is given up (`reorder_memory_stops`). The swaps back to the best
position make room the same way but are never given up (`swapWithRoom`): they recreate the nodes the exploration
orphaned, which a collection in between has reclaimed. `reorderTo` and a caller's own `siftDown` do what they are
told and throw. A caller's `gc()` deliberately leaves the memory-limited regime in place (only a growth, or a
collection of `ensureCapacity`'s own leaving a quarter free, ends it), so the checks last through a reordering.
While both tables can grow nothing is asked, so the tuned constants above are untouched. The scenario is
reproducible only against a nearly full heap (a table 65% live, ballast blocking its doubling), which the suite
does not set up; it was verified by hand: `reorder()` completes with 14 stops where unchecked swaps ran the table
full after ten.

Bookkeeping (per-variable node lists, parent counts) is built lazily on the first reorder and dropped
afterwards unless `keepReorderingStructures()` is set. Set it only when reordering is frequent enough that
rebuilding dominates; it costs one int per node slot plus one per valid node and puts work on every node
created. Neither a collection nor a growth drops it, so within one reordering pass it is derived once.

**An order change says what moved, and every listener decides for itself.** There is no blanket
invalidation: `VariableOrderObserver` carries `orderChanged(previousVariableToLevel,
currentVariableToLevel, movedVariables)` and `variablesInserted(level, count)`, and the two permit very
different things.

**These are the order's listeners, not a diagram's**, which is the whole reason the parameter lists carry
no `origin`: `DdVariableOrderImpl` holds the `ObserverGroup` and dispatches once, so a listener over both
diagrams — a registered simplifying compose is registered with both tables for GC pruning — hears a move
exactly once instead of twice and needs no test for which diagram it is hearing about. Both caches
implement it and are registered with the order directly. Table events (`NodeTableObserver`: `beforeGc`,
`afterGc`, `afterTableGrowth`) still fire per table and still say which, because an entry can straddle
two of them (§6) — and `MtBddCache` responds to each differently, which is why those stay small adapters
in the diagram rather than a branch on `origin` inside the cache.

- An **order change** changes relative order, so anything folding a level into a value is stale. Every
  cache listening to the order drops everything - both operation caches and the registered `Exists` and
  MTBDD `Compose`. Strictly, only the satisfaction counts (ranging over the levels below their node) and
  `constrain` (deciding top-down) are wrong afterwards: reordering rewrites in place, so an entry that is a
  statement about functions - `exists`, `compose`, `restrict` and the simplify family included, whose level
  cut-offs only decide where the recursion stops - still holds. Keeping those is not worth the
  classification: invalidation is lazy, so dropping costs one clear per cache on next use; `reorder()`
  collects before sifting, which clears everything anyway (below); and the kept entries are hardly ever
  asked again, since the swaps reshape the diagram (the `and` hit count over swaps-then-replay was the same
  to within one hit). `RegressionTests` pins the counts and `constrain`.

  **During a reordering, caches are cleared rather than maintained** (`isReordering`): a collection or a
  growth then invalidates every cache over the tables, registered ones included, before its "nothing died"
  check. The swaps orphan the old nodes - a rewritten node gets new children - so a collection in between
  reclaims most of what the caches name: one sifting pass pruned the whole `and` cache away (33,083 of
  33,083 bins on a 17-bit comparator, 2,068 of 2,069 on queens-9). One clear beats pruning repeatedly, and
  clearing on a growth spares rehashing entries into a grown cache only to lose them.

  **It fires once per reordering, not once per swap.** `siftDown` is the public one-swap form and brackets
  itself like any other entry point; the primitive underneath (`swapWithNextLevel`) is silent, so a sifting
  pass makes thousands of swaps and reports one change. Deferring is sound because nothing reads an
  operation cache or a stored level while the order is moving — a swap only rewrites nodes, and reordering
  may not run while an operation is in flight — so the only requirement is that every listener hears
  before the next operation does. `beginReordering`/`endReordering` are the bracket, `reorderingFrom` the
  snapshot they compare against, and a change that moved nothing is not reported at all.
  `reorder_notifications` against `reorder_swaps` is what the batching is worth.
- An **insertion** preserves relative order, which is stronger than it looks: every level *comparison*
  answers as before, since both sides shift by the same rule (`l < L ? l : l + count`). So nothing goes
  stale by the *order* having changed. What moves is a *stored* level, and there is
  exactly one: `MtBddOperations.Compose` holds a `maxReplacedLevel`, shifted by
  `count` if at or below the insertion point. On an order change it rescans for it and drops its own
  caches. `BddOperations.Exists` rebuilds its by-level quantified set on an order change only when
  `movedVariables` holds one of the quantified variables, asserting against the full rebuild either way.
  A cut-off that is a level stops being a level once relative order changes. The block form of the
  insertion reports `count` once rather than firing the hook `count` times — the repeated form composes to
  the same shift, but only while every listener does nothing else with the level.

  **Every variable creation fires it**, appends included: appending at the bottom is an insertion at the
  level the first new variable lands on, with nothing below it to push. That is what lets one event carry
  both halves of what a creation means — where the levels moved, and that the count changed — so the
  caches hear it as a listener like everything else rather than being poked separately.

**Three shapes that must translate**, and where:

- **Node construction.** `makeFunction(level, low, high)` resolves the level through `levelToVariable`;
  `makeFunctionForVariable(variable, …)` (`MtBddImpl`) is the form when the variable is already in hand.
  Handing a variable to the level-taking one builds a node for a different variable entirely.
  `DdVariableOrderImpl.appendVariables`/`insertVariables` write the new variable into `levelToVariable` before
  calling `makeFunction`, which reads it straight back — but only while the order is explicit; a new
  variable goes to the bottom, so it is its own level and the implicit order already says so.
- **Caller-supplied data inside a recursion.** `compose`'s replacement array, `restrict`'s cube and `split`'s
  `NatSet` are indexed by *variable*, while the descent and its cut-off are by *level*. Each recursion
  takes `decisionVariable(node)` for the lookup and `levelOfVariable(variable)` for the ordering, and
  its cut-off is `maxLevel(...)` — never `NatSet.length() - 1`, which is a variable bound.
- **A mapping shorter than the variable count.** It leaves the rest unchanged, and under a non-identity
  order one of those can sit *above* the greatest replaced level, so the recursion reaches it. Both
  compose implementations bounds-check before indexing rather than relying on the cut-off.

Statistics (`DdVariableOrderImpl.report`, folded into the BDD's contribution to the context's
map and prefixed with `configuration().name()`, which is empty by default): `reorder_saved_nodes` against `reorder_swaps` /
`reorder_rewritten_nodes` is the benefit-vs-cost pair, summarised as `reorder_work_per_saved_node`.
`reorder_collections` says whether `MAXIMUM_SIFT_GARBAGE` is set sensibly,
`reorder_abandoned_directions` whether `MAXIMUM_SIFT_GROWTH` is, `reorder_memory_stops` whether the heap rather
than either decided how far sifting went, and `reorder_notifications` against
`reorder_swaps` what batching the order-change event is worth. `reorder_identity_reverts` outside a
deliberate `reorderToIdentity()`
signals the fast path is worth more than it looks. Each table additionally reports the share of its own
counters that fell inside a reordering bracket - `node_table_reorder_created_nodes`, `_reorder_gc_count`,
`_reorder_gc_collected_nodes`, `_reorder_gc_time_milliseconds` - as differences between snapshots taken
in `beginReordering`/`endReordering`, so the operations' cost is the total minus that and nothing on the
hot path pays for the split.

Reordering is explicit and must not run while an operation is in flight or from inside a callback.
Saturated nodes stay saturated: 14 bits is the refcount, and pinning is by design.

## 10. Value numbering: `Values<V>` and `BddMap<V>`

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
  `(function, values)` (the numbering's key space in the high half of the key, §3), so `equals` is `==` and
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

## 11. Language, style and conventions

- **Java 11** source, target and `--release`. No records, no `var`, no pattern-matching `instanceof`, no
  text blocks, no `Stream.toList()`. Existing code uses none of these — keep it that way.
- **Zero runtime dependencies.** `jspecify`, `error_prone_annotations`, `immutables` are `compileOnly`;
  Guava/Hamcrest/JUnit are test-only. Never add a runtime dependency.
- `@NullMarked` on the package (`package-info.java`), NullAway in jspecify mode; mark nullables with
  `org.jspecify.annotations.@Nullable`. NullAway runs with `assertsEnabled`, so `assert x != null;`
  narrows for it — the intended way to tell it about a nullable field a branch has established. It does
  not infer implications, so the assertion must sit *inside* the branch using the value, and a
  disjunctive precondition at the top of the method (`universeDomain || applySimplifyCache != null`) does
  not narrow anything; branching on the field would trade a warning for worse code. The `*Simplify`
  caches of `BddImpl`/`MtBddImpl` are where this bites, and the source of the NullAway warnings the
  build currently reports.
- Suppressions are narrow and carry a reason: `@SuppressWarnings("NullAway.Init")` per field on JMH
  `@State` fields, `// NOPMD - <reason>` on deliberate reference comparisons (factory and numbering
  identity is *the* check; `equals` would be wrong) and on `System.out` in benchmark mains.
  `@SuppressWarnings("AssertWithSideEffects")` sits on the classes whose public methods bracket with
  `assert accessGuard.acquire()`. `PMD.AvoidReassigningParameters` sits on the three implementations, which
  canonicalize an operand order in place before a cache lookup; `PMD.CouplingBetweenObjects` on `BooleanCache`,
  `MtBddCache` and `MtBddImpl`, where every cache shape (and both diagrams) meet by design. `./gradlew build`
  runs PMD over all three source sets and fails on a violation, so a suppression is the way to keep one.
- **Assertions carry real work.** `assert accessGuard.acquire(); … assert accessGuard.release();` and
  `assert isValidFunction(f)` are the standard preamble of a public method. Tests run with `-ea` and rely
  on it. An assertion auditing a *whole* structure where the operation touches one part of it - a table
  `check()`, `isNoneMarked()`, a full cache scan - is written `assert !Assertions.COSTLY_ASSERTIONS || …`:
  it runs only with the system property `JBDD_COSTLY_ASSERTIONS`, which JBDD's own test tasks set. Without it
  a user's `-ea` run would be quadratic (a client's test went from 2 s to 700 s); with `-ea`
  alone, a cache still checks every entry it hands out, and audits itself fully after each prune. Keep validation in assertions, not in runtime checks, on hot paths — with `-ea` off, invalid
  arguments corrupt the structure quietly rather than throwing.
- **An exception inside an operation, including one a callback throws, is fatal.** Nothing is written to
  recover from one: no `try`/`finally` releasing references on the way out. `finally` is only for
  `Reference.reachabilityFence`, which keeps a wrapper alive, not the diagram consistent.
- Per-call memos keyed by functions, nodes or indices are `collections.IntIntHashMap` / `IntObjectHashMap` (public, for
  users too), never a boxing `Map<Integer, ...>`.
- **Internally everything is by level; everything crossing the API boundary is by variable.** Name locals
  `...Level` / `...Variable` and translate at exactly one point per class.
- **Name the ends of the level axis `min`/`max`, never `low`/`high` or `shallow`/`deep`.** A level is an
  `int` and recursion counts upward, so `minLevel` is the first place something must happen and `maxLevel`
  the last (`maxReplacedLevel`, `maxRestrictedLevel`, `maxSplitLevel`, `maxLevelOf`). `low`/`high` are the
  two *children* and must not also name a direction in the order. `shallow`/`deep` stay available for
  prose about a structure as a whole, not for naming a bound.
- **Plural of leaf is `leaves`.** `terminal` remains the MTBDD's own word for a valued leaf — it is in the
  name of the structure — and the two are not interchangeable elsewhere.
- **Not thread-safe.** `ConcurrentAccessGuard` only *detects* (under assertions) and logs.

### Comment and documentation style

Comments here are few and load-bearing. Both halves of that matter: do not add prose, and do not remove
the prose that is carrying something.

- **Javadoc describes semantics, and lives on the interface.** What the method *means*, what the caller
  owes, what it may *not* assume — not how it is achieved. It is where the denotational types earn their
  keep, so the implementation usually needs no javadoc at all. Say explicitly when something is a
  heuristic or not a stability promise, since that is semantics too.
- **Condensed is correct.** One line is the normal length. Do not restate the signature, do not expand a
  clear one-liner into paragraphs, and do not restate the cross-cutting contracts (callback purity,
  reference ownership, cursor validity) that are stated once elsewhere — document only a *deviation*.
- **No implementation detail in javadoc.** No caches, no tables, no allocation. The exception is where the
  mechanism *is* the semantics the caller buys: a registered operation says that it reserves internal
  bookkeeping for that operation so repeating it pays off, and stops there — a caller does not need to
  know a cache is allocated, only that binding once is worth something.
- **Non-public code gets no explanatory comments.** Assume a reader fluent in Java and in BDDs; do not
  narrate what the code says. Two things are worth a comment: a **why** that the code cannot show (why
  this design over the obvious alternative, what a tuned constant was tuned against —
  `MAXIMUM_SIFT_GARBAGE` records the benchmark sweep that picked 0.40), and a **meaning** that is deep and
  intertwined with the rest of the structure. `NodeTable`'s field comments are the model for the second:
  each states an invariant relating that field to others, which is not recoverable by reading any single
  method.
- A statistic is a `Statistic` constant next to its counter (§3), and its sentence says what question the number
  answers.

### Performance is not standard-Java performance here

The hot paths are tiny static helpers over flat `int[]`s, called from deep recursions, and the usual
intuitions transfer badly. Two habits follow:

- **Measure; do not reason.** Several plausible optimizations in this codebase measured *slower* and are
  documented as such (appending during the sweep instead of filtering after, keeping cache entries across
  a swap, caching high edges in the path stack), and others measured nothing: writing `makeNode`'s chain
  walk out instead of handing `findNode` a capturing predicate (the lambda shows up as frames of its own in
  a profile, 3% of queens), and `ONE.shiftLeft(k)` for `TWO.pow(k)` in the satisfaction counts - both within
  the 3% noise of the synthetic, DIMACS and random workloads. Anything performance-motivated is backed by `src/jmh`.
- **Watch what a change does to inlining, not just to instruction count.** The JIT's inlining budget is in
  *bytecode size*, and that size includes code the JVM may never execute — an assertion counts, so adding
  a message to an `assert` in a tiny method can push it over `MaxInlineSize` (35 bytes, below which it is
  inlined wherever it is called) and make every caller pay a call. Hence an assertion in a method that
  small - an accessor, a cursor's `current()`, a one-line utility - carries no message; say it in a
  comment instead. Larger methods keep their messages: they are inlined only when hot, against a budget
  ten times larger, and the message is what makes a failure diagnosable. Independently of size, no
  assertion another one already implies: a cache hit asserts `isValid(binStart)`, which covers the
  result, not the result again - the redundant pair measured ~3% on an `and`-bound workload with
  assertions *off*. That is one instance, not the rule: field
  layout, megamorphic call sites (§8), allocation on a per-element path (§14.5) and escape analysis all
  bite the same way. Treat any "obviously harmless" edit to a small, hot, frequently called method as a
  measurable change.

## 12. Tests

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
  BDD, MDD and MTBDD. All three are driven as `BinaryDd` (§3), which `BddImpl` is natively, so only the
  other two need an adapter; one that can reorder implements `ReorderableDd` on top (`MddAsBinaryDd` does
  not — MDDs do not reorder, and `byLevel` keys on exactly that).
- **`FailFastExtension`** skips the rest of a class once one test fails (the shared structure is then
  suspect).
- **`TestProfile`** scales how much data those two suites generate (`jbdd.test.scale`, §1). Only the
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
- **New operations need:** theory coverage against the reference evaluation, an invariant check, and — if
  it touches ordering — a reordered variant.

## 13. Benchmarks

`src/jmh/java/...`, JMH, DIMACS instances in `src/jmh/resources`. They exist to catch performance regressions, so
each is a workload a client runs, not a comparison between implementation options: a benchmark written to decide
an option is run, its result recorded where the decision is documented, and removed. Performance claims in
comments and the changelog are expected to be backed by these; **measure before tuning a constant.**

| task | workload |
|---|---|
| `jmhRandom` | `RandomBenchmark`: 18,000 random operations over a growing pool of functions |
| `jmhSynthetic` | `SyntheticBenchmark` (11 queens, a 1024-bit adder), `SyntheticSetBenchmark` (queens over `BddSet`) |
| `jmhDimacs` | `DimacsBenchmark`: CNF instances conjoined clause by clause |
| `jmhEnumeration` | `EnumerationBenchmark`: solution and path cursors over `EnumerationState`'s shapes (many free variables, some, none, long paths), on the identity order and on one every cursor translates |
| `jmhNary` | `NaryBenchmark`: `or(int[])` over guarded cubes, `and(int[])` over clauses, cold |
| `jmhMtBdd` | `MtBddBenchmark`: the object layer's maps as a synthesis tool uses them - edge trees united under a registered operator, mapped, split on the inputs, paired, inverted, filtered |
| `jmhReorder` | `ReorderBenchmark`: sifting, sifting in groups and `reorderTo` over 8 queens and a 24-bit adder, with and without the kept bookkeeping |
| `jmhRelational` | `RelationalProductBenchmark`: preimages `exists next. R & S`, cold, over a counter, a twisted shift register and a union of random transitions (current and next state interleaved, 16 and 32 bits), sixteen targets each |
| `jmhNatSet` | `NatSetBenchmark`: the set operations a synthesis tool spends its time in, per shape |
| `jmhCube` | `CubeBenchmark`: `contains`, `implies` and `intersects` over cubes drawn in `NatSetBenchmark`'s shapes |

`NatSetBenchmark` sits in `collections` and times each operation over a pool of 1024 random sets of one shape
(singletons, tiny sets, 2 to 32 elements below 64, sparse ones below 4096), so a time is per set, over sets
that differ. The representation (`NatSetUtil.MAX_ARRAY_SIZE`, `WORD_COST`) is fixed; the layouts it was chosen
against were measured once and are recorded in §3.

## 14. Where the sharp edges are

Ranked by how much time they cost when you get them wrong:

1. **Cache invalidation on GC.** Silent wrong answers, arbitrarily far from the cause.
2. **Work-stack discipline.** Push too late, pop too early, or miss an early-return path and you get a
   use-after-free manifesting only when GC happens to fire mid-recursion.
3. **Cross-table reasoning.** Which table can GC here? Which operands live on which side? Every
   `MtBddImpl` method touching `bdd` needs this answered explicitly.
4. **Ephemeral parameters escaping their call** (lazy cursors, retained scratch arrays).
5. **Scratch-array aliasing.** `apply`'s `values[]` and the `DepthPool<int[]>` buffers are mutated in
   place across the whole recursion; anything that must remember a leaf's values (`cartesianProduct`) has
   to clone. Same contract on the way out: a `Cursor` hands back its own working state, which is also why
   `ValuedCursor` exposes a separate `value()` accessor rather than a pair object — a step then allocates
   nothing. `DepthPool` is valid only because these recursions are strictly depth-first and sequential —
   not under any future parallelization.
6. **Level vs variable.** Invisible to every test that does not reorder, which is why the theory suites
   run reordered and never-reordered variants side by side.
7. **Assertions are the validation layer.** An over-eager or inverted assertion is itself a bug and will
   not be caught by a production run. Corollary: an untested method is not merely unverified — its
   assertions have never been evaluated at all.
