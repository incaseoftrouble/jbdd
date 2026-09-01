/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2017-2023 Tobias Meggendorfer.
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

import static de.tum.in.jbdd.Util.*;
import static java.util.Map.entry;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

final class BooleanCache {
    private static final Logger logger = Logger.getLogger(BooleanCache.class.getName());

    private static final int[] EMPTY_INT_ARRAY = new int[0];
    private static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];

    private final BooleanBase<?, ?> bdd;
    private int composeReuseCount = 0;
    private int existsReuseCount = 0;
    private int restrictReuseCount = 0;
    private int validityChecks = 0;

    private final BinaryToIntCache andCache;
    private final TernaryToIntCache andSimplifyCache;
    private final BinaryToIntCache xorCache;
    private final TernaryToIntCache xorSimplifyCache;
    private final BinaryToIntCache simplifyCache;
    private final BinaryToIntCache constrainCache;
    private final BinaryToBooleanCache intersectsCache;
    private final TernaryToIntCache iteCache;
    private final QuaternaryToIntCache iteSimplifyCache;
    private final UnaryToIntCache existsCache;
    private BitSet existsVariables = new BitSet(0);
    private final UnaryToObjectCache<BigInteger> satisfactionCache;
    private final BinaryToObjectCache<BigInteger> satisfactionInCache;
    private final UnaryToIntCache composeCache;
    private final BinaryToIntCache composeSimplifyCache;
    private int[] composeArray = EMPTY_INT_ARRAY;
    private final UnaryToIntCache restrictCache;
    private BitSet restrictVariables = new BitSet(0);
    private BitSet restrictValues = new BitSet(0);
    private final Map<String, IntCache> caches;

    private int lookupHash;

    BooleanCache(BooleanBase<?, ?> bdd) {
        this.bdd = bdd;
        this.lookupHash = -1;

        andCache = new BinaryToIntCache(bdd);
        andSimplifyCache = new TernaryToIntCache(bdd);
        xorCache = new BinaryToIntCache(bdd);
        xorSimplifyCache = new TernaryToIntCache(bdd);
        simplifyCache = new BinaryToIntCache(bdd);
        constrainCache = new BinaryToIntCache(bdd);
        intersectsCache = new BinaryToBooleanCache(bdd);
        iteCache = new TernaryToIntCache(bdd);
        iteSimplifyCache = new QuaternaryToIntCache(bdd);
        existsCache = new UnaryToIntCache(bdd);
        satisfactionCache = new UnaryToObjectCache<>(bdd);
        satisfactionInCache = new BinaryToObjectCache<>(bdd);
        restrictCache = new UnaryToIntCache(bdd);

        BooleanSupplier composeValid = () -> {
            for (int composeNode : composeArray) {
                if (!bdd.isValidFunction(composeNode)) {
                    return false;
                }
            }
            return true;
        };
        composeCache = new UnaryToIntCache(bdd, composeValid);
        composeSimplifyCache = new BinaryToIntCache(bdd, composeValid);

        caches = Map.ofEntries(
                entry("and", andCache),
                entry("and_simplify", andSimplifyCache),
                entry("xor", xorCache),
                entry("xor_simplify", xorSimplifyCache),
                entry("ite", iteCache),
                entry("ite_simplify", iteSimplifyCache),
                entry("satisfaction", satisfactionCache),
                entry("satisfaction_in", satisfactionInCache),
                entry("intersects", intersectsCache),
                entry("simplify", simplifyCache),
                entry("constrain", constrainCache),
                entry("compose", composeCache),
                entry("compose_simplify", composeSimplifyCache),
                entry("exists", existsCache),
                entry("restrict", restrictCache));

        tableSizeChanged(0);

        if (bdd.configuration().logStatisticsOnShutdown()) {
            registerForCleanupStatistics(bdd, bdd.configuration().name());
        }
    }

    int lookupHash() {
        return lookupHash;
    }

    UnaryToIntCache composeCache() {
        return composeCache;
    }

    BinaryToIntCache composeSimplifyCache() {
        return composeSimplifyCache;
    }

    // Size and invalidation

    void tableSizeChanged(int invalidatedNodes) {
        onBddNodesInvalidated(invalidatedNodes);

        logger.log(Level.FINER, "Growing caches if necessary");
        BddConfiguration configuration = bdd.configuration();

        int unarySize = bdd.tableSize() / configuration.cacheUnaryDivider();
        satisfactionCache.grow(unarySize);
        satisfactionInCache.grow(unarySize);

        int binarySize = bdd.tableSize() / configuration.cacheBinaryDivider();
        andCache.grow(binarySize);
        xorCache.grow(binarySize);
        simplifyCache.grow(binarySize);
        constrainCache.grow(binarySize);
        intersectsCache.grow(binarySize);

        int ternarySize = bdd.tableSize() / configuration.cacheTernaryDivider();
        andSimplifyCache.grow(ternarySize);
        xorSimplifyCache.grow(ternarySize);
        iteCache.grow(ternarySize);
        iteSimplifyCache.grow(ternarySize);

        int ephemeralSize = bdd.tableSize() / configuration.cacheEphemeralMultiplier();
        existsCache.grow(ephemeralSize);
        composeCache.grow(ephemeralSize);
        composeSimplifyCache.grow(ephemeralSize);
        restrictCache.grow(ephemeralSize);
    }

    void variablesChanged() {
        // Satisfaction counts are counts over [decisionVariable, numberOfVariables) - they are keyed on
        // the node alone, but their value depends on the variable count, so adding a variable makes every
        // stored entry wrong. Growing is not enough: CacheBase#grow only records a desired size and
        // resizes lazily.
        satisfactionCache.invalidate();
        satisfactionInCache.invalidate();

        BddConfiguration configuration = bdd.configuration();
        int unarySize = bdd.tableSize() / configuration.cacheUnaryDivider();
        satisfactionCache.grow(unarySize);
        satisfactionInCache.grow(unarySize);

        int ephemeralSize = bdd.tableSize() / configuration.cacheEphemeralMultiplier();
        existsCache.grow(ephemeralSize);
        composeCache.grow(ephemeralSize);
        composeSimplifyCache.grow(ephemeralSize);
        restrictCache.grow(ephemeralSize);
    }

    private Collection<IntCache> caches() {
        return caches.values();
    }

    void invalidate() {
        caches().forEach(IntCache::invalidate);
    }

    void onBddNodesInvalidated(int invalidatedNodes) {
        if (invalidatedNodes == 0) {
            return;
        }
        validityChecks += 1;
        // If we reclaimed a lot of nodes, we won't be able to save much, so don't try
        boolean preserve = bdd.configuration().useCachePreserve() && invalidatedNodes < bdd.tableSize() / 2;
        for (IntCache cache : caches()) {
            cache.clearInvalidNodes(preserve);
        }
    }

    // Lookup

    void initCompose(int[] replacements, int highestReplacement) {
        if (composeArray.length - 1 == highestReplacement) {
            int mismatch = Arrays.mismatch(composeArray, replacements);
            if (mismatch == -1 || mismatch > highestReplacement) {
                composeReuseCount += 1;
                return;
            }
        }
        this.composeArray = Arrays.copyOf(replacements, highestReplacement + 1);
        composeCache.invalidate();
        composeSimplifyCache.invalidate();
    }

    void initExists(BitSet quantifiedVariables) {
        if (quantifiedVariables.equals(this.existsVariables)) {
            existsReuseCount += 1;
            return;
        }
        this.existsVariables = quantifiedVariables;
        existsCache.invalidate();
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

    int lookupAnd(int function1, int function2) {
        assert bdd.isValidNonConstantFunction(function1) && bdd.isValidNonConstantFunction(function2);
        assert binarySymmetricWellOrdered(function1, function2);
        int result = andCache.lookup(function1, function2);
        lookupHash = andCache.lookupHash();
        return result;
    }

    int lookupAndSimplify(int function1, int function2, int domain) {
        assert bdd.isValidNonConstantFunction(function1)
                && bdd.isValidNonConstantFunction(function2)
                && bdd.isValidNonConstantFunction(domain);
        assert binarySymmetricWellOrdered(function1, function2);
        int result = andSimplifyCache.lookup(function1, function2, domain);
        lookupHash = andSimplifyCache.lookupHash();
        return result;
    }

    int lookupXor(int function1, int function2) {
        assert bdd.isValidNonConstantFunction(function1) && bdd.isValidNonConstantFunction(function2);
        assert binarySymmetricWellOrdered(function1, function2);
        int result = xorCache.lookup(function1, function2);
        lookupHash = xorCache.lookupHash();
        return result;
    }

    int lookupXorSimplify(int function1, int function2, int domain) {
        assert bdd.isValidNonConstantFunction(function1)
                && bdd.isValidNonConstantFunction(function2)
                && bdd.isValidNonConstantFunction(domain);
        assert binarySymmetricWellOrdered(function1, function2);
        int result = xorSimplifyCache.lookup(function1, function2, domain);
        lookupHash = xorSimplifyCache.lookupHash();
        return result;
    }

    int lookupSimplify(int function, int domain) {
        assert bdd.isValidNonConstantFunction(function)
                && bdd.isPositive(function)
                && bdd.isValidNonConstantFunction(domain);
        int result = simplifyCache.lookup(function, domain);
        lookupHash = simplifyCache.lookupHash();
        return result;
    }

    int lookupConstrain(int function, int domain) {
        assert bdd.isValidNonConstantFunction(function)
                && bdd.isPositive(function)
                && bdd.isValidNonConstantFunction(domain);
        int result = constrainCache.lookup(function, domain);
        lookupHash = constrainCache.lookupHash();
        return result;
    }

    int lookupIntersects(int function1, int function2) {
        assert bdd.isValidNonConstantFunction(function1) && bdd.isValidNonConstantFunction(function2);
        assert binarySymmetricWellOrdered(function1, function2);
        int result = intersectsCache.lookup(function1, function2);
        lookupHash = intersectsCache.lookupHash();
        return result;
    }

    int lookupIfThenElse(int function1, int function2, int function3) {
        assert bdd.isValidNonConstantFunction(function1)
                && bdd.isPositive(function1)
                && bdd.isValidNonConstantFunction(function2)
                && bdd.isPositive(function2)
                && bdd.isValidNonConstantFunction(function3);
        int result = iteCache.lookup(function1, function2, function3);
        lookupHash = iteCache.lookupHash();
        return result;
    }

    int lookupIfThenElseSimplify(int function1, int function2, int function3, int domain) {
        assert bdd.isValidNonConstantFunction(function1)
                && bdd.isPositive(function1)
                && bdd.isValidNonConstantFunction(function2)
                && bdd.isPositive(function2)
                && bdd.isValidNonConstantFunction(function3)
                && bdd.isValidNonConstantFunction(domain);
        int result = iteSimplifyCache.lookup(function1, function2, function3, domain);
        lookupHash = iteSimplifyCache.lookupHash();
        return result;
    }

    @Nullable
    BigInteger lookupSatisfaction(int function) {
        assert bdd.isValidNonConstantFunction(function);
        BigInteger result = satisfactionCache.lookup(function);
        lookupHash = satisfactionCache.lookupHash();
        return result;
    }

    @Nullable
    BigInteger lookupSatisfactionIn(int function, int domain) {
        assert bdd.isValidNonConstantFunction(function) && bdd.isValidNonConstantFunction(domain);
        BigInteger result = satisfactionInCache.lookup(function, domain);
        lookupHash = satisfactionInCache.lookupHash();
        return result;
    }

    int lookupCompose(int function) {
        assert bdd.isValidNonConstantFunction(function) && bdd.isPositive(function);
        int result = composeCache.lookup(function);
        lookupHash = composeCache.lookupHash();
        return result;
    }

    int lookupComposeSimplify(int function, int domain) {
        assert bdd.isValidNonConstantFunction(function)
                && bdd.isPositive(function)
                && bdd.isValidNonConstantFunction(domain);
        int result = composeSimplifyCache.lookup(function, domain);
        lookupHash = composeSimplifyCache.lookupHash();
        return result;
    }

    int lookupRestrict(int function) {
        assert bdd.isValidNonConstantFunction(function);
        int result = restrictCache.lookup(function);
        lookupHash = restrictCache.lookupHash();
        return result;
    }

    int lookupExists(int function) {
        assert bdd.isValidNonConstantFunction(function);
        int result = existsCache.lookup(function);
        lookupHash = existsCache.lookupHash();
        return result;
    }

    // Put

    void putAnd(int hash, int function1, int function2, int result) {
        assert bdd.isValidNonConstantFunction(function1)
                && bdd.isValidNonConstantFunction(function2)
                && bdd.isValidFunction(result);
        assert binarySymmetricWellOrdered(function1, function2);
        andCache.put(hash, function1, function2, result);
    }

    void putAndSimplify(int hash, int function1, int function2, int domain, int result) {
        assert bdd.isValidNonConstantFunction(function1)
                && bdd.isValidNonConstantFunction(function2)
                && bdd.isValidNonConstantFunction(domain)
                && bdd.isValidFunction(result);
        assert binarySymmetricWellOrdered(function1, function2);
        andSimplifyCache.put(hash, function1, function2, domain, result);
    }

    void putXor(int hash, int function1, int function2, int result) {
        assert bdd.isValidNonConstantFunction(function1)
                && bdd.isValidNonConstantFunction(function2)
                && bdd.isValidFunction(result);
        assert binarySymmetricWellOrdered(function1, function2);
        xorCache.put(hash, function1, function2, result);
    }

    void putXorSimplify(int hash, int function1, int function2, int domain, int result) {
        assert bdd.isValidNonConstantFunction(function1)
                && bdd.isValidNonConstantFunction(function2)
                && bdd.isValidNonConstantFunction(domain)
                && bdd.isValidFunction(result);
        assert binarySymmetricWellOrdered(function1, function2);
        xorSimplifyCache.put(hash, function1, function2, domain, result);
    }

    void putSimplify(int hash, int function, int domain, int result) {
        assert bdd.isValidNonConstantFunction(function)
                && bdd.isPositive(function)
                && bdd.isValidNonConstantFunction(domain)
                && bdd.isValidFunction(result);
        simplifyCache.put(hash, function, domain, result);
    }

    void putConstrain(int hash, int function, int domain, int result) {
        assert bdd.isValidNonConstantFunction(function)
                && bdd.isPositive(function)
                && bdd.isValidNonConstantFunction(domain)
                && bdd.isValidFunction(result);
        constrainCache.put(hash, function, domain, result);
    }

    void putIntersects(int hash, int function1, int function2, boolean result) {
        assert bdd.isValidNonConstantFunction(function1) && bdd.isValidNonConstantFunction(function2);
        assert binarySymmetricWellOrdered(function1, function2);
        intersectsCache.put(hash, function1, function2, result);
    }

    void putIfThenElse(int hash, int function1, int function2, int function3, int result) {
        assert bdd.isValidNonConstantFunction(function1)
                && bdd.isPositive(function1)
                && bdd.isValidNonConstantFunction(function2)
                && bdd.isPositive(function2)
                && bdd.isValidNonConstantFunction(function3)
                && bdd.isValidFunction(result);
        iteCache.put(hash, function1, function2, function3, result);
    }

    void putIfThenElseSimplify(int hash, int function1, int function2, int function3, int domain, int result) {
        assert bdd.isValidNonConstantFunction(function1)
                && bdd.isPositive(function1)
                && bdd.isValidNonConstantFunction(function2)
                && bdd.isPositive(function2)
                && bdd.isValidNonConstantFunction(function3)
                && bdd.isValidNonConstantFunction(domain)
                && bdd.isValidFunction(result);
        iteSimplifyCache.put(hash, function1, function2, function3, domain, result);
    }

    void putSatisfaction(int hash, int function, BigInteger satisfactionCount) {
        assert bdd.isValidNonConstantFunction(function);
        satisfactionCache.put(hash, function, satisfactionCount);
    }

    void putSatisfactionIn(int hash, int function, int domain, BigInteger satisfactionCount) {
        assert bdd.isValidNonConstantFunction(function);
        satisfactionInCache.put(hash, function, domain, satisfactionCount);
    }

    void putCompose(int hash, int node, int result) {
        assert bdd.isValidNonConstantFunction(node) && bdd.isValidFunction(result);
        composeCache.put(hash, node, result);
    }

    void putComposeSimplify(int hash, int node, int domain, int result) {
        assert bdd.isValidNonConstantFunction(node) && bdd.isPositive(node) && bdd.isValidFunction(result);
        composeSimplifyCache.put(hash, node, domain, result);
    }

    void putRestrict(int hash, int function, int result) {
        assert bdd.isValidNonConstantFunction(function) && bdd.isValidFunction(result);
        restrictCache.put(hash, function, result);
    }

    void putExists(int hash, int inputNode, int result) {
        assert bdd.isValidNonConstantFunction(inputNode) && bdd.isValidFunction(result);
        assert hash == HashUtil.hash(inputNode);
        existsCache.put(hash, inputNode, result);
    }

    // Utility

    Map<String, Object> statistics() {
        Map<String, Object> statistics = new HashMap<>();
        caches.forEach((name, cache) -> statistics.putAll(cache.statistics("cache_" + name)));
        statistics.put("validity_checks", String.valueOf(validityChecks));
        statistics.put("compose_reuse_count", String.valueOf(composeReuseCount));
        statistics.put("exists_reuse_count", String.valueOf(existsReuseCount));
        statistics.put("restrict_reuse_count", String.valueOf(restrictReuseCount));
        return statistics;
    }

    abstract static class IntCache extends CacheBase.IntKeys {
        final BooleanBase<?, ?> bdd;
        int lookupHash = 0;

        IntCache(BooleanBase<?, ?> bdd, int arity, int binSize) {
            super(arity, binSize);
            this.bdd = bdd;
        }

        IntCache(BooleanBase<?, ?> bdd, int arity, int binSize, BooleanSupplier cacheDependenciesValid) {
            super(arity, binSize, cacheDependenciesValid);
            this.bdd = bdd;
        }

        int lookupHash() {
            return lookupHash;
        }

        @Override
        protected boolean isValid(int binStart) {
            for (int j = binStart; j < binStart + keyCount; j++) {
                if (!bdd.isValidNonConstantFunction(cache[j])) {
                    return false;
                }
            }
            return isValidResult(binStart);
        }

        @Override
        protected boolean useCachePreserve() {
            return bdd.configuration().useCachePreserve();
        }

        void clearInvalidNodes(boolean attemptPruning) {
            prune(attemptPruning, this::isValid);
        }

        protected abstract boolean isValidResult(int binStart);
    }

    static class UnaryToIntCache extends IntCache {
        UnaryToIntCache(BooleanBase<?, ?> bdd) {
            super(bdd, 1, 2);
        }

        UnaryToIntCache(BooleanBase<?, ?> bdd, BooleanSupplier pruningValid) {
            super(bdd, 1, 2, pruningValid);
        }

        @Override
        protected boolean isValidResult(int binStart) {
            return bdd.isValidFunction(cache[binStart + 1]);
        }

        protected int lookup(int function) {
            ensureValid();
            int hash = HashUtil.hash(function);
            lookupHash = hash;

            int binStart = binSize * binIndex(hash);
            if (function == cache[binStart]) {
                int result = cache[binStart + 1];
                assert bdd.isValidFunction(result);
                statistics.hit();
                return result;
            }
            statistics.miss();
            return NodeTable.PLACEHOLDER;
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

    static class BinaryToIntCache extends IntCache {
        BinaryToIntCache(BooleanBase<?, ?> bdd) {
            super(bdd, 2, 3);
        }

        BinaryToIntCache(BooleanBase<?, ?> bdd, BooleanSupplier cacheDependenciesValid) {
            super(bdd, 2, 3, cacheDependenciesValid);
        }

        @Override
        protected boolean isValidResult(int binStart) {
            return bdd.isValidFunction(cache[binStart + 2]);
        }

        protected int lookup(int function1, int function2) {
            ensureValid();
            int hash = HashUtil.hash(function1, function2);
            lookupHash = hash;
            int binIndex = binIndex(hash);

            int binStart = binSize * binIndex;
            if (function1 == cache[binStart] && function2 == cache[binStart + 1]) {
                int result = cache[binStart + 2];
                assert bdd.isValidFunction(result);
                statistics.hit();
                return result;
            }
            statistics.miss();
            return NodeTable.PLACEHOLDER;
        }

        void put(int hash, int function1, int function2, int result) {
            ensureValid();
            assert hash == HashUtil.hash(function1, function2);
            statistics.put();

            int binIndex = binIndex(hash);
            int binStart = binSize * binIndex;
            cache[binStart] = function1;
            cache[binStart + 1] = function2;
            cache[binStart + 2] = result;
        }
    }

    static class TernaryToIntCache extends IntCache {
        TernaryToIntCache(BooleanBase<?, ?> bdd) {
            super(bdd, 3, 4);
        }

        @Override
        protected boolean isValidResult(int binStart) {
            return bdd.isValidFunction(cache[binStart + 3]);
        }

        protected int lookup(int function1, int function2, int function3) {
            ensureValid();
            int hash = HashUtil.hash(function1, function2, function3);
            lookupHash = hash;

            int binStart = binSize * binIndex(hash);
            if (function1 == cache[binStart] && function2 == cache[binStart + 1] && function3 == cache[binStart + 2]) {
                int result = cache[binStart + 3];
                assert bdd.isValidFunction(result);
                statistics.hit();
                return result;
            }
            statistics.miss();
            return NodeTable.PLACEHOLDER;
        }

        void put(int hash, int function1, int function2, int function3, int result) {
            ensureValid();
            assert hash == HashUtil.hash(function1, function2, function3);
            statistics.put();

            int binStart = binSize * binIndex(hash);
            cache[binStart] = function1;
            cache[binStart + 1] = function2;
            cache[binStart + 2] = function3;
            cache[binStart + 3] = result;
        }
    }

    static class QuaternaryToIntCache extends IntCache {
        QuaternaryToIntCache(BooleanBase<?, ?> bdd) {
            super(bdd, 4, 5);
        }

        @Override
        protected boolean isValidResult(int binStart) {
            return bdd.isValidFunction(cache[binStart + 4]);
        }

        protected int lookup(int function1, int function2, int function3, int function4) {
            ensureValid();
            int hash = HashUtil.hash(function1, function2, function3, function4);
            lookupHash = hash;

            int binStart = binSize * binIndex(hash);
            if (function1 == cache[binStart]
                    && function2 == cache[binStart + 1]
                    && function3 == cache[binStart + 2]
                    && function4 == cache[binStart + 3]) {
                int result = cache[binStart + 4];
                assert bdd.isValidFunction(result);
                statistics.hit();
                return result;
            }
            statistics.miss();
            return NodeTable.PLACEHOLDER;
        }

        void put(int hash, int function1, int function2, int function3, int function4, int result) {
            ensureValid();
            assert hash == HashUtil.hash(function1, function2, function3, function4);
            statistics.put();

            int binStart = binSize * binIndex(hash);
            cache[binStart] = function1;
            cache[binStart + 1] = function2;
            cache[binStart + 2] = function3;
            cache[binStart + 3] = function4;
            cache[binStart + 4] = result;
        }
    }

    static class BinaryToBooleanCache extends IntCache {
        private BitSet values = new BitSet();

        BinaryToBooleanCache(BooleanBase<?, ?> bdd) {
            super(bdd, 2, 2);
        }

        @Override
        protected boolean isValidResult(int binStart) {
            return true;
        }

        @Override
        protected void growInto(int newSize, int[] newCache, boolean preserve) {
            if (preserve) {
                BitSet newValues = new BitSet();
                rehashInto(newSize, newCache, (oldBin, newBin) -> newValues.set(newBin, values.get(oldBin)));
                this.values = newValues;
            } else {
                values.clear();
            }
        }

        protected int lookup(int function1, int function2) {
            ensureValid();
            int hash = HashUtil.hash(function1, function2);
            lookupHash = hash;
            int binIndex = binIndex(hash);

            int binStart = binSize * binIndex;
            if (function1 == cache[binStart] && function2 == cache[binStart + 1]) {
                statistics.hit();
                return values.get(binIndex) ? bdd.trueFunction() : bdd.falseFunction();
            }
            statistics.miss();
            return NodeTable.PLACEHOLDER;
        }

        void put(int hash, int function1, int function2, boolean result) {
            ensureValid();
            assert hash == HashUtil.hash(function1, function2);
            statistics.put();

            int binIndex = binIndex(hash);
            int binStart = binSize * binIndex;
            cache[binStart] = function1;
            cache[binStart + 1] = function2;
            values.set(binIndex, result);
        }
    }

    @SuppressWarnings("unchecked")
    static class UnaryToObjectCache<V> extends IntCache {
        private Object[] values = EMPTY_OBJECT_ARRAY;

        UnaryToObjectCache(BooleanBase<?, ?> bdd) {
            super(bdd, 1, 1);
        }

        @Override
        protected boolean isValidResult(int binStart) {
            return true;
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
        protected V lookup(int function) {
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

    @SuppressWarnings("unchecked")
    static class BinaryToObjectCache<V> extends IntCache {
        private Object[] values = EMPTY_OBJECT_ARRAY;

        BinaryToObjectCache(BooleanBase<?, ?> bdd) {
            super(bdd, 2, 2);
        }

        @Override
        protected boolean isValidResult(int binStart) {
            return true;
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
        protected V lookup(int function1, int function2) {
            ensureValid();
            int hash = HashUtil.hash(function1, function2);
            lookupHash = hash;
            int binIndex = binIndex(hash);

            int binStart = binSize * binIndex;
            if (function1 == cache[binStart] && function2 == cache[binStart + 1]) {
                statistics.hit();
                return (V) values[binIndex];
            }
            statistics.miss();
            return null;
        }

        void put(int hash, int function1, int function2, V result) {
            ensureValid();
            assert hash == HashUtil.hash(function1, function2);
            statistics.put();

            int binIndex = binIndex(hash);
            int binStart = binSize * binIndex;
            cache[binStart] = function1;
            cache[binStart + 1] = function2;
            values[binIndex] = result;
        }
    }
}
