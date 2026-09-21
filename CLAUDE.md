# JBDD — agent guide

Pure-Java (Reduced Ordered) Binary Decision Diagrams and variants. Single Gradle module, single package
`de.tum.in.jbdd`, GPLv3, `group = de.tum.in`, version `0.7.0` (in `build.gradle.kts` + `README.md`).
Design goals, in this order: **correctness, simplicity, performance, zero runtime dependencies.**

This file is the whole design reference; there is no companion document. Read the relevant section before
touching memory management, caches, enumeration, reordering or the value numbering — those are the areas
where a plausible-looking change is silently wrong. Decision diagrams are hard to get right: **prefer
asking for clarification over guessing.**

## 0. Working agreements

- **Do not run the full test suite proactively** — it takes minutes. Ask the user to run it, or run a
  filtered subset. Compile + a targeted test class is the normal inner loop.
- **Spotless / PMD / ErrorProne / NullAway failures are the owner's to fix.** Do not silence them with
  blanket suppressions, and do not treat them as your blocker. If a rule genuinely does not apply,
  suppress at the narrowest scope with a reason.
- **Document the status quo only.** No "previously we did X", no "considered but rejected" narration in
  source or docs, no plan/proposal text. Genuine future work is a short `// TODO` on the line it concerns.
- **Keep this file in sync, in the same pass as the change.** A change that invalidates a paragraph here
  invalidates it for every future session. The same goes for anything that turns out to be a
  "I would have wanted to know this beforehand" — that belongs here, not in a commit message. But **edit,
  do not append**: sharpen or replace the paragraph that was wrong. This is a working reference, not a
  log, and a file that grows without being cut stops being read.
- **The owner edits and reviews alongside you.** Files changing under you mid-session is normal, not a
  sign something broke. Re-read a file before editing it rather than trusting an earlier read, and prefer
  the IDEA MCP (§1) for the live state — that is largely why it is wired up.
- `CHANGELOG.md` is kept current per release (`0.7.0` in progress). `README.md` carries the version, the
  feature list and the recommended JVM flags.

## 1. Build, test, tooling

```bash
./gradlew build            # compile + spotless + errorprone/nullaway + pmd + test
./gradlew test             # JUnit 5, -ea, heap 2g..8g; minutes
./gradlew testSmall        # same suite, theory data scaled to 0.05; ~1 min
./gradlew test -Pjbdd.test.scale=0.2                       # any scale, either task
./gradlew test --tests 'de.tum.in.jbdd.RegressionTests'    # quick loop
./gradlew compileJava compileTestJava -q                   # quickest check
./gradlew spotlessApply    # palantir-java-format, 120 cols; pre-commit hook runs spotlessCheck
./gradlew jmhRandom | jmhSynthetic | jmhDimacs | jmhEnumeration | jmh
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
- **The Gradle configuration cache is deliberately off** (`gradle.properties`). It snapshots the task
  graph, so the `-Pjbdd.*` forwarding in `build.gradle.kts` would capture one run's values and every later
  run silently reuse them — a switch then appears to have no effect and any A/B run using it is
  worthless. Build caching stays on.
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
├─ NodeBasedDd                      nodes: nodeFor / nodeReferenceCount / nodeCount / size / gc
├─ BooleanTerminalDecisionDiagram   codomain fixed to bool, domain generic  → Bdd, Mdd
├─ BooleanDecisionDiagram           domain fixed to binary (highOf/lowOf)   → Bdd, MtBdd
└─ ReorderableDd                    structural: level / variableAtLevel / reorder / reorderTo
```

- The `...Dd` suffix marks the two **implementation-side** interfaces; `...DecisionDiagram` marks the
  **denotational** ones. `NodeBasedDd` is public because it is useful, not because it is stable — it
  describes how this implementation happens to represent functions.
- `DecisionDiagram` carries the ownership contract only and names no node at all. Everything counting
  nodes — `size(function)`, `nodeCount`, `gc()` — sits on `NodeBasedDd`. `gc()` is a *hint*: the caller
  says "now is a good time", the implementation may decline, and it is a semantic no-op either way.
- `ReorderableDd` is entirely denotational — it says what order is wanted, never how to get there. Only
  implementations that actually reorder have it, so `Mdd` does not.
- Functional interfaces: `BinaryDecisionDiagram`, `MultiValuedDecisionDiagram`,
  `MultiTerminalDecisionDiagram`. Public facades: `Bdd` (= binary + reorderable + node-based), `MtBdd`,
  `Mdd` (no reordering).
- `BooleanTerminalDecisionDiagram<S, P>` is the whole boolean-valued logical API over assignment type `S`
  and path type `P` (`BitSet`/`BinaryPath` for BDDs, `int[]`/`int[]` for MDDs): `and`, `andNot`, `exists`,
  `forall`, `ifThenElse`, `constrain`/`simplify`, solution and path cursors, the `xyIn` / `xySimplify`
  variants.

Implementations (package-private; construct only via factories):

- `BooleanBase<S, P>` — shared engine for `BddImpl` and `MddImpl`.
- `MtBddImpl` — **not** a `BooleanBase`. It *composes* a `BddImpl` (its variable universe) and owns its
  own `NodeTable.Binary`: two disjoint DAGs, one variable numbering, two independent GCs.
- `NodeTable` — the manual-memory heart: flat primitive arrays, hash-consed unique table, packed metadata
  `<VAR:17><REF:14><MARK:1>` (asserted to fill an `int`), free list, mark-and-sweep GC, growth, optional
  reordering bookkeeping. `NodeTable.Binary` (parallel `low[]`/`high[]`) and `NodeTable.Multi` (jagged
  `int[][]`); each diagram supplies a nested `Table` filling in the abstract hooks.
- `BddContextImpl` — owns everything the BDD and its MTBDD *share*: the variable order, variable creation,
  the entire sifting engine, the `reorder_*` statistics. It constructs both diagrams eagerly (BDD first —
  the MTBDD hangs its caches and observers off it) and hands each a reference to itself; both delegate
  their `ReorderableDd` surface to it, so `bdd.reorder()` and `mtBdd.reorder()` are literally the same
  call. Each diagram keeps only its own table, cache and `rewriteLevelAfterSwap` half of a swap.
- `ConcurrentAccessGuard` — thread-identity, reentrant, assertion-only. A field on `BooleanBase` and
  separately on `MtBddImpl`. Every public write entry point brackets its body with
  `assert guard.acquire(); … assert guard.release();`. Pure-delegation methods (`or` calling `and`) are
  deliberately unguarded — they do no write of their own and would conflict with what they delegate to.
  It only *detects*; synchronization remains the caller's job.

Entry points — never `new BddImpl(...)` outside tests:

- `BddFactory.buildBdd() / buildMtBdd() / buildMdd()` (each optionally with a `BddConfiguration`).
- `BddContext.create(...)` — the BDD/MTBDD pair over **one** variable order (`bdd()`, `mtBdd()`), plus the
  operational `siftDown(level)`.
- `BinaryFactoryContext.create(...)` — a context plus the object-layer factories `bddSets()`, `bddMaps()`
  (one each per context).
- `BddConfiguration` — an `org.immutables` `@Value.Immutable` generating `ImmutableBddConfiguration`:
  initial table sizes (`bddInitialSize()`/`mtbddInitialSize()` separately), cache dividers, growth factor,
  GC knobs. It extends `NodeTableConfiguration`, which is all `NodeTable` ever sees.

### Object layer — wrappers with automatic reference management

- `BddSet`/`BddSetFactory` over a `Bdd`; `BddMap<V>`/`Values<V>`/`BddMapFactory` over the MTBDD.
- `GcReferenceManager`: wrappers are held by `WeakReference` and a `ReferenceQueue` drives
  `reference`/`dereference` — no `finalize()`, which carries a hefty penalty. `protect(container)` is
  canonical per `canonicalKey()` (function id by default; `BddMap` keys on `(function, values)`, since a
  function id alone is ambiguous across numberings).
- `BddSet` deliberately exposes nothing assuming a fixed variable universe — callers always name the
  support they mean.
- `DimacsReader` parses DIMACS CNF (benchmarks/tests). `DelegatingBdd` is the instrumentation hook.

### Navigation: types that are not in a file of their own

Many important types are nested. Searching for `ValuesImpl.java` will fail.

| Type | Lives in |
|---|---|
| `ValuesImpl`, `BddMapImpl`, `MapKey`, `RelabelerImpl`, `RegisteredApply`, `RegisteredReplacer` | `BddMapFactoryImpl.java` |
| `BddSetImpl` | `BddSetFactoryImpl.java` |
| `PathWalk`, `SolutionCursor`, `PathCursor`, `BddTable`, `ComposeAnalysis` | `BddImpl.java` |
| `PathWalk`, `SolutionCursor`, `PathCursor`, `MddTable` | `MddImpl.java` |
| `MtBddTable`, `SplitBijection`, `IntTupleBijection`, `DepthPool`, `IntArrayList` | `MtBddImpl.java` |
| the registered operations (`Compose`, `Exists`, `Apply`, `Mapper`, …) | `BddOperations.java`, `MtBddOperations.java` |
| `BddMap.Operator/VariableReplacer/Mapper/Combiner/Selector/Relation/Relabeler` | `BddMap.java` |
| `BddSet.Quantifier`, `BddSet.VariableReplacer` | `BddSet.java` |
| the concrete cache shapes (`BinaryToIntCache`, `ApplySimplifyCache`, …) | `BooleanCache.java`, `MtBddCache.java` |

## 4. Memory management: three overlapping mechanisms

Manual, and the three protect against different things. Confusing them is the classic bug.

1. **Reference counts.** Per node, 14 saturating bits in the metadata word. Saturated = pinned forever,
   ref/deref become no-ops (variable nodes are saturated at creation). `consume`/`updateWith`/
   `ReferenceGuard` are the rebalancing helpers. MTBDD *terminals* get a parallel `byte[]
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
duration of its subtree) lives on the BDD work stack. Plain `compose` gets away with not protecting its
replacement array because it builds no BDD node anywhere; `composeSimplify` must push the mapping too (a
registered composer references it instead).

Two things a work-stack push does **not** do: survive past the call, and cover a *bare terminal value* the
caller referenced but has not embedded under any node — the mark phase only reaches leaves transitively.
Hence `MtBddTable`'s reclaim spares any value with a nonzero refcount even if unmarked.

### GC and growth

`ensureCapacity()` runs from `makeNode` when free nodes fall to ≤ 25%. It lives **once**, in `NodeTable`,
and is `final`; each table supplies hooks (`configuration`, `notifyBeforeGc`/`notifyAfterGc`/
`notifyAfterTableGrowth`, `sweepManagedLeaves`, `checkOwner`, `bytesPerSlot`). Keep it that way — a
per-table copy is how one table silently loses a step (draining `ProtectionTracker` before marking, say).

```
drain phantom refs (notifyBeforeGc), then
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
  Used memory includes uncollected JVM garbage, so the estimate is pessimistic on purpose. When the larger
  table would not fit, the live threshold rises to 0.9 (`MEMORY_PRESSURE_LIVE_NODE_THRESHOLD`) — a dense
  table collected often beats being unable to allocate — and if even that leaves the table full,
  `ensureCapacity` fails loudly rather than handing `makeNode` a table with no free node.

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
above now avoids by growing instead.

`MtBddTable` additionally sweeps terminal values in the *same* mark pass via the managed-leaf hooks
(`markLeafNodeIfManaged`, `anyManagedLeafMarked`, `recurse*`). Leaf marks live in a separate `BitSet
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
`node_table_memory_limited_grow_count` says the heap, not the configuration, is picking the table size.
All O(1) per collection; nothing on the hot path.

## 5. The recursive-operation skeleton

Core operations are **plain recursion** over the diagram; deep structures are handled with a large `-Xss`,
not hand-rolled explicit stacks (the iterative implementation was removed: ~10% slower on some benchmarks
and tedious to maintain). Keep new operations in that shape.

Every `compute*` / `*Recursive` follows six steps; deviations are where bugs live:

1. Trivial/constant short-circuits — **before** any cache lookup, always.
2. Canonicalize operand order, *only* if the operation is genuinely commutative.
3. Cache lookup; keep the raw pre-modulo hash (`lookupHash()`).
4. Shannon-expand on the smallest top **level**; an operand not carrying that variable passes through
   unchanged on both branches. `Integer.MAX_VALUE` is the usual "this side is constant" sentinel.
5. Recurse, pushing each result to the work stack before building the parent.
6. Build, pop, `put(hash, …)`, return.

The stashed hash stays valid across the recursion because the keys it came from stay alive (work stack).
If the table grew meanwhile, the hash still lands in a legal bin — a wasted slot, not corruption.

**MTBDD-specific constraint:** the combining function is an **opaque caller lambda with no assumed
algebra**. There is no `f == g ⇒ f` shortcut and no idempotence — `apply(f, f, op)` must fully recurse.
Callers opt into properties explicitly via `MtBddBinaryOperator`/`MtBddNaryOperator`
(`commutative`/`neutral`/`absorbing`), which are *unchecked preconditions* spot-checked at leaves by
`checkLaws` under `-ea`. `commutative` enables step 2, `neutral` returns the other operand unchanged,
`absorbing` prunes both subtrees.

**Callbacks run inside the traversal.** An `apply` operator, a `map` function, a `where` predicate and the
like are invoked with the work stacks loaded, so each must be a pure function of its arguments and must
not start another operation on the diagram — one that does trips the `workStacksEmpty()` assertion.
Purity is the general contract; a method's javadoc should document only a *deviation* from it.

## 6. Operation caches

`CacheBase` is the shared engine: flat `int[]` table, fixed stride per bin, prime sizes, lazy resize,
**one candidate bin per key** — direct-mapped (`mod(hash, size)`, no probing), so a collision *overwrites*.
These are memos, not maps: **a cached result may vanish and every caller must be correct without it.**
Two key flavours: `CacheBase.IntKeys` packs `int` keys into the bin (`keyCount` leading slots the key,
the rest the result); `CacheBase.ObjectKeys<V>` keys on an object (`cartesianProduct`'s operand tuple).
Resizing lives in the base (`rehashInto`, hashing the stored key exactly as `lookup` did); a cache whose
result sits in a *parallel* array overrides `growInto` and passes a `BinRelocation`. `BooleanCache` (one
table) and `MtBddCache` (entries can straddle *two* tables) subclass it separately — each concrete cache
knows, per slot, which table it belongs to (`isValidBdd`/`isValidMtbdd`).

**Validity is reactive, never proactive.** A cached result is not referenced or pinned. When a table GCs,
a hook re-checks or wholesale invalidates the affected slots; between GCs entries are assumed good. Two
consequences that are easy to get wrong:

- The prune hook must run on **every** GC of **every** table an entry can reference, **before any new
  allocation** — node ids are recycled, so a stale entry can silently become "valid" again while naming a
  different function. Hooks are `NodeTableObserver`s; `NodeTableObserverGroup` holds *caller-owned*
  observers weakly (`register`) and *diagram-owned* ones strongly (`registerStrongly`). A diagram's own
  hook in the weak list is eventually collected, after which pruning silently stops and wrong answers
  appear at random — **always `registerStrongly` for those.**
- Anything a key *implicitly* depends on but does not encode must invalidate the whole cache when it
  changes. Four flavours:
  - **Stable** — keys are plain ids (`agreement`, `ite`, `update`, `simplify`, `constrain`). Nothing extra.
  - **Ephemeral "current parameter"** — `compose` (`int[]` mapping), `exists` (a `BitSet`), `restrict`
    (two `BitSet`s), `apply`/`map`/`mapBoolean`/`applyBoolean` (an opaque operator compared by identity;
    the paired `*Simplify` cache is invalidated by the same `initX`), `count` (a predicate),
    `canReachMatch` (a predicate). `initX(...)`
    compares against the previous call's parameter and invalidates wholesale on change. **This works only
    for eagerly-completing calls** — the lazy cursor from `assignmentCursor` outlives its own `initX` and
    must be drained before any other query runs; an assertion enforces it.
  - **Per-call scratch state** — `split`/`splitCombine` and `cartesianProduct` produce *indices into a
    bijection built fresh per call*, so an older entry names a numbering that no longer exists.
    `initSplit()`/`initCartesianProduct()` therefore invalidate unconditionally at every entry. Within one
    call they are sound because interning is idempotent. Note `cartesianProduct`'s key is the whole
    operand tuple and its recursion rewrites that array in place — it must be cloned before descending,
    which is also what the cache stores.
  - **Implicit global state** — satisfaction/assignment counts range over `[decisionVariable,
    numberOfVariables)`, so `createVariable` invalidates them (`variablesChanged()`) though
    `numberOfVariables()` appears in no key.

**Simplify-fused operations.** `andSimplify`/`composeSimplify` (BDD) and `applySimplify`/`mapSimplify`/
`composeSimplify` (MTBDD) are *one* recursion with the plain operation as the `domain == TRUE` special
case, not a wrapper around `simplify(op(...))` — the domain is cofactored on the way down, so a branch the
domain excludes is never visited. Each carries a second cache keyed on the same arguments plus the domain,
invalidated together with the first. That domain-carrying cache straddles both tables, which is why the
*registered* simplify variants register their prune hook with the BDD as well as the MTBDD; a plain
registered `apply`/`compose` only needs the MTBDD's.

Two invariants easily lost: widening a domain (`domainVar < variable` → `or` of the cofactors) is always
sound because a *larger* domain constrains more; cofactoring the domain on a variable is sound only when
that variable means the same on both sides — for `composeSimplify` only when it maps to itself
(`aligned`), since the domain speaks about *post*-substitution variables.

## 7. Registered operations

The escape hatch from ephemeral invalidation: bind the parameter once, get a **private cache** that
survives alternation between operations. A plain call hands the diagram a freshly built translation on
every invocation; the diagram reads that as a different operator and drops the shared ephemeral cache, so
repeating one operation never reuses an entry.

Int layer: `registerCompose`/`registerComposeSimplify` on both `Bdd` and `MtBdd`, `Bdd#registerExists`,
and `MtBdd#registerApply`/`registerApplySimplify`/`registerMap`/`registerMapSimplify`/`registerMapBoolean`/
`registerApplyBoolean`, returning `RegisteredOperation.Unary`/`Binary`/`Ternary`. Only the compose forms
bind *nodes*, and those own them: `ProtectedOperation` + `ProtectionTracker` reference the operands on
construction and drop them via a `PhantomReference` when released or unreachable. The rest bind a lambda or
a `BitSet` and hold nothing. Their private caches grow on usage (`growOnUsage`) rather than tracking table
size, and each registers its prune hook with *every* table its entries can name — the two boolean-valued
MTBDD operations always straddle both, since their results are BDD functions.

Object layer: handle types bound once and applied repeatedly — `BddMap.Operator<V>` (plus `applyIn`),
`BddMap.VariableReplacer` (plus `replaceIn`), `BddMap.Mapper<V, O>`, `BddMap.Combiner<V, W, O>`,
`BddMap.Selector<V>`, `BddMap.Relation<V>`, `BddSet.Quantifier`, `BddSet.VariableReplacer`.

- **Each extends `RegisteredOperation`**, which is where `release()` and the one statement of what
  binding means both live. The root declares no abstract method, so a handle that also extends a
  `java.util.function` type (`BinaryOperator`, `UnaryOperator`, `Function`, `BiFunction`) stays a valid
  `@FunctionalInterface` — keep it that way when adding one. `BddMap.Relabeler` deliberately does not:
  it is created by `createRelabeling`, not registered, and holds a destination numbering that outlives
  it, so a `release()` on it would mean something else.
- `BddMap.VariableReplacer` is the exception to the `java.util.function` part: replacing variables never
  looks at a terminal, so one instance serves maps over *any* numbering and each result stays over its
  operand's — a generic method, which no lambda can implement, hence its explicit
  `@SuppressWarnings("PMD.ImplicitFunctionalInterface")`.
- **All of them are backed** by an int-layer registered operation. `BddSetFactory`'s two variable
  replacements take the set of variables they replace, because a handle is built before it sees a set:
  the resolved substitution is absolute, so naming the variables is all it takes to resolve it once
  instead of per call.
- **Constants are pinned at registration, domains are not.** `RegisteredApply` resolves `neutral` and
  `absorbing` to raw terminals once and holds each as a `BddMap` constant, keeping that leaf referenced so
  its index cannot be recycled into another value; `pinsHold()` asserts this on every call. A
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
MddImpl    PathWalk(function)         → SolutionCursor, PathCursor
MtBddImpl  PathWalk(function, values) → AssignmentCursor, PathCursor   (both ValuedCursor)
```

`PathWalk` is *not* a `Cursor` — it hands nothing out, it only moves; the cursors differ only in what they
make of its state. Each is a `final` class in a field of its own type, so nothing in the walk dispatches
virtually. **Do not unify the three behind a shared interface:** the differences (branching arity,
complement edges, what counts as a dead branch) sit in the innermost loop and would make those call sites
megamorphic. Near-duplicate walks are the right trade here.

**A solution enumeration is a path enumeration plus a counter.** A path fixes only the variables the walk
decides on; everything else in the support is free, and every combination extends that path to a solution.
The free set is computed once per path (`BitSets.difference`) and counted off with `BitSets.increment`.
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
ones are counted underneath. `forEachSolution` and the cursors agree on it.

Everything above is by level; `current()` is by variable. On a diagram that has never reordered the two
coincide and the cursor hands out the walk's own sets directly; after a reorder it hands out a
variable-indexed buffer, distinguished by a `@Nullable translated` field (no buffer = no translation).
**That buffer is maintained incrementally, never rebuilt.** A step changes a handful of bits while the
sets hold the whole path, so every writer mirrors its own flips: `PathWalk` routes all four of its writes
through `assign`/`pushSupport`/`popSupport`, and `SolutionCursor.increment` does the same for the free
levels. The walk snapshots `levelToVariable` at construction, so a mirrored write is one array load and
cannot be invalidated by a variable creation resizing the context's array. Measured against rebuilding per
step (`EnumerationBenchmark`): solutions 4.3x with many free variables, 1.65x with none, paths 1.8x–2.6x;
on the identity order the mirroring is a null check that never fires. Two rules keep it correct: every
write to either level set goes through one of the four writers, and `currentIsConsistent()` rebuilds and
compares under `-ea`, so a writer that forgot to mirror fails at the cursor, not downstream.

Rough costs per element (18 variables, ~50k solutions, identity order) against the native recursions:
solutions ≈ 6 ns vs 3 ns; solutions in a domain ≈ 6 ns vs 5 ns; paths ≈ 40 ns vs 17 ns. The path gap is
the explicit stack against the JVM's call stack and is close to irreducible in this shape — caching high
edges in the stack and a positive-node + `lookingFor` representation were both measured; neither helped.

## 9. Reordering

Variables keep their numbers; only their **level** moves. `BddContextImpl` owns the bijection
(`variableToLevel`/`levelToVariable`) and both diagrams read it, so a shared variable universe cannot
drift apart.

`reordered` is a fast-path switch *and* the arrays' liveness flag: while false the order is the identity
and is **not stored at all** — both arrays are `EMPTY_INT_ARRAY`, `level`/`variableAtLevel` answer `v`, and
a workload that never reorders never allocates them. `makeOrderExplicit()` writes out the identity ahead
of the only two things that bend it (`siftDown`, and `createVariableAtLevel` anywhere but the bottom); it
leaves `reordered` set although the order is at that instant still the identity, which is sound only
because both callers make it not be within the same critical section. `revertIfIdentity()` is the other
direction. That flag is worth more than an array load — it also decides whether enumeration writes
straight into a variable-indexed set or translates through a buffer (§8). It is O(variables), so it is
asked only after `reorder`, `reorderTo` and `reorderToIdentity`, never per swap;
`reorder_identity_reverts` counts how often it fires.

`siftDown(level)` is the primitive: exchange the variables at `level` and `level + 1`, rewriting exactly
the nodes that must change, **in place**. Node ids survive, so every `int` a caller holds keeps denoting
the same function. It sits on `BddContext`, not the diagrams — it moves both, and it is the only
*operational* method in the reordering surface.

- `reorder()` — sifting: heaviest variable first, each swept to its best position within its group,
  abandoning a direction once it has grown past `MAXIMUM_SIFT_GROWTH`. Candidate positions are measured by
  exact live node count, which `parentCount` (live-parent counts, maintained with cascade and
  resurrection) gives for free.
- `reorder(List<BitSet> groups)` — restrict movement to within already-contiguous blocks; a variable in no
  group stays put.
- `reorderTo(List<BitSet> blocks)` — *put the order into this shape*: block `i` entirely above block
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

Bookkeeping (per-variable node lists, parent counts) is built lazily on the first reorder and dropped
afterwards unless `keepReorderingStructures()` is set. Set it only when reordering is frequent enough that
rebuilding dominates; it costs one int per node slot plus one per valid node and puts work on every node
created. Neither a collection nor a growth drops it, so within one reordering pass it is derived once.

**An order change says what moved, and every listener decides for itself.** There is no blanket
invalidation: `NodeTableObserver` carries `levelsSwapped(origin, level)` and `variableInserted(origin,
level)`, and the two permit very different things.

- A **swap** changes relative order, so anything folding a level comparison into a value is stale. Both
  operation caches drop everything. They need not — reordering rewrites in place, so an entry that is only
  a statement about node ids (`and`, `xor`, `ite`, `intersects`; MTBDD-side `apply`, `map`, `map_boolean`,
  `agreement`, `update`, `ite`) is still true. Keeping them was built and measured and bought nothing: the
  total `and` hit count over swaps-then-replay was identical to within one hit, because the swap reshapes
  the diagram and the surviving entries simply stop being asked about. A keep-list is a classification
  every new operation would have to be sorted into, and a wrong sort is silent corruption — so the
  analysis lives in `BooleanCache.levelsSwapped`'s javadoc, not in its body.
- An **insertion** preserves relative order, which is stronger than it looks: every level *comparison*
  answers as before, since both sides shift by the same rule (`l < L ? l : l + 1`). So no cached value
  goes stale and `createVariableAtLevel` invalidates nothing. What moves is a *stored* level, and there are
  exactly two: `BddOperations.Compose` and `MtBddOperations.Compose` hold a `maxReplacedLevel`, shifted by
  one if at or below the insertion point. On a swap they recompute it instead (`BddOperations` in O(1)
  from the two levels that moved, under an assertion against the full rescan) and drop their own caches.
  A cut-off that is a level stops being a level once relative order changes.

**Three shapes that must translate**, and where:

- **Node construction.** `makeFunction(level, low, high)` resolves the level through `levelToVariable`;
  `makeFunctionForVariable(variable, …)` (`MtBddImpl`) is the form when the variable is already in hand.
  Handing a variable to the level-taking one builds a node for a different variable entirely.
  `BddContextImpl.createVariable`/`createVariables` write the new variable into `levelToVariable` before
  calling `makeFunction`, which reads it straight back — but only while the order is explicit; a new
  variable goes to the bottom, so it is its own level and the implicit order already says so.
- **Caller-supplied data inside a recursion.** `compose`'s replacement array and `restrict`'s/`split`'s
  `BitSet`s are indexed by *variable*, while the descent and its cut-off are by *level*. Each recursion
  takes `decisionVariable(node)` for the lookup and `level(variable)` for the ordering, and its cut-off is
  `maxLevel(...)` — never `BitSet.length() - 1`, which is a variable bound.
- **A mapping shorter than the variable count.** It leaves the rest unchanged, and under a non-identity
  order one of those can sit *above* the greatest replaced level, so the recursion reaches it. Both
  compose implementations bounds-check before indexing rather than relying on the cut-off.

Statistics: `bdd_reorder_saved_nodes` against `bdd_reorder_swaps` / `bdd_reorder_rewritten_nodes` is the
benefit-vs-cost pair, summarised as `bdd_reorder_work_per_saved_node`. `bdd_reorder_collections` says
whether `MAXIMUM_SIFT_GARBAGE` is set sensibly, `bdd_reorder_abandoned_directions` whether
`MAXIMUM_SIFT_GROWTH` is. `bdd_reorder_identity_reverts` outside a deliberate `reorderToIdentity()`
signals the fast path is worth more than it looks.

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
  operation cross-numbering. Neither is imposed. Per-automaton and per-SCC are the scopes worth measuring.
- **Same numbering ⇒ raw int operations.** `agreement` is raw terminal equality on its own cache;
  `ifThenElse`, `update`, `where`, `domainOf` never unwrap a value. Maps are canonicalized on
  `(function, values)` (`MapKey`, identity on the numbering), so `equals` is `==` and O(1), and
  semantically equal maps over one numbering *are* the same object.
- **Cross-numbering is supported, not forbidden.** `apply(other, combiner, destination)` and
  `where(other, BiPredicate)` resolve each side through its own numbering in a single traversal — no
  `adopt` pass first, and the two sides need not share a value type. Operations that must *produce* a map
  over one numbering still go through `Values.adopt` explicitly; implicit adoption was rejected because it
  would permanently append the other map's values and hide an O(|other|) traversal behind an O(1)-looking
  call.
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
- **Indices are recycled, not append-only.** `ValuesImpl` is a `NodeTableObserver`: on GC it forgets
  entries whose raw terminal was *globally* reclaimed, walks its high-water mark (`biggestAliveIndex`)
  back and rebuilds `freeGaps` by one ascending scan, so fresh values pack in from the bottom. The mapping
  is a bijection at all times and an index's meaning is stable while it is alive — which is what lets a
  cached raw result stay valid. `afterGc` returns immediately unless something below `biggestAliveIndex`
  was reclaimed; the walk back reads `toValue`, not `reclaimedValues`, because a slot is vacant either
  because it was just reclaimed or because it was already a gap, and the mark must step over both.
  Reclamation is conservative: with several numberings sharing the raw int space, a reclaimed value cannot
  be attributed to one, so only globally dead entries are dropped.
- **No canonicalization, freezing or interning of numberings.** Two consequences to design around:
  structure sharing is insertion-order dependent, and equality across numberings is pessimistic (same
  function + different numbering counts as unequal even when semantically equal). Use
  `agreement(other).isUniverse()` where semantic comparison is wanted.
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
  narrows for it — the intended way to tell it about a nullable field a branch has established (the
  per-operation caches in `MtBddImpl` are the standing example). It does not infer implications, so the
  assertion must sit *inside* the branch using the value; branching on the field would trade a warning for
  worse code.
- Suppressions are narrow and carry a reason: `@SuppressWarnings("NullAway.Init")` per field on JMH
  `@State` fields, `// NOPMD - <reason>` on deliberate reference comparisons (factory and numbering
  identity is *the* check; `equals` would be wrong) and on `System.out` in benchmark mains.
  `@SuppressWarnings("AssertWithSideEffects")` sits on the classes whose public methods bracket with
  `assert accessGuard.acquire()`.
- **Assertions carry real work.** `assert accessGuard.acquire(); … assert accessGuard.release();` and
  `assert isValidFunction(f)` are the standard preamble of a public method. Tests run with `-ea` and rely
  on it. Keep validation in assertions, not in runtime checks, on hot paths — with `-ea` off, invalid
  arguments corrupt the structure quietly rather than throwing.
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
- Statistics fields get a comment saying what question the number answers.

### Performance is not standard-Java performance here

The hot paths are tiny static helpers over flat `int[]`s, called from deep recursions, and the usual
intuitions transfer badly. Two habits follow:

- **Measure; do not reason.** Several plausible optimizations in this codebase measured *slower* and are
  documented as such (appending during the sweep instead of filtering after, keeping cache entries across
  a swap, caching high edges in the path stack). Anything performance-motivated is backed by `src/jmh`.
- **Watch what a change does to inlining, not just to instruction count.** The JIT's inlining budget is in
  *bytecode size*, and that size includes code the JVM may never execute — an assertion counts, so adding
  a message to an `assert` in a small static helper can push it over the threshold and make every caller
  pay a call, which is why some of them carry a bare `assert`. That is one instance, not the rule: field
  layout, megamorphic call sites (§8), allocation on a per-element path (§14.5) and escape analysis all
  bite the same way. Treat any "obviously harmless" edit to a small, hot, frequently called method as a
  measurable change.

## 12. Tests

`src/test/java/de/tum/in/jbdd`, JUnit 5 + Hamcrest.

- **`BddTheories` / `MtBddTheories`** — the main suite and the bulk of the runtime. `Generator` fills each
  diagram once with random syntax trees, producing unary/binary/ternary data points fed to parameterized
  theories that compare the diagram against a reference `SyntaxTree`/`IntSyntaxTree` evaluation and
  re-check table invariants.
- **`BddTheories` runs seven diagram variants**: three `BddImpl` and three `MtBddAsTestBdd` — never
  reordered, reordered with the bookkeeping rebuilt per reorder, reordered with
  `keepReorderingStructures(true)` — plus one `MddImpl`. An `@AfterEach` hook applies random `siftDown`s
  to the four reordered ones every `REORDER_EVERY` theories; the never-reordered ones are the control.
  `MtBddTheories` mirrors this with three `Context`s (its data points carry the context they were built
  in). `@AfterAll` asserts the order actually moved, so the stress cannot silently degrade into a no-op.
  `infoMap` is a `LinkedHashMap` on purpose: it keys on diagrams, whose hash is their identity hash, so a
  plain `HashMap` iterates differently per JVM run and a failure would move or vanish between runs of the
  same code. Each diagram is built from a named `BddConfiguration` whose `toString()` reports that name —
  which is how a failing data point says which variant it came from.
- **`TestBdd` + `TestBddImpl`, `MddAsTestBdd`, `MtBddAsTestBdd`** — adapters letting the theories run the
  same assertions against BDD, MDD and MTBDD. `TestBdd` adds introspection (`invalidateCache`,
  `isValidFunction`, `check`, `treeToString`) and extends the *functional* interface only; an adapter that
  can reorder implements `ReorderableDd` on top (`MddAsTestBdd` does not — MDDs do not reorder).
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
- Targeted tests: `BddTest`, `MtBddTest`, `BddMapTest`, `ValuesTest`, `ReorderTest`, `HashTest`,
  `UtilityTest`, `DimacsReaderTest`. `SyntheticTest` — n-queens counts as an end-to-end sanity check.
  **`RegressionTests` — one test per past bug; add here when fixing one.**
- `ValuesTest` holds the numbering-level tests: split residuals, a merging cartesian product and a
  supertype view via `createRelabeling`. `RegisteredOperationsTest` holds the object layer's handles —
  that each agrees with the plain operation it stands for, warm cache included (the `applyIn`/`replaceIn`
  ones pinned to their contract via `agreement(…).containsAll`). PMD's coupling limit applies to test
  classes too, which is what keeps these two apart and out of `BddMapTest`.
- **New operations need:** theory coverage against the reference evaluation, an invariant check, and — if
  it touches ordering — a reordered variant.

## 13. Benchmarks

`src/jmh/java/...`, JMH, DIMACS instances in `src/jmh/resources`. Performance claims in comments and the
changelog are expected to be backed by these; **measure before tuning a constant.** `RandomBenchmark`,
`SyntheticBenchmark`, `SyntheticSetBenchmark`, `DimacsBenchmark`, `EnumerationBenchmark`,
`HashSchemeBenchmark`.

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

## 15. Open work

Ordered by how much they block.

- **`solutionCursorIn` is native only on `BddImpl`.** `MddImpl.solutionCursorIn` still materializes
  `and(function, domain)` (both overloads carry a `// TODO Native`). The BDD product walk (§8) is the
  template; the MDD version needs the same dead-end backtracking over n-ary children. `MtBddImpl` has no
  domain-restricted enumeration at all — `assignmentCursor`'s predicate is over terminals, a different
  question.
- **A dedicated single-diagram path walk.** `PathCursor` pays ~5 ns/path to carry a domain it never uses.
  Worth re-duplicating the walk only if path enumeration turns out to be hot.
- **Incremental buffer mirroring is not in `MtBddImpl.PathWalk`,** which shares the order and therefore
  translates too. `MddImpl` needs none of it — an MDD does not reorder, so its cursors never translate.
- **Freeze + intern numberings** — only if sharing measurements justify it. Build against a mutable
  scratch numbering, then canonicalize (safe while only one map references it) and intern by value
  sequence, so isomorphic states converge on the same `Values` object: equality becomes precise and
  sharing canonical, one pass per numbering at finalization. The primitive is `map(function, interner)`
  with an interner handing out `0,1,2,…` on first sight — no new `MtBdd` method, but `map`'s contract must
  be strengthened first: guarantee the **order of first invocations** is low-first DFS (*not* "exactly
  once per value" — the caches are lossy, so a value may be re-presented after eviction; an idempotent
  interner is unaffected, but "exactly once" would be false), and specify low-first explicitly rather than
  only "deterministic", so an injective `apply`'s output is provably already canonical and the pass can be
  skipped. `computeApply` and `computeMap` both recurse low-then-high; write it down. The deciding
  measurement: with a per-automaton numbering, how much structural sharing is lost against a canonicalized
  per-state numbering?
- **Inverted split: `Map<BddMap<V>, BddSet>`** — per distinct residual, the set of split-variable
  valuations reaching it; the shape a hand-built `Map<Edge, BddSet>` has. Mechanically `split` followed by
  `invert` of the index function, but it must live inside `MtBddImpl`: `split`'s pieces are unprotected and
  must be referenced before any further call (which is why `splitRelabeled` exists). Outside, it would
  rest on "a BDD-side `invert` happens not to collect MTBDD nodes" — true today, and exactly the kind of
  cross-table reasoning that breaks quietly.
- **`invert` sizes its array-vs-`HashMap` path from a global watermark.** `MtBddImpl.invert` takes
  `domainSize = allocatedValues.length()` — monotone and global — to decide a *per-function* operation, so
  one high-out-degree map permanently forces every later `invert` onto the `HashMap` path. `domainSize`
  only sets the array width (the per-node loops already walk the accumulated `values` BitSet), so raising
  `INVERT_ARRAY_DOMAIN_THRESHOLD` is the cheap fix, not a per-function bound. `invertRecursiveArray`
  allocates a fresh `int[domainSize]` at every constant leaf and cannot use `DepthPool`, because a low
  branch's array is still live while the high branch recurses to the same depth.

**Rejected, with reasons.** Relabel hooks on `ifThenElse`/`restrict`/`compose`: a stateful hook makes the
result depend on the lambda, forcing per-call cache invalidation — for `ifThenElse` that destroys a
*persistent* cache. (`splitRelabeled` is not a precedent: it exists so no intermediate function is exposed
unprotected, i.e. memory safety, not renumbering efficiency.) A native cross-numbering `agreement`:
possible, but `agreementRecursive`'s `node1 == node2` shortcut and its operand-order canonicalization both
become unsound, and its currently *stable* cache would become an ephemeral-parameter one. Revisit only if
`adopt` traversals measurably dominate, and then with a separate cache.
