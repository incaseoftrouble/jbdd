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

import de.tum.in.jbdd.collections.MutableNatSet;
import de.tum.in.jbdd.collections.NatSet;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Preimages, the relational product of symbolic model checking: {@code exists next. R(current, next) & S(next)} for a
 * transition relation {@code R} over interleaved current- and next-state variables and a batch of targets {@code S}
 * over the next-state ones, cold.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class RelationalProductBenchmark {
    private static final int TARGETS = 16;

    @SuppressWarnings("NullAway.Init")
    @Param({"COUNTER", "SHIFT", "SPARSE"})
    public Relation relation;

    @Param({"16", "32"})
    public int bits;

    @SuppressWarnings("NullAway.Init")
    private BddImpl bdd;

    private int transitions;

    @SuppressWarnings("NullAway.Init")
    private int[] targets;

    @SuppressWarnings("NullAway.Init")
    private NatSet next;

    public enum Relation {
        /** {@code x' = x + 1}: a functional relation, linear in the interleaved order. */
        COUNTER,
        /** A twisted shift register, {@code x'_i <-> x_(i-1)} and {@code x'_0 <-> !x_(n-1)}. */
        SHIFT,
        /** A union of random transitions, each a partial cube over the current and the next state. */
        SPARSE
    }

    @Setup(Level.Trial)
    public void setup() {
        bdd = (BddImpl) BddFactory.buildBdd();
        // Current-state x_i is variable 2i, next-state x'_i is 2i + 1.
        bdd.createVariables(2 * bits);
        MutableNatSet nextVariables = MutableNatSet.create();
        for (int i = 0; i < bits; i++) {
            nextVariables.set(nextState(i));
        }
        next = nextVariables;
        Random random = new Random(bits);
        transitions = bdd.reference(buildRelation(random));

        targets = new int[TARGETS];
        for (int t = 0; t < TARGETS; t++) {
            int target = bdd.falseFunction();
            for (int c = 0; c < 8; c++) {
                int cube = bdd.reference(randomCube(random, false, true));
                target = bdd.consume(bdd.or(target, cube), target, cube);
            }
            targets[t] = bdd.reference(target);
        }
    }

    private static int currentState(int bit) {
        return 2 * bit;
    }

    private static int nextState(int bit) {
        return 2 * bit + 1;
    }

    private int buildRelation(Random random) {
        int result = bdd.trueFunction();
        switch (relation) {
            case COUNTER:
                int carry = bdd.trueFunction();
                for (int i = 0; i < bits; i++) {
                    int current = bdd.variableFunction(currentState(i));
                    int sum = bdd.reference(bdd.xor(current, carry));
                    int step = bdd.reference(bdd.equivalence(bdd.variableFunction(nextState(i)), sum));
                    result = bdd.consume(bdd.and(result, step), result, step);
                    bdd.dereference(sum);
                    carry = bdd.updateWith(bdd.and(carry, current), carry);
                }
                bdd.dereference(carry);
                return result;
            case SHIFT:
                for (int i = 0; i < bits; i++) {
                    int source = i == 0
                            ? bdd.not(bdd.variableFunction(currentState(bits - 1)))
                            : bdd.variableFunction(currentState(i - 1));
                    int step = bdd.reference(bdd.equivalence(bdd.variableFunction(nextState(i)), source));
                    result = bdd.consume(bdd.and(result, step), result, step);
                }
                return result;
            case SPARSE:
                result = bdd.falseFunction();
                for (int t = 0; t < 4 * bits; t++) {
                    int transition = bdd.reference(randomCube(random, true, true));
                    result = bdd.consume(bdd.or(result, transition), result, transition);
                }
                return result;
        }
        throw new AssertionError(relation);
    }

    /** A cube fixing each chosen state's variables at random with probability one half. */
    private int randomCube(Random random, boolean overCurrent, boolean overNext) {
        int cube = bdd.trueFunction();
        for (int i = 0; i < bits; i++) {
            for (int variable : new int[] {overCurrent ? currentState(i) : -1, overNext ? nextState(i) : -1}) {
                if (variable >= 0 && random.nextBoolean()) {
                    int literal = bdd.variableFunction(variable);
                    cube = bdd.updateWith(bdd.and(cube, random.nextBoolean() ? literal : bdd.not(literal)), cube);
                }
            }
        }
        return cube;
    }

    // Cold: a preimage is computed once per fixpoint step, and a repetition would be answered from the cache.
    @Setup(Level.Invocation)
    public void invalidate() {
        bdd.invalidateCache();
    }

    @Benchmark
    public void andExists(Blackhole bh) {
        for (int target : targets) {
            bh.consume(bdd.andExists(transitions, target, next));
        }
    }
}
