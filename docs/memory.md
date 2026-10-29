# Memory management: three overlapping mechanisms

The design and the why; the rules a session must hold are `CLAUDE.md` §3.

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
3. **`GcReferenceManager`** — the object layer (`docs/design.md`).

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

## GC and growth

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
