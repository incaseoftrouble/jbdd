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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.tum.in.jbdd.collections.MutableNatSet;
import de.tum.in.jbdd.collections.NatSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CubeTest {
    // x0 & !x2
    private static final Cube CUBE = Cube.of(NatSet.of(0, 1), NatSet.of(0, 2));

    @Test
    void testFactories() {
        assertEquals(NatSet.of(0), CUBE.assignment());
        assertEquals(NatSet.of(0, 2), CUBE.support());
        assertEquals(Cube.of(NatSet.of(3), NatSet.of(3)), Cube.literal(3, true));
        assertEquals(Cube.of(NatSet.of(), NatSet.of(3)), Cube.literal(3, false));
        assertEquals(Cube.of(NatSet.of(1, 2), NatSet.of(1, 2)), Cube.positive(NatSet.of(1, 2)));
        assertEquals(Cube.of(NatSet.of(), NatSet.of(1, 2)), Cube.negative(NatSet.of(1, 2)));
        assertTrue(Cube.empty().isEmpty());
        assertEquals("1?0", CUBE.toString());
    }

    @Test
    void testCopyingAndSharing() {
        // of copies: nothing done to the sets it was given changes the cube; what it hands out never changes.
        MutableNatSet valuation = MutableNatSet.copyOf(NatSet.of(0));
        MutableNatSet support = MutableNatSet.copyOf(NatSet.of(0, 2));
        Cube safe = Cube.of(valuation, support);
        valuation.clear();
        support.clear();
        assertEquals(CUBE, safe);
        assertFalse(safe.assignment() instanceof MutableNatSet);
        assertFalse(safe.support() instanceof MutableNatSet);
        assertSame(safe, safe.copy());

        // ofUnsafe shares: the cube is the sets it was given and hands out.
        NatSet assignment = NatSet.of(0);
        NatSet owned = NatSet.of(0, 2);
        Cube unsafe = Cube.ofUnsafe(assignment, owned);
        assertSame(assignment, unsafe.assignment());
        assertSame(owned, unsafe.support());
        assertEquals(CUBE, unsafe);
        assertThrows(AssertionError.class, () -> Cube.ofUnsafe(NatSet.of(1), NatSet.of(0)));
    }

    @Test
    void testQueries() {
        assertEquals(2, CUBE.size());
        assertTrue(CUBE.fixes(2));
        assertFalse(CUBE.fixes(1));
        assertTrue(CUBE.value(0));
        assertFalse(CUBE.value(2));
        assertThrows(IllegalArgumentException.class, () -> CUBE.value(1));
        assertEquals(NatSet.of(0), CUBE.assignment());
        assertEquals(NatSet.of(2), CUBE.negatives());

        assertTrue(CUBE.contains(NatSet.of(0, 1)));
        assertTrue(CUBE.contains(NatSet.of(0)));
        assertFalse(CUBE.contains(NatSet.of(0, 2)));
        assertFalse(CUBE.contains(NatSet.of()));

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
        assertEquals(Optional.of(Cube.of(NatSet.of(0), NatSet.of(0, 1, 2))), CUBE.intersection(Cube.literal(1, false)));
    }

    @Test
    void testDerivedCubes() {
        assertEquals(Cube.of(NatSet.of(0, 2), NatSet.of(0, 2)), CUBE.with(2, true));
        assertEquals(Cube.literal(0, true), CUBE.without(2));
        assertEquals(CUBE, CUBE.without(5));
        assertEquals(Cube.literal(2, false), CUBE.restrictedTo(NatSet.of(1, 2)));
        // Operations never touch their operand.
        assertEquals(Cube.of(NatSet.of(0), NatSet.of(0, 2)), CUBE);
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
        assertEquals(sets.var(1).intersection(sets.var(2)), sets.of(Cube.positive(NatSet.of(1, 2))));
    }

    private static NatSet randomSet(Random random, int span, int count) {
        MutableNatSet set = random.nextBoolean() ? MutableNatSet.create() : MutableNatSet.dense(span);
        for (int index = 0; index < count; index++) {
            set.set(random.nextInt(span));
        }
        // Immutable or mutable, as array or words (dense: words past the last element).
        return random.nextBoolean() ? NatSet.copyOf(set) : set;
    }

    // A cube over the given sets, sometimes held as mutable words running past their last element.
    private static Cube cube(Random random, NatSet assignment, NatSet support, int span) {
        if (random.nextBoolean()) {
            return Cube.of(assignment, support);
        }
        MutableNatSet wideAssignment = MutableNatSet.dense(span + 64);
        wideAssignment.or(assignment.intersection(support));
        MutableNatSet wideSupport = MutableNatSet.dense(span + 64);
        wideSupport.or(support);
        return Cube.ofUnsafe(wideAssignment, random.nextBoolean() ? wideSupport : support);
    }

    private static boolean fixesAlike(Cube cube, NatSet valuation, int variable) {
        return cube.assignment().contains(variable) == valuation.contains(variable);
    }

    @Test
    void predicatesAgreeWithTheirDefinition() {
        // Words are compared word by word, anything else variable by variable: both, over every representation.
        Random random = new Random(5);
        for (int span : new int[] {8, 64, 200, 5000}) {
            for (int round = 0; round < 300; round++) {
                NatSet support = randomSet(random, span, random.nextInt(8));
                Cube cube = cube(random, randomSet(random, span, random.nextInt(8)), support, span);
                NatSet otherSupport = random.nextBoolean()
                        ? support.intersection(randomSet(random, span, 8))
                        : randomSet(random, span, 4);
                Cube other = cube(
                        random,
                        random.nextBoolean() ? cube.assignment() : randomSet(random, span, 4),
                        otherSupport,
                        span);
                NatSet valuation = random.nextBoolean()
                        ? cube.assignment().union(randomSet(random, span, 3).difference(support))
                        : randomSet(random, span, 6);

                assertEquals(support.allMatch(v -> fixesAlike(cube, valuation, v)), cube.contains(valuation));
                assertEquals(
                        other.support()
                                .allMatch(v -> cube.support().contains(v) && fixesAlike(cube, other.assignment(), v)),
                        cube.implies(other));
                assertEquals(
                        cube.support()
                                .noneMatch(
                                        v -> other.support().contains(v) && !fixesAlike(cube, other.assignment(), v)),
                        cube.intersects(other));
                assertEquals(cube.intersects(other), other.intersects(cube));
                int variable = random.nextInt(span);
                Cube with = cube.with(variable, random.nextBoolean());
                assertEquals(support.union(NatSet.of(variable)), with.support());
                assertEquals(
                        cube.without(variable).with(variable, with.assignment().contains(variable)), with);
                assertEquals(
                        support.difference(NatSet.of(variable)),
                        cube.without(variable).support());
            }
        }
    }
}
