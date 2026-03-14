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

import java.math.BigInteger;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
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
    private int partialInvalidationCount = 0;

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

    public void partialInvalidate() {
        partialInvalidationCount += 1;
        andCache.prune();
        andSimplifyCache.prune();
        xorCache.prune();
        xorSimplifyCache.prune();
        simplifyCache.prune();
        impliesCache.prune();
        intersectsCache.prune();
        iteCache.prune();
        satisfactionCache.prune();
        composeCache.prune();
        existsCache.prune();
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
        composeCache.clear();
    }

    void initExists(BitSet quantifiedVariables) {
        if (quantifiedVariables.equals(this.existsVariables)) {
            existsReuseCount += 1;
            return;
        }
        this.existsVariables = quantifiedVariables;
        existsCache.clear();
    }

    int lookupAnd(int function1, int function2) {
        assert binarySymmetricWellOrdered(function1, function2);
        return andCache.lookup(function1, function2);
    }

    int lookupAndSimplify(int function1, int function2, int domain) {
        assert binarySymmetricWellOrdered(function1, function2);
        return andSimplifyCache.lookup(function1, function2, domain);
    }

    int lookupXor(int function1, int function2) {
        assert binarySymmetricWellOrdered(function1, function2);
        return xorCache.lookup(function1, function2);
    }

    int lookupXorSimplify(int function1, int function2, int domain) {
        assert binarySymmetricWellOrdered(function1, function2);
        return xorSimplifyCache.lookup(function1, function2, domain);
    }

    int lookupSimplify(int function, int domain) {
        return simplifyCache.lookup(function, domain);
    }

    int lookupImplies(int function1, int function2) {
        return impliesCache.lookup(function1, function2);
    }

    int lookupIntersects(int function1, int function2) {
        assert binarySymmetricWellOrdered(function1, function2);
        return intersectsCache.lookup(function1, function2);
    }

    int lookupIfThenElse(int function1, int function2, int function3) {
        return iteCache.lookup(function1, function2, function3);
    }

    @Nullable
    BigInteger lookupSatisfaction(int function) {
        return satisfactionCache.lookup(function);
    }

    int lookupCompose(int function) {
        return composeCache.lookup(function);
    }

    int lookupExists(int function) {
        return existsCache.lookup(function);
    }

    // Put

    void putAnd(int hash, int function1, int function2, int result) {
        assert binarySymmetricWellOrdered(function1, function2);
        andCache.put(hash, function1, function2, result);
    }

    void putAndSimplify(int hash, int function1, int function2, int domain, int result) {
        assert binarySymmetricWellOrdered(function1, function2);
        andSimplifyCache.put(hash, function1, function2, domain, result);
    }

    void putXor(int hash, int function1, int function2, int result) {
        assert binarySymmetricWellOrdered(function1, function2);
        xorCache.put(hash, function1, function2, result);
    }

    void putXorSimplify(int hash, int function1, int function2, int domain, int result) {
        assert binarySymmetricWellOrdered(function1, function2);
        xorSimplifyCache.put(hash, function1, function2, domain, result);
    }

    void putSimplify(int hash, int function, int domain, int result) {
        simplifyCache.put(hash, function, domain, result);
    }

    void putImplies(int hash, int function1, int function2, boolean result) {
        impliesCache.put(hash, function1, function2, result);
    }

    void putIntersects(int hash, int function1, int function2, boolean result) {
        assert binarySymmetricWellOrdered(function1, function2);
        intersectsCache.put(hash, function1, function2, result);
    }

    void putIfThenElse(int hash, int function1, int function2, int function3, int result) {
        iteCache.put(hash, function1, function2, function3, result);
    }

    void putSatisfaction(int hash, int function, BigInteger satisfactionCount) {
        satisfactionCache.put(hash, function, satisfactionCount);
    }

    void putCompose(int hash, int function, int result) {
        composeCache.put(hash, function, result);
    }

    void putExists(int hash, int inputNode, int result) {
        assert bdd.isValidNonConstantFunction(inputNode) && bdd.isValidFunction(result);
        assert hash == HashUtil.hash(inputNode);
        existsCache.put(hash, inputNode, result);
    }

    // Utility

    public String getStatistics() {
        satisfactionCache.loadFactor();
        return String.format(
                "Cache Statistics:\n" //
                        + "And: size: %d, load: %s\n %s\n"
                        + "AndSimplify: size: %d, load: %s\n %s\n"
                        + "Xor: size: %d, load: %s\n %s\n"
                        + "XorSimplify: size: %d, load: %s\n %s\n"
                        + "Simplify: size: %d, load: %s\n %s\n"
                        + "Ite: size: %d, load: %s\n %s\n"
                        + "Satisfaction: size: %d, load: %s\n %s\n"
                        + "Implies: size: %d, load: %s\n %s\n"
                        + "Intersects: size: %d, load: %s\n %s\n"
                        + "Compose: current size: %d, load: %s\n %s\n Reuse count: %d\n"
                        + "Quantification: current size: %d, load: %s\n %s\n Reuse count: %d\n"
                        + "Partial invalidations: %d",
                andCache.size(),
                andCache.loadFactor(),
                andCache.statistics,
                andSimplifyCache.size(),
                andSimplifyCache.loadFactor(),
                andSimplifyCache.statistics,
                xorCache.size(),
                xorCache.loadFactor(),
                xorCache.statistics,
                xorSimplifyCache.size(),
                xorSimplifyCache.loadFactor(),
                xorSimplifyCache.statistics,
                simplifyCache.size(),
                simplifyCache.loadFactor(),
                simplifyCache.statistics,
                iteCache.size(),
                iteCache.loadFactor(),
                iteCache.statistics,
                satisfactionCache.size(),
                satisfactionCache.loadFactor(),
                satisfactionCache.statistics,
                impliesCache.size(),
                impliesCache.loadFactor(),
                impliesCache.statistics,
                intersectsCache.size(),
                intersectsCache.loadFactor(),
                intersectsCache.statistics,
                composeCache.size(),
                composeCache.loadFactor(),
                composeCache.statistics,
                composeReuseCount,
                existsCache.size(),
                existsCache.loadFactor(),
                existsCache.statistics,
                existsReuseCount,
                partialInvalidationCount);
    }

    private static final class CacheAccessStatistics {
        private int hitCount = 0;
        private int hitCountSinceInvalidation = 0;
        private int putCount = 0;
        private int putCountSinceInvalidation = 0;
        private int invalidationCount = 0;
        private int pruningCount = 0;
        private int totalPrunedEntries = 0;

        void cacheHit() {
            hitCount++;
            hitCountSinceInvalidation++;
        }

        void invalidation() {
            invalidationCount++;
            hitCountSinceInvalidation = 0;
            putCountSinceInvalidation = 0;
        }

        void prune(int prunedEntries) {
            pruningCount++;
            totalPrunedEntries += prunedEntries;
        }

        void put() {
            putCount++;
            putCountSinceInvalidation++;
        }

        @Override
        public String toString() {
            float hitToPutRatio = (float) hitCount / Math.max(putCount, 1);
            return String.format(
                    "Cache access: put=%d, hit=%d, hit-to-put=%3.3f%n"
                            + "       invalidation: %d times, since last: put=%d, hit=%d, prunes: %d times / %d entries",
                    putCount,
                    hitCount,
                    hitToPutRatio,
                    invalidationCount,
                    putCountSinceInvalidation,
                    hitCountSinceInvalidation,
                    pruningCount,
                    totalPrunedEntries);
        }
    }

    abstract class IntCache {
        final int arity;
        final int binSize;
        int binCount = 0;
        int[] cache = EMPTY_INT_ARRAY;
        CacheAccessStatistics statistics = new CacheAccessStatistics();
        BooleanSupplier pruningValid;

        IntCache(int arity, int binSize) {
            this.arity = arity;
            this.binSize = binSize;
            this.pruningValid = () -> true;
        }

        IntCache(int arity, int binSize, BooleanSupplier pruningValid) {
            this.arity = arity;
            this.binSize = binSize;
            this.pruningValid = pruningValid;
        }

        int size() {
            assert binCount == cache.length / binSize;
            return binCount;
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

        void prune() {
            if (statistics.putCountSinceInvalidation == 0) {
                return;
            }
            if (statistics.putCountSinceInvalidation < size() / 2 || !pruningValid.getAsBoolean()) {
                clear();
                return;
            }

            int pruned = 0;
            for (int binStart = 0; binStart < cache.length; binStart += binSize) {
                if (cache[binStart] != placeholder && !isValid(binStart)) {
                    pruned += 1;
                    cache[binStart] = placeholder;
                }
            }
            statistics.prune(pruned);
        }

        void grow(int newSize) {
            if (newSize < 2 * binCount) {
                prune();
            } else {
                int newKeyCount = Primes.nextPrime(newSize);
                int[] newCache = new int[newKeyCount * binSize];

                boolean preserve = bdd.configuration().useCachePreserveOnGrow()
                        && statistics.putCountSinceInvalidation > newKeyCount / 4;
                grow(newKeyCount, newCache, preserve);
                if (!preserve && placeholder != 0) {
                    doClear();
                }

                cache = newCache;
                binCount = newKeyCount;
                assert size() == newKeyCount;
            }
        }

        private void doClear() {
            if (binSize == 1) {
                Arrays.fill(cache, placeholder);
            } else {
                for (int i = 0; i < cache.length; i += binSize) {
                    cache[i] = placeholder;
                }
            }
        }

        void clear() {
            if (statistics.putCountSinceInvalidation == 0) {
                return;
            }
            statistics.invalidation();
            doClear();
        }

        boolean isValid(int binStart) {
            for (int j = binStart; j < binStart + arity; j++) {
                if (!bdd.isValidNonConstantFunction(cache[j])) {
                    return false;
                }
            }
            return isValidResult(binStart);
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

            for (int binIndex = 0; binIndex < binCount; binIndex++) {
                int binStart = binIndex * binSize;
                int key1 = cache[binStart];
                if (key1 == placeholder || !isValid(binStart)) {
                    if (placeholder != 0) {
                        newCache[binStart] = placeholder;
                    }
                    continue;
                }
                int key2 = cache[binStart + 1];
                int newBin = binSize * mod(HashUtil.hash(key1, key2), newSize);
                newCache[newBin] = key1;
                newCache[newBin + 1] = key2;
                newCache[newBin + 2] = cache[binStart + 2];
            }
        }

        protected int lookup(int function1, int function2) {
            assert bdd.isValidNonConstantFunction(function1) && bdd.isValidNonConstantFunction(function2);

            int hash = HashUtil.hash(function1, function2);
            lookupHash = hash;
            int binIndex = binIndex(hash);

            int binStart = binSize * binIndex;
            if (function1 == cache[binStart] && function2 == cache[binStart + 1]) {
                int result = cache[binStart + 2];
                assert bdd.isValidFunction(result);
                statistics.cacheHit();
                return result;
            }
            return placeholder;
        }

        void put(int hash, int function1, int function2, int result) {
            assert bdd.isValidNonConstantFunction(function1)
                    && bdd.isValidNonConstantFunction(function2)
                    && bdd.isValidFunction(result);
            assert hash == HashUtil.hash(function1, function2);

            int binIndex = binIndex(hash);
            statistics.put();

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

            for (int binIndex = 0; binIndex < binCount; binIndex++) {
                int binStart = binIndex * binSize;
                int key1 = cache[binStart];
                if (key1 == placeholder || !isValid(binStart)) {
                    if (placeholder != 0) {
                        newCache[binStart] = placeholder;
                    }
                    continue;
                }
                int key2 = cache[binStart + 1];
                int key3 = cache[binStart + 2];
                int newBin = binSize * mod(HashUtil.hash(key1, key2, key3), newSize);
                newCache[newBin] = key1;
                newCache[newBin + 1] = key2;
                newCache[newBin + 2] = key3;
                newCache[newBin + 3] = cache[binStart + 3];
            }
        }

        protected int lookup(int function1, int function2, int function3) {
            assert bdd.isValidNonConstantFunction(function1)
                    && bdd.isValidNonConstantFunction(function2)
                    && bdd.isValidNonConstantFunction(function3);

            int hash = HashUtil.hash(function1, function2, function3);
            lookupHash = hash;
            int binIndex = binIndex(hash);

            int binStart = binSize * binIndex;
            if (function1 == cache[binStart] && function2 == cache[binStart + 1] && function3 == cache[binStart + 2]) {
                int result = cache[binStart + 3];
                assert bdd.isValidFunction(result);
                statistics.cacheHit();
                return result;
            }
            return placeholder;
        }

        void put(int hash, int function1, int function2, int function3, int result) {
            assert bdd.isValidNonConstantFunction(function1)
                    && bdd.isValidNonConstantFunction(function2)
                    && bdd.isValidNonConstantFunction(function3)
                    && bdd.isValidFunction(result);
            assert hash == HashUtil.hash(function1, function2, function3);

            int binIndex = binIndex(hash);
            statistics.put();

            int binStart = binSize * binIndex;
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
                for (int binIndex = 0; binIndex < binCount; binIndex++) {
                    int binStart = binIndex * binSize;
                    int key1 = cache[binStart];
                    if (key1 == placeholder || !isValid(binStart)) {
                        if (placeholder != 0) {
                            newCache[binStart] = placeholder;
                        }
                        continue;
                    }
                    int key2 = cache[binStart + 1];
                    int newBinIndex = mod(HashUtil.hash(key1, key2), newSize);
                    int newBinStart = binSize * newBinIndex;
                    newCache[newBinStart] = key1;
                    newCache[newBinStart + 1] = key2;
                    if (values.get(binIndex)) {
                        newValues.set(binIndex);
                    }
                }
                this.values = newValues;
            } else {
                values.clear();
            }
        }

        protected int lookup(int function1, int function2) {
            assert bdd.isValidNonConstantFunction(function1) && bdd.isValidNonConstantFunction(function2);

            int hash = HashUtil.hash(function1, function2);
            lookupHash = hash;
            int binIndex = binIndex(hash);

            int binStart = binSize * binIndex;
            if (function1 == cache[binStart] && function2 == cache[binStart + 1]) {
                statistics.cacheHit();
                return values.get(binIndex) ? bdd.trueFunction() : bdd.falseFunction();
            }
            return placeholder;
        }

        void put(int hash, int function1, int function2, boolean result) {
            assert bdd.isValidNonConstantFunction(function1) && bdd.isValidNonConstantFunction(function2);
            assert hash == HashUtil.hash(function1, function2);

            int binIndex = binIndex(hash);
            statistics.put();

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

            for (int binIndex = 0; binIndex < binCount; binIndex++) {
                int binStart = binIndex * binSize;
                int key = cache[binStart];
                if (key == placeholder || !isValid(binStart)) {
                    if (placeholder != 0) {
                        newCache[binStart] = placeholder;
                    }
                    continue;
                }
                int newBinIndex = mod(HashUtil.hash(key), newSize);
                int newBinStart = binSize * newBinIndex;
                newCache[newBinStart] = key;
                newCache[newBinStart + 1] = cache[binStart + 1];
            }
        }

        protected int lookup(int function) {
            assert bdd.isValidNonConstantFunction(function);

            int hash = HashUtil.hash(function);
            lookupHash = hash;
            int binIndex = binIndex(hash);

            int binStart = binSize * binIndex;
            if (function == cache[binStart]) {
                statistics.cacheHit();
                return cache[binStart + 1];
            }
            return placeholder;
        }

        void put(int hash, int function, int result) {
            assert bdd.isValidNonConstantFunction(function) && bdd.isValidFunction(result);
            assert hash == HashUtil.hash(function);

            int binIndex = binIndex(hash);
            statistics.put();

            int binStart = binSize * binIndex;
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
                for (int binIndex = 0; binIndex < binCount; binIndex++) {
                    int binStart = binIndex * binSize;
                    int key = cache[binStart];
                    if (key == placeholder || !isValid(binStart)) {
                        if (placeholder != 0) {
                            newCache[binStart] = placeholder;
                        }
                        continue;
                    }
                    int newBinIndex = mod(HashUtil.hash(key), newSize);
                    int newBinStart = binSize * newBinIndex;
                    newCache[newBinStart] = key;
                    newValues[newBinStart] = values[binIndex];
                }
                this.values = newValues;
            } else {
                this.values = new Object[newSize];
            }
        }

        @Nullable
        protected V lookup(int function) {
            assert bdd.isValidNonConstantFunction(function);

            int hash = HashUtil.hash(function);
            lookupHash = hash;
            int binIndex = binIndex(hash);

            int binStart = binSize * binIndex;
            if (function == cache[binStart]) {
                statistics.cacheHit();
                return (V) values[binIndex];
            }
            return null;
        }

        void put(int hash, int function, V result) {
            assert bdd.isValidNonConstantFunction(function);
            assert hash == HashUtil.hash(function);

            int binIndex = binIndex(hash);
            statistics.put();

            int binStart = binSize * binIndex;
            cache[binStart] = function;
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
                logger.info(cache.bdd.statistics());
            }
        }
    }
}
