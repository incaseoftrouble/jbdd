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

/**
 * Conjunctions and disjunctions of many literals, each built as a left fold of binary operations the way a client
 * translates a formula (a sum of products, a long clause): a chain of nodes, one per variable, each built from the one
 * below it. The literals come in ascending variable order (each new one below the accumulated chain, which is rebuilt
 * bottom-up), descending (each new one on top) or in random order; every formula has random polarities, so each is a
 * chain of its own.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class ChainBenchmark {
    @Param({"32", "256"})
    public int length;

    @Param({"ascending", "descending", "random"})
    @SuppressWarnings("NullAway.Init")
    public String order;

    @SuppressWarnings("NullAway.Init")
    private Bdd bdd;

    @SuppressWarnings("NullAway.Init")
    private int[] variables;

    // per formula: its literals' variable indices in fold order, their polarities, and whether it is a conjunction
    @SuppressWarnings("NullAway.Init")
    private int[][] literalVariables;

    @SuppressWarnings("NullAway.Init")
    private boolean[][] polarities;

    @SuppressWarnings("NullAway.Init")
    private boolean[] conjunction;

    @Setup(Level.Trial)
    public void setup() {
        bdd = BddFactory.buildBdd();
        variables = bdd.createVariables(length);
        Random random = new Random(length * 31L + order.hashCode());
        int formulas = 16384 / length;
        literalVariables = new int[formulas][length];
        polarities = new boolean[formulas][length];
        conjunction = new boolean[formulas];
        for (int f = 0; f < formulas; f++) {
            int[] indices = literalVariables[f];
            for (int i = 0; i < length; i++) {
                indices[i] = order.equals("descending") ? length - 1 - i : i;
                polarities[f][i] = random.nextBoolean();
            }
            if (order.equals("random")) {
                for (int i = length - 1; i > 0; i--) {
                    int j = random.nextInt(i + 1);
                    int swap = indices[i];
                    indices[i] = indices[j];
                    indices[j] = swap;
                }
            }
            conjunction[f] = random.nextBoolean();
        }
    }

    // Cold: a formula is translated once in practice, and a repetition would be answered from the cache.
    @Setup(Level.Invocation)
    public void invalidate() {
        bdd.invalidateCache();
    }

    @Benchmark
    public int fold() {
        int[] results = new int[literalVariables.length];
        for (int f = 0; f < literalVariables.length; f++) {
            boolean and = conjunction[f];
            int accumulator = and ? bdd.trueFunction() : bdd.falseFunction();
            for (int i = 0; i < length; i++) {
                int variable = variables[literalVariables[f][i]];
                int literal = polarities[f][i] ? variable : bdd.not(variable);
                accumulator =
                        bdd.updateWith(and ? bdd.and(accumulator, literal) : bdd.or(accumulator, literal), accumulator);
            }
            results[f] = accumulator;
        }
        int nodes = bdd.referencedNodeCount();
        for (int result : results) {
            bdd.dereference(result);
        }
        return nodes;
    }
}
