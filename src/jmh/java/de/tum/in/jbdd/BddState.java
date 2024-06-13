/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2023 Tobias Meggendorfer.
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

import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@State(Scope.Benchmark)
public class BddState {
    @Param({"1"})
    private float cacheSizeFactor;

    @Param({"true"})
    private boolean partialInvalidation;

    @Param({"true"})
    private boolean preserveCache;

    @Param({"false"})
    private boolean emulateMdd;

    private Bdd bdd;

    @SuppressWarnings("NumericCastThatLosesPrecision")
    @Setup(Level.Iteration)
    public void setUpBdd() {
        BddConfiguration configuration = ImmutableBddConfiguration.builder()
                .cacheBinaryDivider((int) (BddConfiguration.DEFAULT_CACHE_BINARY_DIVIDER / cacheSizeFactor))
                .cacheTernaryDivider((int) (BddConfiguration.DEFAULT_CACHE_TERNARY_DIVIDER / cacheSizeFactor))
                .cacheSatisfactionDivider((int) (BddConfiguration.DEFAULT_CACHE_SATISFACTION_DIVIDER / cacheSizeFactor))
                .cacheComposeMultiplier((int) (BddConfiguration.DEFAULT_CACHE_COMPOSE_MULTIPLIER * cacheSizeFactor))
                .cacheQuantificationMultiplier(
                        (int) (BddConfiguration.DEFAULT_CACHE_QUANTIFICATION_MULTIPLIER * cacheSizeFactor))
                .useCachePartialInvalidate(partialInvalidation)
                .useCachePreserveOnGrow(preserveCache)
                .build();
        bdd = emulateMdd ? new MddAsTestBdd(new MddImpl(configuration)) : BddFactory.buildBdd(configuration);
    }

    public Bdd bdd() {
        return bdd;
    }
}
