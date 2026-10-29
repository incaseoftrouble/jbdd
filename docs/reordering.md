# Reordering

The design and the why; the rules a session must hold are `CLAUDE.md` §3.

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
straight into a variable-indexed set or translates through a buffer (`docs/enumeration.md`). It is O(variables), so it is
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
two of them (`docs/operations.md`) — and `MtBddCache` responds to each differently, which is why those stay small adapters
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
