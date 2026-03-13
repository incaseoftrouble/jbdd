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
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

@SuppressWarnings("PMD.TooManyFields")
final class BooleanCache {
    private static final Logger logger = Logger.getLogger(BooleanCache.class.getName());

    @SuppressWarnings("StaticCollection")
    private static final Collection<BooleanCache> cacheShutdownHook = new ConcurrentLinkedDeque<>();

    private static final int[] EMPTY_INT_ARRAY = new int[0];
    private static final BigInteger[] EMPTY_BIGINT_ARRAY = new BigInteger[0];

    private final BooleanBase<?, ?> associatedBdd;
    private final int placeholder;
    private final CacheAccessStatistics andAccessStatistics = new CacheAccessStatistics();
    private final CacheAccessStatistics xorAccessStatistics = new CacheAccessStatistics();
    private final CacheAccessStatistics simplifyAccessStatistics = new CacheAccessStatistics();
    private final CacheAccessStatistics impliesAccessStatistics = new CacheAccessStatistics();
    private final CacheAccessStatistics intersectsAccessStatistics = new CacheAccessStatistics();
    private final CacheAccessStatistics satisfactionAccessStatistics = new CacheAccessStatistics();
    private final CacheAccessStatistics iteAccessStatistics = new CacheAccessStatistics();
    private final CacheAccessStatistics composeAccessStatistics = new CacheAccessStatistics();
    private int composeReuseCount = 0;
    private final CacheAccessStatistics quantificationAccessStatistics = new CacheAccessStatistics();
    private int quantificationReuseCount = 0;
    private int partialInvalidationCount = 0;

    private int andKeyCount = 0;
    private int[] andCache = EMPTY_INT_ARRAY;

    private int xorKeyCount = 0;
    private int[] xorCache = EMPTY_INT_ARRAY;

    private int simplifyKeyCount = 0;
    private int[] simplifyCache = EMPTY_INT_ARRAY;

    private int impliesKeyCount = 0;
    private int[] impliesCache = EMPTY_INT_ARRAY;
    private final BitSet impliesValues = new BitSet();

    private int intersectsKeyCount = 0;
    private int[] intersectsCache = EMPTY_INT_ARRAY;
    private final BitSet intersectsValues = new BitSet();

    private int iteKeyCount = 0;
    private int[] iteCache = EMPTY_INT_ARRAY;

    private int[] composeArray = EMPTY_INT_ARRAY;
    private int composeHighestReplacement = -1;
    private int composeKeyCount = 0;
    private int[] composeCache = EMPTY_INT_ARRAY;

    private BitSet quantificationVariables = new BitSet(0);
    private int quantificationKeyCount = 0;
    private int[] quantificationCache = EMPTY_INT_ARRAY;
    private final BitSet quantificationExists = new BitSet();

    private int satisfactionKeyCount = 0;
    private int[] satisfactionKey = EMPTY_INT_ARRAY;
    private BigInteger[] satisfactionResult = EMPTY_BIGINT_ARRAY;

    private int lookupHash;
    private int lookupResult;

    BooleanCache(BooleanBase<?, ?> associatedBdd) {
        this.associatedBdd = associatedBdd;
        this.placeholder = associatedBdd.placeholder();
        this.lookupHash = -1;
        this.lookupResult = placeholder;

        BddConfiguration configuration = associatedBdd.configuration();
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
        int node1var = associatedBdd.decisionVariable(node1);
        int node2var = associatedBdd.decisionVariable(node2);
        return node1var < node2var || (node1var == node2var && node1 < node2);
    }

    int lookupHash() {
        return lookupHash;
    }

    int lookupResult() {
        return lookupResult;
    }

    // Load factors

    private float andLoadFactor() {
        int loadedAndBins = 0;
        for (int i = 0; i < andKeyCount(); i++) {
            if (andCache[3 * i] != placeholder) {
                loadedAndBins++;
            }
        }
        return (float) loadedAndBins / andKeyCount();
    }

    private float xorLoadFactor() {
        int loadedXorBins = 0;
        for (int i = 0; i < xorKeyCount(); i++) {
            if (xorCache[3 * i] != placeholder) {
                loadedXorBins++;
            }
        }
        return (float) loadedXorBins / xorKeyCount();
    }

    private float simplifyLoadFactor() {
        int loadedSimplifyBins = 0;
        for (int i = 0; i < simplifyKeyCount(); i++) {
            if (simplifyCache[3 * i] != placeholder) {
                loadedSimplifyBins++;
            }
        }
        return (float) loadedSimplifyBins / simplifyKeyCount();
    }

    private float impliesLoadFactor() {
        int loadedImpliesBins = 0;
        for (int i = 0; i < impliesKeyCount(); i++) {
            if (impliesCache[2 * i] != placeholder) {
                loadedImpliesBins++;
            }
        }
        return (float) loadedImpliesBins / impliesKeyCount();
    }

    private float intersectsLoadFactor() {
        int loadedIntersectsBins = 0;
        for (int i = 0; i < intersectsKeyCount(); i++) {
            if (intersectsCache[2 * i] != placeholder) {
                loadedIntersectsBins++;
            }
        }
        return (float) loadedIntersectsBins / intersectsKeyCount();
    }

    private float iteLoadFactor() {
        int loadedIteBins = 0;
        for (int i = 0; i < iteKeyCount(); i++) {
            if (iteCache[4 * i] != placeholder) {
                loadedIteBins++;
            }
        }
        return (float) loadedIteBins / iteKeyCount();
    }

    private float satisfactionLoadFactor() {
        int loadedSatisfactionBins = 0;
        for (int i = 0; i < satisfactionKeyCount(); i++) {
            if (satisfactionKey[i] != placeholder) {
                loadedSatisfactionBins++;
            }
        }
        return (float) loadedSatisfactionBins / satisfactionKeyCount();
    }

    private float composeLoadFactor() {
        int loadedComposeBins = 0;
        for (int i = 0; i < composeKeyCount(); i++) {
            if (composeCache[2 * i] != placeholder) {
                loadedComposeBins++;
            }
        }
        return (float) loadedComposeBins / composeKeyCount();
    }

    private float quantificationLoadFactor() {
        int loadedQuantificationBins = 0;
        for (int i = 0; i < quantificationKeyCount(); i++) {
            if (quantificationCache[2 * i] != placeholder) {
                loadedQuantificationBins++;
            }
        }
        return (float) loadedQuantificationBins / quantificationKeyCount();
    }

    // Key mapping

    private int andCachePosition(int hash) {
        return mod(hash, andKeyCount());
    }

    private int andKeyCount() {
        assert andKeyCount == andCache.length / 3;
        return andKeyCount;
    }

    private int xorCachePosition(int hash) {
        return mod(hash, xorKeyCount());
    }

    private int xorKeyCount() {
        assert xorKeyCount == xorCache.length / 3;
        return xorKeyCount;
    }

    private int simplifyCachePosition(int hash) {
        return mod(hash, simplifyKeyCount());
    }

    private int simplifyKeyCount() {
        assert simplifyKeyCount == simplifyCache.length / 3;
        return simplifyKeyCount;
    }

    private int impliesCachePosition(int hash) {
        return mod(hash, impliesKeyCount());
    }

    private int impliesKeyCount() {
        assert impliesKeyCount == impliesCache.length / 2;
        return impliesKeyCount;
    }

    private int intersectsCachePosition(int hash) {
        return mod(hash, intersectsKeyCount());
    }

    private int intersectsKeyCount() {
        assert intersectsKeyCount == intersectsCache.length / 2;
        return intersectsKeyCount;
    }

    private int iteCachePosition(int hash) {
        return mod(hash, iteKeyCount());
    }

    private int iteKeyCount() {
        assert iteKeyCount == iteCache.length / 4;
        return iteKeyCount;
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

    private int quantificationCachePosition(int hash) {
        return mod(hash, quantificationKeyCount());
    }

    private int quantificationKeyCount() {
        assert quantificationKeyCount == quantificationCache.length / 2;
        return quantificationKeyCount;
    }

    // Size and invalidation

    public void tableSizeChanged() {
        logger.log(Level.FINER, "Growing caches if necessary");
        growAnd();
        growXor();
        growSimplify();
        growImplies();
        growIntersects();
        growIte();
        growSatisfaction();
        growCompose();
        growQuantification();
    }

    public void variablesChanged() {
        growSatisfaction();
        growCompose();
        growQuantification();
    }

    private void pruneAnd() {
        if (andAccessStatistics.putCountSinceInvalidation == 0) {
            return;
        }
        if (andAccessStatistics.putCountSinceInvalidation < andKeyCount / 2) {
            andAccessStatistics.invalidation();
            clearAnd();
            return;
        }

        BooleanBase<?, ?> bdd = associatedBdd;
        andAccessStatistics.partialInvalidation();
        int[] andCache = this.andCache;
        for (int i = 0; i < andKeyCount(); i++) {
            int binStart = 3 * i;
            if (!(bdd.isValidNonConstantFunction(andCache[binStart])
                    && bdd.isValidNonConstantFunction(andCache[binStart + 1])
                    && bdd.isValidFunction(andCache[binStart + 2]))) {
                andCache[binStart] = placeholder;
            }
        }
    }

    private void pruneXor() {
        if (xorAccessStatistics.putCountSinceInvalidation == 0) {
            return;
        }
        if (xorAccessStatistics.putCountSinceInvalidation < xorKeyCount / 2) {
            xorAccessStatistics.invalidation();
            clearXor();
            return;
        }

        BooleanBase<?, ?> bdd = associatedBdd;
        xorAccessStatistics.partialInvalidation();
        int[] xorCache = this.xorCache;
        for (int i = 0; i < xorKeyCount(); i++) {
            int binStart = 3 * i;
            if (!(bdd.isValidNonConstantFunction(xorCache[binStart])
                    && bdd.isValidNonConstantFunction(xorCache[binStart + 1])
                    && bdd.isValidFunction(xorCache[binStart + 2]))) {
                xorCache[binStart] = placeholder;
            }
        }
    }

    private void pruneSimplify() {
        if (simplifyAccessStatistics.putCountSinceInvalidation == 0) {
            return;
        }
        if (simplifyAccessStatistics.putCountSinceInvalidation < simplifyKeyCount / 2) {
            simplifyAccessStatistics.invalidation();
            clearSimplify();
            return;
        }

        BooleanBase<?, ?> bdd = associatedBdd;
        simplifyAccessStatistics.partialInvalidation();
        int[] simplifyCache = this.simplifyCache;
        for (int i = 0; i < simplifyKeyCount(); i++) {
            int binStart = 3 * i;
            if (!(bdd.isValidNonConstantFunction(simplifyCache[binStart])
                    && bdd.isValidNonConstantFunction(simplifyCache[binStart + 1])
                    && bdd.isValidFunction(simplifyCache[binStart + 2]))) {
                simplifyCache[binStart] = placeholder;
            }
        }
    }

    private void pruneImplies() {
        if (impliesAccessStatistics.putCountSinceInvalidation == 0) {
            return;
        }
        if (impliesAccessStatistics.putCountSinceInvalidation < impliesKeyCount / 2) {
            impliesAccessStatistics.invalidation();
            clearImplies();
            return;
        }

        BooleanBase<?, ?> bdd = associatedBdd;
        impliesAccessStatistics.partialInvalidation();
        int[] impliesCache = this.impliesCache;
        for (int i = 0; i < impliesKeyCount(); i++) {
            if (impliesCache[i] == placeholder) {
                continue;
            }
            int binStart = 2 * i;
            if (!(bdd.isValidNonConstantFunction(impliesCache[binStart])
                    && bdd.isValidNonConstantFunction(impliesCache[binStart + 1]))) {
                impliesCache[i] = placeholder;
            }
        }
    }

    private void pruneIntersects() {
        if (intersectsAccessStatistics.putCountSinceInvalidation == 0) {
            return;
        }
        if (intersectsAccessStatistics.putCountSinceInvalidation < intersectsKeyCount / 2) {
            intersectsAccessStatistics.invalidation();
            clearIntersects();
            return;
        }

        BooleanBase<?, ?> bdd = associatedBdd;
        intersectsAccessStatistics.partialInvalidation();
        int[] intersectsCache = this.intersectsCache;
        for (int i = 0; i < intersectsKeyCount(); i++) {
            if (intersectsCache[i] == placeholder) {
                continue;
            }
            int binStart = 2 * i;
            if (!(bdd.isValidNonConstantFunction(intersectsCache[binStart])
                    && bdd.isValidNonConstantFunction(intersectsCache[binStart + 1]))) {
                intersectsCache[i] = placeholder;
            }
        }
    }

    private void pruneIte() {
        if (iteAccessStatistics.putCountSinceInvalidation == 0) {
            return;
        }
        if (iteAccessStatistics.putCountSinceInvalidation < iteKeyCount / 4) {
            iteAccessStatistics.invalidation();
            clearIte();
            return;
        }

        BooleanBase<?, ?> bdd = associatedBdd;
        iteAccessStatistics.partialInvalidation();
        int[] iteCache = this.iteCache;
        for (int binStart = 0; binStart < iteCache.length; binStart += 4) {
            int first = iteCache[binStart];
            if (first == placeholder) {
                continue;
            }
            if (!(bdd.isValidNonConstantFunction(first)
                    && bdd.isValidNonConstantFunction(iteCache[binStart + 1])
                    && bdd.isValidNonConstantFunction(iteCache[binStart + 2])
                    && bdd.isValidFunction(iteCache[binStart + 3]))) {
                iteCache[binStart] = placeholder;
            }
        }
    }

    private void pruneSatisfaction() {
        if (satisfactionAccessStatistics.putCountSinceInvalidation == 0) {
            return;
        }
        if (satisfactionAccessStatistics.putCountSinceInvalidation < satisfactionKeyCount / 2) {
            satisfactionAccessStatistics.invalidation();
            clearSatisfaction();
            return;
        }

        satisfactionAccessStatistics.partialInvalidation();
        int[] satisfactionKey = this.satisfactionKey;
        for (int i = 0; i < satisfactionKeyCount(); i++) {
            if (!associatedBdd.isValidNonConstantFunction(satisfactionKey[i])) {
                satisfactionKey[i] = placeholder;
            }
        }
    }

    private void pruneCompose() {
        if (composeAccessStatistics.putCountSinceInvalidation == 0) {
            return;
        }
        if (composeAccessStatistics.putCountSinceInvalidation < composeKeyCount / 2) {
            composeAccessStatistics.invalidation();
            clearCompose();
            return;
        }

        BooleanBase<?, ?> bdd = associatedBdd;
        int[] composeCache = this.composeCache;
        boolean composeAllValid = true;
        for (int composeNode : composeArray) {
            if (!bdd.isValidFunction(composeNode)) {
                composeAllValid = false;
                break;
            }
        }
        if (composeAllValid) {
            composeAccessStatistics.partialInvalidation();
            for (int binStart = 0; binStart < composeCache.length; binStart += 2) {
                int first = composeCache[binStart];
                if (first == placeholder) {
                    continue;
                }
                if (!(bdd.isValidNonConstantFunction(first) && bdd.isValidFunction(composeCache[binStart + 1]))) {
                    composeCache[binStart] = placeholder;
                }
            }
        } else {
            clearCompose();
        }
    }

    private void pruneQuantification() {
        if (quantificationAccessStatistics.putCountSinceInvalidation == 0) {
            return;
        }
        if (quantificationAccessStatistics.putCountSinceInvalidation < quantificationKeyCount / 2) {
            quantificationAccessStatistics.invalidation();
            clearQuantification();
            return;
        }

        BooleanBase<?, ?> bdd = associatedBdd;
        quantificationAccessStatistics.partialInvalidation();
        int[] quantificationCache = this.quantificationCache;
        for (int binStart = 0; binStart < quantificationCache.length; binStart += 2) {
            int first = quantificationCache[binStart];
            if (first == placeholder) {
                continue;
            }
            if (!(bdd.isValidNonConstantFunction(first) && bdd.isValidFunction(quantificationCache[binStart + 1]))) {
                quantificationCache[binStart] = placeholder;
            }
        }
    }

    public void partialInvalidate() {
        partialInvalidationCount += 1;
        pruneAnd();
        pruneXor();
        pruneSimplify();
        pruneImplies();
        pruneIntersects();
        pruneIte();
        pruneSatisfaction();
        pruneCompose();
        pruneQuantification();
    }

    private void growAnd() {
        BooleanBase<?, ?> bdd = associatedBdd;
        int size = bdd.tableSize() / bdd.configuration().cacheBinaryDivider();
        if (size < 2 * andKeyCount()) {
            pruneAnd();
        } else {
            int keyCount = Primes.nextPrime(size);
            int[] newAnd = new int[keyCount * 3];

            if (bdd.configuration().useCachePreserveOnGrow()
                    && andAccessStatistics.putCountSinceInvalidation > andKeyCount / 4) {
                for (int i = 0; i < andKeyCount; i++) {
                    int binStart = 3 * i;
                    int input1 = andCache[binStart];
                    if (input1 == placeholder) {
                        if (placeholder != 0) {
                            andCache[binStart] = placeholder;
                        }
                        continue;
                    }
                    int input2 = andCache[binStart + 1];
                    int result = andCache[binStart + 2];
                    if (!(bdd.isValidNonConstantFunction(input1)
                            && bdd.isValidNonConstantFunction(input2)
                            && bdd.isValidFunction(result))) {
                        continue;
                    }
                    int newPosition = mod(HashUtil.hash(input1, input2), keyCount);
                    int newBinStart = 3 * newPosition;
                    newAnd[newBinStart] = input1;
                    newAnd[newBinStart + 1] = input2;
                    newAnd[newBinStart + 2] = result;
                }
            }

            andCache = newAnd;
            andKeyCount = keyCount;
            assert andKeyCount() == keyCount;
        }
    }

    private void clearAnd() {
        for (int i = 0; i < andCache.length; i += 3) {
            andCache[i] = placeholder;
        }
    }

    private void growXor() {
        BooleanBase<?, ?> bdd = associatedBdd;
        int size = bdd.tableSize() / bdd.configuration().cacheBinaryDivider();
        if (size < 2 * xorKeyCount()) {
            pruneXor();
        } else {
            int keyCount = Primes.nextPrime(size);
            int[] newXor = new int[keyCount * 3];

            if (bdd.configuration().useCachePreserveOnGrow()
                    && xorAccessStatistics.putCountSinceInvalidation > xorKeyCount / 4) {
                for (int i = 0; i < xorKeyCount; i++) {
                    int binStart = 3 * i;
                    int input1 = xorCache[binStart];
                    if (input1 == placeholder) {
                        if (placeholder != 0) {
                            xorCache[binStart] = placeholder;
                        }
                        continue;
                    }
                    int input2 = xorCache[binStart + 1];
                    int result = xorCache[binStart + 2];
                    if (!(bdd.isValidNonConstantFunction(input1)
                            && bdd.isValidNonConstantFunction(input2)
                            && bdd.isValidFunction(result))) {
                        continue;
                    }
                    int newPosition = mod(HashUtil.hash(input1, input2), keyCount);
                    int newBinStart = 3 * newPosition;
                    newXor[newBinStart] = input1;
                    newXor[newBinStart + 1] = input2;
                    newXor[newBinStart + 2] = result;
                }
            }

            xorCache = newXor;
            xorKeyCount = keyCount;
            assert xorKeyCount() == keyCount;
        }
    }

    private void clearXor() {
        for (int i = 0; i < xorCache.length; i += 3) {
            xorCache[i] = placeholder;
        }
    }

    private void growSimplify() {
        BooleanBase<?, ?> bdd = associatedBdd;
        int size = bdd.tableSize() / bdd.configuration().cacheBinaryDivider();
        if (size < 2 * simplifyKeyCount()) {
            pruneSimplify();
        } else {
            int keyCount = Primes.nextPrime(size);
            int[] newSimplify = new int[keyCount * 3];

            if (bdd.configuration().useCachePreserveOnGrow()
                    && simplifyAccessStatistics.putCountSinceInvalidation > simplifyKeyCount / 4) {
                for (int i = 0; i < simplifyKeyCount; i++) {
                    int binStart = 3 * i;
                    int input1 = simplifyCache[binStart];
                    if (input1 == placeholder) {
                        if (placeholder != 0) {
                            simplifyCache[binStart] = placeholder;
                        }
                        continue;
                    }
                    int input2 = simplifyCache[binStart + 1];
                    int result = simplifyCache[binStart + 2];
                    if (!(bdd.isValidNonConstantFunction(input1)
                            && bdd.isValidNonConstantFunction(input2)
                            && bdd.isValidFunction(result))) {
                        continue;
                    }
                    int newPosition = mod(HashUtil.hash(input1, input2), keyCount);
                    int newBinStart = 3 * newPosition;
                    newSimplify[newBinStart] = input1;
                    newSimplify[newBinStart + 1] = input2;
                    newSimplify[newBinStart + 2] = result;
                }
            }

            simplifyCache = newSimplify;
            simplifyKeyCount = keyCount;
            assert simplifyKeyCount() == keyCount;
        }
    }

    private void clearSimplify() {
        for (int i = 0; i < simplifyCache.length; i += 3) {
            simplifyCache[i] = placeholder;
        }
    }

    private void growImplies() {
        int size = associatedBdd.tableSize() / associatedBdd.configuration().cacheBinaryDivider();
        if (size < 2 * impliesKeyCount) {
            pruneImplies();
        } else {
            int keyCount = Primes.nextPrime(size);
            impliesCache = new int[keyCount * 2];
            impliesKeyCount = keyCount;
            assert impliesKeyCount() == keyCount;
        }
    }

    private void clearImplies() {
        for (int i = 0; i < impliesCache.length; i += 2) {
            impliesCache[i] = placeholder;
        }
    }

    private void growIntersects() {
        int size = associatedBdd.tableSize() / associatedBdd.configuration().cacheBinaryDivider();
        if (size < 2 * intersectsKeyCount) {
            pruneIntersects();
        } else {
            int keyCount = Primes.nextPrime(size);
            intersectsCache = new int[keyCount * 2];
            intersectsKeyCount = keyCount;
            assert intersectsKeyCount() == keyCount;
        }
    }

    private void clearIntersects() {
        for (int i = 0; i < intersectsCache.length; i += 2) {
            intersectsCache[i] = placeholder;
        }
    }

    private void growIte() {
        BooleanBase<?, ?> bdd = associatedBdd;
        int size = bdd.tableSize() / bdd.configuration().cacheTernaryDivider();

        if (size < 2 * iteKeyCount) {
            pruneIte();
        } else {
            int keyCount = Primes.nextPrime(size);
            int[] newIte = new int[keyCount * 4];

            if (bdd.configuration().useCachePreserveOnGrow()
                    && iteAccessStatistics.putCountSinceInvalidation > iteKeyCount / 4) {
                for (int i = 0; i < iteKeyCount; i++) {
                    int binStart = 4 * i;
                    int input1 = iteCache[binStart];
                    if (input1 == placeholder) {
                        if (placeholder != 0) {
                            newIte[binStart] = placeholder;
                        }
                        continue;
                    }
                    int input2 = iteCache[binStart + 1];
                    int input3 = iteCache[binStart + 2];
                    int result = iteCache[binStart + 3];
                    if (!(bdd.isValidNonConstantFunction(input1)
                            && bdd.isValidNonConstantFunction(input2)
                            && bdd.isValidNonConstantFunction(input3)
                            && bdd.isValidFunction(result))) {
                        if (placeholder != 0) {
                            newIte[binStart] = placeholder;
                        }
                        continue;
                    }
                    int newPosition = mod(HashUtil.hash(input1, input2, input3), keyCount);
                    int newBinStart = 4 * newPosition;
                    newIte[newBinStart] = input1;
                    newIte[newBinStart + 1] = input2;
                    newIte[newBinStart + 2] = input3;
                    newIte[newBinStart + 3] = result;
                }

                iteCache = newIte;
                iteKeyCount = keyCount;
            } else {
                iteCache = newIte;
                iteKeyCount = keyCount;
                if (placeholder != 0) {
                    clearIte();
                }
            }

            assert iteKeyCount() == keyCount;
        }
    }

    private void clearIte() {
        for (int i = 0; i < iteCache.length; i += 4) {
            iteCache[i] = placeholder;
        }
    }

    private void growSatisfaction() {
        int size = associatedBdd.tableSize() / associatedBdd.configuration().cacheUnaryDivider();
        if (size < 2 * satisfactionKeyCount) {
            pruneSatisfaction();
        } else {
            int keyCount = Primes.nextPrime(size);
            satisfactionKey = new int[keyCount];
            satisfactionResult = new BigInteger[keyCount];
            satisfactionKeyCount = keyCount;
            if (placeholder != 0) {
                clearSatisfaction();
            }
            assert satisfactionKeyCount() == keyCount;
        }
    }

    private void clearSatisfaction() {
        Arrays.fill(satisfactionKey, placeholder);
    }

    private void growCompose() {
        BooleanBase<?, ?> bdd = associatedBdd;
        int size = bdd.numberOfVariables() * bdd.configuration().cacheUnaryDivider();
        if (size < 2 * composeKeyCount) {
            pruneCompose();
        } else {
            int keyCount = Primes.nextPrime(size);
            int[] newCompose = new int[keyCount * 2];

            if (bdd.configuration().useCachePreserveOnGrow()
                    && composeAccessStatistics.putCountSinceInvalidation > composeKeyCount / 4) {
                for (int i = 0; i < composeKeyCount; i++) {
                    int binStart = 2 * i;
                    int input = composeCache[binStart];
                    int result = composeCache[binStart + 1];
                    if (!(bdd.isValidNonConstantFunction(input) && bdd.isValidFunction(result))) {
                        if (placeholder != 0) {
                            newCompose[binStart] = placeholder;
                        }
                        continue;
                    }
                    int newPosition = mod(HashUtil.hash(input), keyCount);
                    int newBinStart = 2 * newPosition;
                    newCompose[newBinStart] = input;
                    newCompose[newBinStart + 1] = result;
                }
                composeCache = newCompose;
                composeKeyCount = keyCount;
            } else {
                composeCache = newCompose;
                composeKeyCount = keyCount;
                if (placeholder != 0) {
                    clearCompose();
                }
            }

            assert composeKeyCount() == keyCount;
        }
    }

    private void clearCompose() {
        for (int i = 0; i < composeCache.length; i += 2) {
            composeCache[i] = placeholder;
        }
    }

    private void growQuantification() {
        int size = associatedBdd.numberOfVariables()
                * associatedBdd.configuration().cacheEphemeralMultiplier();
        if (size < 2 * quantificationKeyCount) {
            pruneQuantification();
        } else {
            int keyCount = Primes.nextPrime(size);
            quantificationCache = new int[keyCount * 2];
            quantificationKeyCount = keyCount;
            assert quantificationKeyCount() == keyCount;
        }
    }

    private void clearQuantification() {
        for (int i = 0; i < quantificationCache.length; i += 2) {
            quantificationCache[i] = placeholder;
        }
    }

    // Lookup

    void initCompose(int[] replacements, int highestReplacement) {
        if (this.composeHighestReplacement == highestReplacement) {
            int mismatch = Arrays.mismatch(composeArray, replacements);
            if (mismatch == -1 || mismatch > highestReplacement) {
                composeReuseCount += 1;
                return;
            }
        }
        this.composeArray = Arrays.copyOf(replacements, highestReplacement);
        this.composeHighestReplacement = highestReplacement;
        composeAccessStatistics.invalidation();
        clearCompose();
    }

    void initQuantification(BitSet quantifiedVariables) {
        if (quantifiedVariables.equals(this.quantificationVariables)) {
            quantificationReuseCount += 1;
            return;
        }
        this.quantificationVariables = quantifiedVariables;
        quantificationAccessStatistics.invalidation();
        clearQuantification();
    }

    boolean lookupAnd(int function1, int function2) {
        assert binarySymmetricWellOrdered(function1, function2);
        assert associatedBdd.isValidNonConstantFunction(function1)
                && associatedBdd.isValidNonConstantFunction(function2);

        int hash = HashUtil.hash(function1, function2);
        lookupHash = hash;
        int cachePosition = andCachePosition(hash);

        int binStart = 3 * cachePosition;
        if (function1 == andCache[binStart] && function2 == andCache[binStart + 1]) {
            int result = andCache[binStart + 2];
            lookupResult = result;

            assert associatedBdd.isValidFunction(result);
            andAccessStatistics.cacheHit();
            return true;
        }
        return false;
    }

    boolean lookupXor(int function1, int function2) {
        assert binarySymmetricWellOrdered(function1, function2);
        assert associatedBdd.isValidNonConstantFunction(function1)
                && associatedBdd.isValidNonConstantFunction(function2);

        int hash = HashUtil.hash(function1, function2);
        lookupHash = hash;
        int cachePosition = xorCachePosition(hash);

        int binStart = 3 * cachePosition;
        if (function1 == xorCache[binStart] && function2 == xorCache[binStart + 1]) {
            int result = xorCache[binStart + 2];
            lookupResult = result;

            assert associatedBdd.isValidFunction(result);
            xorAccessStatistics.cacheHit();
            return true;
        }
        return false;
    }

    boolean lookupSimplify(int function, int domain) {
        assert associatedBdd.isValidNonConstantFunction(function) && associatedBdd.isValidNonConstantFunction(domain);

        int hash = HashUtil.hash(function, domain);
        lookupHash = hash;
        int cachePosition = simplifyCachePosition(hash);

        int binStart = 3 * cachePosition;
        if (function == simplifyCache[binStart] && domain == simplifyCache[binStart + 1]) {
            int result = simplifyCache[binStart + 2];
            lookupResult = result;

            assert associatedBdd.isValidFunction(result);
            simplifyAccessStatistics.cacheHit();
            return true;
        }
        return false;
    }

    boolean lookupImplies(int function1, int function2) {
        assert associatedBdd.isValidNonConstantFunction(function1)
                && associatedBdd.isValidNonConstantFunction(function2);

        int hash = HashUtil.hash(function1, function2);
        lookupHash = hash;
        int cachePosition = impliesCachePosition(hash);

        int binStart = 2 * cachePosition;
        if (function1 == impliesCache[binStart] && function2 == impliesCache[binStart + 1]) {
            lookupResult =
                    impliesValues.get(cachePosition) ? associatedBdd.trueFunction() : associatedBdd.falseFunction();
            impliesAccessStatistics.cacheHit();
            return true;
        }
        return false;
    }

    public boolean lookupIntersects(int function1, int function2) {
        assert associatedBdd.isValidNonConstantFunction(function1)
                && associatedBdd.isValidNonConstantFunction(function2);

        int hash = HashUtil.hash(function1, function2);
        lookupHash = hash;
        int cachePosition = intersectsCachePosition(hash);

        int binStart = 2 * cachePosition;
        if (function1 == intersectsCache[binStart] && function2 == intersectsCache[binStart + 1]) {
            lookupResult =
                    intersectsValues.get(cachePosition) ? associatedBdd.trueFunction() : associatedBdd.falseFunction();
            intersectsAccessStatistics.cacheHit();
            return true;
        }
        return false;
    }

    boolean lookupIfThenElse(int function1, int function2, int function3) {
        assert associatedBdd.isValidNonConstantFunction(function1)
                && associatedBdd.isValidNonConstantFunction(function2)
                && associatedBdd.isValidNonConstantFunction(function3);

        int hash = HashUtil.hash(function1, function2, function3);
        lookupHash = hash;
        int cachePosition = iteCachePosition(hash);

        int binStart = 4 * cachePosition;
        if (function1 == iteCache[binStart]
                && function2 == iteCache[binStart + 1]
                && function3 == iteCache[binStart + 2]) {
            int result = iteCache[binStart + 3];
            lookupResult = result;
            assert associatedBdd.isValidFunction(result);
            iteAccessStatistics.cacheHit();
            return true;
        }
        return false;
    }

    @Nullable
    BigInteger lookupSatisfaction(int node) {
        assert associatedBdd.isValidNonConstantFunction(node);

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

    boolean lookupCompose(int inputNode) {
        assert associatedBdd.isValidNonConstantFunction(inputNode);

        int hash = HashUtil.hash(inputNode);
        lookupHash = hash;

        int cachePosition = composeCachePosition(hash);
        int[] composeCache = this.composeCache;

        int binStart = 2 * cachePosition;
        if (composeCache[binStart] == inputNode) {
            int result = composeCache[binStart + 1];
            lookupResult = result;
            assert associatedBdd.isValidFunction(result);
            composeAccessStatistics.cacheHit();
            return true;
        }
        return false;
    }

    boolean lookupQuantification(int inputNode, boolean exists) {
        assert associatedBdd.isValidNonConstantFunction(inputNode);

        int hash = HashUtil.hash(inputNode, exists);
        lookupHash = hash;

        int cachePosition = quantificationCachePosition(hash);
        int[] quantificationCache = this.quantificationCache;

        int binStart = 2 * cachePosition;
        if (quantificationCache[binStart] == inputNode && quantificationExists.get(cachePosition) == exists) {
            int result = quantificationCache[binStart + 1];
            lookupResult = result;
            assert associatedBdd.isValidFunction(result);
            quantificationAccessStatistics.cacheHit();
            return true;
        }
        return false;
    }

    // Put

    void putAnd(int hash, int function1, int function2, int result) {
        assert binarySymmetricWellOrdered(function1, function2);
        assert associatedBdd.isValidNonConstantFunction(function1)
                && associatedBdd.isValidNonConstantFunction(function2)
                && associatedBdd.isValidFunction(result);
        assert hash == HashUtil.hash(function1, function2);

        int cachePosition = andCachePosition(hash);
        andAccessStatistics.put();

        int binStart = 3 * cachePosition;
        andCache[binStart] = function1;
        andCache[binStart + 1] = function2;
        andCache[binStart + 2] = result;
    }

    void putXor(int hash, int function1, int function2, int result) {
        assert binarySymmetricWellOrdered(function1, function2);
        assert associatedBdd.isValidNonConstantFunction(function1)
                && associatedBdd.isValidNonConstantFunction(function2)
                && associatedBdd.isValidFunction(result);
        assert hash == HashUtil.hash(function1, function2);

        int cachePosition = xorCachePosition(hash);
        xorAccessStatistics.put();

        int binStart = 3 * cachePosition;
        xorCache[binStart] = function1;
        xorCache[binStart + 1] = function2;
        xorCache[binStart + 2] = result;
    }

    void putSimplify(int hash, int function, int domain, int result) {
        assert associatedBdd.isValidNonConstantFunction(function)
                && associatedBdd.isValidNonConstantFunction(domain)
                && associatedBdd.isValidFunction(result);
        assert hash == HashUtil.hash(function, domain);

        int cachePosition = simplifyCachePosition(hash);
        simplifyAccessStatistics.put();

        int binStart = 3 * cachePosition;
        simplifyCache[binStart] = function;
        simplifyCache[binStart + 1] = domain;
        simplifyCache[binStart + 2] = result;
    }

    void putImplies(int hash, int function1, int function2, boolean result) {
        assert associatedBdd.isValidNonConstantFunction(function1)
                && associatedBdd.isValidNonConstantFunction(function2);
        assert hash == HashUtil.hash(function1, function2);

        int cachePosition = impliesCachePosition(hash);
        impliesAccessStatistics.put();

        int binStart = 2 * cachePosition;
        impliesCache[binStart] = function1;
        impliesCache[binStart + 1] = function2;
        impliesValues.set(cachePosition, result);
    }

    void putIntersects(int hash, int function1, int function2, boolean result) {
        assert associatedBdd.isValidNonConstantFunction(function1)
                && associatedBdd.isValidNonConstantFunction(function2);
        assert hash == HashUtil.hash(function1, function2);

        int cachePosition = intersectsCachePosition(hash);
        intersectsAccessStatistics.put();

        int binStart = 2 * cachePosition;
        intersectsCache[binStart] = function1;
        intersectsCache[binStart + 1] = function2;
        intersectsValues.set(cachePosition, result);
    }

    void putIfThenElse(int hash, int function1, int function2, int function3, int result) {
        assert associatedBdd.isValidNonConstantFunction(function1)
                && associatedBdd.isValidNonConstantFunction(function2)
                && associatedBdd.isValidNonConstantFunction(function3)
                && associatedBdd.isValidFunction(result);
        assert hash == HashUtil.hash(function1, function2, function3);

        iteAccessStatistics.put();
        int cachePosition = iteCachePosition(hash);

        int binStart = 4 * cachePosition;
        iteCache[binStart] = function1;
        iteCache[binStart + 1] = function2;
        iteCache[binStart + 2] = function3;
        iteCache[binStart + 3] = result;
    }

    void putSatisfaction(int hash, int function, BigInteger satisfactionCount) {
        assert associatedBdd.isValidNonConstantFunction(function);
        assert hash == HashUtil.hash(function);

        satisfactionAccessStatistics.put();
        int cachePosition = satisfactionCachePosition(hash);

        satisfactionKey[cachePosition] = function;
        satisfactionResult[cachePosition] = satisfactionCount;
    }

    void putCompose(int hash, int function, int result) {
        assert associatedBdd.isValidNonConstantFunction(function) && associatedBdd.isValidFunction(result);
        assert hash == HashUtil.hash(function);

        composeAccessStatistics.put();
        int cachePosition = composeCachePosition(hash);

        int binStart = 2 * cachePosition;
        composeCache[binStart] = function;
        composeCache[binStart + 1] = result;
    }

    void putQuantification(int hash, int inputNode, boolean exists, int result) {
        assert associatedBdd.isValidNonConstantFunction(inputNode) && associatedBdd.isValidFunction(result);
        assert hash == HashUtil.hash(inputNode, exists);

        quantificationAccessStatistics.put();
        int cachePosition = quantificationCachePosition(hash);

        int binStart = 2 * cachePosition;
        quantificationCache[binStart] = inputNode;
        quantificationCache[binStart + 1] = result;
        quantificationExists.set(cachePosition, exists);
    }

    // Utility

    public String getStatistics() {
        return String.format(
                "Cache Statistics:\n" //
                        + "And: size: %d, load: %s\n %s\n"
                        + "Xor: size: %d, load: %s\n %s\n"
                        + "Simplify: size: %d, load: %s\n %s\n"
                        + "Ite: size: %d, load: %s\n %s\n"
                        + "Satisfaction: size: %d, load: %s\n %s\n"
                        + "Implies: size: %d, load: %s\n %s\n"
                        + "Intersects: size: %d, load: %s\n %s\n"
                        + "Compose: current size: %d, load: %s\n %s\n Reuse count: %d\n"
                        + "Quantification: current size: %d, load: %s\n %s\n Reuse count: %d\n"
                        + "Partial invalidations: %d",
                andKeyCount(),
                andLoadFactor(),
                andAccessStatistics,
                xorKeyCount(),
                xorLoadFactor(),
                xorAccessStatistics,
                simplifyKeyCount(),
                simplifyLoadFactor(),
                simplifyAccessStatistics,
                iteKeyCount(),
                iteLoadFactor(),
                iteAccessStatistics,
                satisfactionKeyCount(),
                satisfactionLoadFactor(),
                satisfactionAccessStatistics,
                impliesKeyCount(),
                impliesLoadFactor(),
                impliesAccessStatistics,
                intersectsKeyCount(),
                intersectsLoadFactor(),
                intersectsAccessStatistics,
                composeKeyCount(),
                composeLoadFactor(),
                composeAccessStatistics,
                composeReuseCount,
                quantificationKeyCount(),
                quantificationLoadFactor(),
                quantificationAccessStatistics,
                quantificationReuseCount,
                partialInvalidationCount);
    }

    private static final class CacheAccessStatistics {
        private int hitCount = 0;
        private int hitCountSinceInvalidation = 0;
        private int putCount = 0;
        private int putCountSinceInvalidation = 0;
        private int invalidationCount = 0;
        private int partialInvalidationCount = 0;

        void cacheHit() {
            hitCount++;
            hitCountSinceInvalidation++;
        }

        void invalidation() {
            invalidationCount++;
            hitCountSinceInvalidation = 0;
            putCountSinceInvalidation = 0;
        }

        void partialInvalidation() {
            partialInvalidationCount++;
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
                            + "       invalidation: %d times (%d partial), since last: put=%d, hit=%d",
                    putCount,
                    hitCount,
                    hitToPutRatio,
                    invalidationCount,
                    partialInvalidationCount,
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

    private static final class ShutdownHookPrinter implements Runnable {
        @Override
        public void run() {
            if (!logger.isLoggable(Level.INFO)) {
                return;
            }
            for (BooleanCache cache : cacheShutdownHook) {
                logger.info(cache.associatedBdd.statistics());
            }
        }
    }
}
