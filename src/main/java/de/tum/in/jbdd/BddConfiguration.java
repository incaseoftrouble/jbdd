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
    public static final int DEFAULT_CACHE_BINARY_DIVIDER = 32;
    public static final int DEFAULT_CACHE_SATISFACTION_DIVIDER = 32;
    public static final int DEFAULT_CACHE_TERNARY_DIVIDER = 64;
    public static final int DEFAULT_CACHE_NEGATION_DIVIDER = 32;
    public static final int DEFAULT_CACHE_COMPOSE_DIVIDER = 64;
    public static final double DEFAULT_NODE_TABLE_FREE_NODE_PERCENTAGE = 0.10d;
    public static final double DEFAULT_NODE_TABLE_GROWTH_FACTOR = 1.5d;

    @Value.Default
    public int initialSize() {
        return 1024;
    }

    @Value.Default
    public int cacheBinaryDivider() {
        return DEFAULT_CACHE_BINARY_DIVIDER;
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
    public int cacheNegationDivider() {
        return DEFAULT_CACHE_NEGATION_DIVIDER;
    }

    @Value.Default
    public int cacheComposeDivider() {
        return DEFAULT_CACHE_COMPOSE_DIVIDER;
    }

    @Value.Default
    public boolean logStatisticsOnShutdown() {
        return false;
    }

    @Value.Default
    public double minimumFreeNodePercentageAfterGc() {
        return DEFAULT_NODE_TABLE_FREE_NODE_PERCENTAGE;
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
    public boolean threadSafetyCheck() {
        return false;
    }
}
