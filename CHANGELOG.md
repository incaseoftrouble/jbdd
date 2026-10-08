# 0.x

## 0.7 

### 0.7.0 (2026-XX-XX)

0.7 is a major rewrite of the library (helped by AI agents).

Diagrams

* Complement edges
* MTBDDs (`MtBdd`) over the BDD's variables
* MDDs (`Mdd`): functions with boolean values over n-valued variables (an MDD offers `simplify` but no `constrain`, since an n-valued variable has no canonical nearest domain value)
* Dynamic variable reordering by sifting, moving a BDD and its MTBDD together (`DdContext`, `DdVariableOrder`: `reorder`, within groups, `reorderTo` a block shape, `reorderToIdentity`, `siftDown`)
* The API distinguishes a boolean function (what a node with its complement flag denotes) from the node holding it, and is renamed and restructured accordingly
* `Cube`: a conjunction of literals - what path walks, `implicants`, `primeImplicants` and `shortestPath` hand out and `restrict` and `BddSetFactory.of` take - with the usual cube operations (`implies`, `intersection`, `with`, `restrictedTo`, `antichain`, ...)
* Only the recursive implementation remains: the iterative one (`BddFactory.buildBddIterative`, `buildBddRecursive`) was about 10% slower on some benchmarks and tedious to maintain, and a large stack is cheap (`-Xss128m`)

Object layer

* `BddMap<V>`: an MTBDD whose values are objects, numbered by a caller-chosen `Values<V>`; maps over different numberings combine value by value
* `BinaryFactoryContext.create(...)` gives a context with its `bddSets()` and `bddMaps()`; it replaces `BddSetFactory.create()`
* `BinaryFactoryContext.attachToSets` (an `Attachment<BddSet, A>`): bind a caller's object to every set, built on first request and living as long as the set
* `BddSetFactory.pin`: keep a set's diagram for the factory's lifetime, whatever happens to its objects
* `BddSetFactory.of(expression, ExpressionStructure)`: build a set from a propositional expression of the caller's own type, read through an accessor, taking sets the caller already has for subexpressions as they are
* `BddSet.fold`, `BddSetFactory.fold(roots, folder)` and `BddMap.fold`: a diagram folded into anything of the caller's own, each node once, children first and high before low; a set's folder sees `true` and complements, a map's its values, once each in that order - the folder may build sets and maps

Collections (`de.tum.in.jbdd.collections`)

* `NatSet` / `MutableNatSet` take the place of `java.util.BitSet` throughout the API: a set of naturals over primitives - a few elements far apart as a sorted array, anything else as words - viewable as a `Set<Integer>` (`boxed()`); `NatSet`'s factories and operations return immutable sets, `MutableNatSet` has `BitSet`'s mutators; a fresh result is a `MutableNatSet`, a cached one (`BddSet.support()`) a `NatSet`.
* `NatSets`: helpers over `NatSet` - mapped copies and views, `int` encodings, a binary counter (`increment`), a power-set `Cursor`, unions and intersections of many sets, equality of two sets on a scope (`equalOn`, `equalOnIntersection`)
* `Cursor` (`valid` / `current` / `advance`) takes the place of `Iterator` for solutions and paths: `current()` is the walk's own state, so a step copies nothing (no need for tracking `hasNext()`, which was costly / complicated for diagrams)
* `IntIntHashMap` / `IntObjectHashMap`: hash maps keyed by `int` that box nothing

New operations

* `andNot`, `forall` (also on `BddSet`), `intersects` (`and(f, g) != FALSE`), `anyPathMatches`
* `andExists` (`BinaryDecisionDiagram`, `BddSet`, registered as `registerAndExists`): the relational product `exists(and(f, g), vars)` in one recursion that never builds the conjunction; `orForall` its dual
* `and(int[])` / `or(int[])`: n-ary conjunction and disjunction; `BddSetFactory.union` / `intersection` over many sets delegate to them
* `simplify` and `constrain`: reduce a function `f` to a given domain `d`, i.e. preserve the values of `f` where `d` is true but otherwise do whatever - `constrain` as the generalized cofactor (on binary diagrams only), `simplify` heuristically; on `BddSet` and `BddMap` as well as on the int layer
* `xyIn`: perform operation `xy` relative to a given domain `d` (e.g.\ count satisfying assignments of `x` in `d`); `xySimplify`: perform `simplify(xy(...), d)`, but potentially much faster
* `satisfyingFraction` (`BinaryDecisionDiagram` and `BddSet`): the fraction of all assignments satisfying a function as a best-effort `double`, independent of the number of variables and precise for small fractions; `satisfyingFractionIn(function, domain)`: the probability that an assignment drawn uniformly from the domain satisfies the function, without building the conjunction
* `registerXy`: bind an operation's parameter once and get a private cache for it, surviving alternation with other operations - `compose` / `composeSimplify` (BDD and MTBDD), `exists` and `andExists` (BDD), `apply` / `applySimplify` / `map` / `mapSimplify` / `mapBoolean` / `applyBoolean` (MTBDD), plus the object-layer handles over them
* `decisionVariable` / `high` / `low` on `BddSet` and `BddMap`: the Shannon decomposition, so a structural recursion needs no node access; `BddSet.restrict` and `BddSetFactory.ifThenElse`
* `BddUtil`, computed from a `BinaryDecisionDiagram`'s public operations alone: `implicants` (also on `BddSet`, with its inverse `of(Cube)`) - a cover by cubes, complement first for a CNF cover; `primeImplicants` - the Blake canonical form, the same cubes under any variable order; `shortestPath` - the path to true with the fewest decisions
* `adopt` (`BinaryDecisionDiagram`, `MultiTerminalDecisionDiagram`, `BddSetFactory`, `Values`): a function of another diagram rebuilt in this one under a variable mapping (and, for maps, a value mapping) - one memoized pass; the object layer creates missing variables and maps each distinct value once, before the pass
* `BddSet.split` (and `MtBdd.splitBdd`): a set as a `BddMap<BddSet>` over some variables, mapping each of their assignments to the residual it restricts the set to; `BddMap.split(variables, destination, residual)` the same for maps, each residual transformed on its way; a split's codomain is exactly the values its meta-function takes
* `BddMap.inverse` (and `MtBdd.invert`): every value with its domain, in one pass
* `Values.ifThenElse(int variable, ...)`, `Values.apply(List, BddMapBinaryOperator)` (an associative operator folded over many maps in one traversal), `cartesianProduct`
* `allMatch` (`MtBdd` and `BddMap`) and `BddMap.agreesWith`: whether a predicate holds between two functions everywhere, stopping at the first counterexample - semantic equality across value numberings

Changed

* `compose` reads its mapping without writing to it; 0.6 resolved placeholders in the caller's array (if the same operation is used multiple times, consider `registerCompose`)
* The BDD `compose` / `composeSimplify` (and with them `replaceVariables` and the registered replacers) builds exponentially fewer nodes where the replaced variables sit below those their replacements read
* `DimacsReader` moved to `de.tum.in.jbdd.io`
* Statistics: `statistics()` (on `DdContext` and `Mdd`, a `StatisticsSource`) is a map of numbers rather than a string, read to a detail - `COUNTERS` only reads the fields kept as the diagrams run (no pass over a table, readable from another thread), `FULL` adds a pass over each table; `describeStatistics()` says what each key means - its kind (counter, gauge, maximum, or a ratio with the keys it is computed from) and a sentence; `Statistics.formatStatistics` renders a map as sorted `key=value` lines
* `BddConfiguration`: `initialSize` / `mtbddInitialSize`, one `cacheSizeDivider` (for 0.6's five per-operation dividers), `keepReorderingStructures`, `useGarbageCollection`, `gcLiveNodeThreshold` (for `minimumFreeNodePercentageAfterGc`) and `name`, which prefixes the statistics keys; tables always double (`growthFactor` is gone); `threadSafetyCheck` is gone, concurrent use being detected under assertions
* Removed `BddConfiguration.logStatisticsOnShutdown()`: its output went through `java.util.logging`, whose own shutdown hook resets the handlers first, so nothing was printed - read `statistics()` from a shutdown hook of your own instead
* A node table the heap no longer lets grow keeps working densely packed and throws `OutOfMemoryError` once a collection frees too little, instead of slowing to a full collection every few allocations
* Assertions auditing a whole table or cache only run with the system property `JBDD_COSTLY_ASSERTIONS` (set by JBDD's own tests); `-ea` alone checks the entries actually used
* New benchmarks, as regression workloads: `NatSetBenchmark` (`jmhNatSet`, the set operations a synthesis tool spends its time in, per shape), `CubeBenchmark` (`jmhCube`), `EnumerationBenchmark` (`jmhEnumeration`, the cursors over four shapes and two orders), `NaryBenchmark` (`jmhNary`), `MtBddBenchmark` (`jmhMtBdd`, the object layer's maps as a synthesis tool uses them), `ReorderBenchmark` (`jmhReorder`) and `RelationalProductBenchmark` (`jmhRelational`)

## 0.6

### 0.6.0 (2023-06-12)

* Major rewrite and simplification of the internal structure, overall ~1.5-2x runtime improvements
* Switched to a dumber, much simpler hash function, which seems to be much faster in practice (another ~2x improvement on several benchmarks)
* Breaking change: True and False node are now negative (in preparation for MTBDDs, where all leafs will be negative)
* Breaking change: Changed some method names (removed `get...` prefix)
* Added several benchmarks and optimized some default values based on that
* Deleted several caches which were close to useless
* Fixed a performance bug in the iterative implementation of `support()`
* Removed a runtime dependency that accidentally slipped in
* Add an implementation of `BddSet` and automatic reference management
* Add spotless for formatting

## 0.5

### 0.5.2 (2020-07-08)

* Maintenance and version bumps

### 0.5.1 (2019-08-19)

* Small fixes and improvements

### 0.5.0 (2019-08-06)

* Added a (mostly) iterative implementation of bdds to alleviate stack overflow problems for deep structures
* Added set views on bdd nodes
* Bump gradle and dependency versions
* Small improvements
* Improve performance in some cases by fixing the hash function
* Renamed some of the public methods

## 0.4.x

### 0.4.0 (2018-05-28)

* Removed synchronization - access should be synchronized on a higher level
* Added a solution iterator

## 0.3.x

### 0.3.2 (2018-02-16)

* Fixed a stupid bug in `createVariables(int)`

### 0.3.1 (2018-02-15)

* Re-add non-null annotations

### 0.3.0 (2018-02-15)

* Added utility methods to `Bdd` (`createVariables(int)` and `getSatisfyingAssignment(int)`).
* A synchronized BDD can now only be obtained via the `BddFactory`.
* `Bdd#support` does not clear the passed BitSet anymore.
* Reordered some code.
* Update build infrastructure, drop `javax.annotations` and SpotBugs (waiting for the checker framework gradle plugin to mature).

## 0.2.x

### 0.2.0 (2017-10-10)

* Improved `forEachMinimalSolution` (don't use a complex iterator, but rather a simple recursion).
* Added an adaption of `forEachMinimalSolution` where additionally the relevant variables of the solution are passed.
* Added `forEachNonEmptyPath`, which is a partial version of the above `forEachMinimalSolution`.
* Upgrade Gradle and the static analysis tools.
* Removed Guava dependency (now only JRE is needed).

## 0.1.x

### 0.1.3 (2017-09-27)

* Fixed a synchronization issue, added some more convenience methods.

### 0.1.2 (2017-07-26)

* Add a simple synchronization wrapper for the Bdd interface.
* Removed the minimal solution iterator, since it can't be synchronized.

### 0.1.1 (2017-06-24)

* Add automated deployment.
* Fixed the package name (`jbdd` instead of `jdd`).

### 0.1.0 (2017-06-23)

* Initial release.
