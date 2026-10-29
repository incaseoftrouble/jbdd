# Operations: the recursion skeleton, the caches, registered operations

The design and the why; the rules a session must hold are `CLAUDE.md` §3.

## The recursive-operation skeleton

Core operations are **plain recursion** over the diagram; deep structures are handled with a large `-Xss`,
not hand-rolled explicit stacks (an iterative implementation measured ~10% slower on some benchmarks and is
tedious to maintain). Keep new operations in that shape.

Every `compute*` / `*Recursive` follows six steps; deviations are where bugs live:

1. Trivial/constant short-circuits — **before** any cache lookup, always.
2. Canonicalize operand order, *only* if the operation is genuinely commutative.
3. Cache lookup; keep the raw pre-modulo hash (`lookupHash()`), and count the step (`table.step()`) on a miss.
   A recursion with a memo of its own (adopt's map, unateness, literals, influences) counts on its memo's miss,
   one without (`invert`) on every non-terminal call; walks that only mark nodes (values, cubes, matches) and
   folds do not count - they are linear in a diagram whose steps did. The count is `DdContext.work()` and the
   statistic `operation_steps`: clients measure their own cost by it (SemML's explorer budgets its rounds in it),
   so a new operation that does not count makes their budgets wrong without failing anything.
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

**The residual product is one descent too** (`MtBddImpl#residualProduct`, the interface javadoc has the algebra and
the chains it equals; `ResidualProducts.ofCartesianProduct` is the chain, the default implementation and the theories'
oracle). Its operands map variables of the function as `compose`'s mapping does (`placeholder()` leaves a variable).
The state is the residual, a function of the operator's diagram, and the operands still in the descent, by the
ascending variable they replace: at each step the operands that reached a value the valuation decides restrict the
residual (`BddImpl#restrictLiteral`), then every operand whose variable is outside the residual's support
(`supportArray`) leaves, so the tuple shrinks along the path as in `and(int[])` - an empty support or an all-terminal tuple
is a leaf, a single operand left descends alone (`residualProductSingle`: the residual cannot change before it reaches
a value, so no supports and no tuples, under the same key), otherwise it splits on the operands' least level. Results go to `ResidualProductCache`, keyed
`[residual, node_1, ..., node_k]` (the variables are the replaced ones in the residual's support, so the residual
determines them) and per-call (the leaves index pairs numbered afresh,
`ResidualProducts.Pairs`). Every residual stays on its diagram's secondary work stack for the call, which is what keeps the cache's
keys and the pairs valid across the collections a restriction may cause - and why the cache checks the operands' nodes
only. The function is the *operator*: its variables index the operands, it is only restricted and asked for its
support in its own diagram and never descended with the operands, so it may live in any diagram - the int layer takes
it as an `Operator` (diagram and function; a `BddImpl` descends natively, any other `BinaryDecisionDiagram` gets the
chain), `Values.residualProduct` as a `BddSet` of any context (`residualProductRelabeled`, which also relabels the pairs
outside the descent as `splitBddRelabeled` does). The joint descents (`ifThenElse`, the domains, `compose`'s
replacements) cannot do that: there the BDD decides on the MTBDD's own variables. An assignment reaching each pair is `cubes` of the result (`BddMap.cubes()` on the object layer).
A split's codomain is the values its meta-function takes (`valuesOf`), since combining interns residuals that a later
combination merges away; the relabeled splits relabel only those (arrays still by residual index).

**Cubes of paths** (`PathCubes`): `MultiTerminalDecisionDiagram.cubes(f[, values])` (`BddMap.cubes()` on the object layer) is `valuesOf`'s walk keeping the
first path to each value (low before high, each node once; a `NatSet` stops once all have one), `shortestCube(f,
predicate)` the bounded shortest-path search of `BddUtil.shortestPath` with "the terminal is accepted" for "the terminal
is true", `shortestCubes` that per value. Both give a `ValueCubes` (`codomain()`, `cubeFor(value)`, `forEach` by
ascending value; `PathCubes.OfMap` over a map), not a bare map. `MtBddImpl` walks for `cubes` with the table's mark bits, as `valuesOf`
marks (each node marked on entry, so the path above a marked node is marked and one `doSetMarkBelow` unmarks all). On a BDD, `cube(f)` is a greedy descent (every node but false reaches true)
and `shortestCube(f)` is `BddUtil.shortestPath`.

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

## Operation caches

`CacheBase` is the shared engine: flat `int[]` table, fixed stride per bin, prime sizes, lazy resize,
**one candidate bin per key** — direct-mapped (`mod(hash, size)`, no probing), so a collision *overwrites*.
These are memos, not maps: **a cached result may vanish and every caller must be correct without it.**
Two key flavours: `CacheBase.IntKeys` packs `int` keys into the bin (`keyCount` leading slots the key,
the rest the result) and does every lookup and put (`findBin`/`storeKeys` by key count, `resultIn`/`storeResult`
for a result kept in the bin); a concrete cache adds only where its result lives and what makes an entry stale.
`BooleanCache.IntCache` asserts every key is a *non-constant* function, since step 1 of the skeleton short-circuits
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
    in that prefix share it), the support (`supportCache`, ascending variables per node; filled by composition only, which grows it with
    its puts, within one composition too and up to a bin per node: it is `supportArray`'s only memo, which at a fixed
    small size recomputes shared subdiagrams' supports hundreds of times per node - support queries walk the diagram and take an entry
    where they meet one) and the satisfying fraction (`FractionCache`: per regular node its own fraction and its complement's, interleaved, so that
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
    operator compared by `equals`, which is identity for a caller's lambda; the object layer hands over value classes
    - `ValueMap`, `ValueCombination`, `ValueRelation`, ... in `BddMapFactoryImpl`, equal where the caller's function
    and the numberings are - so a caller repeating a function keeps the cache rather than a lambda built per call
    clearing it; the paired `*Simplify` cache is invalidated by the same `initX`; the
    n-ary one keys on the cloned operand tuple, as `cartesianProduct` does), `count` (a predicate),
    `canReachMatch` (a predicate), `allMatch` (a predicate, like `applyBoolean`). `initX(...)`
    compares against the previous call's parameter and invalidates wholesale on change. **This works only
    for eagerly-completing calls** — the lazy cursor from `assignmentCursor` outlives its own `initX` and
    must be drained before any other query runs; an assertion enforces it.
  - **Per-call scratch state** — `split`/`splitCombine`, `splitBdd`/`splitBddCombine`,
    `cartesianProduct` and `residualProduct` produce *indices into a bijection built fresh per call*, so an older entry names a
    numbering that no longer exists. `initSplit()`/`initSplitBdd()`/`initCartesianProduct()`/`initResidualProduct()`
    therefore invalidate unconditionally at every entry. `splitBdd`'s residuals are BDD functions, interned on the
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

**Simplify-fused operations.** `andSimplify` and `andExistsSimplify` (BDD) and `applySimplify`/`mapSimplify`/
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
registered handle carries both. The conjunction is never built: on the preimages of `jmhRelational` (`CLAUDE.md` §6) the
fused form creates 2.3 to 4.7 times fewer nodes and takes 0.4 to 0.85 of the time of `and` then `exists` (the
random transitions gaining least).
`orForall` is its complement. `BddTheories.testAndExists` pins it, both operand orders, registered and dual, to
the composition and the syntax trees.
`andExistsSimplify` is the same recursion cofactoring a domain on the way down, `computeAndSimplify`'s way: a domain
reading a quantified variable is projected first (`exists`, which only widens it), so the domain never has a quantified
level; at a quantified level the two results are `orSimplify`'d against the same domain, at a free one the domain is
split with the operands and a branch it excludes is not visited. The low-equals-a-high-cofactor shortcut is left out:
simplified results are only right on the domain, so one bounds nothing. Its ternary cache sits next to the and-exists
one and is invalidated with it (`initAndExists`); the registered handle (`BddOperations.Exists`, also its `Ternary`)
carries a third private cache. `testAndExistsSimplify` pins it, both orders and registered, to `simplify(andExists)` on
the projected domain and checks the result reads no quantified variable.

**The BDD compose is a joint descent** (`computeCompose`, also `composeSimplify` and both registered forms). Its
state is the function and the domain restricted to the path, with each replaced variable the function still
reads and that replacement restricted to the path. That tuple is the whole context, so results go to the
stable `ComposeTupleCache`, keyed `[F, D, v_1, ..., v_k, R_1, ..., R_k]` (the variables are part of the key: the
same replacement for another variable means something else). A replacement the path made constant is
substituted into `F` right away, so a decided disjunct or conjunct ends the descent. Where `F` reads no replaced variable any more, the descent
ends too: `F` itself, simplified to the domain. Otherwise the step splits
on `F`'s top variable. Left alone, it is a path literal: the domain is restricted by it, and so is each
replacement the child still reads (`restrictLiteral`, one prebuilt cube per literal, through `RestrictCubeCache`);
a branch outside the domain is skipped, its replacements never restricted, and the children are reassembled over
it - a node per level where they lie below. Replaced, it is an if-then-else over its replacement, taking one
branch directly where the domain decides it. Carrying the path is what keeps compose from building a branch for both values of a variable its replacements read and
discarding one half - exponential where the replaced variables sit below those
(`testComposeCarriesThePathToTheReplacements`); keying on content shares a state reached along paths that
restrict alike. Results agree with the composition wherever the domain holds.
`analyzeCompose` reads a mapping in one pass: nothing replaced, only constants (`restrict` takes it), only constants
and literals (a renaming), or anything else. The path pays only for a replacement that reads a variable the mapping
leaves alone above the one it replaces - only those become path literals that restrict it. A replacement whose top
lies below its variable reads none, and a literal the path decides is answered by the if-then-else; so a renaming,
and a mapping whose other replacements all lie below their variables (one level comparison each, no support read),
is composed **node by node** (`isNodewise`, `composeNodewise`) - a replacement reaching above takes the joint descent,
even where it reads only replaced variables there: per node `ite(R_v, c(high), c(low))`, memoized on the node - no supports, no path, the same work.
For a literal above both results the node is made directly, so an order-preserving renaming copies the diagram node
for node (the MTBDD's composition, always node by node, does the same); otherwise the if-then-else sorts it in, which
keeps swaps and merges correct. With a domain it is `simplify` of the composition: a pass over each, where the joint
descent's states can be their product. The plain call's cache is ephemeral on the resolved mapping (`initNodewise`),
invalidated when a collection frees one of its replacements; the registered `BddOperations.NodewiseCompose` references them
and has a private cache.

## Registered operations

The escape hatch from ephemeral invalidation: bind the parameter once, get a **private cache** that
survives alternation between operations. A plain call hands the diagram a freshly built translation on
every invocation; the diagram reads that as a different operator and drops the shared ephemeral cache, so
repeating one operation never reuses an entry.

Int layer: `registerCompose`/`registerComposeSimplify` on both `Bdd` and `MtBdd`, `Bdd#registerExists`,
`registerAndExists` and `registerAndExistsSimplify` (one `BddOperations.Exists`, unary the exists, binary the
and-exists, ternary its simplifying form, each with its private cache; an unused cache is never allocated, `CacheBase` sizing lazily), and `MtBdd#registerApply`/`registerApplySimplify`/`registerMap`/`registerMapSimplify`/`registerMapBoolean`/
`registerApplyBoolean`, returning `RegisteredOperation.Unary`/`Binary`/`Ternary`. The BDD compose forms hold
no cache: the composition's is keyed on its whole context (above), so they only resolve and protect the mapping
once - except a node-by-node one's (`BddOperations.NodewiseCompose`, above), keyed on the node alone. Only the compose forms bind *nodes*, and those own them: `ProtectedOperation` + `ProtectionTracker` reference the operands on
construction and drop them via a `PhantomReference` when released or unreachable. The rest bind a lambda or
a `NatSet` and hold nothing. Their private caches start at an eighth of an ephemeral cache's size and grow
with their use up to it (`CacheBase.growWithUsageUpTo`, set with the floor on every table growth: `putBin` asks
to double once the puts since the last clear pass three quarters of the size, and the next store does - so a cache
grows within one long operation too; a store therefore may replace a cache's arrays, so a parallel array is indexed
by the bin `storeKeys` returned, never written as `values[storeKeys(...)]`, which takes the old array),
counting into their kind's total instead of their own statistics, of which they keep only the counts since
the last clear that their growth and pruning read (`CacheBase.countInto`; per diagram a fixed set of kinds, reported as
`cache_registered_<kind>_*`: the counts of every handle's cache, past ones included, the caches created, their
growths, the largest size and how many caches asked for more than their limit - whether the limit is the right one), and each registers its prune hook with *every* table its entries can name — the two boolean-valued
MTBDD operations always straddle both, since their results are BDD functions.

Object layer: handle types bound once and applied repeatedly — `BddMap.Operator<V>` (plus `applyIn`),
`BddMap.VariableReplacer` (plus `replaceIn`), `BddMap.Mapper<V, O>`, `BddMap.Combiner<V, W, O>`,
`BddMap.Selector<V>`, `BddMap.Relation<V>`, `BddSet.Quantifier`, `BddSet.RelationalProduct` (plus `applyIn`, backed by
a second registration, `registerAndExistsSimplify`, which its `release()` releases too), `BddSet.VariableReplacer`.

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
