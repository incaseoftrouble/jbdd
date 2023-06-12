/*
 * Copyright (C) 2017 (See AUTHORS)
 *
 * This file is part of JBDD.
 *
 * JBDD is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JBDD is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with JBDD.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.tum.in.jbdd;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/*
 * Possible improvements:
 *  - Not regrow every time but do partial invalidate
 */
@SuppressWarnings({"PMD.UseUtilityClass", "PMD.TooManyFields"})
final class BddCache {
    private static final byte NOT_AN_OPERATION = 0;
    private static final byte BINARY_OPERATION_AND = 1;
    private static final byte BINARY_OPERATION_EQUIVALENCE = 4;
    private static final byte BINARY_OPERATION_EXISTS = 6;
    private static final byte BINARY_OPERATION_IMPLIES = 3;
    private static final byte BINARY_OPERATION_N_AND = 7;
    private static final byte BINARY_OPERATION_OR = 2;
    private static final byte BINARY_OPERATION_XOR = 5;

    private static final Logger logger = Logger.getLogger(BddCache.class.getName());

    @SuppressWarnings("StaticCollection")
    private static final Collection<BddCache> cacheShutdownHook = new ConcurrentLinkedDeque<>();

    private static final int[] EMPTY_INT_ARRAY = new int[0];
    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
    private static final BigInteger[] EMPTY_BIGINT_ARRAY = new BigInteger[0];

    private final BddImpl associatedBdd;
    private final int placeholder;
    private final CacheAccessStatistics binaryAccessStatistics = new CacheAccessStatistics();
    private final CacheAccessStatistics satisfactionAccessStatistics = new CacheAccessStatistics();
    private final CacheAccessStatistics ternaryAccessStatistics = new CacheAccessStatistics();
    private final CacheAccessStatistics negationAccessStatistics = new CacheAccessStatistics();
    private final CacheAccessStatistics composeAccessStatistics = new CacheAccessStatistics();

    private int negationKeyCount = 0;
    private int[] negationCache = EMPTY_INT_ARRAY;

    private int binaryKeyCount = 0;
    private byte[] binaryOp = EMPTY_BYTE_ARRAY;
    private int[] binaryCache = EMPTY_INT_ARRAY;

    private int ternaryKeyCount = 0;
    private int[] ternaryCache = EMPTY_INT_ARRAY;

    private int[] composeArray = EMPTY_INT_ARRAY;
    private int composeHighestReplacement = -1;
    private int composeKeyCount = 0;
    private int[] composeCache = EMPTY_INT_ARRAY;

    private int satisfactionKeyCount = 0;
    private int[] satisfactionKey = EMPTY_INT_ARRAY;
    private BigInteger[] satisfactionResult = EMPTY_BIGINT_ARRAY;

    private int lookupHash;
    private int lookupResult;

    BddCache(BddImpl associatedBdd) {
        this.associatedBdd = associatedBdd;
        this.placeholder = associatedBdd.placeholder();
        this.lookupHash = -1;
        this.lookupResult = placeholder;

        BddConfiguration configuration = associatedBdd.getConfiguration();
        reallocateNegation();
        reallocateBinary();
        reallocateTernary();
        reallocateSatisfaction();
        reallocateCompose();

        if (logger.isLoggable(Level.INFO) && configuration.logStatisticsOnShutdown()) {
            logger.log(Level.FINER, "Adding {0} to shutdown hook", this);
            addToShutdownHook(this);
        }
    }

    private static void addToShutdownHook(BddCache cache) {
        ShutdownHookLazyHolder.init();
        cacheShutdownHook.add(cache);
    }

    private static boolean isBinaryOperation(byte operationId) {
        return operationId == BINARY_OPERATION_AND
                || operationId == BINARY_OPERATION_EQUIVALENCE
                || operationId == BINARY_OPERATION_IMPLIES
                || operationId == BINARY_OPERATION_N_AND
                || operationId == BINARY_OPERATION_OR
                || operationId == BINARY_OPERATION_XOR
                || operationId == BINARY_OPERATION_EXISTS;
    }

    private static boolean isTernaryOperation(byte operationId) {
        return operationId == 0;
    }

    private static int mod(int value, int modulus) {
        int val = value % modulus;
        return val < 0 ? val + modulus : val;
    }

    boolean binarySymmetricWellOrdered(int node1, int node2) {
        int node1var = associatedBdd.variable(node1);
        int node2var = associatedBdd.variable(node2);
        return node1var < node2var || (node1var == node2var && node1 < node2);
    }

    int lookupHash() {
        return lookupHash;
    }

    int lookupResult() {
        return lookupResult;
    }

    void clearComposeCache() {
        composeAccessStatistics.invalidation();
        for (int i = 0; i < composeCache.length; i += 2) {
            composeCache[i] = placeholder;
        }
    }

    private float negationLoadFactor() {
        int loadedNegationBins = 0;
        for (int i = 0; i < negationCacheKeyCount(); i++) {
            if (negationCache[2 * i] != placeholder) {
                loadedNegationBins++;
            }
        }
        return (float) loadedNegationBins / (float) negationCacheKeyCount();
    }

    private float binaryLoadFactor() {
        int loadedBinaryBins = 0;
        for (int i = 0; i < binaryKeyCount(); i++) {
            if (binaryOp[i] != placeholder) {
                loadedBinaryBins++;
            }
        }
        return (float) loadedBinaryBins / (float) binaryKeyCount();
    }

    private float ternaryLoadFactor() {
        int loadedTernaryBins = 0;
        for (int i = 0; i < ternaryKeyCount(); i++) {
            if (ternaryCache[4 * i] != placeholder
                    || ternaryCache[4 * i + 1] != placeholder
                    || ternaryCache[4 * i + 2] != placeholder) {
                loadedTernaryBins++;
            }
        }
        return (float) loadedTernaryBins / (float) ternaryKeyCount();
    }

    private float satisfactionLoadFactor() {
        int loadedSatisfactionBins = 0;
        for (int i = 0; i < satisfactionKeyCount(); i++) {
            if (satisfactionKey[i] != placeholder) {
                loadedSatisfactionBins++;
            }
        }
        return (float) loadedSatisfactionBins / (float) satisfactionKeyCount();
    }

    private float composeLoadFactor() {
        int loadedComposeBins = 0;
        for (int i = 0; i < composeKeyCount(); i++) {
            if (composeCache[2 * i] != placeholder) {
                loadedComposeBins++;
            }
        }
        return (float) loadedComposeBins / (float) composeKeyCount();
    }

    private int negationCachePosition(int hash) {
        return mod(hash, negationCacheKeyCount());
    }

    private int negationCacheKeyCount() {
        assert negationKeyCount == negationCache.length / 2;
        return negationKeyCount;
    }

    private int binaryCachePosition(int hash) {
        return mod(hash, binaryKeyCount());
    }

    private int binaryKeyCount() {
        assert binaryKeyCount == binaryCache.length / 3;
        return binaryKeyCount;
    }

    private int ternaryCachePosition(int hash) {
        return mod(hash, ternaryKeyCount());
    }

    private int ternaryKeyCount() {
        assert ternaryKeyCount == ternaryCache.length / 4;
        return ternaryKeyCount;
    }

    private int satisfactionCachePosition(int hash) {
        return mod(hash, satisfactionKeyCount());
    }

    private int satisfactionKeyCount() {
        assert satisfactionKeyCount == satisfactionKey.length;
        return satisfactionKeyCount;
    }

    private int composeCachePosition(int hash) {
        return mod(hash, composeKeyCount());
    }

    private int composeKeyCount() {
        assert composeKeyCount == composeCache.length / 2;
        return composeKeyCount;
    }

    void invalidate() {
        logger.log(Level.FINER, "Invalidating caches");
        negationAccessStatistics.invalidation();
        reallocateNegation();
        binaryAccessStatistics.invalidation();
        reallocateBinary();
        ternaryAccessStatistics.invalidation();
        reallocateTernary();
        satisfactionAccessStatistics.invalidation();
        reallocateSatisfaction();
        reallocateCompose();
    }

    public void variablesChanged() {
        satisfactionAccessStatistics.invalidation();
        reallocateSatisfaction();
        reallocateCompose();
    }

    boolean lookupAnd(int inputNode1, int inputNode2) {
        assert binarySymmetricWellOrdered(inputNode1, inputNode2);
        return binaryLookup(BINARY_OPERATION_AND, inputNode1, inputNode2);
    }

    boolean lookupEquivalence(int inputNode1, int inputNode2) {
        assert binarySymmetricWellOrdered(inputNode1, inputNode2);
        return binaryLookup(BINARY_OPERATION_EQUIVALENCE, inputNode1, inputNode2);
    }

    boolean lookupExists(int inputNode, int variableCube) {
        return binaryLookup(BINARY_OPERATION_EXISTS, inputNode, variableCube);
    }

    boolean lookupIfThenElse(int inputNode1, int inputNode2, int inputNode3) {
        return ternaryLookup((byte) 0, inputNode1, inputNode2, inputNode3);
    }

    boolean lookupImplication(int inputNode1, int inputNode2) {
        return binaryLookup(BINARY_OPERATION_IMPLIES, inputNode1, inputNode2);
    }

    boolean lookupNAnd(int inputNode1, int inputNode2) {
        assert binarySymmetricWellOrdered(inputNode1, inputNode2);
        return binaryLookup(BINARY_OPERATION_N_AND, inputNode1, inputNode2);
    }

    boolean lookupNot(int node) {
        return negationLookup(node);
    }

    boolean lookupOr(int inputNode1, int inputNode2) {
        assert binarySymmetricWellOrdered(inputNode1, inputNode2);
        return binaryLookup(BINARY_OPERATION_OR, inputNode1, inputNode2);
    }

    @Nullable
    BigInteger lookupSatisfaction(int node) {
        assert associatedBdd.isNodeValid(node);

        int hash = HashUtil.hash(node);
        lookupHash = hash;
        int cachePosition = satisfactionCachePosition(hash);
        int[] satisfactionKey = this.satisfactionKey;

        @SuppressWarnings("UnnecessaryLocalVariable")
        int binStart = cachePosition;
        if (satisfactionKey[binStart] == node) {
            BigInteger result = satisfactionResult[binStart];
            satisfactionAccessStatistics.cacheHit();
            return result;
        }
        return null;
    }

    void initCompose(int[] array, int highestReplacement) {
        if (this.composeHighestReplacement == highestReplacement) {
            int mismatch = Arrays.mismatch(composeArray, array);
            if (mismatch == -1 || mismatch > highestReplacement) {
                return;
            }
        }
        this.composeArray = Arrays.copyOf(array, highestReplacement);
        this.composeHighestReplacement = highestReplacement;
        clearComposeCache();
    }

    boolean lookupCompose(int inputNode) {
        assert associatedBdd.isNodeValid(inputNode);

        int hash = HashUtil.hash(inputNode);
        lookupHash = hash;

        int cachePosition = composeCachePosition(hash);
        int[] composeCache = this.composeCache;

        int binStart = 2 * cachePosition;
        if (composeCache[binStart] == inputNode) {
            int result = composeCache[binStart + 1];
            lookupResult = result;
            assert associatedBdd.isNodeValidOrLeaf(result);
            composeAccessStatistics.cacheHit();
            return true;
        }
        return false;
    }

    boolean lookupXor(int inputNode1, int inputNode2) {
        assert binarySymmetricWellOrdered(inputNode1, inputNode2);
        return binaryLookup(BINARY_OPERATION_XOR, inputNode1, inputNode2);
    }

    void putAnd(int hash, int inputNode1, int inputNode2, int resultNode) {
        assert binarySymmetricWellOrdered(inputNode1, inputNode2);
        binaryPut(BINARY_OPERATION_AND, hash, inputNode1, inputNode2, resultNode);
    }

    void putEquivalence(int hash, int inputNode1, int inputNode2, int resultNode) {
        assert binarySymmetricWellOrdered(inputNode1, inputNode2);
        binaryPut(BINARY_OPERATION_EQUIVALENCE, hash, inputNode1, inputNode2, resultNode);
    }

    void putExists(int hash, int inputNode, int variableCube, int resultNode) {
        binaryPut(BINARY_OPERATION_EXISTS, hash, inputNode, variableCube, resultNode);
    }

    void putIfThenElse(int hash, int inputNode1, int inputNode2, int inputNode3, int resultNode) {
        ternaryPut((byte) 0, hash, inputNode1, inputNode2, inputNode3, resultNode);
    }

    void putImplication(int hash, int inputNode1, int inputNode2, int resultNode) {
        binaryPut(BINARY_OPERATION_IMPLIES, hash, inputNode1, inputNode2, resultNode);
    }

    void putNAnd(int hash, int inputNode1, int inputNode2, int resultNode) {
        assert binarySymmetricWellOrdered(inputNode1, inputNode2);
        binaryPut(BINARY_OPERATION_N_AND, hash, inputNode1, inputNode2, resultNode);
    }

    void putNot(int hash, int inputNode, int resultNode) {
        assert associatedBdd.isNodeValid(inputNode) && associatedBdd.isNodeValidOrLeaf(resultNode);
        negationPut(hash, inputNode, resultNode);
    }

    void putOr(int hash, int inputNode1, int inputNode2, int resultNode) {
        assert binarySymmetricWellOrdered(inputNode1, inputNode2);
        binaryPut(BINARY_OPERATION_OR, hash, inputNode1, inputNode2, resultNode);
    }

    void putSatisfaction(int hash, int node, BigInteger satisfactionCount) {
        assert associatedBdd.isNodeValid(node);
        assert hash == HashUtil.hash(node);

        satisfactionAccessStatistics.put();
        int cachePosition = satisfactionCachePosition(hash);
        int[] satisfactionKey = this.satisfactionKey;
        BigInteger[] satisfactionResult = this.satisfactionResult;

        satisfactionKey[cachePosition] = node;
        satisfactionResult[cachePosition] = satisfactionCount;
    }

    void putCompose(int hash, int inputNode, int resultNode) {
        assert associatedBdd.isNodeValid(inputNode) && associatedBdd.isNodeValidOrLeaf(resultNode);
        assert hash == HashUtil.hash(inputNode);

        composeAccessStatistics.put();
        int cachePosition = composeCachePosition(hash);
        int[] composeCache = this.composeCache;

        int binStart = 2 * cachePosition;
        composeCache[binStart] = inputNode;
        composeCache[binStart + 1] = resultNode;
    }

    void putXor(int hash, int inputNode1, int inputNode2, int resultNode) {
        assert binarySymmetricWellOrdered(inputNode1, inputNode2);
        binaryPut(BINARY_OPERATION_XOR, hash, inputNode1, inputNode2, resultNode);
    }

    private void reallocateNegation() {
        int size = associatedBdd.tableSize() / associatedBdd.getConfiguration().cacheNegationDivider();
        boolean invalidate;
        if (size < 2 * negationKeyCount) {
            invalidate = true;
        } else {
            int keyCount = Primes.nextPrime(size);
            negationCache = new int[keyCount * 2];
            invalidate = placeholder != 0;
            negationKeyCount = keyCount;
            assert negationCacheKeyCount() == keyCount;
        }
        if (invalidate) {
            for (int i = 0; i < negationCache.length; i += 2) {
                negationCache[i] = placeholder;
            }
        }
    }

    private void reallocateBinary() {
        int size = associatedBdd.tableSize() / associatedBdd.getConfiguration().cacheBinaryDivider();
        if (size < 2 * binaryKeyCount) {
            Arrays.fill(binaryOp, NOT_AN_OPERATION);
        } else {
            int keyCount = Primes.nextPrime(size);
            binaryOp = new byte[keyCount];
            binaryCache = new int[keyCount * 3];
            binaryKeyCount = keyCount;
            assert binaryKeyCount() == keyCount;
        }
    }

    private void reallocateTernary() {
        int size = associatedBdd.tableSize() / associatedBdd.getConfiguration().cacheTernaryDivider();
        boolean invalidate;
        if (size < 2 * ternaryKeyCount) {
            invalidate = true;
        } else {
            int keyCount = Primes.nextPrime(size);
            ternaryCache = new int[keyCount * 4];
            invalidate = placeholder != 0;
            ternaryKeyCount = keyCount;
            assert ternaryKeyCount() == keyCount;
        }
        if (invalidate) {
            for (int i = 0; i < ternaryCache.length; i += 4) {
                ternaryCache[i] = placeholder;
            }
        }
    }

    private void reallocateSatisfaction() {
        int size = associatedBdd.tableSize() / associatedBdd.getConfiguration().cacheSatisfactionDivider();
        boolean invalidate;
        if (size < 2 * satisfactionKeyCount) {
            invalidate = true;
        } else {
            int keyCount = Primes.nextPrime(size);
            satisfactionKey = new int[keyCount];
            invalidate = placeholder != 0;
            satisfactionResult = new BigInteger[keyCount];
            satisfactionKeyCount = keyCount;
            assert satisfactionKeyCount() == keyCount;
        }
        if (invalidate) {
            Arrays.fill(satisfactionKey, placeholder);
        }
    }

    private void reallocateCompose() {
        int size = associatedBdd.numberOfVariables()
                * associatedBdd.getConfiguration().cacheComposeDivider();
        if (size < 2 * composeKeyCount) {
            clearComposeCache();
        } else {
            int keyCount = Primes.nextPrime(size);
            composeCache = new int[keyCount * 2];
            composeKeyCount = keyCount;
            assert composeKeyCount() == keyCount;
        }
    }

    private boolean negationLookup(int inputNode) {
        assert associatedBdd.isNodeValid(inputNode);

        int hash = HashUtil.hash(inputNode);
        lookupHash = hash;
        int cachePosition = negationCachePosition(hash);
        int[] negationCache = this.negationCache;

        int binStart = 2 * cachePosition;
        if (negationCache[binStart] == inputNode) {
            int result = negationCache[binStart + 1];
            lookupResult = result;
            assert associatedBdd.isNodeValidOrLeaf(result);
            negationAccessStatistics.cacheHit();
            return true;
        }
        return false;
    }

    private void negationPut(int hash, int inputNode, int resultNode) {
        assert associatedBdd.isNodeValid(inputNode) && associatedBdd.isNodeValidOrLeaf(resultNode);
        assert hash == HashUtil.hash(inputNode);

        negationAccessStatistics.put();
        int cachePosition = negationCachePosition(hash);
        int[] negationCache = this.negationCache;

        int binStart = 2 * cachePosition;
        negationCache[binStart] = inputNode;
        negationCache[binStart + 1] = resultNode;
    }

    private boolean binaryLookup(byte operationId, int inputNode1, int inputNode2) {
        assert isBinaryOperation(operationId);
        assert associatedBdd.isNodeValid(inputNode1) && associatedBdd.isNodeValid(inputNode2);

        int hash = HashUtil.hash(operationId, inputNode1, inputNode2);
        lookupHash = hash;
        int cachePosition = binaryCachePosition(hash);
        byte[] binaryOp = this.binaryOp;
        int[] binaryCache = this.binaryCache;

        int binStart = 3 * cachePosition;
        if (inputNode1 == binaryCache[binStart]
                && inputNode2 == binaryCache[binStart + 1]
                && operationId == binaryOp[cachePosition]) {
            int result = binaryCache[binStart + 2];
            lookupResult = result;

            assert associatedBdd.isNodeValidOrLeaf(result);
            binaryAccessStatistics.cacheHit();
            return true;
        }
        return false;
    }

    private void binaryPut(byte operationId, int hash, int inputNode1, int inputNode2, int resultNode) {
        assert isBinaryOperation(operationId);
        assert associatedBdd.isNodeValid(inputNode1)
                && associatedBdd.isNodeValid(inputNode2)
                && associatedBdd.isNodeValidOrLeaf(resultNode);
        assert hash == HashUtil.hash(operationId, inputNode1, inputNode2);

        int cachePosition = binaryCachePosition(hash);
        binaryAccessStatistics.put();
        int[] binaryCache = this.binaryCache;
        byte[] binaryOp = this.binaryOp;

        int binStart = 3 * cachePosition;
        binaryOp[cachePosition] = operationId;
        binaryCache[binStart] = inputNode1;
        binaryCache[binStart + 1] = inputNode2;
        binaryCache[binStart + 2] = resultNode;
    }

    private boolean ternaryLookup(byte operationId, int inputNode1, int inputNode2, int inputNode3) {
        assert isTernaryOperation(operationId)
                && associatedBdd.isNodeValid(inputNode1)
                && associatedBdd.isNodeValid(inputNode2)
                && associatedBdd.isNodeValid(inputNode3);
        assert isTernaryOperation(operationId);

        int hash = HashUtil.hash(inputNode1, inputNode2, inputNode3);
        lookupHash = hash;
        int cachePosition = ternaryCachePosition(hash);
        int[] ternaryCache = this.ternaryCache;

        int binStart = 4 * cachePosition;
        if (inputNode1 == ternaryCache[binStart]
                && inputNode2 == ternaryCache[binStart + 1]
                && inputNode3 == ternaryCache[binStart + 2]) {
            int result = ternaryCache[binStart + 3];
            lookupResult = result;
            assert associatedBdd.isNodeValidOrLeaf(result);
            ternaryAccessStatistics.cacheHit();
            return true;
        }
        return false;
    }

    private void ternaryPut(
            byte operationId, int hash, int inputNode1, int inputNode2, int inputNode3, int resultNode) {
        assert associatedBdd.isNodeValid(inputNode1)
                && associatedBdd.isNodeValid(inputNode2)
                && associatedBdd.isNodeValid(inputNode3)
                && associatedBdd.isNodeValidOrLeaf(resultNode);
        assert isTernaryOperation(operationId);
        assert hash == HashUtil.hash(inputNode1, inputNode2, inputNode3);

        ternaryAccessStatistics.put();
        int cachePosition = ternaryCachePosition(hash);
        int[] ternaryCache = this.ternaryCache;

        int binStart = 4 * cachePosition;
        ternaryCache[binStart] = inputNode1;
        ternaryCache[binStart + 1] = inputNode2;
        ternaryCache[binStart + 2] = inputNode3;
        ternaryCache[binStart + 3] = resultNode;
    }

    public String getStatistics() {
        return String.format(
                "Negation: size: %d, load: %s\n"
                        + " %s\nBinary: size: %d, load: %s\n"
                        + " %s\nTernary: size: %d, load: %s\n"
                        + " %s\nSatisfaction: size: %d, load: %s\n"
                        + " %s\nCompose: current size: %d, load: %s\n"
                        + " %s",
                negationCacheKeyCount(),
                negationLoadFactor(),
                negationAccessStatistics,
                binaryKeyCount(),
                binaryLoadFactor(),
                binaryAccessStatistics,
                ternaryKeyCount(),
                ternaryLoadFactor(),
                ternaryAccessStatistics,
                satisfactionKeyCount(),
                satisfactionLoadFactor(),
                satisfactionAccessStatistics,
                composeKeyCount(),
                composeLoadFactor(),
                composeAccessStatistics);
    }

    private static final class CacheAccessStatistics {
        private int hitCount = 0;
        private int hitCountSinceInvalidation = 0;
        private int putCount = 0;
        private int putCountSinceInvalidation = 0;
        private int invalidationCount = 0;

        void cacheHit() {
            hitCount++;
            hitCountSinceInvalidation++;
        }

        void invalidation() {
            invalidationCount++;
            hitCountSinceInvalidation = 0;
            putCountSinceInvalidation = 0;
        }

        void put() {
            putCount++;
            putCountSinceInvalidation++;
        }

        @Override
        public String toString() {
            float hitToPutRatio = (float) hitCount / (float) Math.max(putCount, 1);
            return String.format(
                    "Cache access: put=%d, hit=%d, hit-to-put=%3.3f%n"
                            + "       invalidation: %d times, since last: put=%d, hit=%d",
                    putCount,
                    hitCount,
                    hitToPutRatio,
                    invalidationCount,
                    putCountSinceInvalidation,
                    hitCountSinceInvalidation);
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

    @SuppressWarnings("PMD.SystemPrintln")
    private static final class ShutdownHookPrinter implements Runnable {
        @Override
        public void run() {
            if (!logger.isLoggable(Level.INFO)) {
                return;
            }
            for (BddCache cache : cacheShutdownHook) {
                logger.info(cache.associatedBdd.statistics());
                logger.info(cache.getStatistics());
            }
        }
    }
}
