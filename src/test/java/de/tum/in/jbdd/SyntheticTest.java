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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class SyntheticTest {
    private static final int[][] nQueensPairs = {
        {4, 2},
        {5, 10},
        {6, 4},
        {7, 40},
        {8, 92},
        {9, 352}
    };

    /* The boards grow exponentially, so the largest one alone dominates this test and scaling its size
     * proportionally would not do anything useful - the bound moves in whole boards instead. */
    private static int maxBoardSize() {
        double scale = TestProfile.scale();
        if (scale < 0.5) {
            return 7;
        }
        return scale < 0.9 ? 8 : 9;
    }

    @Test
    void testQueens() {
        int maxBoardSize = maxBoardSize();

        for (int[] pair : nQueensPairs) {
            if (pair[0] > maxBoardSize) {
                continue;
            }

            Bdd bdd = BddFactory.buildBdd();
            assertThat(
                    bdd.countSatisfyingAssignments(BddBuilder.makeQueens(bdd, pair[0]))
                            .intValueExact(),
                    is(pair[1]));
        }
    }
}
