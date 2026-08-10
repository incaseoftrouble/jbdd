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

import org.immutables.value.Value;

@SuppressWarnings("MethodReturnAlwaysConstant")
@Value.Immutable
public class BddConfiguration {
    public static final int DEFAULT_CACHE_UNARY_DIVIDER = 64;
    public static final int DEFAULT_CACHE_BINARY_DIVIDER = 32;
    public static final int DEFAULT_CACHE_TERNARY_DIVIDER = 32;
    public static final int DEFAULT_CACHE_EPHEMERAL_MULTIPLIER = 32;
    public static final int DEFAULT_MTBDD_CACHE_UNARY_DIVIDER = 64;
    public static final int DEFAULT_MTBDD_CACHE_BINARY_DIVIDER = 32;
    public static final int DEFAULT_MTBDD_CACHE_TERNARY_DIVIDER = 32;
    public static final int DEFAULT_MTBDD_CACHE_EPHEMERAL_MULTIPLIER = 32;
    public static final int DEFAULT_REGISTERED_OPERATION_DIVIDER = 8;
    public static final double DEFAULT_NODE_TABLE_GROWTH_FACTOR = 2.0d;

    /** An optional, human-readable name for this configuration's instance - used to label diagnostics
     * (e.g. shutdown statistics) instead of falling back to an identity-based label; empty by default. */
    @Value.Default
    public String name() {
        return "";
    }

    @Value.Default
    public int initialSize() {
        return 1024;
    }

    /** Initial node-table size of the boolean diagram; defaults to {@link #initialSize()}. */
    @Value.Default
    public int bddInitialSize() {
        return initialSize();
    }

    /**
     * Initial node-table size of the companion MTBDD (see {@code MtBddImpl}); defaults to
     * {@link #initialSize()}. Separate from {@link #bddInitialSize()} because every {@code Bdd} carries an
     * MTBDD whether or not it is ever used, so the two tables' useful starting sizes rarely match.
     */
    @Value.Default
    public int mtbddInitialSize() {
        return initialSize();
    }

    @Value.Default
    public int cacheUnaryDivider() {
        return DEFAULT_CACHE_UNARY_DIVIDER;
    }

    @Value.Default
    public int cacheBinaryDivider() {
        return DEFAULT_CACHE_BINARY_DIVIDER;
    }

    @Value.Default
    public int cacheTernaryDivider() {
        return DEFAULT_CACHE_TERNARY_DIVIDER;
    }

    @Value.Default
    public int cacheEphemeralMultiplier() {
        return DEFAULT_CACHE_EPHEMERAL_MULTIPLIER;
    }

    @Value.Default
    public int mtbddCacheUnaryDivider() {
        return DEFAULT_MTBDD_CACHE_UNARY_DIVIDER;
    }

    @Value.Default
    public int mtbddCacheBinaryDivider() {
        return DEFAULT_MTBDD_CACHE_BINARY_DIVIDER;
    }

    @Value.Default
    public int mtbddCacheTernaryDivider() {
        return DEFAULT_MTBDD_CACHE_TERNARY_DIVIDER;
    }

    @Value.Default
    public int mtbddCacheEphemeralMultiplier() {
        return DEFAULT_MTBDD_CACHE_EPHEMERAL_MULTIPLIER;
    }

    /** Further divides down a registered operation's (see {@link RegisteredOperation}) initial cache size
     * relative to the base ephemeral cache it would otherwise match - registered operations have a
     * different usage pattern (one fixed parameter, reused many times) and grow from there based on actual
     * usage (see {@link CacheBase.IntKeys#growOnUsage()}) rather than tracking the table size directly. */
    @Value.Default
    public int registeredOperationDivider() {
        return DEFAULT_REGISTERED_OPERATION_DIVIDER;
    }

    @Value.Default
    public boolean logStatisticsOnShutdown() {
        return false;
    }

    @Value.Default
    public double growthFactor() {
        return DEFAULT_NODE_TABLE_GROWTH_FACTOR;
    }

    @Value.Default
    public boolean useGarbageCollection() {
        return true;
    }

    @Value.Default
    public boolean useCachePreserve() {
        return true;
    }

    @Value.Default
    public boolean threadSafetyCheck() {
        return false;
    }
}
