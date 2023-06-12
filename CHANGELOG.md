# 0.x

## 0.6.x

# 0.6.1

* Made benchmarks more consistent across runs
* Improvements to `exists` / caching for quantification queries
* Native support for `forall` queries

# 0.6.0 (2023-06-12)

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

 + Initial release.