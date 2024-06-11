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

@SuppressWarnings({"PMD.UseUtilityClass", "PMD.TooManyFields"})
final class BddCache {
    private static final Logger logger = Logger.getLogger(BddCache.class.getName());

    private static final byte NOT_AN_OPERATION = 0;
    private static final byte BINARY_OPERATION_AND = (byte) 97;
    private static final byte BINARY_OPERATION_XOR = (byte) 193;

    @SuppressWarnings("StaticCollection")
    private static final Collection<BddCache> cacheShutdownHook = new ConcurrentLinkedDeque<>();

    private static final int[] EMPTY_INT_ARRAY = new int[0];
    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
    private static final BigInteger[] EMPTY_BIGINT_ARRAY = new BigInteger[0];

    private final BddImpl associatedBdd;
    private final int placeholder;
    private final CacheAccessStatistics binaryAccessStatistics = new CacheAccessStatistics();
    private final CacheAccessStatistics impliesAccessStatistics = new CacheAccessStatistics();
    private final CacheAccessStatistics satisfactionAccessStatistics = new CacheAccessStatistics();
    private final CacheAccessStatistics ternaryAccessStatistics = new CacheAccessStatistics();
    private final CacheAccessStatistics composeAccessStatistics = new CacheAccessStatistics();
    private int composeReuseCount = 0;
    private final CacheAccessStatistics quantificationAccessStatistics = new CacheAccessStatistics();
    private int quantificationReuseCount = 0;

    private int binaryKeyCount = 0;
    private byte[] binaryOp = EMPTY_BYTE_ARRAY;
    private int[] binaryCache = EMPTY_INT_ARRAY;

    private int impliesKeyCount = 0;
    private int[] impliesCache = EMPTY_INT_ARRAY;
    private final BitSet impliesValues = new BitSet();

    private int ternaryKeyCount = 0;
    private int[] ternaryCache = EMPTY_INT_ARRAY;

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

    BddCache(BddImpl associatedBdd) {
        this.associatedBdd = associatedBdd;
        this.placeholder = associatedBdd.placeholder();
        this.lookupHash = -1;
        this.lookupResult = placeholder;

        BddConfiguration configuration = associatedBdd.configuration();
        tableSizeChanged();

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
        return operationId == BINARY_OPERATION_AND || operationId == BINARY_OPERATION_XOR;
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

    // Load factors

    private float binaryLoadFactor() {
        int loadedBinaryBins = 0;
        for (int i = 0; i < binaryKeyCount(); i++) {
            if (binaryOp[i] != placeholder) {
                loadedBinaryBins++;
            }
        }
        return (float) loadedBinaryBins / (float) binaryKeyCount();
    }

    private float impliesLoadFactor() {
        int loadedImpliesBins = 0;
        for (int i = 0; i < impliesKeyCount(); i++) {
            if (impliesCache[2 * i] != placeholder) {
                loadedImpliesBins++;
            }
        }
        return (float) loadedImpliesBins / (float) impliesKeyCount();
    }

    private float ternaryLoadFactor() {
        int loadedTernaryBins = 0;
        for (int i = 0; i < ternaryKeyCount(); i++) {
            if (ternaryCache[4 * i] != placeholder) {
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

    private float quantificationLoadFactor() {
        int loadedQuantificationBins = 0;
        for (int i = 0; i < quantificationKeyCount(); i++) {
            if (quantificationCache[2 * i] != placeholder) {
                loadedQuantificationBins++;
            }
        }
        return (float) loadedQuantificationBins / (float) quantificationKeyCount();
    }

    // Key mapping

    private int binaryCachePosition(int hash) {
        return mod(hash, binaryKeyCount());
    }

    private int binaryKeyCount() {
        assert binaryKeyCount == binaryCache.length / 3;
        return binaryKeyCount;
    }

    private int impliesCachePosition(int hash) {
        return mod(hash, impliesKeyCount());
    }

    private int impliesKeyCount() {
        assert impliesKeyCount == impliesCache.length / 2;
        return impliesKeyCount;
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
        growBinary();
        growImplies();
        growTernary();
        growSatisfaction();
        growCompose();
        growQuantification();
    }

    public void variablesChanged() {
        growSatisfaction();
        growCompose();
        growQuantification();
    }

    private void pruneBinary() {
        if (binaryAccessStatistics.putCountSinceInvalidation == 0) {
            return;
        }
        if (binaryAccessStatistics.putCountSinceInvalidation < binaryKeyCount / 2) {
            binaryAccessStatistics.invalidation();
            clearBinary();
            return;
        }

        BddImpl bdd = associatedBdd;
        binaryAccessStatistics.partialInvalidation();
        int[] binaryCache = this.binaryCache;
        for (int i = 0; i < binaryKeyCount(); i++) {
            if (binaryOp[i] == NOT_AN_OPERATION) {
                continue;
            }
            int binStart = 3 * i;
            if (!(bdd.isNodeValidOrTerminal(binaryCache[binStart])
                    && bdd.isNodeValidOrTerminal(binaryCache[binStart + 1])
                    && bdd.isNodeValidOrTerminal(binaryCache[binStart + 2]))) {
                binaryOp[i] = NOT_AN_OPERATION;
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

        BddImpl bdd = associatedBdd;
        impliesAccessStatistics.partialInvalidation();
        int[] impliesCache = this.impliesCache;
        for (int i = 0; i < impliesKeyCount(); i++) {
            if (impliesCache[i] == placeholder) {
                continue;
            }
            int binStart = 2 * i;
            if (!(bdd.isNodeValidOrTerminal(impliesCache[binStart])
                    && bdd.isNodeValidOrTerminal(impliesCache[binStart + 1]))) {
                impliesCache[i] = placeholder;
            }
        }
    }

    private void pruneTernary() {
        if (ternaryAccessStatistics.putCountSinceInvalidation == 0) {
            return;
        }
        if (ternaryAccessStatistics.putCountSinceInvalidation < ternaryKeyCount / 4) {
            ternaryAccessStatistics.invalidation();
            clearTernary();
            return;
        }

        BddImpl bdd = associatedBdd;
        ternaryAccessStatistics.partialInvalidation();
        int[] ternaryCache = this.ternaryCache;
        for (int binStart = 0; binStart < ternaryCache.length; binStart += 4) {
            int first = ternaryCache[binStart];
            if (first == placeholder) {
                continue;
            }
            if (!(bdd.isNodeValid(first)
                    && bdd.isNodeValid(ternaryCache[binStart + 1])
                    && bdd.isNodeValid(ternaryCache[binStart + 2])
                    && bdd.isNodeValid(ternaryCache[binStart + 3]))) {
                ternaryCache[binStart] = placeholder;
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
            if (!associatedBdd.isNodeValid(satisfactionKey[i])) {
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

        BddImpl bdd = associatedBdd;
        int[] composeCache = this.composeCache;
        boolean composeAllValid = true;
        for (int composeNode : composeArray) {
            if (!bdd.isNodeValidOrTerminal(composeNode)) {
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
                if (!(bdd.isNodeValid(first) && !bdd.isNodeValidOrTerminal(composeCache[binStart + 1]))) {
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

        BddImpl bdd = associatedBdd;
        quantificationAccessStatistics.partialInvalidation();
        int[] quantificationCache = this.quantificationCache;
        for (int binStart = 0; binStart < quantificationCache.length; binStart += 2) {
            int first = quantificationCache[binStart];
            if (first == placeholder) {
                continue;
            }
            if (!(bdd.isNodeValid(first) && !bdd.isNodeValidOrTerminal(quantificationCache[binStart + 1]))) {
                quantificationCache[binStart] = placeholder;
            }
        }
    }

    public void partialInvalidate() {
        pruneBinary();
        pruneImplies();
        pruneTernary();
        pruneSatisfaction();
        pruneCompose();
        pruneQuantification();
    }

    private void growBinary() {
        BddImpl bdd = associatedBdd;
        int size = bdd.tableSize() / bdd.configuration().cacheBinaryDivider();
        if (size < 2 * binaryKeyCount) {
            pruneBinary();
        } else {
            int keyCount = Primes.nextPrime(size);
            byte[] newOp = new byte[keyCount];
            int[] newBinary = new int[keyCount * 3];

            if (bdd.configuration().useCachePreserveOnGrow()
                    && binaryAccessStatistics.putCountSinceInvalidation > binaryKeyCount / 4) {
                for (int i = 0; i < binaryKeyCount; i++) {
                    if (binaryOp[i] == NOT_AN_OPERATION) {
                        continue;
                    }
                    int binStart = 3 * i;
                    int input1 = binaryCache[binStart];
                    int input2 = binaryCache[binStart + 1];
                    int result = binaryCache[binStart + 2];
                    if (!(bdd.isNodeValid(input1) && bdd.isNodeValid(input2) && bdd.isNodeValidOrTerminal(result))) {
                        continue;
                    }
                    int newPosition = mod(HashUtil.hash(input1, input2), keyCount);
                    newOp[newPosition] = binaryOp[i];
                    int newBinStart = 3 * newPosition;
                    newBinary[newBinStart] = input1;
                    newBinary[newBinStart + 1] = input2;
                    newBinary[newBinStart + 2] = result;
                }
            }

            binaryOp = newOp;
            binaryCache = newBinary;
            binaryKeyCount = keyCount;
            assert binaryKeyCount() == keyCount;
        }
    }

    private void clearBinary() {
        Arrays.fill(binaryOp, NOT_AN_OPERATION);
    }

    private void growImplies() {
        int size = associatedBdd.tableSize() / associatedBdd.configuration().cacheImpliesDivider();
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

    private void growTernary() {
        BddImpl bdd = associatedBdd;
        int size = bdd.tableSize() / bdd.configuration().cacheTernaryDivider();

        if (size < 2 * ternaryKeyCount) {
            pruneTernary();
        } else {
            int keyCount = Primes.nextPrime(size);
            int[] newTernary = new int[keyCount * 4];

            if (bdd.configuration().useCachePreserveOnGrow()
                    && ternaryAccessStatistics.putCountSinceInvalidation > ternaryKeyCount / 4) {
                for (int i = 0; i < ternaryKeyCount; i++) {
                    int binStart = 4 * i;
                    int input1 = ternaryCache[binStart];
                    if (input1 == placeholder) {
                        if (placeholder != 0) {
                            newTernary[binStart] = placeholder;
                        }
                        continue;
                    }
                    int input2 = ternaryCache[binStart + 1];
                    int input3 = ternaryCache[binStart + 2];
                    int result = ternaryCache[binStart + 3];
                    if (!(bdd.isNodeValid(input1)
                            && bdd.isNodeValid(input2)
                            && bdd.isNodeValid(input3)
                            && bdd.isNodeValidOrTerminal(result))) {
                        if (placeholder != 0) {
                            newTernary[binStart] = placeholder;
                        }
                        continue;
                    }
                    int newPosition = mod(HashUtil.hash(input1, input2, input3), keyCount);
                    int newBinStart = 4 * newPosition;
                    newTernary[newBinStart] = input1;
                    newTernary[newBinStart + 1] = input2;
                    newTernary[newBinStart + 2] = input3;
                    newTernary[newBinStart + 3] = result;
                }

                ternaryCache = newTernary;
                ternaryKeyCount = keyCount;
            } else {
                ternaryCache = newTernary;
                ternaryKeyCount = keyCount;
                if (placeholder != 0) {
                    clearTernary();
                }
            }

            assert ternaryKeyCount() == keyCount;
        }
    }

    private void clearTernary() {
        for (int i = 0; i < ternaryCache.length; i += 4) {
            ternaryCache[i] = placeholder;
        }
    }

    private void growSatisfaction() {
        int size = associatedBdd.tableSize() / associatedBdd.configuration().cacheSatisfactionDivider();
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
        BddImpl bdd = associatedBdd;
        int size = bdd.numberOfVariables() * bdd.configuration().cacheComposeMultiplier();
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
                    if (!(bdd.isNodeValid(input) && bdd.isNodeValidOrTerminal(result))) {
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
                * associatedBdd.configuration().cacheQuantificationMultiplier();
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

    boolean lookupAnd(int inputNode1, int inputNode2) {
        assert binarySymmetricWellOrdered(inputNode1, inputNode2);
        return binaryLookup(BINARY_OPERATION_AND, inputNode1, inputNode2);
    }

    boolean lookupXor(int inputNode1, int inputNode2) {
        assert binarySymmetricWellOrdered(inputNode1, inputNode2);
        return binaryLookup(BINARY_OPERATION_XOR, inputNode1, inputNode2);
    }

    boolean lookupImplies(int inputNode1, int inputNode2) {
        assert associatedBdd.isNodeValid(inputNode1) && associatedBdd.isNodeValid(inputNode2);

        int hash = HashUtil.hash(inputNode1, inputNode2);
        lookupHash = hash;
        int cachePosition = impliesCachePosition(hash);

        int binStart = 2 * cachePosition;
        if (inputNode1 == impliesCache[binStart] && inputNode2 == impliesCache[binStart + 1]) {
            lookupResult = impliesValues.get(cachePosition) ? associatedBdd.trueNode() : associatedBdd.falseNode();
            impliesAccessStatistics.cacheHit();
            return true;
        }
        return false;
    }

    boolean lookupIfThenElse(int inputNode1, int inputNode2, int inputNode3) {
        assert associatedBdd.isNodeValid(inputNode1)
                && associatedBdd.isNodeValid(inputNode2)
                && associatedBdd.isNodeValid(inputNode3);

        int hash = HashUtil.hash(inputNode1, inputNode2, inputNode3);
        lookupHash = hash;
        int cachePosition = ternaryCachePosition(hash);

        int binStart = 4 * cachePosition;
        if (inputNode1 == ternaryCache[binStart]
                && inputNode2 == ternaryCache[binStart + 1]
                && inputNode3 == ternaryCache[binStart + 2]) {
            int result = ternaryCache[binStart + 3];
            lookupResult = result;
            assert associatedBdd.isNodeValidOrTerminal(result);
            ternaryAccessStatistics.cacheHit();
            return true;
        }
        return false;
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
            assert associatedBdd.isNodeValidOrTerminal(result);
            composeAccessStatistics.cacheHit();
            return true;
        }
        return false;
    }

    boolean lookupQuantification(int inputNode, boolean exists) {
        assert associatedBdd.isNodeValid(inputNode);

        int hash = HashUtil.hash(inputNode, exists);
        lookupHash = hash;

        int cachePosition = quantificationCachePosition(hash);
        int[] quantificationCache = this.quantificationCache;

        int binStart = 2 * cachePosition;
        if (quantificationCache[binStart] == inputNode && quantificationExists.get(cachePosition) == exists) {
            int result = quantificationCache[binStart + 1];
            lookupResult = result;
            assert associatedBdd.isNodeValidOrTerminal(result);
            quantificationAccessStatistics.cacheHit();
            return true;
        }
        return false;
    }

    // Put

    void putAnd(int hash, int inputNode1, int inputNode2, int resultNode) {
        assert binarySymmetricWellOrdered(inputNode1, inputNode2);
        binaryPut(BINARY_OPERATION_AND, hash, inputNode1, inputNode2, resultNode);
    }

    void putXor(int hash, int inputNode1, int inputNode2, int resultNode) {
        assert binarySymmetricWellOrdered(inputNode1, inputNode2);
        binaryPut(BINARY_OPERATION_XOR, hash, inputNode1, inputNode2, resultNode);
    }

    private boolean binaryLookup(byte operationId, int inputNode1, int inputNode2) {
        assert isBinaryOperation(operationId);
        assert associatedBdd.isNodeValid(inputNode1) && associatedBdd.isNodeValid(inputNode2);

        int hash = HashUtil.hash(operationId, inputNode1, inputNode2);
        lookupHash = hash;
        int cachePosition = binaryCachePosition(hash);

        int binStart = 3 * cachePosition;
        if (operationId == binaryOp[cachePosition]
                && inputNode1 == binaryCache[binStart]
                && inputNode2 == binaryCache[binStart + 1]) {
            int result = binaryCache[binStart + 2];
            lookupResult = result;

            assert associatedBdd.isNodeValidOrTerminal(result);
            binaryAccessStatistics.cacheHit();
            return true;
        }
        return false;
    }

    private void binaryPut(byte operationId, int hash, int inputNode1, int inputNode2, int resultNode) {
        assert isBinaryOperation(operationId);
        assert associatedBdd.isNodeValid(inputNode1)
                && associatedBdd.isNodeValid(inputNode2)
                && associatedBdd.isNodeValidOrTerminal(resultNode);
        assert hash == HashUtil.hash(operationId, inputNode1, inputNode2);

        int cachePosition = binaryCachePosition(hash);
        binaryAccessStatistics.put();

        int binStart = 3 * cachePosition;
        binaryOp[cachePosition] = operationId;
        binaryCache[binStart] = inputNode1;
        binaryCache[binStart + 1] = inputNode2;
        binaryCache[binStart + 2] = resultNode;
    }

    void putImplies(int hash, int inputNode1, int inputNode2, boolean result) {
        assert associatedBdd.isNodeValid(inputNode1) && associatedBdd.isNodeValid(inputNode2);
        assert hash == HashUtil.hash(inputNode1, inputNode2);

        int cachePosition = impliesCachePosition(hash);
        impliesAccessStatistics.put();

        int binStart = 2 * cachePosition;
        impliesCache[binStart] = inputNode1;
        impliesCache[binStart + 1] = inputNode2;
        impliesValues.set(cachePosition, result);
    }

    void putIfThenElse(int hash, int inputNode1, int inputNode2, int inputNode3, int resultNode) {
        assert associatedBdd.isNodeValid(inputNode1)
                && associatedBdd.isNodeValid(inputNode2)
                && associatedBdd.isNodeValid(inputNode3)
                && associatedBdd.isNodeValidOrTerminal(resultNode);
        assert hash == HashUtil.hash(inputNode1, inputNode2, inputNode3);

        ternaryAccessStatistics.put();
        int cachePosition = ternaryCachePosition(hash);

        int binStart = 4 * cachePosition;
        ternaryCache[binStart] = inputNode1;
        ternaryCache[binStart + 1] = inputNode2;
        ternaryCache[binStart + 2] = inputNode3;
        ternaryCache[binStart + 3] = resultNode;
    }

    void putSatisfaction(int hash, int node, BigInteger satisfactionCount) {
        assert associatedBdd.isNodeValid(node);
        assert hash == HashUtil.hash(node);

        satisfactionAccessStatistics.put();
        int cachePosition = satisfactionCachePosition(hash);

        satisfactionKey[cachePosition] = node;
        satisfactionResult[cachePosition] = satisfactionCount;
    }

    void putCompose(int hash, int inputNode, int resultNode) {
        assert associatedBdd.isNodeValid(inputNode) && associatedBdd.isNodeValidOrTerminal(resultNode);
        assert hash == HashUtil.hash(inputNode);

        composeAccessStatistics.put();
        int cachePosition = composeCachePosition(hash);

        int binStart = 2 * cachePosition;
        composeCache[binStart] = inputNode;
        composeCache[binStart + 1] = resultNode;
    }

    void putQuantification(int hash, int inputNode, boolean exists, int resultNode) {
        assert associatedBdd.isNodeValid(inputNode) && associatedBdd.isNodeValidOrTerminal(resultNode);
        assert hash == HashUtil.hash(inputNode, exists);

        quantificationAccessStatistics.put();
        int cachePosition = quantificationCachePosition(hash);

        int binStart = 2 * cachePosition;
        quantificationCache[binStart] = inputNode;
        quantificationCache[binStart + 1] = resultNode;
        quantificationExists.set(cachePosition, exists);
    }

    // Utility

    public String getStatistics() {
        return String.format(
                "Binary: size: %d, load: %s\n %s\n" + "Ternary: size: %d, load: %s\n %s\n"
                        + "Satisfaction: size: %d, load: %s\n %s\n"
                        + "Implies: size: %d, load: %s\n %s\n"
                        + "Compose: current size: %d, load: %s\n %s\n Reuse count: %d\n"
                        + "Quantification: current size: %d, load: %s\n %s\n Reuse count: %d",
                binaryKeyCount(),
                binaryLoadFactor(),
                binaryAccessStatistics,
                ternaryKeyCount(),
                ternaryLoadFactor(),
                ternaryAccessStatistics,
                satisfactionKeyCount(),
                satisfactionLoadFactor(),
                satisfactionAccessStatistics,
                impliesKeyCount(),
                impliesLoadFactor(),
                impliesAccessStatistics,
                composeKeyCount(),
                composeLoadFactor(),
                composeAccessStatistics,
                composeReuseCount,
                quantificationKeyCount(),
                quantificationLoadFactor(),
                quantificationAccessStatistics,
                quantificationReuseCount);
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
            float hitToPutRatio = (float) hitCount / (float) Math.max(putCount, 1);
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

    @SuppressWarnings("PMD.SystemPrintln")
    private static final class ShutdownHookPrinter implements Runnable {
        @Override
        public void run() {
            if (!logger.isLoggable(Level.INFO)) {
                return;
            }
            for (BddCache cache : cacheShutdownHook) {
                logger.info(cache.associatedBdd.statistics());
            }
        }
    }
}
