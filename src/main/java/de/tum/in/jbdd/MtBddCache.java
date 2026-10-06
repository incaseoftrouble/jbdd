/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2026 Tobias Meggendorfer.
 *
 * JBDD is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * JBDD is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with JBDD. If not, see <http://www.gnu.org/licenses/>.
 */
package de.tum.in.jbdd;

import static de.tum.in.jbdd.MtBddCache.Slot.BDD;
import static de.tum.in.jbdd.MtBddCache.Slot.MTBDD;
import static de.tum.in.jbdd.MtBddCache.Slot.PLAIN;
import static java.util.Map.entry;

import de.tum.in.jbdd.collections.MutableNatSet;
import de.tum.in.jbdd.collections.NatSet;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

/**
 * Operation cache for {@link MtBddImpl}, mirroring {@link BooleanCache}'s design but its own family (not extending
 * {@link BooleanCache} itself - only the shared {@link CacheBase.IntKeys} hashing/growth/prune engine), since every entry
 * here potentially straddles two independent {@link NodeTable}s: {@code mtbdd}'s own and the companion
 * {@code bdd}'s. Every concrete cache below knows, per key/result slot, which of the two tables that slot
 * belongs to (a {@code BooleanCache} entry only ever has one). Validity is checked reactively (on prune,
 * not on lookup) exactly like {@link BooleanCache} - a stale entry just reads back as a miss once the
 * relevant table's {@code onBddGarbageCollection()}/{@code onMtBddGarbageCollection()} sweep removes it,
 * nothing proactively pins cached results. {@code apply}/{@code map}/{@code mapBoolean} are additionally
 * ephemeral on their opaque operator/predicate argument - see {@code initApply}/{@code initMap}/
 * {@code initMapBoolean}.
 */
@SuppressWarnings({"PMD.TooManyFields", "PMD.CouplingBetweenObjects"})
final class MtBddCache implements VariableOrderObserver, StatisticsReporter {
    private static final int[] EMPTY_INT_ARRAY = new int[0];
    private static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];

    private final MtBddImpl mtbdd;
    private final BddImpl bdd;

    private int applyReuseCount = 0;
    private int mapReuseCount = 0;
    private int mapBooleanReuseCount = 0;
    private int applyBooleanReuseCount = 0;
    private int allMatchReuseCount = 0;
    private int composeReuseCount = 0;
    private int restrictReuseCount = 0;
    private int reachesMatchReuseCount = 0;
    private int countReuseCount = 0;

    private final BinaryCache applyCache;
    private final TernaryCache applySimplifyCache;
    private @Nullable MtBddBinaryOperator currentApplyOp;
    private final UnaryCache mapCache;
    private final BinaryCache mapSimplifyCache;
    private @Nullable IntUnaryOperator currentMapOp;
    private final UnaryCache mapBooleanCache;
    private @Nullable IntPredicate currentMapBooleanPredicate;
    private final BinaryCache agreementCache;
    private final BinaryCache applyBooleanCache;
    private @Nullable MtBddBinaryPredicate currentApplyBooleanPredicate;
    private final BinaryToBooleanCache allMatchCache;
    private @Nullable MtBddBinaryPredicate currentAllMatchPredicate;
    private final BinaryCache simplifyCache;
    private final BinaryCache constrainCache;
    private final TernaryCache updateCache;
    private final TernaryCache ifThenElseCache;
    private final UnaryCache composeCache;
    private final BinaryCache composeSimplifyCache;
    private int[] composeArray = EMPTY_INT_ARRAY;
    private final UnaryCache restrictCache;
    private Cube restriction = Cube.empty();
    private final MutableNatSet noValueMatchesCache = MutableNatSet.create();
    private @Nullable IntPredicate currentAnyValueMatches;
    private final UnaryCache splitCache;
    private final TernaryCache splitCombineCache;
    private final UnaryCache splitBddCache;
    private final TernaryCache splitBddCombineCache;
    private final MtbddNodesToIntCache cartesianProductCache;
    private final MtbddNodesToIntCache naryApplyCache;
    private @Nullable MtBddNaryOperator currentNaryApplyOp;
    private final UnaryToObjectCache<BigInteger> satisfactionCache;
    private @Nullable IntPredicate currentCountPredicate;

    private final Map<String, MtbddCacheStorage> caches;

    private int lookupHash;

    MtBddCache(MtBddImpl mtbdd, BddImpl bdd) {
        this.mtbdd = mtbdd;
        this.bdd = bdd;
        this.lookupHash = -1;

        applyCache = new BinaryCache(mtbdd, bdd, MTBDD, MTBDD, MTBDD);
        applySimplifyCache = new TernaryCache(mtbdd, bdd, MTBDD, MTBDD, BDD, MTBDD);
        mapCache = new UnaryCache(mtbdd, bdd, MTBDD, MTBDD);
        mapSimplifyCache = new BinaryCache(mtbdd, bdd, MTBDD, BDD, MTBDD);
        mapBooleanCache = new UnaryCache(mtbdd, bdd, MTBDD, BDD);
        agreementCache = new BinaryCache(mtbdd, bdd, MTBDD, MTBDD, BDD);
        applyBooleanCache = new BinaryCache(mtbdd, bdd, MTBDD, MTBDD, BDD);
        allMatchCache = new BinaryToBooleanCache(mtbdd, bdd);
        simplifyCache = new BinaryCache(mtbdd, bdd, MTBDD, BDD, MTBDD);
        constrainCache = new BinaryCache(mtbdd, bdd, MTBDD, BDD, MTBDD);
        updateCache = new TernaryCache(mtbdd, bdd, MTBDD, BDD, PLAIN, MTBDD);
        ifThenElseCache = new TernaryCache(mtbdd, bdd, BDD, MTBDD, MTBDD, MTBDD);
        restrictCache = new UnaryCache(mtbdd, bdd, MTBDD, MTBDD);
        splitCache = new UnaryCache(mtbdd, bdd, MTBDD, MTBDD);
        splitCombineCache = new TernaryCache(mtbdd, bdd, MTBDD, MTBDD, PLAIN, MTBDD);
        splitBddCache = new UnaryCache(mtbdd, bdd, BDD, MTBDD);
        splitBddCombineCache = new TernaryCache(mtbdd, bdd, MTBDD, MTBDD, PLAIN, MTBDD);
        cartesianProductCache = new MtbddNodesToIntCache(mtbdd, bdd);
        naryApplyCache = new MtbddNodesToIntCache(mtbdd, bdd);
        satisfactionCache = new UnaryToObjectCache<>(mtbdd, bdd);

        // Like BooleanCache's own composeValid: composeArray holds Bdd function ids the caller supplies,
        // not values MtBddCache itself keeps alive - a Bdd-side GC pass can invalidate one of them without
        // changing the array's own values, which initCompose's plain mismatch check (below) would then
        // miss entirely, silently reusing entries built under a since-invalidated mapping. composeCache's
        // own key (just the mtbdd node) doesn't encode composeArray at all, so there's no way to prune
        // selectively here - any dependency going stale invalidates the whole cache.
        BooleanSupplier composeValid = () -> {
            for (int composeNode : composeArray) {
                if (!bdd.isValidFunction(composeNode)) {
                    return false;
                }
            }
            return true;
        };
        composeCache = new UnaryCache(mtbdd, bdd, composeValid, MTBDD, MTBDD);
        composeSimplifyCache = new BinaryCache(mtbdd, bdd, composeValid, MTBDD, BDD, MTBDD);

        caches = Map.ofEntries(
                entry("apply", applyCache),
                entry("apply_simplify", applySimplifyCache),
                entry("map", mapCache),
                entry("map_simplify", mapSimplifyCache),
                entry("compose_simplify", composeSimplifyCache),
                entry("map_boolean", mapBooleanCache),
                entry("agreement", agreementCache),
                entry("apply_boolean", applyBooleanCache),
                entry("all_match", allMatchCache),
                entry("simplify", simplifyCache),
                entry("constrain", constrainCache),
                entry("update", updateCache),
                entry("ite", ifThenElseCache),
                entry("compose", composeCache),
                entry("restrict", restrictCache),
                entry("split", splitCache),
                entry("split_combine", splitCombineCache),
                entry("split_bdd", splitBddCache),
                entry("split_bdd_combine", splitBddCombineCache),
                entry("cartesian_product", cartesianProductCache),
                entry("nary_apply", naryApplyCache),
                entry("count", satisfactionCache));

        tableSizeChanged(0, NatSet.of());
    }

    BinaryCache applyCache() {
        return applyCache;
    }

    TernaryCache applySimplifyCache() {
        return applySimplifyCache;
    }

    UnaryCache mapCache() {
        return mapCache;
    }

    BinaryCache mapSimplifyCache() {
        return mapSimplifyCache;
    }

    UnaryCache mapBooleanCache() {
        return mapBooleanCache;
    }

    UnaryCache composeCache() {
        return composeCache;
    }

    BinaryCache composeSimplifyCache() {
        return composeSimplifyCache;
    }

    int lookupHash() {
        return lookupHash;
    }

    // Size and invalidation

    void tableSizeChanged(int reclaimedNodes, NatSet reclaimedValues) {
        onMultiTerminalNodesInvalidated(reclaimedNodes, reclaimedValues);

        int size = mtbdd.tableSize() / bdd.configuration().cacheSizeDivider();

        mapBooleanCache.grow(size / 2);
        satisfactionCache.grow(size / 2);

        agreementCache.grow(size);
        simplifyCache.grow(size);
        constrainCache.grow(size);
        updateCache.grow(size);
        ifThenElseCache.grow(size);
        splitCombineCache.grow(size);
        splitBddCombineCache.grow(size);
        applyCache.grow(size);
        applyBooleanCache.grow(size);
        allMatchCache.grow(size);
        applySimplifyCache.grow(size);
        mapCache.grow(size);
        mapSimplifyCache.grow(size);
        composeCache.grow(size);
        composeSimplifyCache.grow(size);
        restrictCache.grow(size);
        splitCache.grow(size);
        splitBddCache.grow(size);
        cartesianProductCache.grow(size);
        naryApplyCache.grow(size);
    }

    void variablesChanged() {
        // See BooleanCache#variablesChanged - assignment counts are keyed on the node alone but their
        // value ranges over [decisionVariable, numberOfVariables), so a new variable invalidates all of
        // them.
        satisfactionCache.invalidate();

        int size = mtbdd.tableSize() / bdd.configuration().cacheSizeDivider();
        satisfactionCache.grow(size / 2);
        composeCache.grow(size);
        composeSimplifyCache.grow(size);
        restrictCache.grow(size);
    }

    /**
     * @see BooleanCache#orderChanged
     */
    @Override
    public void orderChanged(int[] previousVariableToLevel, int[] currentVariableToLevel, NatSet movedVariables) {
        // see BooleanCache#orderChanged
        invalidate();
    }

    @Override
    public void variablesInserted(int level, int count) {
        // As BooleanCache's: nothing moved, but the count did.
        variablesChanged();
    }

    private Collection<MtbddCacheStorage> caches() {
        return caches.values();
    }

    void invalidate() {
        for (MtbddCacheStorage intCache : caches()) {
            intCache.invalidate();
        }
        noValueMatchesCache.clear();
    }

    void onBooleanNodesInvalidated(int invalidatedNodes) {
        if (bdd.isReordering()) {
            // See BooleanCache#onBddNodesInvalidated.
            invalidate();
            return;
        }
        if (invalidatedNodes == 0) {
            return;
        }
        // If we reclaimed a lot of nodes, we won't be able to save much, so don't try
        boolean preserve = invalidatedNodes < bdd.tableSize() / 2;
        for (MtbddCacheStorage cache : caches()) {
            cache.clearInvalidBddNodes(preserve);
        }
    }

    void onMultiTerminalNodesInvalidated(int invalidatedNodes, NatSet reclaimedValues) {
        if (bdd.isReordering()) {
            // See BooleanCache#onBddNodesInvalidated.
            invalidate();
            return;
        }
        if (invalidatedNodes == 0 && reclaimedValues.isEmpty()) {
            return;
        }
        boolean preserve = invalidatedNodes < mtbdd.tableSize() / 2;
        for (MtbddCacheStorage cache : caches()) {
            cache.clearInvalidMtbddNodes(preserve);
        }
        noValueMatchesCache.clear();
    }

    // Ephemeral "current parameter" init

    void initApply(MtBddBinaryOperator op) {
        if (op.equals(currentApplyOp)) {
            applyReuseCount += 1;
            return;
        }
        currentApplyOp = op;
        applyCache.invalidate();
        applySimplifyCache.invalidate();
    }

    void initMap(IntUnaryOperator op) {
        if (op.equals(currentMapOp)) {
            mapReuseCount += 1;
            return;
        }
        currentMapOp = op;
        mapCache.invalidate();
        mapSimplifyCache.invalidate();
    }

    void initApplyBoolean(MtBddBinaryPredicate predicate) {
        if (predicate.equals(currentApplyBooleanPredicate)) {
            applyBooleanReuseCount += 1;
            return;
        }
        currentApplyBooleanPredicate = predicate;
        applyBooleanCache.invalidate();
    }

    void initAllMatch(MtBddBinaryPredicate predicate) {
        if (predicate.equals(currentAllMatchPredicate)) {
            allMatchReuseCount += 1;
            return;
        }
        currentAllMatchPredicate = predicate;
        allMatchCache.invalidate();
    }

    void initMapBoolean(IntPredicate predicate) {
        if (predicate.equals(currentMapBooleanPredicate)) {
            mapBooleanReuseCount += 1;
            return;
        }
        currentMapBooleanPredicate = predicate;
        mapBooleanCache.invalidate();
    }

    /**
     * Points the compose caches at {@code replacements}, keeping their contents only if that mapping is
     * the one they were filled under.
     *
     * <p>Sameness is judged on the whole resolved mapping, not on a prefix of it. Truncating to what the
     * recursion can reach would have to be by <em>index</em>, and the cut-off available here is a
     * <em>level</em> - the same thing only while nothing has reordered. Once they part company a replaced
     * variable can have a small level and a large index, so its entry falls outside the prefix: a differing
     * mapping compares equal and the cache is reused for it, and the dependency check below never looks at
     * that replacement, so the cache is not invalidated when it dies. Both give wrong answers rather than
     * stale ones. One copy of an array at most as long as the variable count is the price of not having to
     * reason about that; the cut-off stays a level, but only where it belongs, as the recursion's own bound.
     *
     * <p>Matching contents is still not enough on its own. The entries are function ids the caller supplies,
     * and an ephemeral compose protects them only for the duration of one call, so between two calls a
     * replacement can be collected and its slot handed to an unrelated function - the new mapping then
     * compares equal while denoting something else, and validity cannot see it, a recycled id being a
     * perfectly valid function. So {@link #onBooleanNodesInvalidated} forgets the mapping whenever an id
     * came free, which is precisely when that can happen; the empty array is a sound sentinel because a
     * mapping that replaces nothing never gets here.
     */
    void initCompose(int[] replacements) {
        assert replacements.length > 0 : "A mapping replacing nothing must not reach the compose caches";
        if (Arrays.equals(composeArray, replacements)) {
            composeReuseCount += 1;
            return;
        }
        this.composeArray = replacements.clone();
        composeCache.invalidate();
        composeSimplifyCache.invalidate();
    }

    void initRestrict(Cube restriction) {
        if (restriction.equals(this.restriction)) {
            restrictReuseCount += 1;
            return;
        }
        // A copy: the caller's cube may be a walk's working state.
        this.restriction = restriction.copy();
        restrictCache.invalidate();
    }

    void initAnyValueMatches(@Nullable IntPredicate predicate) {
        if (predicate == null) {
            return;
        }
        if (predicate.equals(currentAnyValueMatches)) {
            reachesMatchReuseCount += 1;
            // Adopt the new instance even on a reuse, so that isCurrentAnyValueMatches can be a plain
            // reference check.
            currentAnyValueMatches = predicate;
            return;
        }
        currentAnyValueMatches = predicate;
        noValueMatchesCache.clear();
    }

    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    boolean isCurrentAnyValueMatches(IntPredicate predicate) {
        // Reference equality on purpose: initAnyValueMatches adopts the instance it was handed, so this
        // asks "is the memo the one this caller populated", not "is it an equivalent predicate".
        return predicate == currentAnyValueMatches;
    }

    /**
     * Like {@link #initSplit}, and for the same reason: {@code cartesianProduct}'s results are indices into
     * a bijection built fresh per call, so an entry from a previous call names a tuple in a numbering that
     * no longer exists. Within one call the memo is sound - interning is idempotent, so a repeated operand
     * tuple always yields the same index.
     */
    void initCartesianProduct() {
        cartesianProductCache.invalidate();
    }

    void initNaryApply(MtBddNaryOperator op) {
        if (op.equals(currentNaryApplyOp)) {
            return;
        }
        currentNaryApplyOp = op;
        naryApplyCache.invalidate();
    }

    void initSplitBdd() {
        splitBddCache.invalidate();
        splitBddCombineCache.invalidate();
    }

    void initSplit() {
        splitCache.invalidate();
        splitCombineCache.invalidate();
    }

    void initCount(IntPredicate predicate) {
        if (predicate.equals(currentCountPredicate)) {
            countReuseCount += 1;
            return;
        }
        currentCountPredicate = predicate;
        satisfactionCache.invalidate();
    }

    // Lookup

    BinaryCache agreementCache() {
        return agreementCache;
    }

    BinaryCache applyBooleanCache() {
        return applyBooleanCache;
    }

    BinaryToBooleanCache allMatchCache() {
        return allMatchCache;
    }

    int lookupBinaryToBdd(BinaryCache cache, int function1, int function2) {
        assert mtbdd.isValidFunction(function1) && mtbdd.isValidFunction(function2);
        int result = cache.lookup(function1, function2);
        lookupHash = cache.lookupHash();
        return result;
    }

    void putBinaryToBdd(BinaryCache cache, int hash, int function1, int function2, int result) {
        assert mtbdd.isValidFunction(function1) && mtbdd.isValidFunction(function2) && bdd.isValidFunction(result);
        cache.put(hash, function1, function2, result);
    }

    int lookupSimplify(int function, int domain) {
        assert mtbdd.isValidFunction(function) && bdd.isValidFunction(domain);
        int result = simplifyCache.lookup(function, domain);
        lookupHash = simplifyCache.lookupHash();
        return result;
    }

    int lookupConstrain(int function, int domain) {
        assert mtbdd.isValidFunction(function) && bdd.isValidFunction(domain);
        int result = constrainCache.lookup(function, domain);
        lookupHash = constrainCache.lookupHash();
        return result;
    }

    int lookupUpdate(int function, int bddAssignments, int value) {
        assert mtbdd.isValidFunction(function) && bdd.isValidFunction(bddAssignments);
        int result = updateCache.lookup(function, bddAssignments, value);
        lookupHash = updateCache.lookupHash();
        return result;
    }

    int lookupIfThenElse(int bddIf, int mtbddThen, int mtbddElse) {
        assert bdd.isValidFunction(bddIf) && mtbdd.isValidFunction(mtbddThen) && mtbdd.isValidFunction(mtbddElse);
        int result = ifThenElseCache.lookup(bddIf, mtbddThen, mtbddElse);
        lookupHash = ifThenElseCache.lookupHash();
        return result;
    }

    int lookupRestrict(int function) {
        assert mtbdd.isValidFunction(function);
        int result = restrictCache.lookup(function);
        lookupHash = restrictCache.lookupHash();
        return result;
    }

    boolean lookupNoValueMatches(int node) {
        assert mtbdd.isValidFunction(node);
        return noValueMatchesCache.contains(node);
    }

    int lookupSplit(int node) {
        assert mtbdd.isValidFunction(node);
        int result = splitCache.lookup(node);
        lookupHash = splitCache.lookupHash();
        return result;
    }

    int lookupSplitBdd(int bddFunction) {
        assert bdd.isValidNonConstantFunction(bddFunction);
        int result = splitBddCache.lookup(bddFunction);
        lookupHash = splitBddCache.lookupHash();
        return result;
    }

    void putSplitBdd(int hash, int bddFunction, int result) {
        assert bdd.isValidNonConstantFunction(bddFunction) && mtbdd.isValidFunction(result);
        splitBddCache.put(hash, bddFunction, result);
    }

    int lookupSplitBddCombine(int lowFragment, int highFragment, int level) {
        assert mtbdd.isValidFunction(lowFragment) && mtbdd.isValidFunction(highFragment);
        int result = splitBddCombineCache.lookup(lowFragment, highFragment, level);
        lookupHash = splitBddCombineCache.lookupHash();
        return result;
    }

    void putSplitBddCombine(int hash, int lowFragment, int highFragment, int level, int result) {
        assert mtbdd.isValidFunction(lowFragment) && mtbdd.isValidFunction(highFragment);
        splitBddCombineCache.put(hash, lowFragment, highFragment, level, result);
    }

    int lookupSplitCombine(int lowFragment, int highFragment, int variable) {
        assert mtbdd.isValidFunction(lowFragment) && mtbdd.isValidFunction(highFragment);
        int result = splitCombineCache.lookup(lowFragment, highFragment, variable);
        lookupHash = splitCombineCache.lookupHash();
        return result;
    }

    int lookupNaryApply(int[] functions) {
        assert Arrays.stream(functions).allMatch(mtbdd::isValidFunction);
        int result = naryApplyCache.lookup(functions);
        lookupHash = naryApplyCache.lookupHash();
        return result;
    }

    // Retains functions as the key, as putCartesianProduct does.
    void putNaryApply(int hash, int[] functions, int result) {
        assert Arrays.stream(functions).allMatch(mtbdd::isValidFunction) && mtbdd.isValidFunction(result);
        naryApplyCache.put(hash, functions, result);
    }

    int lookupCartesianProduct(int[] functions) {
        assert Arrays.stream(functions).allMatch(mtbdd::isValidFunction);
        int result = cartesianProductCache.lookup(functions);
        lookupHash = cartesianProductCache.lookupHash();
        return result;
    }

    @Nullable
    BigInteger lookupCount(int node) {
        assert mtbdd.isValidFunction(node);
        BigInteger result = satisfactionCache.lookup(node);
        lookupHash = satisfactionCache.lookupHash();
        return result;
    }

    // Put

    void putSimplify(int hash, int function, int domain, int result) {
        assert mtbdd.isValidFunction(function) && bdd.isValidFunction(domain) && mtbdd.isValidFunction(result);
        simplifyCache.put(hash, function, domain, result);
    }

    void putConstrain(int hash, int function, int domain, int result) {
        assert mtbdd.isValidFunction(function) && bdd.isValidFunction(domain) && mtbdd.isValidFunction(result);
        constrainCache.put(hash, function, domain, result);
    }

    void putUpdate(int hash, int function, int bddAssignments, int value, int result) {
        assert mtbdd.isValidFunction(function) && bdd.isValidFunction(bddAssignments) && mtbdd.isValidFunction(result);
        updateCache.put(hash, function, bddAssignments, value, result);
    }

    void putIfThenElse(int hash, int bddIf, int mtbddThen, int mtbddElse, int result) {
        assert bdd.isValidFunction(bddIf)
                && mtbdd.isValidFunction(mtbddThen)
                && mtbdd.isValidFunction(mtbddElse)
                && mtbdd.isValidFunction(result);
        ifThenElseCache.put(hash, bddIf, mtbddThen, mtbddElse, result);
    }

    void putRestrict(int hash, int function, int result) {
        assert mtbdd.isValidFunction(function) && mtbdd.isValidFunction(result);
        restrictCache.put(hash, function, result);
    }

    void putSplit(int hash, int node, int result) {
        assert mtbdd.isValidFunction(node) && mtbdd.isValidFunction(result);
        splitCache.put(hash, node, result);
    }

    void putSplitCombine(int hash, int lowFragment, int highFragment, int variable, int result) {
        assert mtbdd.isValidFunction(lowFragment)
                && mtbdd.isValidFunction(highFragment)
                && mtbdd.isValidFunction(result);
        splitCombineCache.put(hash, lowFragment, highFragment, variable, result);
    }

    /**
     * Note that {@code functions} is retained as the entry's key, so the caller must hand over an array
     * nobody mutates afterwards - the {@code cartesianProduct} recursion rewrites its operand array in
     * place while descending.
     */
    void putCartesianProduct(int hash, int[] functions, int result) {
        assert Arrays.stream(functions).allMatch(mtbdd::isValidFunction) && mtbdd.isValidFunction(result);
        cartesianProductCache.put(hash, functions, result);
    }

    void putCount(int hash, int node, BigInteger result) {
        assert mtbdd.isValidFunction(node);
        satisfactionCache.put(hash, node, result);
    }

    void markNoValueMatches(int node) {
        assert mtbdd.isValidFunction(node);
        noValueMatchesCache.set(node);
    }

    // Utility

    @Override
    public void report(StatisticsReport report, StatisticsDetail detail) {
        caches.forEach((name, cache) -> cache.report(report.about("cache_", name), detail));
        reportReuse(report, "apply", applyReuseCount);
        reportReuse(report, "map", mapReuseCount);
        reportReuse(report, "map_boolean", mapBooleanReuseCount);
        reportReuse(report, "apply_boolean", applyBooleanReuseCount);
        reportReuse(report, "all_match", allMatchReuseCount);
        reportReuse(report, "compose", composeReuseCount);
        reportReuse(report, "restrict", restrictReuseCount);
        reportReuse(report, "reaches_match", reachesMatchReuseCount);
        reportReuse(report, "count", countReuseCount);
    }

    private static void reportReuse(StatisticsReport report, String cache, int reuseCount) {
        report.about("cache_", cache).put(CacheStatistics.REUSE_COUNT, reuseCount);
    }

    interface MtbddCacheStorage extends StatisticsReporter {
        void invalidate();

        void clearInvalidMtbddNodes(boolean preserve);

        void clearInvalidBddNodes(boolean preserve);
    }

    /** What a slot of a bin holds - it decides which collection can make the entry stale. */
    enum Slot {
        MTBDD,
        BDD,
        /** A plain number, such as a variable or a value index: never stale. */
        PLAIN
    }

    /**
     * The int-keyed caches over both diagrams: which slots of a bin (keys, then the result if it is kept in the bin)
     * hold nodes of which diagram is all that tells them apart, and it decides the validity checks.
     */
    abstract static class IntCache extends CacheBase.IntKeys implements MtbddCacheStorage {
        final MtBddImpl mtbdd;
        final BddImpl bdd;
        private final int[] bddSlots;
        private final int[] mtbddSlots;

        IntCache(MtBddImpl mtbdd, BddImpl bdd, int keyCount, BooleanSupplier cacheDependenciesValid, Slot... slots) {
            super(keyCount, slots.length, cacheDependenciesValid);
            this.mtbdd = mtbdd;
            this.bdd = bdd;
            this.bddSlots = slotsOf(slots, BDD);
            this.mtbddSlots = slotsOf(slots, MTBDD);
        }

        private static int[] slotsOf(Slot[] slots, Slot kind) {
            return IntStream.range(0, slots.length)
                    .filter(i -> slots[i] == kind)
                    .toArray();
        }

        @Override
        protected boolean isValid(int binStart) {
            return isValidBdd(binStart) && isValidMtbdd(binStart);
        }

        /** Checks only this entry's {@code Bdd}-side slots (key and/or result); {@code true} if none. */
        protected boolean isValidBdd(int binStart) {
            for (int slot : bddSlots) {
                if (!bdd.isValidFunction(cache[binStart + slot])) {
                    return false;
                }
            }
            return true;
        }

        /** Checks only this entry's {@code MtBddImpl}-side slots (key and/or result). */
        protected boolean isValidMtbdd(int binStart) {
            for (int slot : mtbddSlots) {
                if (!mtbdd.isValidFunction(cache[binStart + slot])) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void clearInvalidBddNodes(boolean attemptPruning) {
            prune(attemptPruning, this::isValidBdd);
        }

        @Override
        public void clearInvalidMtbddNodes(boolean attemptPruning) {
            prune(attemptPruning, this::isValidMtbdd);
        }
    }

    /** One key to an int result kept in the bin, e.g. MTBDD to MTBDD ({@code map}) or to BDD ({@code mapBoolean}). */
    static final class UnaryCache extends IntCache {
        UnaryCache(MtBddImpl mtbdd, BddImpl bdd, Slot key, Slot result) {
            this(mtbdd, bdd, () -> true, key, result);
        }

        UnaryCache(MtBddImpl mtbdd, BddImpl bdd, BooleanSupplier cacheDependenciesValid, Slot key, Slot result) {
            super(mtbdd, bdd, 1, cacheDependenciesValid, key, result);
        }

        int lookup(int key) {
            return resultIn(findBin(key));
        }

        void put(int hash, int key, int result) {
            storeResult(storeKeys(hash, key), result);
        }
    }

    /** Two keys to an int result kept in the bin, e.g. {@code apply} or {@code simplify}. */
    static final class BinaryCache extends IntCache {
        BinaryCache(MtBddImpl mtbdd, BddImpl bdd, Slot key1, Slot key2, Slot result) {
            this(mtbdd, bdd, () -> true, key1, key2, result);
        }

        BinaryCache(
                MtBddImpl mtbdd,
                BddImpl bdd,
                BooleanSupplier cacheDependenciesValid,
                Slot key1,
                Slot key2,
                Slot result) {
            super(mtbdd, bdd, 2, cacheDependenciesValid, key1, key2, result);
        }

        int lookup(int key1, int key2) {
            return resultIn(findBin(key1, key2));
        }

        void put(int hash, int key1, int key2, int result) {
            storeResult(storeKeys(hash, key1, key2), result);
        }
    }

    /** Three keys to an int result kept in the bin, e.g. {@code ifThenElse} or {@code update}. */
    static final class TernaryCache extends IntCache {
        TernaryCache(MtBddImpl mtbdd, BddImpl bdd, Slot key1, Slot key2, Slot key3, Slot result) {
            super(mtbdd, bdd, 3, () -> true, key1, key2, key3, result);
        }

        int lookup(int key1, int key2, int key3) {
            return resultIn(findBin(key1, key2, key3));
        }

        void put(int hash, int key1, int key2, int key3, int result) {
            storeResult(storeKeys(hash, key1, key2, key3), result);
        }
    }

    /** Two MTBDD keys to a bit - an answer, not a function, so only the keys can go stale. */
    static final class BinaryToBooleanCache extends IntCache {
        static final int MISS = -1;

        private Bits values = new Bits(0);

        BinaryToBooleanCache(MtBddImpl mtbdd, BddImpl bdd) {
            super(mtbdd, bdd, 2, () -> true, MTBDD, MTBDD);
        }

        @Override
        protected void growInto(int newSize, int[] newCache, boolean preserve) {
            Bits newValues = new Bits(newSize);
            if (preserve) {
                rehashInto(newSize, newCache, (oldBin, newBin) -> newValues.set(newBin, values.get(oldBin)));
            }
            this.values = newValues;
        }

        /** {@link #MISS}, or the cached bit as 1 or 0. */
        int lookup(int function1, int function2) {
            int bin = findBin(function1, function2);
            if (bin < 0) {
                return MISS;
            }
            return values.get(bin) ? 1 : 0;
        }

        void put(int hash, int function1, int function2, boolean result) {
            values.set(storeKeys(hash, function1, function2), result);
        }
    }

    @SuppressWarnings("unchecked")
    static final class UnaryToObjectCache<V> extends IntCache {
        private Object[] values = EMPTY_OBJECT_ARRAY;

        UnaryToObjectCache(MtBddImpl mtbdd, BddImpl bdd) {
            super(mtbdd, bdd, 1, () -> true, MTBDD);
        }

        @Override
        protected void growInto(int newSize, int[] newCache, boolean preserve) {
            if (preserve) {
                Object[] newValues = new Object[newSize];
                rehashInto(newSize, newCache, (oldBin, newBin) -> newValues[newBin] = values[oldBin]);
                this.values = newValues;
            } else {
                this.values = new Object[newSize];
            }
        }

        @Nullable
        V lookup(int function) {
            int bin = findBin(function);
            return bin < 0 ? null : (V) values[bin];
        }

        void put(int hash, int function, V result) {
            values[storeKeys(hash, function)] = result;
        }
    }

    static final class MtbddNodesToIntCache extends CacheBase.ObjectKeys<int[]> implements MtbddCacheStorage {
        private int[] values = EMPTY_INT_ARRAY;
        final MtBddImpl mtbdd;
        final BddImpl bdd;
        int lookupHash;

        MtbddNodesToIntCache(MtBddImpl mtbdd, BddImpl bdd) {
            this.mtbdd = mtbdd;
            this.bdd = bdd;
        }

        int lookupHash() {
            return lookupHash;
        }

        @Override
        protected boolean isValid(int binStart) {
            int[] nodes = cache[binStart];
            if (nodes == null) {
                return false;
            }
            if (!mtbdd.isValidFunction(values[binStart])) {
                return false;
            }
            for (int node : nodes) {
                if (!mtbdd.isValidFunction(node)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        protected int[][] newArray(int size) {
            return new int[size][];
        }

        @Override
        public void clearInvalidBddNodes(boolean attemptPruning) {
            // Purely mtbbd nodes
        }

        @Override
        public void clearInvalidMtbddNodes(boolean attemptPruning) {
            prune(attemptPruning, this::isValid);
        }

        @Override
        protected int hashOf(int[] key) {
            return Arrays.hashCode(key);
        }

        @Override
        protected void growInto(int newSize, int[][] newCache, boolean preserve) {
            if (preserve) {
                int[] newValues = new int[newSize];
                rehashInto(newSize, newCache, (oldBin, newBin) -> newValues[newBin] = values[oldBin]);
                this.values = newValues;
            } else {
                this.values = new int[newSize];
            }
        }

        int lookup(int[] key) {
            ensureValid();
            int hash = Arrays.hashCode(key);
            lookupHash = hash;
            int index = binIndex(hash);
            if (Arrays.equals(key, cache[index])) {
                assert isValid(index);
                statistics.hit();
                return values[index];
            }
            statistics.miss();
            return mtbdd.placeholder();
        }

        void put(int hash, int[] key, int result) {
            ensureValid();
            assert hash == Arrays.hashCode(key);
            int index = putBin(hash);
            cache[index] = key;
            values[index] = result;
        }
    }
}
