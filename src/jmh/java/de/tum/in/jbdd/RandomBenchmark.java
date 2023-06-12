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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

public class RandomBenchmark extends BaseBddBenchmark {
    @SuppressWarnings("StaticCollection")
    private static final List<BddOperation> OPERATION_LIST = List.of(
            n -> n.add(n.bdd.createVariable()),
            n -> n.add(n.bdd.not(n.get())),
            n -> n.add(n.bdd.and(n.get(), n.get())),
            n -> n.add(n.bdd.or(n.get(), n.get())),
            n -> n.add(n.bdd.notAnd(n.get(), n.get())),
            n -> n.add(n.bdd.xor(n.get(), n.get())),
            n -> n.add(n.bdd.implication(n.get(), n.get())),
            n -> n.add(n.bdd.equivalence(n.get(), n.get())),
            n -> n.add(n.bdd.ifThenElse(n.get(), n.get(), n.get())),
            n -> {
                int variables = n.bdd.numberOfVariables();
                BitSet mask = new BitSet(variables);
                BitSet values = new BitSet(variables);
                for (int i = 0; i < Math.min(variables, 10); i++) {
                    if (n.random.nextBoolean()) {
                        mask.set(i);
                        values.set(i, n.random.nextBoolean());
                    }
                }
                n.add(n.bdd.restrict(n.get(), mask, values));
            },
            n -> {
                int[] compose = new int[Math.min(n.bdd.numberOfVariables(), 10)];
                Arrays.setAll(compose, i -> n.get());
                n.add(n.bdd.compose(n.get(), compose));
            },
            n -> {
                int variables = n.bdd.numberOfVariables();
                BitSet mask = new BitSet(variables);
                for (int i = 0; i < Math.min(variables, 10); i++) {
                    if (n.random.nextBoolean()) {
                        mask.set(i);
                    }
                }
                n.add(n.bdd.exists(n.get(), mask));
            },
            n -> n.bdd.support(n.get()),
            n -> n.bdd.implies(n.get(), n.get()),
            n -> n.bdd.countSatisfyingAssignments(n.get()),
            n -> {
                int node = n.get();
                if (n.bdd.support(node).cardinality() < 8) {
                    n.bdd.forEachPath(node, path -> {});
                }
            });

    @FunctionalInterface
    private interface BddOperation {
        void run(BddNodes ops);
    }

    public static class BddNodes {
        public final Bdd bdd;
        public final Random random;
        private final Set<Integer> nodeSet = new HashSet<>();
        private final List<Integer> nodes = new ArrayList<>();
        private int counter = 0;

        public BddNodes(Bdd bdd, Random random) {
            this.bdd = bdd;
            this.random = random;
        }

        public void createVariables(int count) {
            for (int i = 0; i < count; i++) {
                int variable = bdd.createVariable();
                nodes.add(variable);
                nodes.add(bdd.not(variable));
            }
        }

        public void add(int node) {
            if (counter > 6) {
                counter = 0;
                if (nodeSet.add(node)) {
                    bdd.reference(node);
                    nodes.add(node);

                    int size = nodes.size();
                    if (size > 200) {
                        Collections.shuffle(nodes, random);
                        int keep = size - 10;
                        nodes.subList(0, keep).forEach(bdd::dereference);
                        nodeSet.clear();
                        nodeSet.addAll(nodes.subList(keep, size));
                        nodes.clear();
                        nodes.addAll(nodeSet);
                    }
                }
            }
            counter += 1;
        }

        public int get() {
            return nodes.get(random.nextInt(nodes.size()));
        }
    }

    private static List<BddOperation> makeOperations(int count, Random random) {
        List<BddOperation> bddOperations = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            bddOperations.add(OPERATION_LIST.get(random.nextInt(OPERATION_LIST.size())));
        }
        return bddOperations;
    }

    @State(Scope.Benchmark)
    public static class RandomState extends BddState {
        private static final int SEED = 1234;
        private static final int OPERATION_COUNT = 20_000;

        public BddNodes nodes;
        public List<BddOperation> bddOperations;

        @Setup(Level.Trial)
        public void setUpOperations() {
            bddOperations = makeOperations(OPERATION_COUNT, new Random(SEED));
        }

        @Override
        @Setup(Level.Iteration)
        public void setUpBdd() {
            super.setUpBdd();
            nodes = new BddNodes(bdd(), new Random(SEED));
            nodes.createVariables(64);
        }
    }

    @Benchmark
    public static void benchmarkRandom(RandomState state) {
        for (BddOperation operation : state.bddOperations) {
            operation.run(state.nodes);
        }
    }
}
