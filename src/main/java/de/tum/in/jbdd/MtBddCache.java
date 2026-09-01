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

import static java.util.Map.entry;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Operation cache for {@link MtBddImpl}, mirroring {@link BooleanCache}'s design (see
 * {@code MTBDD_CACHE_PLAN.md} for the reasoning) but its own family (not extending {@link BooleanCache}
 * itself - only the shared {@link CacheBase.IntKeys} hashing/growth/prune engine), since every entry
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
final class MtBddCache {
    private static final int[] EMPTY_INT_ARRAY = new int[0];
    private static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];

    private final MtBddImpl mtbdd;
    private final BddImpl bdd;

    private int applyReuseCount = 0;
    private int mapReuseCount = 0;
    private int mapBooleanReuseCount = 0;
    private int applyBooleanReuseCount = 0;
    private int composeReuseCount = 0;
    private int restrictReuseCount = 0;
    private int reachesMatchReuseCount = 0;
    private int countReuseCount = 0;

    private final BinaryToIntCache applyCache;
    private final ApplySimplifyCache applySimplifyCache;
    private @Nullable MtBddBinaryOperator currentApplyOp;
    private final UnaryToIntCache mapCache;
    private final MtbddBddToIntCache mapSimplifyCache;
    private @Nullable IntUnaryOperator currentMapOp;
    private final UnaryToBddCache mapBooleanCache;
    private @Nullable IntPredicate currentMapBooleanPredicate;
    private final BinaryToBddCache agreementCache;
    private final BinaryToBddCache applyBooleanCache;
    private @Nullable MtBddBinaryPredicate currentApplyBooleanPredicate;
    private final MtbddBddToIntCache simplifyCache;
    private final MtbddBddToIntCache constrainCache;
    private final UpdateCache updateCache;
    private final IfThenElseCache ifThenElseCache;
    private final UnaryToIntCache composeCache;
    private final MtbddBddToIntCache composeSimplifyCache;
    private int[] composeArray = EMPTY_INT_ARRAY;
    private final UnaryToIntCache restrictCache;
    private BitSet restrictVariables = new BitSet(0);
    private BitSet restrictValues = new BitSet(0);
    private final BitSet noValueMatchesCache = new BitSet();
    private @Nullable IntPredicate currentAnyValueMatches;
    private final UnaryToIntCache splitCache;
    private final SplitCombineCache splitCombineCache;
    private final MtbddNodesToIntCache cartesianProductCache;
    private final UnaryToObjectCache<BigInteger> satisfactionCache;
    private @Nullable IntPredicate currentCountPredicate;

    private final Map<String, MtbddCacheStorage> caches;

    private int lookupHash;

    MtBddCache(MtBddImpl mtbdd, BddImpl bdd) {
        this.mtbdd = mtbdd;
        this.bdd = bdd;
        this.lookupHash = -1;

        applyCache = new BinaryToIntCache(mtbdd, bdd);
        applySimplifyCache = new ApplySimplifyCache(mtbdd, bdd);
        mapCache = new UnaryToIntCache(mtbdd, bdd);
        mapSimplifyCache = new MtbddBddToIntCache(mtbdd, bdd);
        mapBooleanCache = new UnaryToBddCache(mtbdd, bdd);
        agreementCache = new BinaryToBddCache(mtbdd, bdd);
        applyBooleanCache = new BinaryToBddCache(mtbdd, bdd);
        simplifyCache = new MtbddBddToIntCache(mtbdd, bdd);
        constrainCache = new MtbddBddToIntCache(mtbdd, bdd);
        updateCache = new UpdateCache(mtbdd, bdd);
        ifThenElseCache = new IfThenElseCache(mtbdd, bdd);
        restrictCache = new UnaryToIntCache(mtbdd, bdd);
        splitCache = new UnaryToIntCache(mtbdd, bdd);
        splitCombineCache = new SplitCombineCache(mtbdd, bdd);
        cartesianProductCache = new MtbddNodesToIntCache(mtbdd, bdd);
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
        composeCache = new UnaryToIntCache(mtbdd, bdd, composeValid);
        composeSimplifyCache = new MtbddBddToIntCache(mtbdd, bdd, composeValid);

        caches = Map.ofEntries(
                entry("apply", applyCache),
                entry("apply_simplify", applySimplifyCache),
                entry("map", mapCache),
                entry("map_simplify", mapSimplifyCache),
                entry("compose_simplify", composeSimplifyCache),
                entry("map_boolean", mapBooleanCache),
                entry("agreement", agreementCache),
                entry("apply_boolean", applyBooleanCache),
                entry("simplify", simplifyCache),
                entry("constrain", constrainCache),
                entry("update", updateCache),
                entry("ite", ifThenElseCache),
                entry("compose", composeCache),
                entry("restrict", restrictCache),
                entry("split", splitCache),
                entry("split_combine", splitCombineCache),
                entry("cartesian_product", cartesianProductCache),
                entry("count", satisfactionCache));

        tableSizeChanged(0, BitSets.of());

        if (bdd.configuration().logStatisticsOnShutdown()) {
            Util.registerForCleanupStatistics(mtbdd, bdd.configuration().name());
        }
    }

    BinaryToIntCache applyCache() {
        return applyCache;
    }

    ApplySimplifyCache applySimplifyCache() {
        return applySimplifyCache;
    }

    UnaryToIntCache composeCache() {
        return composeCache;
    }

    MtbddBddToIntCache composeSimplifyCache() {
        return composeSimplifyCache;
    }

    int lookupHash() {
        return lookupHash;
    }

    // Size and invalidation

    void tableSizeChanged(int reclaimedNodes, BitSet reclaimedValues) {
        onMultiTerminalNodesInvalidated(reclaimedNodes, reclaimedValues);

        BddConfiguration configuration = bdd.configuration();
        int size = mtbdd.tableSize();

        int unarySize = size / configuration.mtbddCacheUnaryDivider();
        mapBooleanCache.grow(unarySize);
        satisfactionCache.grow(unarySize);

        int binarySize = size / configuration.mtbddCacheBinaryDivider();
        agreementCache.grow(binarySize);
        simplifyCache.grow(binarySize);
        constrainCache.grow(binarySize);

        int ternarySize = size / configuration.mtbddCacheTernaryDivider();
        updateCache.grow(ternarySize);
        ifThenElseCache.grow(ternarySize);
        splitCombineCache.grow(ternarySize);

        int ephemeralSize = size / configuration.mtbddCacheEphemeralMultiplier();
        applyCache.grow(ephemeralSize);
        applyBooleanCache.grow(ephemeralSize);
        applySimplifyCache.grow(ephemeralSize);
        mapCache.grow(ephemeralSize);
        mapSimplifyCache.grow(ephemeralSize);
        composeCache.grow(ephemeralSize);
        composeSimplifyCache.grow(ephemeralSize);
        restrictCache.grow(ephemeralSize);
        splitCache.grow(ephemeralSize);
        cartesianProductCache.grow(ephemeralSize);
    }

    void variablesChanged() {
        // See BooleanCache#variablesChanged - assignment counts are keyed on the node alone but their
        // value ranges over [decisionVariable, numberOfVariables), so a new variable invalidates all of
        // them.
        satisfactionCache.invalidate();

        BddConfiguration configuration = bdd.configuration();
        satisfactionCache.grow(mtbdd.tableSize() / configuration.mtbddCacheUnaryDivider());

        int ephemeralSize = mtbdd.tableSize() / configuration.mtbddCacheEphemeralMultiplier();
        composeCache.grow(ephemeralSize);
        composeSimplifyCache.grow(ephemeralSize);
        restrictCache.grow(ephemeralSize);
    }

    /**
     * See {@link BooleanCache#levelsSwapped}, which this mirrors, including why it drops everything rather
     * than only what it must. The ones that would survive are {@code apply}, {@code map},
     * {@code map_boolean}, {@code agreement}, {@code update} and {@code ite}, all of which stop at
     * constants and never compare a level. The ones that could not are {@code compose}, {@code restrict}
     * and {@code split} (an early return on a level comparison), {@code split_combine} (a level in its
     * very key), {@code count} (ranges over the variables below the node) and the simplify family (picks
     * a representative by level).
     */
    void levelsSwapped() {
        invalidate();
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
        if (invalidatedNodes == 0) {
            return;
        }
        // See BooleanCache#onBddNodesInvalidated: a freed id is exactly when the mapping can lie.
        composeArray = EMPTY_INT_ARRAY;
        // If we reclaimed a lot of nodes, we won't be able to save much, so don't try
        boolean preserve = bdd.configuration().useCachePreserve() && invalidatedNodes < bdd.tableSize() / 2;
        for (MtbddCacheStorage cache : caches()) {
            cache.clearInvalidBddNodes(preserve);
        }
    }

    void onMultiTerminalNodesInvalidated(int invalidatedNodes, BitSet reclaimedValues) {
        if (invalidatedNodes == 0 && reclaimedValues.isEmpty()) {
            return;
        }
        boolean preserve = bdd.configuration().useCachePreserve() && invalidatedNodes < mtbdd.tableSize() / 2;
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

    void initRestrict(BitSet restrictedVariables, BitSet restrictedVariableValues) {
        if (restrictedVariables.equals(this.restrictVariables)
                && restrictedVariableValues.equals(this.restrictValues)) {
            restrictReuseCount += 1;
            return;
        }
        this.restrictVariables = BitSets.copyOf(restrictedVariables);
        this.restrictValues = BitSets.copyOf(restrictedVariableValues);
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

    int lookupApply(int function1, int function2) {
        assert mtbdd.isValidFunction(function1) && mtbdd.isValidFunction(function2);
        int result = applyCache.lookup(function1, function2);
        lookupHash = applyCache.lookupHash();
        return result;
    }

    int lookupMap(int function) {
        assert mtbdd.isValidFunction(function);
        int result = mapCache.lookup(function);
        lookupHash = mapCache.lookupHash();
        return result;
    }

    int lookupMapSimplify(int function, int domain) {
        assert mtbdd.isValidFunction(function) && bdd.isValidFunction(domain);
        int result = mapSimplifyCache.lookup(function, domain);
        lookupHash = mapSimplifyCache.lookupHash();
        return result;
    }

    int lookupMapBoolean(int function) {
        assert mtbdd.isValidFunction(function);
        int result = mapBooleanCache.lookup(function);
        lookupHash = mapBooleanCache.lookupHash();
        return result;
    }

    BinaryToBddCache agreementCache() {
        return agreementCache;
    }

    BinaryToBddCache applyBooleanCache() {
        return applyBooleanCache;
    }

    int lookupBinaryToBdd(BinaryToBddCache cache, int function1, int function2) {
        assert mtbdd.isValidFunction(function1) && mtbdd.isValidFunction(function2);
        int result = cache.lookup(function1, function2);
        lookupHash = cache.lookupHash();
        return result;
    }

    void putBinaryToBdd(BinaryToBddCache cache, int hash, int function1, int function2, int result) {
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

    int lookupCompose(int function) {
        assert mtbdd.isValidFunction(function);
        int result = composeCache.lookup(function);
        lookupHash = composeCache.lookupHash();
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
        return noValueMatchesCache.get(node);
    }

    int lookupSplit(int node) {
        assert mtbdd.isValidFunction(node);
        int result = splitCache.lookup(node);
        lookupHash = splitCache.lookupHash();
        return result;
    }

    int lookupSplitCombine(int lowFragment, int highFragment, int variable) {
        assert mtbdd.isValidFunction(lowFragment) && mtbdd.isValidFunction(highFragment);
        int result = splitCombineCache.lookup(lowFragment, highFragment, variable);
        lookupHash = splitCombineCache.lookupHash();
        return result;
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

    void putApply(int hash, int function1, int function2, int result) {
        assert mtbdd.isValidFunction(function1) && mtbdd.isValidFunction(function2) && mtbdd.isValidFunction(result);
        applyCache.put(hash, function1, function2, result);
    }

    void putMap(int hash, int function, int result) {
        assert mtbdd.isValidFunction(function) && mtbdd.isValidFunction(result);
        mapCache.put(hash, function, result);
    }

    void putMapSimplify(int hash, int function, int domain, int result) {
        assert mtbdd.isValidFunction(function) && bdd.isValidFunction(domain) && mtbdd.isValidFunction(result);
        mapSimplifyCache.put(hash, function, domain, result);
    }

    void putMapBoolean(int hash, int function, int result) {
        assert mtbdd.isValidFunction(function) && bdd.isValidFunction(result);
        mapBooleanCache.put(hash, function, result);
    }

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

    void putCompose(int hash, int function, int result) {
        assert mtbdd.isValidFunction(function) && mtbdd.isValidFunction(result);
        composeCache.put(hash, function, result);
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

    Map<String, Object> statistics() {
        Map<String, Object> statistics = new HashMap<>();
        caches.forEach((name, cache) -> statistics.putAll(cache.statistics("mtbdd_cache_" + name)));
        statistics.put("mtbdd_cache_apply_reuse_count", String.valueOf(applyReuseCount));
        statistics.put("mtbdd_cache_map_reuse_count", String.valueOf(mapReuseCount));
        statistics.put("mtbdd_cache_map_boolean_reuse_count", String.valueOf(mapBooleanReuseCount));
        statistics.put("mtbdd_cache_apply_boolean_reuse_count", String.valueOf(applyBooleanReuseCount));
        statistics.put("mtbdd_cache_compose_reuse_count", String.valueOf(composeReuseCount));
        statistics.put("mtbdd_cache_restrict_reuse_count", String.valueOf(restrictReuseCount));
        statistics.put("mtbdd_cache_reaches_match_reuse_count", String.valueOf(reachesMatchReuseCount));
        statistics.put("mtbdd_cache_count_reuse_count", String.valueOf(countReuseCount));
        return statistics;
    }

    interface MtbddCacheStorage {
        void invalidate();

        void clearInvalidMtbddNodes(boolean preserve);

        void clearInvalidBddNodes(boolean preserve);

        Map<String, Object> statistics(String name);
    }

    abstract static class IntCache extends CacheBase.IntKeys implements MtbddCacheStorage {
        final MtBddImpl mtbdd;
        final BddImpl bdd;
        int lookupHash;

        IntCache(MtBddImpl mtbdd, BddImpl bdd, int keyCount, int binSize) {
            super(keyCount, binSize);
            this.mtbdd = mtbdd;
            this.bdd = bdd;
        }

        IntCache(MtBddImpl mtbdd, BddImpl bdd, int keyCount, int binSize, BooleanSupplier cacheDependenciesValid) {
            super(keyCount, binSize, cacheDependenciesValid);
            this.mtbdd = mtbdd;
            this.bdd = bdd;
        }

        int lookupHash() {
            return lookupHash;
        }

        @Override
        protected boolean isValid(int binStart) {
            return isValidBdd(binStart) && isValidMtbdd(binStart);
        }

        /** Checks only this entry's {@code Bdd}-side slots (key and/or result); {@code true} if none. */
        protected abstract boolean isValidBdd(int binStart);

        /** Checks only this entry's {@code MtBddImpl}-side slots (key and/or result); always at least one. */
        protected abstract boolean isValidMtbdd(int binStart);

        @Override
        protected boolean useCachePreserve() {
            return bdd.configuration().useCachePreserve();
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

    static final class BinaryToIntCache extends IntCache {
        BinaryToIntCache(MtBddImpl mtbdd, BddImpl bdd) {
            super(mtbdd, bdd, 2, 3);
        }

        @Override
        protected boolean isValidBdd(int binStart) {
            return true;
        }

        @Override
        protected boolean isValidMtbdd(int binStart) {
            return mtbdd.isValidFunction(cache[binStart])
                    && mtbdd.isValidFunction(cache[binStart + 1])
                    && mtbdd.isValidFunction(cache[binStart + 2]);
        }

        int lookup(int function1, int function2) {
            ensureValid();
            int hash = HashUtil.hash(function1, function2);
            lookupHash = hash;
            int binStart = binSize * binIndex(hash);
            if (function1 == cache[binStart] && function2 == cache[binStart + 1]) {
                statistics.hit();
                return cache[binStart + 2];
            }
            statistics.miss();
            return mtbdd.placeholder();
        }

        void put(int hash, int function1, int function2, int result) {
            ensureValid();
            assert hash == HashUtil.hash(function1, function2);
            statistics.put();
            int binStart = binSize * binIndex(hash);
            cache[binStart] = function1;
            cache[binStart + 1] = function2;
            cache[binStart + 2] = result;
        }
    }

    static final class UnaryToIntCache extends IntCache {
        UnaryToIntCache(MtBddImpl mtbdd, BddImpl bdd) {
            super(mtbdd, bdd, 1, 2);
        }

        UnaryToIntCache(MtBddImpl mtbdd, BddImpl bdd, BooleanSupplier cacheDependenciesValid) {
            super(mtbdd, bdd, 1, 2, cacheDependenciesValid);
        }

        @Override
        protected boolean isValidBdd(int binStart) {
            return true;
        }

        @Override
        protected boolean isValidMtbdd(int binStart) {
            return mtbdd.isValidFunction(cache[binStart]) && mtbdd.isValidFunction(cache[binStart + 1]);
        }

        int lookup(int function) {
            ensureValid();
            int hash = HashUtil.hash(function);
            lookupHash = hash;
            int binStart = binSize * binIndex(hash);
            if (function == cache[binStart]) {
                statistics.hit();
                return cache[binStart + 1];
            }
            statistics.miss();
            return mtbdd.placeholder();
        }

        void put(int hash, int function, int result) {
            ensureValid();
            assert hash == HashUtil.hash(function);
            statistics.put();
            int binStart = binSize * binIndex(hash);
            cache[binStart] = function;
            cache[binStart + 1] = result;
        }
    }

    static final class UnaryToBddCache extends IntCache {
        UnaryToBddCache(MtBddImpl mtbdd, BddImpl bdd) {
            super(mtbdd, bdd, 1, 2);
        }

        @Override
        protected boolean isValidBdd(int binStart) {
            return bdd.isValidFunction(cache[binStart + 1]);
        }

        @Override
        protected boolean isValidMtbdd(int binStart) {
            return mtbdd.isValidFunction(cache[binStart]);
        }

        int lookup(int function) {
            ensureValid();
            int hash = HashUtil.hash(function);
            lookupHash = hash;
            int binStart = binSize * binIndex(hash);
            if (function == cache[binStart]) {
                statistics.hit();
                return cache[binStart + 1];
            }
            statistics.miss();
            return bdd.placeholder();
        }

        void put(int hash, int function, int result) {
            ensureValid();
            assert hash == HashUtil.hash(function);
            statistics.put();
            int binStart = binSize * binIndex(hash);
            cache[binStart] = function;
            cache[binStart + 1] = result;
        }
    }

    static final class BinaryToBddCache extends IntCache {
        BinaryToBddCache(MtBddImpl mtbdd, BddImpl bdd) {
            super(mtbdd, bdd, 2, 3);
        }

        @Override
        protected boolean isValidBdd(int binStart) {
            return bdd.isValidFunction(cache[binStart + 2]);
        }

        @Override
        protected boolean isValidMtbdd(int binStart) {
            return mtbdd.isValidFunction(cache[binStart]) && mtbdd.isValidFunction(cache[binStart + 1]);
        }

        int lookup(int function1, int function2) {
            ensureValid();
            int hash = HashUtil.hash(function1, function2);
            lookupHash = hash;
            int binStart = binSize * binIndex(hash);
            if (function1 == cache[binStart] && function2 == cache[binStart + 1]) {
                statistics.hit();
                return cache[binStart + 2];
            }
            statistics.miss();
            return bdd.placeholder();
        }

        void put(int hash, int function1, int function2, int result) {
            ensureValid();
            assert hash == HashUtil.hash(function1, function2);
            statistics.put();
            int binStart = binSize * binIndex(hash);
            cache[binStart] = function1;
            cache[binStart + 1] = function2;
            cache[binStart + 2] = result;
        }
    }

    static final class MtbddBddToIntCache extends IntCache {
        MtbddBddToIntCache(MtBddImpl mtbdd, BddImpl bdd) {
            super(mtbdd, bdd, 2, 3);
        }

        MtbddBddToIntCache(MtBddImpl mtbdd, BddImpl bdd, BooleanSupplier cacheDependenciesValid) {
            super(mtbdd, bdd, 2, 3, cacheDependenciesValid);
        }

        @Override
        protected boolean isValidBdd(int binStart) {
            return bdd.isValidFunction(cache[binStart + 1]);
        }

        @Override
        protected boolean isValidMtbdd(int binStart) {
            return mtbdd.isValidFunction(cache[binStart]) && mtbdd.isValidFunction(cache[binStart + 2]);
        }

        int lookup(int function, int domain) {
            ensureValid();
            int hash = HashUtil.hash(function, domain);
            lookupHash = hash;
            int binStart = binSize * binIndex(hash);
            if (function == cache[binStart] && domain == cache[binStart + 1]) {
                statistics.hit();
                return cache[binStart + 2];
            }
            statistics.miss();
            return mtbdd.placeholder();
        }

        void put(int hash, int function, int domain, int result) {
            ensureValid();
            assert hash == HashUtil.hash(function, domain);
            statistics.put();
            int binStart = binSize * binIndex(hash);
            cache[binStart] = function;
            cache[binStart + 1] = domain;
            cache[binStart + 2] = result;
        }
    }

    /**
     * {@code (mtbdd function, mtbdd function, bdd domain) -> mtbdd function}, for {@code applySimplify}.
     * Same shape as {@link UpdateCache}, but with the {@code Bdd}-side key in the third rather than the
     * second slot - which is exactly what the two validity checks below are keyed on, so the two cannot be
     * merged.
     */
    static final class ApplySimplifyCache extends IntCache {
        ApplySimplifyCache(MtBddImpl mtbdd, BddImpl bdd) {
            super(mtbdd, bdd, 3, 4);
        }

        @Override
        protected boolean isValidBdd(int binStart) {
            return bdd.isValidFunction(cache[binStart + 2]);
        }

        @Override
        protected boolean isValidMtbdd(int binStart) {
            return mtbdd.isValidFunction(cache[binStart])
                    && mtbdd.isValidFunction(cache[binStart + 1])
                    && mtbdd.isValidFunction(cache[binStart + 3]);
        }

        int lookup(int function1, int function2, int domain) {
            ensureValid();
            int hash = HashUtil.hash(function1, function2, domain);
            lookupHash = hash;
            int binStart = binSize * binIndex(hash);
            if (function1 == cache[binStart] && function2 == cache[binStart + 1] && domain == cache[binStart + 2]) {
                statistics.hit();
                return cache[binStart + 3];
            }
            statistics.miss();
            return mtbdd.placeholder();
        }

        void put(int hash, int function1, int function2, int domain, int result) {
            ensureValid();
            assert hash == HashUtil.hash(function1, function2, domain);
            statistics.put();
            int binStart = binSize * binIndex(hash);
            cache[binStart] = function1;
            cache[binStart + 1] = function2;
            cache[binStart + 2] = domain;
            cache[binStart + 3] = result;
        }
    }

    static final class UpdateCache extends IntCache {
        UpdateCache(MtBddImpl mtbdd, BddImpl bdd) {
            super(mtbdd, bdd, 3, 4);
        }

        @Override
        protected boolean isValidBdd(int binStart) {
            return bdd.isValidFunction(cache[binStart + 1]);
        }

        @Override
        protected boolean isValidMtbdd(int binStart) {
            return mtbdd.isValidFunction(cache[binStart]) && mtbdd.isValidFunction(cache[binStart + 3]);
        }

        int lookup(int function, int bddAssignments, int value) {
            ensureValid();
            int hash = HashUtil.hash(function, bddAssignments, value);
            lookupHash = hash;
            int binStart = binSize * binIndex(hash);
            if (function == cache[binStart] && bddAssignments == cache[binStart + 1] && value == cache[binStart + 2]) {
                statistics.hit();
                return cache[binStart + 3];
            }
            statistics.miss();
            return mtbdd.placeholder();
        }

        void put(int hash, int function, int bddAssignments, int value, int result) {
            ensureValid();
            assert hash == HashUtil.hash(function, bddAssignments, value);
            statistics.put();
            int binStart = binSize * binIndex(hash);
            cache[binStart] = function;
            cache[binStart + 1] = bddAssignments;
            cache[binStart + 2] = value;
            cache[binStart + 3] = result;
        }
    }

    static final class SplitCombineCache extends IntCache {
        SplitCombineCache(MtBddImpl mtbdd, BddImpl bdd) {
            super(mtbdd, bdd, 3, 4);
        }

        @Override
        protected boolean isValidBdd(int binStart) {
            return true;
        }

        @Override
        protected boolean isValidMtbdd(int binStart) {
            return mtbdd.isValidFunction(cache[binStart])
                    && mtbdd.isValidFunction(cache[binStart + 1])
                    && mtbdd.isValidFunction(cache[binStart + 3]);
        }

        int lookup(int lowFragment, int highFragment, int variable) {
            ensureValid();
            int hash = HashUtil.hash(lowFragment, highFragment, variable);
            lookupHash = hash;
            int binStart = binSize * binIndex(hash);
            if (lowFragment == cache[binStart]
                    && highFragment == cache[binStart + 1]
                    && variable == cache[binStart + 2]) {
                statistics.hit();
                return cache[binStart + 3];
            }
            statistics.miss();
            return mtbdd.placeholder();
        }

        void put(int hash, int lowFragment, int highFragment, int variable, int result) {
            ensureValid();
            assert hash == HashUtil.hash(lowFragment, highFragment, variable);
            statistics.put();
            int binStart = binSize * binIndex(hash);
            cache[binStart] = lowFragment;
            cache[binStart + 1] = highFragment;
            cache[binStart + 2] = variable;
            cache[binStart + 3] = result;
        }
    }

    static final class IfThenElseCache extends IntCache {
        IfThenElseCache(MtBddImpl mtbdd, BddImpl bdd) {
            super(mtbdd, bdd, 3, 4);
        }

        @Override
        protected boolean isValidBdd(int binStart) {
            return bdd.isValidFunction(cache[binStart]);
        }

        @Override
        protected boolean isValidMtbdd(int binStart) {
            return mtbdd.isValidFunction(cache[binStart + 1])
                    && mtbdd.isValidFunction(cache[binStart + 2])
                    && mtbdd.isValidFunction(cache[binStart + 3]);
        }

        int lookup(int bddIf, int mtbddThen, int mtbddElse) {
            ensureValid();
            int hash = HashUtil.hash(bddIf, mtbddThen, mtbddElse);
            lookupHash = hash;
            int binStart = binSize * binIndex(hash);
            if (bddIf == cache[binStart] && mtbddThen == cache[binStart + 1] && mtbddElse == cache[binStart + 2]) {
                statistics.hit();
                return cache[binStart + 3];
            }
            statistics.miss();
            return mtbdd.placeholder();
        }

        void put(int hash, int bddIf, int mtbddThen, int mtbddElse, int result) {
            ensureValid();
            assert hash == HashUtil.hash(bddIf, mtbddThen, mtbddElse);
            statistics.put();
            int binStart = binSize * binIndex(hash);
            cache[binStart] = bddIf;
            cache[binStart + 1] = mtbddThen;
            cache[binStart + 2] = mtbddElse;
            cache[binStart + 3] = result;
        }
    }

    @SuppressWarnings("unchecked")
    static final class UnaryToObjectCache<V> extends IntCache {
        private Object[] values = EMPTY_OBJECT_ARRAY;

        UnaryToObjectCache(MtBddImpl mtbdd, BddImpl bdd) {
            super(mtbdd, bdd, 1, 1);
        }

        @Override
        protected boolean isValidBdd(int binStart) {
            return true;
        }

        @Override
        protected boolean isValidMtbdd(int binStart) {
            return mtbdd.isValidFunction(cache[binStart]);
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
            ensureValid();
            int hash = HashUtil.hash(function);
            lookupHash = hash;
            int binIndex = binIndex(hash);
            int binStart = binSize * binIndex;
            if (function == cache[binStart]) {
                statistics.hit();
                return (V) values[binIndex];
            }
            statistics.miss();
            return null;
        }

        void put(int hash, int function, V result) {
            ensureValid();
            assert hash == HashUtil.hash(function);
            statistics.put();
            int binIndex = binIndex(hash);
            int binStart = binSize * binIndex;
            cache[binStart] = function;
            values[binIndex] = result;
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
        protected boolean useCachePreserve() {
            return bdd.configuration().useCachePreserve();
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
                statistics.hit();
                return values[index];
            }
            statistics.miss();
            return mtbdd.placeholder();
        }

        void put(int hash, int[] key, int result) {
            ensureValid();
            assert hash == Arrays.hashCode(key);
            statistics.put();
            int index = binIndex(hash);
            cache[index] = key;
            values[index] = result;
        }
    }
}
