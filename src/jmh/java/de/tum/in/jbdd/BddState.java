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

    @Param({"false"})
    private boolean emulateMdd;

    @SuppressWarnings("NullAway.Init")
    private BinaryDecisionDiagram bdd;

    @SuppressWarnings("NumericCastThatLosesPrecision")
    @Setup(Level.Iteration)
    public void setUpBdd() {
        BddConfiguration configuration = ImmutableBddConfiguration.builder()
                .cacheSizeDivider((int) (BddConfiguration.DEFAULT_CACHE_SIZE_DIVIDER / cacheSizeFactor))
                .build();
        bdd = emulateMdd ? new MddAsBinaryDd(new MddImpl(configuration)) : BddFactory.buildBdd(configuration);
    }

    public BinaryDecisionDiagram bdd() {
        return bdd;
    }
}
