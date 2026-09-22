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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CubeTest {
    // x0 & !x2
    private static final Cube CUBE = Cube.of(BitSets.of(0, 1), BitSets.of(0, 2));

    @Test
    void testFactories() {
        assertEquals(BitSets.of(0), CUBE.assignment());
        assertEquals(BitSets.of(0, 2), CUBE.support());
        assertEquals(Cube.of(BitSets.of(3), BitSets.of(3)), Cube.literal(3, true));
        assertEquals(Cube.of(BitSets.of(), BitSets.of(3)), Cube.literal(3, false));
        assertEquals(Cube.of(BitSets.of(1, 2), BitSets.of(1, 2)), Cube.positive(BitSets.of(1, 2)));
        assertEquals(Cube.of(BitSets.of(), BitSets.of(1, 2)), Cube.negative(BitSets.of(1, 2)));
        assertTrue(Cube.empty().isEmpty());
        assertEquals("1?0", CUBE.toString());
    }

    @Test
    void testQueries() {
        assertEquals(2, CUBE.size());
        assertTrue(CUBE.fixes(2));
        assertFalse(CUBE.fixes(1));
        assertTrue(CUBE.value(0));
        assertFalse(CUBE.value(2));
        assertThrows(IllegalArgumentException.class, () -> CUBE.value(1));
        assertEquals(BitSets.of(0), CUBE.positives());
        assertEquals(BitSets.of(2), CUBE.negatives());

        assertTrue(CUBE.contains(BitSets.of(0, 1)));
        assertTrue(CUBE.contains(BitSets.of(0)));
        assertFalse(CUBE.contains(BitSets.of(0, 2)));
        assertFalse(CUBE.contains(BitSets.of()));

        List<String> literals = new ArrayList<>();
        CUBE.forEachLiteral((variable, value) -> literals.add((value ? "" : "!") + variable));
        assertEquals(List.of("0", "!2"), literals);
    }

    @Test
    void testRelations() {
        Cube stronger = CUBE.with(1, true);
        assertTrue(stronger.implies(CUBE));
        assertFalse(CUBE.implies(stronger));
        assertTrue(CUBE.implies(Cube.empty()));
        assertTrue(CUBE.implies(CUBE));

        Cube contradicting = Cube.literal(0, false);
        assertFalse(CUBE.intersects(contradicting));
        assertEquals(Optional.empty(), CUBE.intersection(contradicting));
        assertTrue(CUBE.intersects(Cube.literal(1, false)));
        assertEquals(
                Optional.of(Cube.of(BitSets.of(0), BitSets.of(0, 1, 2))), CUBE.intersection(Cube.literal(1, false)));
    }

    @Test
    void testDerivedCubes() {
        assertEquals(Cube.of(BitSets.of(0, 2), BitSets.of(0, 2)), CUBE.with(2, true));
        assertEquals(Cube.literal(0, true), CUBE.without(2));
        assertEquals(CUBE, CUBE.without(5));
        assertEquals(Cube.literal(2, false), CUBE.restrictedTo(BitSets.of(1, 2)));
        // Operations never touch their operand.
        assertEquals(Cube.of(BitSets.of(0), BitSets.of(0, 2)), CUBE);
    }

    @Test
    void testAntichain() {
        Cube x0 = Cube.literal(0, true);
        Cube x0x1 = x0.with(1, true);
        Cube notX2 = Cube.literal(2, false);
        // x0 & x1 says nothing x0 does not already; the duplicate goes too.
        assertEquals(List.of(x0, notX2), Cube.antichain(List.of(x0x1, x0, notX2, x0.copy())));
        assertEquals(List.of(), Cube.antichain(List.of()));
    }

    @Test
    void testAsFunction() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        BddSetFactory sets = ctx.bddSets();
        assertEquals(sets.var(0).intersection(sets.var(2).complement()), sets.of(CUBE));
        assertEquals(sets.universe(), sets.of(Cube.empty()));
        assertEquals(sets.var(1).intersection(sets.var(2)), sets.of(Cube.positive(BitSets.of(1, 2))));
    }
}
