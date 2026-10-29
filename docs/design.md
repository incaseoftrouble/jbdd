# JBDD's design: theory, architecture, collections, performance habits

The design and the why; the rules a session must hold are `CLAUDE.md` §3.

## BDD theory, as this library realizes it

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
(`docs/reordering.md`).

**Variants.** An **MDD** generalizes the domain (variables take one of *n* values, nodes have *n*
children); an **MTBDD** (ADD) generalizes the codomain (leaves carry values, not just `true`/`false`).
`Bdd` is the intersection of both generalizations. An MTBDD has no `and`/`or`/`xor`; it has
`apply`/`map` with a caller-supplied combining function instead, which is why it cannot reuse the boolean
engine.

## Architecture

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
  `NodeBasedDd` also carries the introspection every implementation can answer: `check()` (`docs/memory.md`),
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
  what the test adapters implement so BDD, MDD and MTBDD can be driven through one type (`docs/tests.md`). The split is
  visible on `bdd()`: the denotational `MultiTerminalDecisionDiagram.bdd()` promises only a
  `BinaryDecisionDiagram`, and `MtBdd` narrows it covariantly to `Bdd`, so only the facade hands out the
  implementation-side surface.
- `BooleanTerminalDecisionDiagram<S, P>` is the whole boolean-valued logical API over assignment type `S`
  and path type `P` (`NatSet`/`Cube` for BDDs, `int[]`/`int[]` for MDDs): `and`, `andNot`, `exists`,
  `forall`, `ifThenElse`, `simplify`, solution and path cursors, the `xyIn` / `xySimplify` variants.
  `constrain`, the generalized cofactor, is `BinaryDecisionDiagram`'s and `MultiTerminalDecisionDiagram`'s only:
  it needs a "nearest" domain-satisfying value, which an n-valued variable does not have, so `MddImpl` offers
  `simplify` alone. So is the relational product `andExists` (with its dual `orForall` and `registerAndExists`):
  the interface's defaults build the conjunction, `BddImpl` fuses (`docs/operations.md`).

Implementations (public, like most of the package - visibility here is deliberately open, so a client can build
on `GcReferenceManager` or `NodeTable`; still, construct the diagrams only through the factories):

- `BooleanBase<S, P>` — shared engine for `BddImpl` and `MddImpl`.
- `MtBddImpl` — **not** a `BooleanBase`. It *composes* a `BddImpl` (its variable universe) and owns its
  own `NodeTable.Binary`: two disjoint DAGs, one variable numbering, two independent GCs.
- `NodeTable` — the manual-memory heart: flat primitive arrays, hash-consed unique table, packed metadata
  `<VAR:17><REF:14><MARK:1>` (asserted to fill an `int`), free list, mark-and-sweep GC, growth, optional
  reordering bookkeeping. `NodeTable.Binary` (parallel `low[]`/`high[]`) and `NodeTable.Multi` (jagged
  `int[][]`); each diagram supplies a nested `Table` filling in the abstract hooks. The unique tables' hash is
  a sum on purpose (children's indices plus 31 times the variable; why, in `Binary.hash`): a mixing hash loses the
  locality of a node's bucket near its children, and the variable's factor 1 put chains built bottom-up into one
  bucket. A factor that a regular structure allocates per variable collides the same way (3, `binaryAdder`).
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
  factories), so a queued wrapper's nodes are not counted live; before a collection the table is also swept for
  wrappers the JVM cleared but its reference handler has not queued yet (`releaseCleared`), so a table collection
  right after `System.gc()` counts exactly the reachable wrappers live - a reordering after one does not depend on
  the JVM's timing (SemML's GR(1) solver sifts that way; without, its circuits differed run to run); `BinaryFactoryContext.statistics()` reports
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
  counterpart: `implicants`, `primeImplicants`, `irredundantCover`, `shortestPath`, and the generic `adopt`.
- `BddSet` deliberately exposes nothing assuming a fixed variable universe — callers always name the
  support they mean.
- `impliedLiterals` / `implyingLiterals` are native in `BddImpl`: one pass with a per-call memo per regular node
  holding both kinds (a literal of another variable is implied by a node iff by both children, implies it iff it
  implies both; the node's own variable from a false or true child), a complement swapping the kinds and negating
  the literals. They hand out a `Cube` read as a consistent set of literals, empty for false (it implies every
  literal) and true (every literal implies it); the test adapters take cofactors.
- `unateness` is native in `BddImpl` too: one visit of every (node, complement) the function reaches, a node of `v`
  whose low child does not imply its high one ruling out positive in `v` (the converse negative, swapped through a
  complement), by `intersectsRecursive` with the complement, which builds nothing. That suffices because the paths
  above a node split the assignments into disjoint parts, each reaching one node of `v` or none.
  `unateness(function, variables)` checks only the nodes of `variables` and descends no deeper than the deepest of
  them, which spares the rest of the diagram but for the implication checks (SemML asks it for the propositions of
  an LTL class, which lie above its temporal operators); the interface's default filters the full one.
- `Cube` is the one type for a conjunction of literals - equivalently a partial assignment:
  path walks, the implied and implying literals and `BddUtil`'s `implicants` / `primeImplicants` / `irredundantCover` / `shortestPath`
  hand them out, `restrict` and
  `BddSetFactory.of` take them, `of(Cube)` builds one's function. Its operations return new cubes; a walk's cube
  is working state (`docs/enumeration.md`). `of` copies what it is given and `ofUnsafe` takes the sets as they are (checking the
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
- `Cursor` (the enumeration shape of `docs/enumeration.md`), `IntIntHashMap` / `IntObjectHashMap`.

## Performance is not standard-Java performance here

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
  layout, megamorphic call sites (`docs/enumeration.md`), allocation on a per-element path (`CLAUDE.md` `docs/operations.md`, edge 5) and escape analysis all
  bite the same way. Treat any "obviously harmless" edit to a small, hot, frequently called method as a
  measurable change.
