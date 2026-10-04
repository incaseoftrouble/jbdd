/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2017-2026 Tobias Meggendorfer.
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
 * The n-ary conjunction and disjunction on the two shapes a client hands them: the union of many guarded cubes (a
 * cube over some variables conjoined with a small function over others) and the conjunction of many clauses.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class NaryBenchmark {
    @Param({"200", "2000"})
    public int operands;

    @SuppressWarnings("NullAway.Init")
    private Bdd bdd;

    @SuppressWarnings("NullAway.Init")
    private int[] guardedCubes;

    @SuppressWarnings("NullAway.Init")
    private int[] clauses;

    @Setup(Level.Trial)
    public void setup() {
        bdd = BddFactory.buildBdd();
        int cubeVariables = 12;
        int inputVariables = 10;
        int[] variables = bdd.createVariables(cubeVariables + inputVariables);
        Random random = new Random(operands);

        guardedCubes = new int[operands];
        for (int i = 0; i < operands; i++) {
            int cube = bdd.trueFunction();
            for (int j = 0; j < cubeVariables; j++) {
                int literal = random.nextBoolean() ? variables[j] : bdd.not(variables[j]);
                cube = bdd.updateWith(bdd.and(cube, literal), cube);
            }
            int input = bdd.trueFunction();
            for (int j = 0; j < 3; j++) {
                int variable = variables[cubeVariables + random.nextInt(inputVariables)];
                int literal = random.nextBoolean() ? variable : bdd.not(variable);
                input = bdd.updateWith(random.nextBoolean() ? bdd.and(input, literal) : bdd.or(input, literal), input);
            }
            guardedCubes[i] = bdd.reference(bdd.and(cube, input));
            bdd.dereference(cube, input);
        }

        clauses = new int[operands];
        for (int i = 0; i < operands; i++) {
            int clause = bdd.falseFunction();
            for (int j = 0; j < 3; j++) {
                int variable = variables[random.nextInt(variables.length)];
                int literal = random.nextBoolean() ? variable : bdd.not(variable);
                clause = bdd.updateWith(bdd.or(clause, literal), clause);
            }
            clauses[i] = clause;
        }
    }

    // Cold: a conjunction is computed once in practice, and a repetition would be answered from the cache.
    @Setup(Level.Invocation)
    public void invalidate() {
        bdd.invalidateCache();
    }

    @Benchmark
    public void unionOfGuardedCubes(Blackhole bh) {
        bh.consume(bdd.or(guardedCubes));
    }

    @Benchmark
    public void conjunctionOfClauses(Blackhole bh) {
        bh.consume(bdd.and(clauses));
    }
}
