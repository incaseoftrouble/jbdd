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

import static java.lang.String.valueOf;
import static java.util.Map.entry;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

@SuppressWarnings("PMD.TooManyFields")
final class BooleanCache {
    private static final Logger logger = Logger.getLogger(BooleanCache.class.getName());

    @SuppressWarnings("StaticCollection")
    private static final Collection<BooleanCache> cacheShutdownHook = new ConcurrentLinkedDeque<>();

    private static final int[] EMPTY_INT_ARRAY = new int[0];
    private static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];

    private final BooleanBase<?, ?> bdd;
    private final int placeholder;
    private int composeReuseCount = 0;
    private int existsReuseCount = 0;
    private int validityChecks = 0;

    private final BinaryToIntCache andCache = new BinaryToIntCache();
    private final TernaryToIntCache andSimplifyCache = new TernaryToIntCache();
    private final BinaryToIntCache xorCache = new BinaryToIntCache();
    private final TernaryToIntCache xorSimplifyCache = new TernaryToIntCache();
    private final BinaryToIntCache simplifyCache = new BinaryToIntCache();
    private final BinaryToBooleanCache impliesCache = new BinaryToBooleanCache();
    private final BinaryToBooleanCache intersectsCache = new BinaryToBooleanCache();
    private final TernaryToIntCache iteCache = new TernaryToIntCache();
    private final UnaryToIntCache existsCache = new UnaryToIntCache();
    private BitSet existsVariables = new BitSet(0);
    private final UnaryToObjectCache<BigInteger> satisfactionCache = new UnaryToObjectCache<>();
    private final BinaryToObjectCache<BigInteger> satisfactionInCache = new BinaryToObjectCache<>();
    private final UnaryToIntCache composeCache;
    private int[] composeArray = EMPTY_INT_ARRAY;

    private int lookupHash;

    BooleanCache(BooleanBase<?, ?> bdd) {
        this.bdd = bdd;
        this.placeholder = bdd.placeholder();
        this.lookupHash = -1;
        composeCache = new UnaryToIntCache(() -> {
            for (int composeNode : composeArray) {
                if (!bdd.isValidFunction(composeNode)) {
                    return false;
                }
            }
            return true;
        });

        BddConfiguration configuration = bdd.configuration();
        tableSizeChanged();

        if (logger.isLoggable(Level.INFO) && configuration.logStatisticsOnShutdown()) {
            logger.log(Level.INFO, "Adding {0} to shutdown hook", this);
            addToShutdownHook(this);
        }
    }

    private static void addToShutdownHook(BooleanCache cache) {
        ShutdownHookLazyHolder.init();
        cacheShutdownHook.add(cache);
    }

    private static int mod(int value, int modulus) {
        int val = value % modulus;
        return val < 0 ? val + modulus : val;
    }

    boolean binarySymmetricWellOrdered(int node1, int node2) {
        int node1var = bdd.decisionVariable(node1);
        int node2var = bdd.decisionVariable(node2);
        return node1var < node2var || (node1var == node2var && node1 < node2);
    }

    int lookupHash() {
        return lookupHash;
    }

    // Size and invalidation

    public void tableSizeChanged() {
        logger.log(Level.FINER, "Growing caches if necessary");
        BddConfiguration configuration = bdd.configuration();

        int unarySize = bdd.tableSize() / configuration.cacheUnaryDivider();
        satisfactionCache.grow(unarySize);
        satisfactionInCache.grow(unarySize);

        int binarySize = bdd.tableSize() / configuration.cacheBinaryDivider();
        andCache.grow(binarySize);
        xorCache.grow(binarySize);
        simplifyCache.grow(binarySize);
        impliesCache.grow(binarySize);
        intersectsCache.grow(binarySize);

        int ternarySize = bdd.tableSize() / configuration.cacheTernaryDivider();
        andSimplifyCache.grow(ternarySize);
        xorSimplifyCache.grow(ternarySize);
        iteCache.grow(ternarySize);

        int ephemeralSize = bdd.tableSize() / configuration.cacheEphemeralMultiplier();
        existsCache.grow(ephemeralSize);
        composeCache.grow(ephemeralSize);
    }

    public void variablesChanged() {
        BddConfiguration configuration = bdd.configuration();
        int unarySize = bdd.tableSize() / configuration.cacheUnaryDivider();
        satisfactionCache.grow(unarySize);

        int ephemeralSize = bdd.tableSize() / configuration.cacheEphemeralMultiplier();
        existsCache.grow(ephemeralSize);
        composeCache.grow(ephemeralSize);
    }

    private List<IntCache> caches() {
        return List.of(
                andCache,
                andSimplifyCache,
                xorCache,
                xorSimplifyCache,
                simplifyCache,
                impliesCache,
                intersectsCache,
                iteCache,
                satisfactionCache,
                satisfactionInCache,
                composeCache,
                existsCache);
    }

    public void invalidate() {
        caches().forEach(IntCache::invalidate);
    }

    public void clearInvalidNodes(boolean attemptPruning) {
        validityChecks += 1;
        for (IntCache cache : caches()) {
            cache.clearInvalidNodes(bdd.configuration().useCachePreserve() && attemptPruning);
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
    }

    void initExists(BitSet quantifiedVariables) {
        if (quantifiedVariables.equals(this.existsVariables)) {
            existsReuseCount += 1;
            return;
        }
        this.existsVariables = quantifiedVariables;
        existsCache.invalidate();
    }

    int lookupAnd(int function1, int function2) {
        assert bdd.isValidNonConstantFunction(function1) && bdd.isValidNonConstantFunction(function2);
        assert binarySymmetricWellOrdered(function1, function2);
        return andCache.lookup(function1, function2);
    }

    int lookupAndSimplify(int function1, int function2, int domain) {
        assert bdd.isValidNonConstantFunction(function1)
                && bdd.isValidNonConstantFunction(function2)
                && bdd.isValidNonConstantFunction(domain);
        assert binarySymmetricWellOrdered(function1, function2);
        return andSimplifyCache.lookup(function1, function2, domain);
    }

    int lookupXor(int function1, int function2) {
        assert bdd.isValidNonConstantFunction(function1) && bdd.isValidNonConstantFunction(function2);
        assert binarySymmetricWellOrdered(function1, function2);
        return xorCache.lookup(function1, function2);
    }

    int lookupXorSimplify(int function1, int function2, int domain) {
        assert bdd.isValidNonConstantFunction(function1)
                && bdd.isValidNonConstantFunction(function2)
                && bdd.isValidNonConstantFunction(domain);
        assert binarySymmetricWellOrdered(function1, function2);
        return xorSimplifyCache.lookup(function1, function2, domain);
    }

    int lookupSimplify(int function, int domain) {
        assert bdd.isValidNonConstantFunction(function)
                && bdd.isPositive(function)
                && bdd.isValidNonConstantFunction(domain);
        return simplifyCache.lookup(function, domain);
    }

    int lookupImplies(int function1, int function2) {
        assert bdd.isValidNonConstantFunction(function1) && bdd.isValidNonConstantFunction(function2);
        return impliesCache.lookup(function1, function2);
    }

    int lookupIntersects(int function1, int function2) {
        assert bdd.isValidNonConstantFunction(function1) && bdd.isValidNonConstantFunction(function2);
        assert binarySymmetricWellOrdered(function1, function2);
        return intersectsCache.lookup(function1, function2);
    }

    int lookupIfThenElse(int function1, int function2, int function3) {
        assert bdd.isValidNonConstantFunction(function1)
                && bdd.isPositive(function1)
                && bdd.isValidNonConstantFunction(function2)
                && bdd.isValidNonConstantFunction(function3);
        return iteCache.lookup(function1, function2, function3);
    }

    @Nullable
    BigInteger lookupSatisfaction(int function) {
        assert bdd.isValidNonConstantFunction(function);
        return satisfactionCache.lookup(function);
    }

    @Nullable
    BigInteger lookupSatisfactionIn(int function, int domain) {
        assert bdd.isValidNonConstantFunction(function) && bdd.isValidNonConstantFunction(domain);
        return satisfactionInCache.lookup(function, domain);
    }

    int lookupCompose(int function) {
        assert bdd.isValidNonConstantFunction(function);
        return composeCache.lookup(function);
    }

    int lookupExists(int function) {
        assert bdd.isValidNonConstantFunction(function);
        return existsCache.lookup(function);
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

    void putImplies(int hash, int function1, int function2, boolean result) {
        assert bdd.isValidNonConstantFunction(function1) && bdd.isValidNonConstantFunction(function2);
        impliesCache.put(hash, function1, function2, result);
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
                && bdd.isValidNonConstantFunction(function3)
                && bdd.isValidFunction(result);
        iteCache.put(hash, function1, function2, function3, result);
    }

    void putSatisfaction(int hash, int function, BigInteger satisfactionCount) {
        assert bdd.isValidNonConstantFunction(function);
        satisfactionCache.put(hash, function, satisfactionCount);
    }

    void putSatisfactionIn(int hash, int function, int domain, BigInteger satisfactionCount) {
        assert bdd.isValidNonConstantFunction(function);
        satisfactionInCache.put(hash, function, domain, satisfactionCount);
    }

    void putCompose(int hash, int function, int result) {
        assert bdd.isValidNonConstantFunction(function) && bdd.isValidFunction(result);
        composeCache.put(hash, function, result);
    }

    void putExists(int hash, int inputNode, int result) {
        assert bdd.isValidNonConstantFunction(inputNode) && bdd.isValidFunction(result);
        assert hash == HashUtil.hash(inputNode);
        existsCache.put(hash, inputNode, result);
    }

    // Utility

    public Map<String, String> getStatistics() {
        Map<String, String> statistics = new HashMap<>();
        Map.ofEntries(
                        entry("and", andCache),
                        entry("and_simplify", andSimplifyCache),
                        entry("xor", xorCache),
                        entry("xor_simplify", xorSimplifyCache),
                        entry("ite", iteCache),
                        entry("satisfaction", satisfactionCache),
                        entry("satisfaction_in", satisfactionInCache),
                        entry("implies", impliesCache),
                        entry("intersects", intersectsCache),
                        entry("compose", composeCache),
                        entry("exists", existsCache))
                .forEach((name, cache) -> statistics.putAll(cache.statistics("cache_" + name)));
        statistics.put("validity_checks", valueOf(validityChecks));
        statistics.put("compose_reuse_count", valueOf(composeReuseCount));
        statistics.put("exists_reuse_count", valueOf(existsReuseCount));
        return statistics;
    }

    private static final class CacheStatistics {
        private int hitCount = 0;
        private int hitCountSinceClear = 0;
        private int putCount = 0;
        private int putCountSinceClear = 0;
        private int missCount = 0;
        private int missCountSinceClear = 0;
        private int clearCount = 0;
        private int pruningCount = 0;
        private int totalPrunedEntries = 0;

        void hit() {
            hitCount++;
            hitCountSinceClear++;
        }

        void miss() {
            missCount++;
            missCountSinceClear++;
        }

        void put() {
            putCount++;
            putCountSinceClear++;
        }

        void clear() {
            clearCount++;
            hitCountSinceClear = 0;
            putCountSinceClear = 0;
            missCountSinceClear = 0;
        }

        void prune(int prunedEntries) {
            pruningCount++;
            totalPrunedEntries += prunedEntries;
        }

        public Map<String, String> data() {
            double hitToPutRatio = (double) hitCount / Math.max(putCount, 1);
            double hitRatio = (double) hitCount / Math.max(hitCount + missCount, 1);

            return Map.ofEntries(
                    entry("put", valueOf(putCount)),
                    entry("hit", valueOf(hitCount)),
                    entry("miss", valueOf(missCount)),
                    entry("hit_ratio", valueOf(hitRatio)),
                    entry("hit_to_put_ratio", valueOf(hitToPutRatio)),
                    entry("clear_count", valueOf(clearCount)),
                    entry("put_since_clear", valueOf(putCountSinceClear)),
                    entry("hit_since_clear", valueOf(hitCountSinceClear)),
                    entry("miss_since_clear", valueOf(missCountSinceClear)),
                    entry("prune_count", valueOf(pruningCount)),
                    entry("pruned_entries", valueOf(totalPrunedEntries)));
        }
    }

    abstract class IntCache {
        final int arity;
        final int binSize;
        int size = 0;
        int[] cache = EMPTY_INT_ARRAY;
        CacheStatistics statistics = new CacheStatistics();
        BooleanSupplier cacheDependenciesValid;
        private int desiredSize = 0;
        private boolean cacheInvalid = true;

        IntCache(int arity, int binSize) {
            this.arity = arity;
            this.binSize = binSize;
            this.cacheDependenciesValid = () -> true;
        }

        IntCache(int arity, int binSize, BooleanSupplier cacheDependenciesValid) {
            this.arity = arity;
            this.binSize = binSize;
            this.cacheDependenciesValid = cacheDependenciesValid;
        }

        int size() {
            assert size == cache.length / binSize;
            return size;
        }

        double loadFactor() {
            int loadedBins = 0;
            for (int i = 0; i < cache.length; i += binSize) {
                if (cache[i] != placeholder) {
                    loadedBins++;
                }
            }
            return (double) loadedBins / size();
        }

        int binIndex(int hash) {
            return mod(hash, size());
        }

        void clearInvalidNodes(boolean attemptPruning) {
            if (cacheInvalid || !cacheDependenciesValid.getAsBoolean()) {
                cacheInvalid = true;
                return;
            }
            if (statistics.putCountSinceClear == 0) {
                assert isEmpty();
                return;
            }
            if (!attemptPruning || statistics.putCountSinceClear < size() / 4) {
                cacheInvalid = true;
                return;
            }
            if (2 * size < desiredSize) {
                // No point in pruning if we need to grow and check anyway
                growToSize();
                return;
            }
            assert !cacheInvalid;

            int pruned = 0;
            for (int binStart = 0; binStart < cache.length; binStart += binSize) {
                if (cache[binStart] != placeholder && !isValid(binStart)) {
                    pruned += 1;
                    cache[binStart] = placeholder;
                }
            }
            statistics.prune(pruned);
            assert allEntriesValid();
        }

        void grow(int size) {
            assert size > 0;
            this.desiredSize = Math.max(size, this.size);
        }

        void ensureValid() {
            if (2 * size < desiredSize) {
                // Also takes care of emptying the cache
                growToSize();
            } else if (cacheInvalid) {
                doClear(cache);
                statistics.clear();
                cacheInvalid = false;
                assert isEmpty();
            }
            assert allEntriesValid();
            assert !cacheInvalid;
        }

        private boolean allEntriesValid() {
            for (int binStart = 0; binStart < cache.length; binStart += binSize) {
                if (cache[binStart] != placeholder && !isValid(binStart)) {
                    return false;
                }
            }
            return true;
        }

        private boolean isEmpty() {
            for (int binStart = 0; binStart < cache.length; binStart += binSize) {
                if (cache[binStart] != placeholder) {
                    return false;
                }
            }
            return true;
        }

        private void growToSize() {
            int newSize = Primes.nextPrime(desiredSize);
            int[] newCache = new int[newSize * binSize];
            if (placeholder != 0) {
                // Need to clear even if preserving values, as this will not write the placeholder
                // to invalid locations
                doClear(newCache);
            }

            boolean preserve =
                    !cacheInvalid && bdd.configuration().useCachePreserve() && statistics.putCountSinceClear > size / 8;
            grow(newSize, newCache, preserve);
            cache = newCache;
            size = newSize;
            if (!preserve) {
                statistics.clear();
                assert isEmpty();
            }
            // If cache was invalid before grow, make sure the cache has been emptied
            assert !cacheInvalid || isEmpty();
            cacheInvalid = false;
        }

        private void doClear(int[] cache) {
            if (binSize == 1) {
                Arrays.fill(cache, placeholder);
            } else {
                for (int i = 0; i < cache.length; i += binSize) {
                    cache[i] = placeholder;
                }
            }
        }

        void invalidate() {
            cacheInvalid = true;
        }

        boolean isValid(int binStart) {
            for (int j = binStart; j < binStart + arity; j++) {
                if (!bdd.isValidNonConstantFunction(cache[j])) {
                    return false;
                }
            }
            return isValidResult(binStart);
        }

        public Map<String, String> statistics(String name) {
            Map<String, String> data = Map.of("size", valueOf(size), "load_factor", valueOf(loadFactor()));
            return Stream.concat(data.entrySet().stream(), statistics.data().entrySet().stream())
                    .collect(Collectors.toUnmodifiableMap(
                            e -> String.format("%s_%s", name, e.getKey()), Map.Entry::getValue));
        }

        protected abstract boolean isValidResult(int binStart);

        protected abstract void grow(int newSize, int[] newKeys, boolean preserve);
    }

    class BinaryToIntCache extends IntCache {
        public BinaryToIntCache() {
            super(2, 3);
        }

        @Override
        protected boolean isValidResult(int binStart) {
            return bdd.isValidFunction(cache[binStart + 2]);
        }

        @Override
        protected void grow(int newSize, int[] newCache, boolean preserve) {
            if (!preserve) {
                return;
            }

            for (int binIndex = 0; binIndex < size; binIndex++) {
                int binStart = binIndex * binSize;
                int key1 = cache[binStart];
                if (key1 == placeholder || !isValid(binStart)) {
                    continue;
                }
                int key2 = cache[binStart + 1];
                int newBinStart = binSize * mod(HashUtil.hash(key1, key2), newSize);
                newCache[newBinStart] = key1;
                newCache[newBinStart + 1] = key2;
                newCache[newBinStart + 2] = cache[binStart + 2];
            }
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
            return placeholder;
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

    class TernaryToIntCache extends IntCache {
        public TernaryToIntCache() {
            super(3, 4);
        }

        @Override
        protected boolean isValidResult(int binStart) {
            return bdd.isValidFunction(cache[binStart + 3]);
        }

        @Override
        protected void grow(int newSize, int[] newCache, boolean preserve) {
            if (!preserve) {
                return;
            }

            for (int binIndex = 0; binIndex < size; binIndex++) {
                int binStart = binIndex * binSize;
                int key1 = cache[binStart];
                if (key1 == placeholder || !isValid(binStart)) {
                    continue;
                }
                int key2 = cache[binStart + 1];
                int key3 = cache[binStart + 2];
                int newBinStart = binSize * mod(HashUtil.hash(key1, key2, key3), newSize);
                newCache[newBinStart] = key1;
                newCache[newBinStart + 1] = key2;
                newCache[newBinStart + 2] = key3;
                newCache[newBinStart + 3] = cache[binStart + 3];
            }
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
            return placeholder;
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

    class BinaryToBooleanCache extends IntCache {
        private BitSet values = new BitSet();

        public BinaryToBooleanCache() {
            super(2, 2);
        }

        @Override
        protected boolean isValidResult(int binStart) {
            return true;
        }

        @Override
        protected void grow(int newSize, int[] newCache, boolean preserve) {
            if (preserve) {
                BitSet newValues = new BitSet();
                for (int binIndex = 0; binIndex < size; binIndex++) {
                    int binStart = binIndex * binSize;
                    int key1 = cache[binStart];
                    if (key1 == placeholder || !isValid(binStart)) {
                        continue;
                    }
                    int key2 = cache[binStart + 1];
                    int newBinIndex = mod(HashUtil.hash(key1, key2), newSize);
                    int newBinStart = binSize * newBinIndex;
                    newCache[newBinStart] = key1;
                    newCache[newBinStart + 1] = key2;
                    if (values.get(binIndex)) {
                        newValues.set(newBinIndex);
                    }
                }
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
            return placeholder;
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

    class UnaryToIntCache extends IntCache {
        public UnaryToIntCache() {
            super(1, 2);
        }

        public UnaryToIntCache(BooleanSupplier pruningValid) {
            super(1, 2, pruningValid);
        }

        @Override
        protected boolean isValidResult(int binStart) {
            return bdd.isValidFunction(binStart + 1);
        }

        @Override
        protected void grow(int newSize, int[] newCache, boolean preserve) {
            if (!preserve) {
                return;
            }

            for (int binIndex = 0; binIndex < size; binIndex++) {
                int binStart = binIndex * binSize;
                int key = cache[binStart];
                if (key == placeholder || !isValid(binStart)) {
                    continue;
                }
                int newBinStart = binSize * mod(HashUtil.hash(key), newSize);
                newCache[newBinStart] = key;
                newCache[newBinStart + 1] = cache[binStart + 1];
            }
        }

        protected int lookup(int function) {
            ensureValid();
            int hash = HashUtil.hash(function);
            lookupHash = hash;

            int binStart = binSize * binIndex(hash);
            if (function == cache[binStart]) {
                statistics.hit();
                return cache[binStart + 1];
            }
            statistics.miss();
            return placeholder;
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

    @SuppressWarnings("unchecked")
    class UnaryToObjectCache<V> extends IntCache {
        private Object[] values = EMPTY_OBJECT_ARRAY;

        public UnaryToObjectCache() {
            super(1, 1);
        }

        @Override
        protected boolean isValidResult(int binStart) {
            return true;
        }

        @Override
        protected void grow(int newSize, int[] newCache, boolean preserve) {
            if (preserve) {
                Object[] newValues = new Object[newSize];
                for (int binIndex = 0; binIndex < size; binIndex++) {
                    int binStart = binIndex * binSize;
                    int key = cache[binStart];
                    if (key == placeholder || !isValid(binStart)) {
                        continue;
                    }
                    int newBinIndex = mod(HashUtil.hash(key), newSize);
                    int newBinStart = binSize * newBinIndex;
                    newCache[newBinStart] = key;
                    newValues[newBinIndex] = values[binIndex];
                }
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
    class BinaryToObjectCache<V> extends IntCache {
        private Object[] values = EMPTY_OBJECT_ARRAY;

        public BinaryToObjectCache() {
            super(2, 2);
        }

        @Override
        protected boolean isValidResult(int binStart) {
            return true;
        }

        @Override
        protected void grow(int newSize, int[] newCache, boolean preserve) {
            if (preserve) {
                Object[] newValues = new Object[newSize];
                for (int binIndex = 0; binIndex < size; binIndex++) {
                    int binStart = binIndex * binSize;
                    int key1 = cache[binStart];
                    if (key1 == placeholder || !isValid(binStart)) {
                        continue;
                    }
                    int key2 = cache[binStart + 1];
                    int newBinIndex = mod(HashUtil.hash(key1, key2), newSize);
                    int newBinStart = binSize * newBinIndex;
                    newCache[newBinStart] = key1;
                    newCache[newBinStart + 1] = key2;
                    newValues[newBinIndex] = values[binIndex];
                }
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

    private static final class ShutdownHookLazyHolder {
        private static final Runnable shutdownHook = new ShutdownHookPrinter();

        static {
            Runtime.getRuntime().addShutdownHook(new Thread(shutdownHook));
        }

        static void init() {
            // bogus method to force static initialization
        }
    }

    private static final class ShutdownHookPrinter implements Runnable {
        @Override
        public void run() {
            if (!logger.isLoggable(Level.INFO)) {
                return;
            }
            for (BooleanCache cache : cacheShutdownHook) {
                logger.info(() -> "CACHE STATISTICS:\n" + cache.bdd.statistics());
            }
        }
    }
}
