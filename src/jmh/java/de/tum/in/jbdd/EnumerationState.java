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

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Diagrams for the enumeration benchmarks, spanning the two things that decide what enumeration costs:
 * how many solutions each path carries (the free-variable counter runs underneath a path, so a shape
 * with many free variables amortizes everything the path itself does), and how long a path is (which is
 * what a per-path rebuild is linear in).
 *
 * <p>The order is either the identity - where a cursor hands out the walk's own sets and no translation
 * happens at all - or odd variables above even ones, which is a permutation no walk can shortcut and
 * which moves every variable but the first and last.
 */
@State(Scope.Benchmark)
public class EnumerationState {
    /**
     * MANY_FREE: one short path, everything else free - the counter dominates entirely. SOME_FREE: half
     * the support decided, half counted. NO_FREE: every support variable decided by the path, so there
     * is one path per solution and nothing to amortize a per-path cost over. LONG_PATHS: few paths and
     * few solutions, but every path is as long as the diagram is deep.
     */
    @Param({"MANY_FREE", "SOME_FREE", "NO_FREE", "LONG_PATHS"})
    private Shape shape;

    @Param({"IDENTITY", "ODDS_FIRST"})
    private Order order;

    private BddImpl bdd;
    private int function;
    private BitSet support;

    public enum Shape {
        MANY_FREE(20),
        SOME_FREE(20),
        NO_FREE(20),
        LONG_PATHS(256);

        final int variables;

        Shape(int variables) {
            this.variables = variables;
        }
    }

    public enum Order {
        IDENTITY,
        ODDS_FIRST
    }

    @Setup(Level.Trial)
    public void setUp() {
        int variables = shape.variables;
        Bdd diagram = BddFactory.buildBdd();
        diagram.createVariables(variables);

        if (order == Order.ODDS_FIRST) {
            // On the empty diagram, so the swaps rewrite nothing and only the order is left behind.
            BitSet odds = new BitSet(variables);
            BitSet evens = new BitSet(variables);
            for (int variable = 0; variable < variables; variable++) {
                (variable % 2 == 0 ? evens : odds).set(variable);
            }
            List<BitSet> blocks = new ArrayList<>(2);
            blocks.add(odds);
            blocks.add(evens);
            diagram.reorderTo(blocks);
        }

        this.bdd = (BddImpl) diagram;
        this.function = bdd.reference(build(bdd, shape));
        this.support = new BitSet(variables);
        this.support.set(0, variables);

        if (order == Order.ODDS_FIRST && !bdd.reordered()) {
            throw new IllegalStateException("The order came back to the identity - nothing would translate");
        }
    }

    /** Every variable is in the support handed to the cursor, whether the function reads it or not. */
    public BitSet support() {
        return support;
    }

    public BddImpl bdd() {
        return bdd;
    }

    public int function() {
        return function;
    }

    private static int build(Bdd bdd, Shape shape) {
        switch (shape) {
            case MANY_FREE:
                return bdd.and(bdd.variableFunction(0), bdd.variableFunction(1));
            case SOME_FREE:
                return parity(bdd, shape.variables / 2);
            case NO_FREE:
                return parity(bdd, shape.variables);
            case LONG_PATHS:
                return exactlyOne(bdd, shape.variables);
            default:
                throw new AssertionError(shape);
        }
    }

    /** XOR of the first {@code count} variables: every path decides all of them, and nothing else. */
    private static int parity(Bdd bdd, int count) {
        int result = bdd.falseFunction();
        for (int variable = 0; variable < count; variable++) {
            result = bdd.updateWith(bdd.xor(result, bdd.variableFunction(variable)), result);
        }
        return result;
    }

    /**
     * Exactly one of {@code count} variables is true: {@code count} solutions, and each is a path that
     * has to decide every variable, so the paths are as long as the diagram is deep.
     */
    private static int exactlyOne(Bdd bdd, int count) {
        int[] noneBelow = new int[count + 1];
        noneBelow[count] = bdd.trueFunction();
        for (int variable = count - 1; variable >= 0; variable--) {
            noneBelow[variable] =
                    bdd.reference(bdd.and(bdd.not(bdd.variableFunction(variable)), noneBelow[variable + 1]));
        }

        int result = bdd.falseFunction();
        int noneAbove = bdd.trueFunction();
        for (int variable = 0; variable < count; variable++) {
            int chosen = bdd.reference(bdd.and(bdd.variableFunction(variable), noneBelow[variable + 1]));
            int term = bdd.reference(bdd.and(noneAbove, chosen));
            bdd.dereference(chosen);
            result = bdd.consume(bdd.or(result, term), result, term);
            noneAbove = bdd.updateWith(bdd.and(noneAbove, bdd.not(bdd.variableFunction(variable))), noneAbove);
        }
        for (int variable = 0; variable < count; variable++) {
            bdd.dereference(noneBelow[variable]);
        }
        bdd.dereference(noneAbove);
        return result;
    }
}
