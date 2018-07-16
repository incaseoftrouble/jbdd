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
  private static final int BINARY_CACHE_OPERATION_ID_OFFSET = 61;
  private static final int BINARY_OPERATION_AND = 0;
  private static final int BINARY_OPERATION_EQUIVALENCE = 4;
  private static final int BINARY_OPERATION_EXISTS = 6;
  private static final int BINARY_OPERATION_IMPLIES = 3;
  private static final int BINARY_OPERATION_N_AND = 1;
  private static final int BINARY_OPERATION_OR = 2;
  private static final int BINARY_OPERATION_XOR = 5;
  private static final int CACHE_VALUE_BIT_SIZE = NodeTable.NODE_IDENTIFIER_BIT_SIZE;
  private static final long CACHE_VALUE_MASK = BitUtil.maskLength(CACHE_VALUE_BIT_SIZE);
  private static final int TERNARY_CACHE_OPERATION_ID_OFFSET = 63;
  private static final int TERNARY_OPERATION_ITE = 0;
  private static final int NEGATION_CACHE_KEY_OFFSET = 32;

  private static final Logger logger = Logger.getLogger(BddCache.class.getName());
  private static final Collection<BddCache> cacheShutdownHook = new ConcurrentLinkedDeque<>();

  private final AbstractBdd associatedBdd;
  private final CacheAccessStatistics binaryAccessStatistics = new CacheAccessStatistics();
  private final int binaryBinsPerHash;
  private final CacheAccessStatistics composeAccessStatistics = new CacheAccessStatistics();
  private final int composeBinsPerHash;
  private final CacheAccessStatistics satisfactionAccessStatistics = new CacheAccessStatistics();
  private final int satisfactionBinsPerHash;
  private final CacheAccessStatistics ternaryAccessStatistics = new CacheAccessStatistics();
  private final int ternaryBinsPerHash;
  private final CacheAccessStatistics negationAccessStatistics = new CacheAccessStatistics();
  private final int negationBinsPerHash;
  private final CacheAccessStatistics volatileAccessStatistics = new CacheAccessStatistics();
  private final int volatileBinsPerHash;
  @SuppressWarnings("NullableProblems")
  private long[] binaryKeyStorage;
  @SuppressWarnings("NullableProblems")
  private int[] binaryResultStorage;
  @Nullable
  private int[] composeStorage;
  private int lookupHash = -1;
  private int lookupResult = -1;
  @SuppressWarnings("NullableProblems")
  private int[] satisfactionKeyStorage;
  @SuppressWarnings("NullableProblems")
  private BigInteger[] satisfactionResultStorage;
  @SuppressWarnings("NullableProblems")
  private long[] ternaryStorage;
  @SuppressWarnings("NullableProblems")
  private long[] negationStorage;
  @SuppressWarnings("NullableProblems")
  private int[] volatileKeyStorage;
  @SuppressWarnings("NullableProblems")
  private int[] volatileResultStorage;

  BddCache(AbstractBdd associatedBdd) {
    this.associatedBdd = associatedBdd;
    BddConfiguration configuration = associatedBdd.getConfiguration();
    negationBinsPerHash = configuration.cacheNegationBinsPerHash();
    binaryBinsPerHash = configuration.cacheBinaryBinsPerHash();
    ternaryBinsPerHash = configuration.cacheTernaryBinsPerHash();
    satisfactionBinsPerHash = configuration.cacheSatisfactionBinsPerHash();
    composeBinsPerHash = configuration.cacheComposeBinsPerHash();
    volatileBinsPerHash = configuration.cacheVolatileBinsPerHash();
    reallocateNegation();
    reallocateBinary();
    reallocateTernary();
    reallocateSatisfaction();
    reallocateCompose();
    reallocateVolatile();

    if (configuration.logStatisticsOnShutdown()) {
      logger.log(Level.FINER, "Adding {0} to shutdown hook", this);
      addToShutdownHook(this);
    }
  }

  private static void addToShutdownHook(BddCache cache) {
    ShutdownHookLazyHolder.init();
    cacheShutdownHook.add(cache);
  }

  private static long buildBinaryKeyStore(long operationId, long inputNode1,
    long inputNode2) {
    assert BitUtil.fits(inputNode1, CACHE_VALUE_BIT_SIZE)
        && BitUtil.fits(inputNode2, CACHE_VALUE_BIT_SIZE);
    long store = inputNode1;
    store |= inputNode2 << CACHE_VALUE_BIT_SIZE;
    store |= operationId << BINARY_CACHE_OPERATION_ID_OFFSET;
    return store;
  }

  private static long buildTernaryFirstStore(long operationId, long inputNode1,
    long inputNode2) {
    assert BitUtil.fits(inputNode1, CACHE_VALUE_BIT_SIZE)
        && BitUtil.fits(inputNode2, CACHE_VALUE_BIT_SIZE);
    return inputNode1 | inputNode2 << CACHE_VALUE_BIT_SIZE
      | operationId << TERNARY_CACHE_OPERATION_ID_OFFSET;
  }

  private static long buildTernarySecondStore(long inputNode3, long resultNode) {
    assert BitUtil.fits(inputNode3, CACHE_VALUE_BIT_SIZE)
        && BitUtil.fits(resultNode, CACHE_VALUE_BIT_SIZE);
    return resultNode | inputNode3 << CACHE_VALUE_BIT_SIZE;
  }

  private static long buildNegationStore(long inputNode, long resultNode) {
    assert BitUtil.fits(inputNode, CACHE_VALUE_BIT_SIZE)
        && BitUtil.fits(resultNode, CACHE_VALUE_BIT_SIZE);
    return resultNode | inputNode << NEGATION_CACHE_KEY_OFFSET;
  }

  private static long getInputNodeFromTernarySecondStore(long ternarySecondStore) {
    return ternarySecondStore >>> CACHE_VALUE_BIT_SIZE;
  }

  private static long getResultNodeFromTernarySecondStore(long ternarySecondStore) {
    return ternarySecondStore & CACHE_VALUE_MASK;
  }

  private static long getResultNodeFromNegationStore(long negationStore) {
    return negationStore & CACHE_VALUE_MASK;
  }

  @SuppressWarnings("NumericCastThatLosesPrecision")
  private static int getKeyNodeFromNegationStore(long negationStore) {
    return (int) (negationStore >>> NEGATION_CACHE_KEY_OFFSET);
  }

  private static void insertInLru(long[] array, int first, int offset, long newValue) {
    System.arraycopy(array, first, array, first + 1, offset - 1);
    array[first] = newValue;
  }

  private static void insertInLru(int[] array, int first, int offset, int newValue) {
    // Copy each element between first and last to its next position
    System.arraycopy(array, first, array, first + 1, offset - 1);
    array[first] = newValue;
  }

  private static void insertInTernaryLru(long[] array, int first, int offset, long newFirstValue,
      long newSecondValue) {
    System.arraycopy(array, first, array, first + 2, offset - 2);
    array[first] = newFirstValue;
    array[first + 1] = newSecondValue;
  }

  private static boolean isBinaryOperation(int operationId) {
    return operationId == BINARY_OPERATION_AND || operationId == BINARY_OPERATION_EQUIVALENCE
      || operationId == BINARY_OPERATION_IMPLIES || operationId == BINARY_OPERATION_N_AND
      || operationId == BINARY_OPERATION_OR || operationId == BINARY_OPERATION_XOR
      || operationId == BINARY_OPERATION_EXISTS;
  }

  private static boolean isTernaryOperation(int operationId) {
    return operationId == TERNARY_OPERATION_ITE;
  }

  private static void updateLru(long[] array, int first, int offset) {
    insertInLru(array, first, offset, array[first + offset]);
  }

  private static void updateLru(int[] array, int first, int offset) {
    insertInLru(array, first, offset, array[first + offset]);
  }

  private static void updateTernaryLru(long[] array, int first, int offset) {
    insertInTernaryLru(array, first, offset, array[first + offset], array[first + offset + 1]);
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

  int getLookupHash() {
    return lookupHash;
  }

  int getLookupResult() {
    return lookupResult;
  }

  void clearVolatileCache() {
    volatileAccessStatistics.invalidation();
    Arrays.fill(volatileKeyStorage, 0);
  }


  String getStatistics() {
    @SuppressWarnings("MagicNumber")
    StringBuilder builder = new StringBuilder(512);
    builder.append("Negation: size: ").append(getNegationCacheKeyCount()) //
        .append(", load: ").append(computeNegationLoadFactor()) //
        .append("\n ").append(negationAccessStatistics) //
        .append("\nBinary: size: ").append(getBinaryCacheKeyCount()) //
        .append(", load: ").append(computeBinaryLoadFactor()) //
        .append("\n ").append(binaryAccessStatistics) //
        .append("\nTernary: size: ").append(getTernaryKeyCount()) //
        .append(", load: ").append(computeTernaryLoadFactor()) //
        .append("\n ").append(ternaryAccessStatistics) //
        .append("\nSatisfaction: size: ").append(getSatisfactionKeyCount()) //
        .append(", load: ").append(computeSatisfactionLoadFactor()) //
        .append("\n ").append(satisfactionAccessStatistics) //
        .append("\nCompose:");
    //noinspection VariableNotUsedInsideIf
    if (composeStorage == null) {
      builder.append(" Disabled");
    } else {
      builder.append(" size: ").append(getComposeKeyCount())
          .append("\n ").append(composeAccessStatistics);
    }
    builder.append("\nCompose volatile: current size: ").append(getVolatileKeyCount()) //
        .append(", load: ").append(computeVolatileLoadFactor()) //
        .append("\n ").append(volatileAccessStatistics);
    return builder.toString();
  }

  private float computeBinaryLoadFactor() {
    int loadedBinaryBins = 0;
    for (int i = 0; i < getBinaryCacheKeyCount(); i++) {
      if (binaryKeyStorage[i] != 0L) {
        loadedBinaryBins++;
      }
    }
    return (float) loadedBinaryBins / (float) getBinaryCacheKeyCount();
  }

  private float computeSatisfactionLoadFactor() {
    int loadedSatisfactionBins = 0;
    for (int i = 0; i < getSatisfactionKeyCount(); i++) {
      if (satisfactionKeyStorage[i] != 0L) {
        loadedSatisfactionBins++;
      }
    }
    return (float) loadedSatisfactionBins / (float) getSatisfactionKeyCount();
  }

  private float computeTernaryLoadFactor() {
    int loadedTernaryBins = 0;
    for (int i = 0; i < getTernaryKeyCount(); i++) {
      if (ternaryStorage[i * 2] != 0L) {
        loadedTernaryBins++;
      }
    }
    return (float) loadedTernaryBins / (float) getTernaryKeyCount();
  }

  private float computeNegationLoadFactor() {
    int loadedNegationBins = 0;
    for (int i = 0; i < getNegationCacheKeyCount(); i++) {
      if (negationStorage[i] != 0L) {
        loadedNegationBins++;
      }
    }
    return (float) loadedNegationBins / (float) getNegationCacheKeyCount();
  }

  private float computeVolatileLoadFactor() {
    int loadedVolatileBins = 0;
    for (int i = 0; i < getVolatileKeyCount(); i++) {
      if (volatileKeyStorage[i] != 0) {
        loadedVolatileBins++;
      }
    }
    return (float) loadedVolatileBins / (float) getVolatileKeyCount();
  }


  private int ensureMinimumCacheKeyCount(int cacheSize) {
    if (cacheSize < associatedBdd.getConfiguration().minimumNodeTableSize()) {
      return MathUtil.nextPrime(associatedBdd.getConfiguration().minimumNodeTableSize());
    }
    return MathUtil.nextPrime(cacheSize);
  }


  private int getBinaryCachePosition(int hash) {
    return mod(hash, getBinaryCacheKeyCount()) * binaryBinsPerHash;
  }

  private int getBinaryCacheKeyCount() {
    return binaryKeyStorage.length / binaryBinsPerHash;
  }

  private int getComposeCachePosition(int hash) {
    return mod(hash, getComposeKeyCount())
        * (2 + associatedBdd.numberOfVariables() + composeBinsPerHash);
  }

  private int getComposeKeyCount() {
    assert composeStorage != null;
    return composeStorage.length / (2 + composeBinsPerHash + associatedBdd.numberOfVariables());
  }

  private int getSatisfactionCachePosition(int hash) {
    return mod(hash, getSatisfactionKeyCount()) * satisfactionBinsPerHash;
  }

  private int getSatisfactionKeyCount() {
    return satisfactionKeyStorage.length / satisfactionBinsPerHash;
  }

  private int getTernaryCachePosition(int hash) {
    return mod(hash, getTernaryKeyCount()) * ternaryBinsPerHash * 2;
  }

  private int getTernaryKeyCount() {
    return ternaryStorage.length / ternaryBinsPerHash / 2;
  }

  private int getNegationCachePosition(int hash) {
    return mod(hash, getNegationCacheKeyCount()) * negationBinsPerHash;
  }

  private int getNegationCacheKeyCount() {
    return negationStorage.length / negationBinsPerHash;
  }

  private int getVolatileCachePosition(int hash) {
    return mod(hash, getVolatileKeyCount()) * volatileBinsPerHash;
  }

  private int getVolatileKeyCount() {
    return volatileKeyStorage.length / volatileBinsPerHash;
  }


  void invalidate() {
    logger.log(Level.FINER, "Invalidating caches");
    invalidateNegation();
    invalidateBinary();
    invalidateTernary();
    invalidateSatisfaction();
    invalidateCompose();
    clearVolatileCache();
  }

  @SuppressWarnings("WeakerAccess")
  void invalidateBinary() {
    binaryAccessStatistics.invalidation();
    reallocateBinary();
  }

  void invalidateCompose() {
    composeAccessStatistics.invalidation();
    reallocateCompose();
  }

  void invalidateSatisfaction() {
    satisfactionAccessStatistics.invalidation();
    reallocateSatisfaction();
  }

  @SuppressWarnings("WeakerAccess")
  void invalidateTernary() {
    ternaryAccessStatistics.invalidation();
    reallocateTernary();
  }

  @SuppressWarnings("WeakerAccess")
  void invalidateNegation() {
    negationAccessStatistics.invalidation();
    reallocateNegation();
  }


  boolean lookupAnd(int inputNode1, int inputNode2) {
    assert binarySymmetricWellOrdered(inputNode1, inputNode2);
    return binaryLookup(BINARY_OPERATION_AND, inputNode1, inputNode2);
  }

  boolean lookupCompose(int inputNode, int[] replacementArray) {
    assert composeStorage != null;
    assert associatedBdd.isNodeValid(inputNode);

    int hash = HashUtil.hash(inputNode, replacementArray);
    lookupHash = hash;
    int cachePosition = getComposeCachePosition(hash);

    if (composeStorage[cachePosition] != inputNode) {
      return false;
    }
    for (int i = 0; i < replacementArray.length; i++) {
      if (composeStorage[cachePosition + 2 + i] != replacementArray[i]) {
        return false;
      }
    }
    if (replacementArray.length < associatedBdd.numberOfVariables()
      && composeStorage[cachePosition + 2 + replacementArray.length] != -1) {
      return false;
    }
    lookupResult = composeStorage[cachePosition + 1];
    assert associatedBdd.isNodeValidOrRoot(lookupResult);
    composeAccessStatistics.cacheHit();
    return true;
  }

  boolean lookupEquivalence(int inputNode1, int inputNode2) {
    assert binarySymmetricWellOrdered(inputNode1, inputNode2);
    return binaryLookup(BINARY_OPERATION_EQUIVALENCE, inputNode1, inputNode2);
  }

  boolean lookupExists(int inputNode, int variableCube) {
    return binaryLookup(BINARY_OPERATION_EXISTS, inputNode, variableCube);
  }

  boolean lookupIfThenElse(int inputNode1, int inputNode2, int inputNode3) {
    return ternaryLookup(TERNARY_OPERATION_ITE, inputNode1, inputNode2, inputNode3);
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
    int cachePosition = getSatisfactionCachePosition(hash);

    if (node == satisfactionKeyStorage[cachePosition]) {
      satisfactionAccessStatistics.cacheHit();
      return satisfactionResultStorage[cachePosition];
    }
    return null;
  }

  boolean lookupVolatile(int inputNode) {
    assert associatedBdd.isNodeValid(inputNode);

    lookupHash = HashUtil.hash(inputNode);
    int cachePosition = getVolatileCachePosition(lookupHash);

    if (volatileBinsPerHash == 1) {
      if (volatileKeyStorage[cachePosition] != inputNode) {
        return false;
      }
      lookupResult = volatileResultStorage[cachePosition];
    } else {
      int offset = -1;
      for (int i = 0; i < volatileBinsPerHash; i++) {
        int keyValue = volatileKeyStorage[cachePosition + i];
        if (keyValue == 0) {
          return false;
        }
        if (keyValue == inputNode) {
          offset = i;
          break;
        }
      }
      if (offset == -1) {
        return false;
      }
      lookupResult = volatileResultStorage[cachePosition + offset];
      if (offset != 0) {
        updateLru(volatileKeyStorage, cachePosition, offset);
        updateLru(volatileResultStorage, cachePosition, offset);
      }
    }
    assert associatedBdd.isNodeValidOrRoot(lookupResult);
    volatileAccessStatistics.cacheHit();
    return true;
  }

  boolean lookupXor(int inputNode1, int inputNode2) {
    assert binarySymmetricWellOrdered(inputNode1, inputNode2);
    return binaryLookup(BINARY_OPERATION_XOR, inputNode1, inputNode2);
  }


  void putAnd(int hash, int inputNode1, int inputNode2, int resultNode) {
    assert binarySymmetricWellOrdered(inputNode1, inputNode2);
    binaryPut(BINARY_OPERATION_AND, hash, inputNode1, inputNode2, resultNode);
  }

  void putCompose(int hash, int inputNode, int[] replacementArray, int resultNode) {
    assert composeStorage != null;
    assert associatedBdd.isNodeValidOrRoot(inputNode)
      && associatedBdd.isNodeValidOrRoot(resultNode);
    assert replacementArray.length <= associatedBdd.numberOfVariables();

    int cachePosition = getComposeCachePosition(hash);
    composeAccessStatistics.put();

    assert hash == HashUtil.hash(inputNode, replacementArray);
    composeStorage[cachePosition] = inputNode;
    composeStorage[cachePosition + 1] = resultNode;
    System.arraycopy(replacementArray, 0, composeStorage, cachePosition + 2,
        replacementArray.length);
    if (replacementArray.length < associatedBdd.numberOfVariables()) {
      composeStorage[cachePosition + 2 + replacementArray.length] = -1;
    }
  }

  void putEquivalence(int hash, int inputNode1, int inputNode2, int resultNode) {
    assert binarySymmetricWellOrdered(inputNode1, inputNode2);
    binaryPut(BINARY_OPERATION_EQUIVALENCE, hash, inputNode1, inputNode2, resultNode);
  }

  void putExists(int hash, int inputNode, int variableCube, int resultNode) {
    binaryPut(BINARY_OPERATION_EXISTS, hash, inputNode, variableCube, resultNode);
  }

  void putIfThenElse(int hash, int inputNode1, int inputNode2, int inputNode3, int resultNode) {
    ternaryPut(TERNARY_OPERATION_ITE, hash, inputNode1, inputNode2, inputNode3, resultNode);
  }

  void putImplication(int hash, int inputNode1, int inputNode2, int resultNode) {
    binaryPut(BINARY_OPERATION_IMPLIES, hash, inputNode1, inputNode2, resultNode);
  }

  void putNAnd(int hash, int inputNode1, int inputNode2, int resultNode) {
    assert binarySymmetricWellOrdered(inputNode1, inputNode2);
    binaryPut(BINARY_OPERATION_N_AND, hash, inputNode1, inputNode2, resultNode);
  }

  void putNot(int inputNode, int resultNode) {
    negationPut(HashUtil.hash(inputNode), inputNode, resultNode);
  }

  void putNot(int hash, int inputNode, int resultNode) {
    assert associatedBdd.isNodeValid(inputNode) && associatedBdd.isNodeValidOrRoot(resultNode);
    negationPut(hash, inputNode, resultNode);
  }

  void putOr(int hash, int inputNode1, int inputNode2, int resultNode) {
    assert binarySymmetricWellOrdered(inputNode1, inputNode2);
    binaryPut(BINARY_OPERATION_OR, hash, inputNode1, inputNode2, resultNode);
  }

  void putSatisfaction(int hash, int node, BigInteger satisfactionCount) {
    assert associatedBdd.isNodeValid(node);

    int cachePosition = getSatisfactionCachePosition(hash);
    satisfactionAccessStatistics.put();

    assert hash == HashUtil.hash(node);
    satisfactionKeyStorage[cachePosition] = node;
    satisfactionResultStorage[cachePosition] = satisfactionCount;
  }

  void putVolatile(int hash, int inputNode, int resultNode) {
    assert associatedBdd.isNodeValid(inputNode)
      && associatedBdd.isNodeValidOrRoot(resultNode);

    int cachePosition = getVolatileCachePosition(hash);
    volatileAccessStatistics.put();

    assert hash == HashUtil.hash(inputNode);
    if (volatileBinsPerHash == 1) {
      volatileKeyStorage[cachePosition] = inputNode;
      volatileResultStorage[cachePosition] = resultNode;
    } else {
      insertInLru(volatileKeyStorage, cachePosition, volatileBinsPerHash, inputNode);
      insertInLru(volatileResultStorage, cachePosition, volatileBinsPerHash, resultNode);
    }
  }

  void putXor(int hash, int inputNode1, int inputNode2, int resultNode) {
    assert binarySymmetricWellOrdered(inputNode1, inputNode2);
    binaryPut(BINARY_OPERATION_XOR, hash, inputNode1, inputNode2, resultNode);
  }


  private void reallocateBinary() {
    int keyCount = associatedBdd.getTableSize()
      / associatedBdd.getConfiguration().cacheBinaryDivider();
    int actualSize = ensureMinimumCacheKeyCount(keyCount) * binaryBinsPerHash;
    binaryKeyStorage = new long[actualSize];
    binaryResultStorage = new int[actualSize];
  }

  private void reallocateCompose() {
    if (!associatedBdd.getConfiguration().useGlobalComposeCache()) {
      composeStorage = null;
      return;
    }
    int keyCount = associatedBdd.getTableSize()
      / associatedBdd.getConfiguration().cacheComposeDivider();
    int actualSize = ensureMinimumCacheKeyCount(keyCount)
      * (2 + associatedBdd.numberOfVariables());
    composeStorage = new int[actualSize];
  }

  private void reallocateSatisfaction() {
    int keyCount = associatedBdd.getTableSize()
      / associatedBdd.getConfiguration().cacheSatisfactionDivider();
    int actualSize = ensureMinimumCacheKeyCount(keyCount) * satisfactionBinsPerHash;
    satisfactionKeyStorage = new int[actualSize];
    satisfactionResultStorage = new BigInteger[actualSize];
  }

  private void reallocateTernary() {
    int keyCount = associatedBdd.getTableSize()
      / associatedBdd.getConfiguration().cacheTernaryDivider();
    int actualSize = ensureMinimumCacheKeyCount(keyCount) * ternaryBinsPerHash * 2;
    ternaryStorage = new long[actualSize];
  }

  private void reallocateNegation() {
    int keyCount = associatedBdd.getTableSize()
      / associatedBdd.getConfiguration().cacheNegationDivider();
    int actualSize = ensureMinimumCacheKeyCount(keyCount) * negationBinsPerHash;
    negationStorage = new long[actualSize];
  }

  void reallocateVolatile() {
    int keyCount = associatedBdd.numberOfVariables()
      * associatedBdd.getConfiguration().cacheVolatileMultiplier();

    volatileAccessStatistics.invalidation();
    int actualSize = MathUtil.nextPrime(keyCount) * volatileBinsPerHash;
    volatileKeyStorage = new int[actualSize];
    volatileResultStorage = new int[actualSize];
  }


  private boolean negationLookup(int inputNode) {
    assert associatedBdd.isNodeValid(inputNode);

    int hash = HashUtil.hash(inputNode);
    lookupHash = hash;
    int cachePosition = getNegationCachePosition(hash);

    if (negationBinsPerHash == 1) {
      long negationStore = negationStorage[cachePosition];
      int negationKeyNode = getKeyNodeFromNegationStore(negationStore);
      if (inputNode != negationKeyNode) {
        return false;
      }
      //noinspection NumericCastThatLosesPrecision
      lookupResult = (int) getResultNodeFromNegationStore(negationStore);
    } else {
      int offset = -1;
      for (int i = 0; i < negationBinsPerHash; i++) {
        long negationStore = negationStorage[cachePosition + i];
        int negationKeyNode = getKeyNodeFromNegationStore(negationStore);
        if (inputNode == negationKeyNode) {
          offset = i;
          //noinspection NumericCastThatLosesPrecision
          lookupResult = (int) getResultNodeFromNegationStore(negationStore);
          break;
        }
      }
      if (offset == -1) {
        return false;
      }
      if (offset != 0) {
        updateLru(negationStorage, cachePosition, offset);
      }
    }

    assert associatedBdd.isNodeValidOrRoot(lookupResult);
    negationAccessStatistics.cacheHit();
    return true;
  }

  private void negationPut(int hash, int inputNode, int resultNode) {
    assert associatedBdd.isNodeValid(inputNode) && associatedBdd.isNodeValidOrRoot(resultNode);

    int cachePosition = getNegationCachePosition(hash);
    negationAccessStatistics.put();

    long negationStore = buildNegationStore(inputNode, resultNode);
    assert hash == HashUtil.hash(inputNode);
    if (negationBinsPerHash == 1) {
      negationStorage[cachePosition] = negationStore;
    } else {
      insertInLru(negationStorage, cachePosition, negationBinsPerHash, negationStore);
    }
  }

  private boolean binaryLookup(int operationId, int inputNode1, int inputNode2) {
    assert isBinaryOperation(operationId);
    assert associatedBdd.isNodeValid(inputNode1) && associatedBdd.isNodeValid(inputNode2);

    long binaryKey = buildBinaryKeyStore(operationId, inputNode1, inputNode2);
    int hash = HashUtil.hash(binaryKey);
    lookupHash = hash;
    int cachePosition = getBinaryCachePosition(hash);

    if (binaryBinsPerHash == 1) {
      if (binaryKey != binaryKeyStorage[cachePosition]) {
        return false;
      }
      lookupResult = binaryResultStorage[cachePosition];
    } else {
      int offset = -1;
      for (int i = 0; i < binaryBinsPerHash; i++) {
        long binaryKeyStore = binaryKeyStorage[cachePosition + i];
        if (binaryKey == binaryKeyStore) {
          offset = i;
          break;
        }
      }
      if (offset == -1) {
        return false;
      }

      lookupResult = binaryResultStorage[cachePosition + offset];
      if (offset != 0) {
        updateLru(binaryKeyStorage, cachePosition, offset);
        updateLru(binaryResultStorage, cachePosition, offset);
      }
    }
    assert associatedBdd.isNodeValidOrRoot(lookupResult);
    binaryAccessStatistics.cacheHit();
    return true;
  }

  private void binaryPut(int operationId, int hash, int inputNode1, int inputNode2,
      int resultNode) {
    assert isBinaryOperation(operationId);
    assert associatedBdd.isNodeValid(inputNode1) && associatedBdd.isNodeValid(inputNode2)
        && associatedBdd.isNodeValidOrRoot(resultNode);

    int cachePosition = getBinaryCachePosition(hash);
    binaryAccessStatistics.put();

    long binaryKey =
        buildBinaryKeyStore(operationId, inputNode1, inputNode2);
    assert hash == HashUtil.hash(binaryKey);

    if (binaryBinsPerHash == 1) {
      binaryKeyStorage[cachePosition] = binaryKey;
      binaryResultStorage[cachePosition] = resultNode;
    } else {
      insertInLru(binaryKeyStorage, cachePosition, binaryBinsPerHash, binaryKey);
      insertInLru(binaryResultStorage, cachePosition, binaryBinsPerHash, resultNode);
    }
  }

  private boolean ternaryLookup(int operationId, int inputNode1, int inputNode2, int inputNode3) {
    assert isTernaryOperation(operationId) && associatedBdd.isNodeValid(inputNode1)
        && associatedBdd.isNodeValid(inputNode2) && associatedBdd.isNodeValid(inputNode3);
    assert isTernaryOperation(operationId);

    long constructedTernaryFirstStore =
        buildTernaryFirstStore(operationId, inputNode1, inputNode2);
    int hash = HashUtil.hash(constructedTernaryFirstStore, inputNode3);
    lookupHash = hash;
    int cachePosition = getTernaryCachePosition(hash);

    if (ternaryBinsPerHash == 1) {
      if (constructedTernaryFirstStore != ternaryStorage[cachePosition]) {
        return false;
      }
      long ternarySecondStore = ternaryStorage[cachePosition + 1];
      //noinspection NumericCastThatLosesPrecision
      if (inputNode3 != (int) getInputNodeFromTernarySecondStore(ternarySecondStore)) {
        return false;
      }
      //noinspection NumericCastThatLosesPrecision
      lookupResult = (int) getResultNodeFromTernarySecondStore(ternarySecondStore);
    } else {
      int offset = -1;
      for (int i = 0; i < ternaryBinsPerHash * 2; i += 2) {
        if (constructedTernaryFirstStore == ternaryStorage[cachePosition + i]) {
          long ternarySecondStore = ternaryStorage[cachePosition + i + 1];
          //noinspection NumericCastThatLosesPrecision
          if (inputNode3 == (int) getInputNodeFromTernarySecondStore(ternarySecondStore)) {
            offset = i;
            //noinspection NumericCastThatLosesPrecision
            lookupResult = (int) getResultNodeFromTernarySecondStore(ternarySecondStore);
            break;
          }
        }
      }
      if (offset == -1) {
        return false;
      }
      if (offset != 0) {
        updateTernaryLru(ternaryStorage, cachePosition, offset);
      }
    }

    assert associatedBdd.isNodeValidOrRoot(lookupResult);
    ternaryAccessStatistics.cacheHit();
    return true;
  }

  private void ternaryPut(int operationId, int hash, int inputNode1, int inputNode2, int inputNode3,
      int resultNode) {
    assert associatedBdd.isNodeValid(inputNode1) && associatedBdd.isNodeValid(inputNode2)
      && associatedBdd.isNodeValid(inputNode3) && associatedBdd.isNodeValidOrRoot(resultNode);
    assert isTernaryOperation(operationId);

    int cachePosition = getTernaryCachePosition(hash);
    ternaryAccessStatistics.put();

    long firstStore = buildTernaryFirstStore(operationId, inputNode1,
        inputNode2);
    long secondStore = buildTernarySecondStore(inputNode3, resultNode);
    assert hash == HashUtil.hash(firstStore, inputNode3);
    if (ternaryBinsPerHash == 1) {
      ternaryStorage[cachePosition] = firstStore;
      ternaryStorage[cachePosition + 1] = secondStore;
    } else {
      insertInTernaryLru(ternaryStorage, cachePosition, ternaryBinsPerHash * 2, firstStore,
        secondStore);
    }
  }


  private static final class CacheAccessStatistics {
    private int hitCount = 0;
    private int hitCountSinceInvalidation = 0;
    private int invalidationCount = 0;
    private int putCount = 0;
    private int putCountSinceInvalidation = 0;

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
      return String.format("Cache access: put=%d, hit=%d, hit-to-put=%3.3f%n"
          + "       invalidation: %d times, since last: put=%d, hit=%d",
        putCount, hitCount, hitToPutRatio, invalidationCount,
        putCountSinceInvalidation, hitCountSinceInvalidation);
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
      if (!logger.isLoggable(Level.FINER)) {
        if (!cacheShutdownHook.isEmpty()) {
          System.err.println("Not printing statistics since FINER level disabled");
        }
        return;
      }
      for (BddCache cache : cacheShutdownHook) {
        System.err.println(cache.associatedBdd.statistics());
        System.err.println(cache.getStatistics());
        System.err.println();
      }
    }
  }
}
