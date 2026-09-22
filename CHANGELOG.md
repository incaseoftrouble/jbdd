# 0.x

## 0.7 

### 0.7.0 (2026-XX-XX)

* Implemented complement edges
* Implemented MDDs (Function with boolean values but n-valued domains for their variables)
* Significant renaming / restructuring of the API: Distinguish between boolean function (what a BDD node abstracts) and internal structure (nodes) to reduce mixing of these now different concepts
* Remove iterative implementation: On some benchmarks about ~10% slower, tedious to maintain, and increasing stack size is cheap
* Separate out the node table structure to have a unified base for BDDs, MTBDDs, MDDs, etc.
* Slightly improved usability of automatic reference management
* New methods:
  * `andNot`
  * `forall` quantification, also on `BddSet`
  * `forEachPath` now has a version with `support` as parameter (replacing the previous `highestVariable`)
  * `anyPathMatches`: check if any path matches a given predicate 
  * `intersects`: check if `and(f, g) != FALSE`
  * `simplify`: (also called `constrain`) reduce a function `f` to a given domain `d`, i.e. preserve the values of `f` where `d` is true but otherwise do whatever - on `BddSet` and `BddMap` as well as on the int layer
  * `xyIn`: Perform operation `xy` relative to a given domain `d` (e.g.\ count satisfying assignments of `x` in `d`)
  * `xySimplify`: Perform `simplify(xy(...), g)`, but potentially much faster
  * `DdContext.formatStatistics`: render a statistics map as sorted `key=value` lines
  * `decisionVariable` / `high` / `low` on `BddSet` and `BddMap`: the Shannon decomposition, so a structural recursion needs no node access
  * `BddSet.restrict` and `BddSetFactory.ifThenElse`: the set-layer counterparts of the int-layer operations
  * `implicants` (`BinaryDecisionDiagram` and `BddSet`) and its inverse `of(Cube)`: a cover of a function by cubes, recording a literal only where the set is not monotone in that variable - complement first for a CNF cover
  * `BddSet.split` (and `MtBdd.splitBdd` on the int layer): a set as a `BddMap<BddSet>` over some variables, mapping each of their assignments to the residual it restricts the set to - one recursion; `inverse()` gives the partition by residual
  * `BddMap.inverse`: every value with its domain, in one pass
  * `BddMap.split(variables, destination, residual)`: the split with each residual map transformed on its way into the meta-map, in one traversal
  * `Values.ifThenElse(int variable, ...)`: a single node when the variable comes before both maps, the general construction otherwise
  * `Values.apply(List, BddMapBinaryOperator)`: an associative operator folded over many maps in one traversal
  * `allMatch` (`MtBdd` and `BddMap`) and `BddMap.agreesWith`: whether a predicate holds between two functions everywhere, without building the set where it does and stopping at the first counterexample - semantic equality across value numberings
  * `registerXy`: Bind an operation's parameter once and get a private cache for it, surviving alternation with other operations - `compose` / `composeSimplify` (BDD and MTBDD), `exists` (BDD), `apply` / `applySimplify` / `map` / `mapSimplify` / `mapBoolean` / `applyBoolean` (MTBDD), plus the object-layer handles over them
* `BinaryPath` is now `Cube`, the type of every conjunction of literals (paths, implicants, `restrict`, `BddSetFactory.of`), with the usual cube operations (`implies`, `intersection`, `with`, `restrictedTo`, `antichain`, ...); `BddSetFactory.union(Iterable<Cube>)`
* Cubes (`of(Cube)`, `conjunction`, `disjunction`, `BddSetFactory.of`) are built bottom-up in linear time without recursion; they were quadratic and recursed as deep as the cube was long
* Node tables report the share of their statistics spent inside reorderings (`node_table_reorder_*`)
* The n-ary MTBDD `apply` is memoised on its operand tuple; it walked every combination of paths before
* A numbering no longer forgets a value it assigned since the last collection when that collection reclaims a dead, unrelated allocation of the same raw terminal (it could lose a value handed out before the map using it was built)
* `restrict` walks the part of the restriction fixing the topmost decisions without building or caching anything, and keys its cache on the remaining literals only - so restrictions differing in a fixed prefix share it
* `and(int[])` / `or(int[])` on the int layer: n-ary conjunction and disjunction, folding deepest top level first and stopping at the absorbing constant; `BddSetFactory.union` / `intersection` over many sets delegate to them
* Assertions auditing a whole table or cache only run with the system property `JBDD_COSTLY_ASSERTIONS` (set by JBDD's own tests); `-ea` alone checks the entries actually used
* Significant improvement of `compose` / `ifThenElse` in certain cases (e.g.\ identifying constant replacements)
* Preserve cached values when possible (should provide notable improvements on some workloads)

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