# 0.x

## 0.7 

### 0.7.0 (2026-XX-XX)

* Implemented complement edges
* Implemented MDDs (Function with boolean values but n-valued domains for their variables)
* Significant renaming / restructuring of the API: Distinguish between boolean function (what a BDD node abstracts) and internal structure (nodes) to reduce mixing of these now different concepts
* Remove iterative implementation: On some benchmarks about ~10% slower, tedious to maintain, and increasing stack size is cheap
* Separate out the node table structure to have a unified base for BDDs, MTBDDs, MDDs, etc.
* Slightly improved usability of automatic reference management; its wrappers are canonical through a primitive-keyed table, so a lookup allocates nothing (`DdContainer.canonicalKey` is a `long`)
* New methods:
  * `andNot`
  * `forall` quantification, also on `BddSet`
  * `forEachPath` now has a version with `support` as parameter (replacing the previous `highestVariable`)
  * `anyPathMatches`: check if any path matches a given predicate 
  * `intersects`: check if `and(f, g) != FALSE`
  * `satisfyingFraction` (`BinaryDecisionDiagram` and `BddSet`): the fraction of all assignments satisfying a function as a best-effort `double` - independent of the number of variables, no `BigInteger` per node, and as precise for small fractions reached through a complement edge as for any other; `satisfyingFractionIn(function, domain)`: the probability that an assignment drawn uniformly from the domain satisfies the function, without building the conjunction, and precise however small the domain
  * `simplify`: (also called `constrain`) reduce a function `f` to a given domain `d`, i.e. preserve the values of `f` where `d` is true but otherwise do whatever - on `BddSet` and `BddMap` as well as on the int layer
  * `xyIn`: Perform operation `xy` relative to a given domain `d` (e.g.\ count satisfying assignments of `x` in `d`)
  * `xySimplify`: Perform `simplify(xy(...), g)`, but potentially much faster
  * `DdContext.formatStatistics`: render a statistics map as sorted `key=value` lines; statistics values are numbers, not strings
  * `decisionVariable` / `high` / `low` on `BddSet` and `BddMap`: the Shannon decomposition, so a structural recursion needs no node access
  * `BddSet.restrict` and `BddSetFactory.ifThenElse`: the set-layer counterparts of the int-layer operations
  * `implicants` (`BddUtil` and `BddSet`) and its inverse `of(Cube)`: a cover of a function by cubes, each cube of a cofactor recording the decision variable only where it does not imply the other cofactor already - complement first for a CNF cover
  * `BddSetFactory.of(expression, ExpressionStructure)`: build a set from a propositional expression of the caller's own type, read through an accessor - one memoized build, n-ary conjunctions and disjunctions stopping at their absorbing operand, with sets the caller already has for subexpressions taken as they are
  * `adopt` (`BinaryDecisionDiagram`, `MultiTerminalDecisionDiagram`, `BddSetFactory`, `Values`): a function of another diagram rebuilt in this one under a variable mapping (and, for maps, a value mapping) - one memoized pass, a single node per source node where the mapping keeps it above its children; the object layer creates missing variables and maps each distinct value once, before the pass, so the value mapping may build anything
  * `BddSet.fold`, `BddSetFactory.fold(roots, folder)` and `BddMap.fold`: a diagram folded into anything of the caller's own, each node once, children first and high before low; a set's folder sees `true` and complements (computed once per node), a map's its values, once each in that order - the folder may build sets and maps
  * `IntIntHashMap` / `IntObjectHashMap`: hash maps keyed by `int` that box nothing - open addressing, every `int` but `Integer.MIN_VALUE` a valid key
  * `NatSet` / `MutableNatSet`: a set of naturals over primitives - a few elements far apart as a sorted array, anything else as words - viewable as a `Set<Integer>` (`boxed()`); `NatSet`'s factories return sets that never change (singletons shared), `MutableNatSet` has `java.util.BitSet`'s mutators; words walked bit by bit (`forEach`, a primitive `iterator()`, `anyMatch`/`allMatch`/`noneMatch`); operands of one representation combined word by word or element by element, without a callback per element or an intermediate copy; a singleton below 64 is a word, arrays kept for sets far apart
  * `NatSet.shifted` / `MutableNatSet.shift`: every element moved by an amount, those that would turn negative dropped
  * `NatSets`: helpers over `NatSet` - mapped copies and views, `int` encodings, a binary counter (`increment`), a power-set `Cursor`
  * A `NatSet` equals other `NatSet`s only and hashes by its words; `boxed()` is a `Set<Integer>` view with `Set`'s equality and hash code
* The API takes and returns `NatSet` where it took and returned `java.util.BitSet`; a fresh result is a `MutableNatSet`, a cached one (`BddSet.support()`) an immutable `NatSet`
* Node tables under memory pressure: a JVM collection (at most once per table size) before memory decides against growing, no growth by less than an eighth, a table that cannot grow collects again only after using half of what it freed, and `OutOfMemoryError` once a collection leaves under a twentieth free - instead of a full mark every few allocations
* `constrain` is `BinaryDecisionDiagram`'s and `MultiTerminalDecisionDiagram`'s: an n-valued variable has no nearest domain value, so an MDD offers `simplify` only
* MTBDD terminal reference counts are `short`s (saturating at 32767) rather than bytes
* The n-ary MTBDD `apply` over two operands keeps the operator's neutral and absorbing values
* Dereferencing the topmost referenced node no longer searches the table downwards for the next one
* Removed `BddConfiguration.logStatisticsOnShutdown()`: its output went through `java.util.logging`, whose own shutdown hook resets the handlers first, so nothing was ever printed - read `DdContext.statistics()` from a shutdown hook of your own instead
* Packages: `Cube`, `Cursor`, `NatSets`, the maps and `NatSet` live in `de.tum.in.jbdd.collections`, `DimacsReader` in `de.tum.in.jbdd.io`; `Cube.ofUnsafe` checks its arguments by assertion only
  * `BddUtil`: what is computed from a `BinaryDecisionDiagram`'s public operations alone - `implicants`, `primeImplicants`, `shortestPath` and a generic `adopt` - as static methods rather than interface defaults
  * `shortestPath` (`BddUtil` and `BddSet`): the path to true with the fewest decisions, the first such in `forEachPath` order - a memoised recursion bounded by the best path found so far, not a walk over every path
  * `primeImplicants` (`BinaryDecisionDiagram` and `BddSet`): all prime implicants, the Blake canonical form - the same cubes under any variable order, where `implicants` is a cover that depends on the diagram
  * `BddSet.split` (and `MtBdd.splitBdd` on the int layer): a set as a `BddMap<BddSet>` over some variables, mapping each of their assignments to the residual it restricts the set to - one recursion; `inverse()` gives the partition by residual
  * `BddMap.inverse`: every value with its domain, in one pass
  * `BddSetFactory.pin`: keep a set's diagram for the factory's lifetime, whatever happens to its objects
  * `BinaryFactoryContext.attachToSets` (an `Attachment<BddSet, A>`): bind a caller's object to every set, built on first request and living as long as the set - a wrapper type stays canonical without a map of its own
  * `BddMap.split(variables, destination, residual)`: the split with each residual map transformed on its way into the meta-map, in one traversal
  * `Values.ifThenElse(int variable, ...)`: a single node when the variable comes before both maps, the general construction otherwise
  * `Values.apply(List, BddMapBinaryOperator)`: an associative operator folded over many maps in one traversal
  * `allMatch` (`MtBdd` and `BddMap`) and `BddMap.agreesWith`: whether a predicate holds between two functions everywhere, without building the set where it does and stopping at the first counterexample - semantic equality across value numberings
  * `registerXy`: Bind an operation's parameter once and get a private cache for it, surviving alternation with other operations - `compose` / `composeSimplify` (BDD and MTBDD), `exists` (BDD), `apply` / `applySimplify` / `map` / `mapSimplify` / `mapBoolean` / `applyBoolean` (MTBDD), plus the object-layer handles over them
* `BinaryPath` is now `Cube`, the type of every conjunction of literals (paths, implicants, `restrict`, `BddSetFactory.of`), with the usual cube operations (`implies`, `intersection`, `with`, `restrictedTo`, `antichain`, ...); `BddSetFactory.union(Iterable<Cube>)`
* The cache classes share their lookups and puts (`CacheBase.IntKeys`); the MTBDD ones are one class per key count, described by which slots hold nodes of which diagram; boolean results are bits
* Caches record the bins written since their last clear while few, and a clear resets only those: an invalidated cache that grew large once no longer costs a full fill per small call (`sparse_clear_count` in the statistics)
* `Cube`'s factory `of` and accessors `assignment()` / `support()` copy, so a cube cannot be changed through them; `ofUnsafe`, `assignmentUnsafe()` and `supportUnsafe()` hand the sets over as they are (`copyAssignment` / `copySupport` are gone)
* Cubes (`of(Cube)`, `conjunction`, `disjunction`, `BddSetFactory.of`) are built bottom-up in linear time without recursion; they were quadratic and recursed as deep as the cube was long
* Node tables report the share of their statistics spent inside reorderings (`node_table_reorder_*`)
* The n-ary MTBDD `apply` is memoised on its operand tuple; it walked every combination of paths before
* `split` / `splitMap` (and `BddSet.split`) protect every value they hand out until their result holds it; a collection in between made the numbering forget a value the result then used - under assertions an error, otherwise a silently merged or missing value
* Dereferencing the last referenced node of a table (an MTBDD table can let go of every node; a BDD table keeps its variables) leaves the table with nothing referenced; its high-water mark stayed on the node, which a collection then freed - under assertions an error, otherwise harmless
* `restrict` walks the part of the restriction fixing the topmost decisions without building or caching anything, and keys its cache on the remaining literals only - so restrictions differing in a fixed prefix share it
* `and(int[])` / `or(int[])` on the int layer: n-ary conjunction and disjunction, folding deepest top level first and stopping at the absorbing constant; `BddSetFactory.union` / `intersection` over many sets delegate to them
* Assertions auditing a whole table or cache only run with the system property `JBDD_COSTLY_ASSERTIONS` (set by JBDD's own tests); `-ea` alone checks the entries actually used
* Significant improvement of `compose` / `ifThenElse` in certain cases (e.g.\ identifying constant replacements)
* The BDD `compose` / `composeSimplify` (and with them `replaceVariables` and the registered replacers) is a joint descent over the function, the domain and the replacements it reads, restricted along the path and cached on that whole tuple: the path reaches the replacements, instead of each branch being composed for both values and one half discarded - exponentially fewer nodes where the replaced variables sit below those their replacements read. The compose, `restrict` and support caches are stable (keyed on their whole context), so a registered BDD compose no longer carries a cache of its own
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