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

import org.junit.jupiter.api.Test;

/**
 * A collection of tests motivated by regressions.
 */
public class BddRegressionTest {
    private static final BddConfiguration config =
            ImmutableBddConfiguration.builder().build();

    @Test
    public void testReferenceOverflow() {
        BddImpl bdd = new BddImpl(false, config);
        int v1 = bdd.createVariable();
        int v2 = bdd.createVariable();
        int and = bdd.and(v1, v2);

        for (int i = 0; i < Integer.MAX_VALUE; i++) {
            bdd.reference(and);
        }

        for (int i = 0; i < Integer.MAX_VALUE; i++) {
            bdd.dereference(and);
        }
    }

    @Test
    public void testIteratorUniquePath() {
        BddImpl bdd = new BddImpl(false, config);
        int v1 = bdd.createVariable();
        int v2 = bdd.createVariable();
        int and = bdd.and(v1, v2);

        bdd.solutionIterator(and).forEachRemaining(bitSet -> {});
    }
}
