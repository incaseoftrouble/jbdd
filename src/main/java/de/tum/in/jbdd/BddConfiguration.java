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
    public static final int DEFAULT_CACHE_BINARY_DIVIDER = 32;
    public static final int DEFAULT_CACHE_IMPLIES_DIVIDER = 64;
    public static final int DEFAULT_CACHE_SATISFACTION_DIVIDER = 32;
    public static final int DEFAULT_CACHE_TERNARY_DIVIDER = 64;
    public static final int DEFAULT_CACHE_COMPOSE_MULTIPLIER = 64;
    public static final int DEFAULT_CACHE_QUANTIFICATION_MULTIPLIER = 32;
    public static final double DEFAULT_NODE_TABLE_GROWTH_FACTOR = 2d;

    @Value.Default
    public int initialSize() {
        return 1024;
    }

    @Value.Default
    public int cacheBinaryDivider() {
        return DEFAULT_CACHE_BINARY_DIVIDER;
    }

    @Value.Default
    public int cacheImpliesDivider() {
        return DEFAULT_CACHE_IMPLIES_DIVIDER;
    }

    @Value.Default
    public int cacheSatisfactionDivider() {
        return DEFAULT_CACHE_SATISFACTION_DIVIDER;
    }

    @Value.Default
    public int cacheTernaryDivider() {
        return DEFAULT_CACHE_TERNARY_DIVIDER;
    }

    @Value.Default
    public int cacheComposeMultiplier() {
        return DEFAULT_CACHE_COMPOSE_MULTIPLIER;
    }

    @Value.Default
    public int cacheQuantificationMultiplier() {
        return DEFAULT_CACHE_QUANTIFICATION_MULTIPLIER;
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
    public boolean useCachePartialInvalidate() {
        return true;
    }

    @Value.Default
    public boolean useCachePreserveOnGrow() {
        return true;
    }

    @Value.Default
    public boolean threadSafetyCheck() {
        return false;
    }
}
