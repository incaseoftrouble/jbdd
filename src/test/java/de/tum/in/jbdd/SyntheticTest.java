package de.tum.in.jbdd;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

public class SyntheticTest {
    private static final int[][] nQueensPairs = {
        {4, 2},
        {5, 10},
        {6, 4},
        {7, 40},
        {8, 92},
        {9, 352}
    };

    @Test
    public void testQueens() {
        for (int[] pair : nQueensPairs) {
            Bdd bdd = BddFactory.buildBdd();
            assertThat(
                    bdd.countSatisfyingAssignments(BddBuilder.makeQueens(bdd, pair[0]))
                            .intValueExact(),
                    is(pair[1]));
        }
    }
}
