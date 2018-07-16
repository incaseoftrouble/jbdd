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

import org.immutables.value.Value;

@SuppressWarnings("MethodReturnAlwaysConstant")
@Value.Immutable
public class BddConfiguration {
  public static final int DEFAULT_CACHE_BINARY_BINS_PER_HASH = 3;
  public static final int DEFAULT_CACHE_BINARY_DIVIDER = 32;
  public static final int DEFAULT_CACHE_COMPOSE_DIVIDER = 128;
  public static final int DEFAULT_CACHE_SATISFACTION_BINS_PER_HASH = 1;
  public static final int DEFAULT_CACHE_SATISFACTION_DIVIDER = 32;
  public static final int DEFAULT_CACHE_TERNARY_BINS_PER_HASH = 3;
  public static final int DEFAULT_CACHE_TERNARY_DIVIDER = 64;
  public static final int DEFAULT_CACHE_NEGATION_BINS_PER_HASH = 3;
  public static final int DEFAULT_CACHE_NEGATION_DIVIDER = 32;
  public static final int DEFAULT_CACHE_VOLATILE_BINS_PER_HASH = 2;
  public static final int DEFAULT_CACHE_VOLATILE_MULTIPLIER = 2;
  public static final int DEFAULT_INITIAL_VARIABLE_NODES = 32;
  public static final int DEFAULT_MINIMUM_CACHE_SIZE = 32;
  public static final int DEFAULT_MINIMUM_NODE_TABLE_SIZE = 100;
  public static final float DEFAULT_NODE_TABLE_FREE_NODE_PERCENTAGE = 0.05f;
  public static final int DEFAULT_NODE_TABLE_GC_DEAD_NODE_COUNT = 2_000;
  public static final int DEFAULT_NODE_TABLE_IS_BIG_THRESHOLD = 40_000;
  public static final int DEFAULT_NODE_TABLE_IS_SMALL_THRESHOLD = 2_000;
  public static final int DEFAULT_NODE_TABLE_MAXIMUM_GROWTH = 50_000;
  public static final int DEFAULT_NODE_TABLE_MINIMUM_FREE_NODE_COUNT = 1_000;
  public static final int DEFAULT_NODE_TABLE_MINIMUM_GROWTH = 5_000;

  @Value.Default
  public int cacheBinaryBinsPerHash() {
    return DEFAULT_CACHE_BINARY_BINS_PER_HASH;
  }

  @Value.Default
  public int cacheBinaryDivider() {
    return DEFAULT_CACHE_BINARY_DIVIDER;
  }

  public final int cacheComposeBinsPerHash() {
    // Currently only 1 supported
    return 1;
  }

  @Value.Default
  public int cacheComposeDivider() {
    return DEFAULT_CACHE_COMPOSE_DIVIDER;
  }

  @Value.Default
  public int cacheSatisfactionBinsPerHash() {
    return DEFAULT_CACHE_SATISFACTION_BINS_PER_HASH;
  }

  @Value.Default
  public int cacheSatisfactionDivider() {
    return DEFAULT_CACHE_SATISFACTION_DIVIDER;
  }

  @Value.Default
  public int cacheTernaryBinsPerHash() {
    return DEFAULT_CACHE_TERNARY_BINS_PER_HASH;
  }

  @Value.Default
  public int cacheTernaryDivider() {
    return DEFAULT_CACHE_TERNARY_DIVIDER;
  }

  @Value.Default
  public int cacheNegationBinsPerHash() {
    return DEFAULT_CACHE_NEGATION_BINS_PER_HASH;
  }

  @Value.Default
  public int cacheNegationDivider() {
    return DEFAULT_CACHE_NEGATION_DIVIDER;
  }

  @Value.Default
  public int cacheVolatileBinsPerHash() {
    return DEFAULT_CACHE_VOLATILE_BINS_PER_HASH;
  }

  @Value.Default
  public int cacheVolatileMultiplier() {
    return DEFAULT_CACHE_VOLATILE_MULTIPLIER;
  }

  @Value.Default
  public int initialVariableNodes() {
    return DEFAULT_INITIAL_VARIABLE_NODES;
  }

  @Value.Default
  public boolean logStatisticsOnShutdown() {
    return true;
  }

  @Value.Default
  public int maximumNodeTableGrowth() {
    return DEFAULT_NODE_TABLE_MAXIMUM_GROWTH;
  }

  @Value.Default
  public int minimumCacheSize() {
    return DEFAULT_MINIMUM_CACHE_SIZE;
  }

  @Value.Default
  public int minimumDeadNodesCountForGcInGrow() {
    return DEFAULT_NODE_TABLE_GC_DEAD_NODE_COUNT;
  }

  @Value.Default
  public int minimumFreeNodeCountAfterGc() {
    return DEFAULT_NODE_TABLE_MINIMUM_FREE_NODE_COUNT;
  }

  @Value.Default
  public float minimumFreeNodePercentageAfterGc() {
    return DEFAULT_NODE_TABLE_FREE_NODE_PERCENTAGE;
  }

  @Value.Default
  public int minimumNodeTableGrowth() {
    return DEFAULT_NODE_TABLE_MINIMUM_GROWTH;
  }

  @Value.Default
  public int minimumNodeTableSize() {
    return DEFAULT_MINIMUM_NODE_TABLE_SIZE;
  }

  @Value.Default
  public int nodeTableBigThreshold() {
    return DEFAULT_NODE_TABLE_IS_BIG_THRESHOLD;
  }

  @Value.Default
  public int nodeTableSmallThreshold() {
    return DEFAULT_NODE_TABLE_IS_SMALL_THRESHOLD;
  }

  @Value.Default
  public boolean useGlobalComposeCache() {
    return false;
  }

  @Value.Default
  public boolean useShannonExists() {
    return true;
  }

  @Value.Default
  public boolean useGarbageCollection() {
    return true;
  }

  @Value.Default
  public int integrityDuplicatesMaximalSize() {
    return 100_000;
  }

  @Value.Default
  public boolean threadSafetyCheck() {
    return false;
  }
}
