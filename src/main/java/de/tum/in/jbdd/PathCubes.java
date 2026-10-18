/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2026 Tobias Meggendorfer.
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

import de.tum.in.jbdd.MultiTerminalDecisionDiagram.ValueCubes;
import de.tum.in.jbdd.collections.IntIntHashMap;
import de.tum.in.jbdd.collections.IntObjectHashMap;
import de.tum.in.jbdd.collections.MutableNatSet;
import de.tum.in.jbdd.collections.NatSet;
import java.util.Optional;
import java.util.function.IntPredicate;

/**
 * Paths of a function to terminals, as cubes: {@link MultiTerminalDecisionDiagram#cubes},
 * {@link MultiTerminalDecisionDiagram#shortestCube}, {@link BinaryDecisionDiagram#cube} and
 * {@link BinaryDecisionDiagram#shortestCube}. Of several candidates each takes the first in path order, low before high.
 */
final class PathCubes {
    private static final boolean[] NO_ASSIGNMENT = new boolean[0];
    private static final int UNREACHABLE = Integer.MAX_VALUE;

    private PathCubes() {}

    static ValueCubes cubes(MultiTerminalDecisionDiagram diagram, int function, IntPredicate values, int expected) {
        OfMap cubes = new OfMap();
        walk(
                diagram,
                function,
                values,
                expected,
                MutableNatSet.create(),
                MutableNatSet.create(),
                new IntIntHashMap(),
                cubes);
        return cubes;
    }

    // Depth first, each node once: a node already visited leads only to values that have their cube already.
    private static void walk(
            MultiTerminalDecisionDiagram diagram,
            int function,
            IntPredicate values,
            int expected,
            MutableNatSet assignment,
            MutableNatSet support,
            IntIntHashMap visited,
            OfMap cubes) {
        if (diagram.isConstant(function)) {
            int value = diagram.evaluate(function, NO_ASSIGNMENT);
            if (!cubes.containsKey(value) && values.test(value)) {
                cubes.put(value, Cube.of(assignment, support));
            }
            return;
        }
        if (cubes.size() == expected || visited.containsKey(function)) {
            return;
        }
        visited.put(function, 0);
        int variable = diagram.decisionVariable(function);
        support.set(variable);
        walk(diagram, diagram.lowOf(function), values, expected, assignment, support, visited, cubes);
        assignment.set(variable);
        walk(diagram, diagram.highOf(function), values, expected, assignment, support, visited, cubes);
        assignment.clear(variable);
        support.clear(variable);
    }

    static ValueCubes shortestCubes(MultiTerminalDecisionDiagram diagram, int function, NatSet values) {
        OfMap cubes = new OfMap();
        values.forEach((int value) -> shortestTo(
                        diagram, function, terminal -> terminalValue(diagram, terminal) == value)
                .ifPresent(cube -> cubes.put(value, cube)));
        return cubes;
    }

    static Optional<Cube> shortestCube(MultiTerminalDecisionDiagram diagram, int function, IntPredicate values) {
        return shortestTo(diagram, function, (int terminal) -> values.test(terminalValue(diagram, terminal)));
    }

    private static int terminalValue(MultiTerminalDecisionDiagram diagram, int terminal) {
        return diagram.evaluate(terminal, NO_ASSIGNMENT);
    }

    // A reduced diagram's nodes other than false all reach true: avoiding false never runs into a dead end.
    static Optional<Cube> cube(BinaryDecisionDiagram bdd, int function) {
        if (function == bdd.falseFunction()) {
            return Optional.empty();
        }
        MutableNatSet assignment = MutableNatSet.create();
        MutableNatSet support = MutableNatSet.create();
        int node = function;
        while (node != bdd.trueFunction()) {
            int variable = bdd.decisionVariable(node);
            support.set(variable);
            int low = bdd.lowOf(node);
            if (low == bdd.falseFunction()) {
                assignment.set(variable);
                node = bdd.highOf(node);
            } else {
                node = low;
            }
        }
        return Optional.of(Cube.of(assignment, support));
    }

    /**
     * A path of {@code function} with the fewest decisions to a terminal {@code accepted} takes (it is given the
     * terminal function), the first in path order of those.
     *
     * <p>A memoized recursion over the diagram, bounded by the shortest path found so far: a subtree that cannot beat
     * it is left early, and what that showed about it is remembered as a lower bound.
     */
    static Optional<Cube> shortestTo(BooleanDecisionDiagram diagram, int function, IntPredicate accepted) {
        // Exact lengths as themselves, the lower bound of a subtree left early as its negation.
        IntIntHashMap lengths = new IntIntHashMap();
        int length = length(diagram, function, accepted, UNREACHABLE, lengths);
        if (length == UNREACHABLE) {
            return Optional.empty();
        }
        MutableNatSet assignment = MutableNatSet.create();
        MutableNatSet support = MutableNatSet.create();
        int node = function;
        for (int remaining = length; remaining > 0; remaining--) {
            int variable = diagram.decisionVariable(node);
            support.set(variable);
            int low = diagram.lowOf(node);
            // Low first: every child on a shortest path got its exact length.
            if (exactLength(diagram, low, accepted, lengths) == remaining - 1) {
                node = low;
            } else {
                assignment.set(variable);
                node = diagram.highOf(node);
                assert exactLength(diagram, node, accepted, lengths) == remaining - 1;
            }
        }
        assert diagram.isConstant(node) && accepted.test(node);
        return Optional.of(Cube.of(assignment, support));
    }

    /** The length of the shortest path of {@code function} if below {@code bound}, else a lower bound of at least it. */
    private static int length(
            BooleanDecisionDiagram diagram, int function, IntPredicate accepted, int bound, IntIntHashMap lengths) {
        if (diagram.isConstant(function)) {
            return accepted.test(function) ? 0 : UNREACHABLE;
        }
        int known = lengths.get(function, Integer.MIN_VALUE);
        if (known != Integer.MIN_VALUE && (known > 0 || -known >= bound)) {
            return Math.abs(known);
        }
        if (bound <= 1) {
            // Every decision node is at least one decision away from a terminal.
            return 1;
        }

        int low = length(diagram, diagram.lowOf(function), accepted, bound - 1, lengths);
        int best = low == UNREACHABLE ? low : low + 1;
        int high = length(diagram, diagram.highOf(function), accepted, Math.min(bound, best) - 1, lengths);
        int length = Math.min(best, high == UNREACHABLE ? high : high + 1);
        // Below the bound, both children were searched far enough for this to be exact; otherwise each is a lower
        // bound and so is their minimum.
        lengths.put(function, length < bound ? length : -length);
        return length;
    }

    private static int exactLength(
            BooleanDecisionDiagram diagram, int function, IntPredicate accepted, IntIntHashMap lengths) {
        if (diagram.isConstant(function)) {
            return accepted.test(function) ? 0 : UNREACHABLE;
        }
        return Math.max(lengths.get(function, -1), -1);
    }

    /** {@link ValueCubes} over a map, filled by {@link #put}. */
    static final class OfMap implements ValueCubes {
        private final IntObjectHashMap<Cube> cubes = new IntObjectHashMap<>();
        private final MutableNatSet codomain = MutableNatSet.create();

        void put(int value, Cube cube) {
            cubes.put(value, cube);
            codomain.set(value);
        }

        boolean containsKey(int value) {
            return codomain.contains(value);
        }

        int size() {
            return cubes.size();
        }

        @Override
        public NatSet codomain() {
            return codomain;
        }

        @Override
        public Cube cubeFor(int value) {
            Cube cube = cubes.get(value);
            if (cube == null) {
                throw new IllegalArgumentException("No cube for value " + value);
            }
            return cube;
        }

        @Override
        public void forEach(MultiTerminalDecisionDiagram.PathValueConsumer action) {
            codomain.forEach((int value) -> action.accept(cubeFor(value), value));
        }

        @Override
        public String toString() {
            return cubes.toString();
        }
    }
}
