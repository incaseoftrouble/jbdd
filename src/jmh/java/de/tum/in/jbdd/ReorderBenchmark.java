/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2017 Tobias Meggendorfer.
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
import java.util.ArrayList;
import java.util.List;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Sifting over the synthetic diagrams, built fresh for every invocation: the whole reordering machinery (the
 * bookkeeping, the swaps, the collections inside the bracket), measured as the time to sift and the nodes saved.
 */
public class ReorderBenchmark extends BaseBddBenchmark {
    @State(Scope.Benchmark)
    public static class ReorderState {
        @SuppressWarnings("NullAway.Init")
        @Param({"QUEENS", "ADDER"})
        private Workload workload;

        @Param({"false", "true"})
        private boolean keepStructures;

        @SuppressWarnings("NullAway.Init")
        private Bdd bdd;

        @SuppressWarnings("NullAway.Init")
        private int[] functions;

        public enum Workload {
            QUEENS,
            ADDER
        }

        @Setup(Level.Invocation)
        public void build() {
            bdd = BddFactory.buildBdd(ImmutableBddConfiguration.builder()
                    .keepReorderingStructures(keepStructures)
                    .build());
            switch (workload) {
                case QUEENS:
                    functions = new int[] {bdd.reference(BddBuilder.makeQueens(bdd, 8))};
                    break;
                case ADDER:
                    int[][] adder = BddBuilder.makeAdder(bdd, 24);
                    List<Integer> all = new ArrayList<>();
                    for (int[] row : adder) {
                        for (int function : row) {
                            all.add(bdd.reference(function));
                        }
                    }
                    functions = all.stream().mapToInt(Integer::intValue).toArray();
                    break;
            }
        }

        public Bdd bdd() {
            return bdd;
        }

        public int[] functions() {
            return functions;
        }
    }

    /** Sifting every variable. */
    @Benchmark
    public static void sift(ReorderState state, Blackhole bh) {
        bh.consume(state.bdd().variableOrder().reorder());
        bh.consume(state.functions());
    }

    /** Sifting within two blocks: the variables below and above the middle, as a client with a known structure asks. */
    @Benchmark
    public static void siftInGroups(ReorderState state, Blackhole bh) {
        Bdd bdd = state.bdd();
        int variables = bdd.numberOfVariables();
        MutableNatSet lower = MutableNatSet.dense(variables);
        MutableNatSet upper = MutableNatSet.dense(variables);
        lower.set(0, variables / 2);
        upper.set(variables / 2, variables);
        List<NatSet> groups = new ArrayList<>(2);
        groups.add(lower);
        groups.add(upper);
        bh.consume(bdd.variableOrder().reorder(groups));
        bh.consume(state.functions());
    }

    /** The order reversed by {@code reorderTo}, then restored: what a prescribed shape costs to establish. */
    @Benchmark
    public static void reverseAndRestore(ReorderState state, Blackhole bh) {
        Bdd bdd = state.bdd();
        int variables = bdd.numberOfVariables();
        List<NatSet> blocks = new ArrayList<>(variables);
        for (int variable = variables - 1; variable >= 0; variable--) {
            blocks.add(NatSet.of(variable));
        }
        bdd.variableOrder().reorderTo(blocks);
        bh.consume(bdd.nodeCount());
        bdd.variableOrder().reorderToIdentity();
        bh.consume(state.functions());
    }
}
