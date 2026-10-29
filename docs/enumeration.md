# Enumeration: cursors, not iterators

The design and the why; the rules a session must hold are `CLAUDE.md` §3.

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
