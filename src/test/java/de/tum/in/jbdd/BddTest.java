/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2017-2023 Tobias Meggendorfer.
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.Is.is;

import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * A collection of simple tests for the BDD class.
 */
class BddTest {
    private static final BddConfiguration config =
            ImmutableBddConfiguration.builder().build();

    private static BitSet buildBitSet(String values) {
        BitSet bitSet = new BitSet(values.length());
        char[] characters = values.toCharArray();
        for (int i = 0; i < characters.length; i++) {
            assert characters[i] == '0' || characters[i] == '1';
            bitSet.set(i, characters[i] == '1');
        }
        return bitSet;
    }

    private static BitSet buildBitSet(int bits, int size) {
        BitSet bitSet = new BitSet(size);
        for (int i = 0; i < size; i++) {
            if ((bits & (1 << i)) != 0) {
                bitSet.set(i);
            }
        }
        return bitSet;
    }

    @Test
    void testDeadNodeCounter() {
        BddImpl bdd = new BddContextImpl(config).bdd();
        NodeTable table = bdd.table();
        int v1 = bdd.createVariable();
        int v2 = bdd.createVariable();

        int and = bdd.reference(bdd.and(v1, v2));
        assertThat(table.approximateDeadNodeCount(), is(0));
        bdd.dereference(and);
        assertThat(table.approximateDeadNodeCount(), is(1));
    }

    @Test
    void testGarbageCollection() {
        BddImpl bdd = new BddContextImpl(config).bdd();
        int v1 = bdd.createVariable();
        int v2 = bdd.createVariable();
        int v3 = bdd.createVariable();

        bdd.gc(); // make sure there is room for it
        int and = bdd.and(v3, v2);
        int or = bdd.reference(bdd.or(and, v1));
        assertThat(bdd.gc(), is(0));
        bdd.dereference(or);

        assertThat(bdd.gc(), is(2));
        bdd.gc(); // should free `and` and `or`
    }

    @Test
    void testDeMorgan() {
        BddImpl bdd = new BddContextImpl(config).bdd();
        int v1 = bdd.createVariable();
        int v2 = bdd.createVariable();
        int notV1 = bdd.reference(bdd.not(v1));
        int notV2 = bdd.reference(bdd.not(v2));

        int and = bdd.reference(bdd.and(v1, v2));
        int notV1OrNotV2 = bdd.reference(bdd.or(notV1, notV2));
        int notOfThat = bdd.reference(bdd.not(notV1OrNotV2));
        assertThat(and, is(notOfThat));
    }

    @Test
    void testXorIdentity() {
        BddImpl bdd = new BddContextImpl(config).bdd();
        int v1 = bdd.createVariable();
        int v2 = bdd.createVariable();
        int notV1 = bdd.not(v1);
        int notV2 = bdd.not(v2);

        int v1AndNotV2 = bdd.reference(bdd.and(v1, notV2));
        int v2AndNotV1 = bdd.reference(bdd.and(v2, notV1));
        int orOfThose = bdd.reference(bdd.or(v1AndNotV2, v2AndNotV1));
        bdd.dereference(v1AndNotV2);
        bdd.dereference(v2AndNotV1);
        int xor = bdd.reference(bdd.xor(v1, v2));
        assertThat(orOfThose, is(xor));
        bdd.dereference(orOfThose);
        bdd.dereference(xor);
    }

    @Test
    void testEquivalenceIdentity() {
        BddImpl bdd = new BddContextImpl(config).bdd();
        NodeTable table = bdd.table();
        int v1 = bdd.createVariable();
        int v2 = bdd.createVariable();

        int and = bdd.and(v1, v2);
        int orOfAndAndNorm = bdd.or(and, bdd.and(bdd.not(v1), bdd.not(v2)));
        int equivalence = bdd.equivalence(v1, v2);
        assertThat(orOfAndAndNorm, is(equivalence));
        assertThat(table.workStacksEmpty(), is(true));
    }

    @Test
    void testNodeCountBelow() {
        BddImpl bdd = new BddContextImpl(config).bdd();
        NodeTable table = bdd.table();
        int v1 = bdd.createVariable();
        int v2 = bdd.createVariable();
        int v3 = bdd.createVariable();
        int v4 = bdd.createVariable();

        assertThat(table.nodeCountBelow(bdd.nodeFor(bdd.trueFunction())), is(0));
        assertThat(table.nodeCountBelow(bdd.nodeFor(bdd.falseFunction())), is(0));
        assertThat(table.nodeCountBelow(bdd.nodeFor(v1)), is(1));
        assertThat(table.nodeCountBelow(bdd.nodeFor(bdd.not(v2))), is(1));
        assertThat(table.nodeCountBelow(bdd.nodeFor(bdd.and(v1, v2))), is(2));
        assertThat(table.nodeCountBelow(bdd.nodeFor(bdd.xor(v1, v2))), is(2));

        int xor12 = bdd.reference(bdd.xor(v1, v2));
        int xor34 = bdd.reference(bdd.xor(v3, v4));
        int xorOfXors = bdd.reference(bdd.xor(xor12, xor34));
        assertThat(table.nodeCountBelow(bdd.nodeFor(xor12)), is(2));
        assertThat(table.nodeCountBelow(bdd.nodeFor(xor34)), is(2));
        assertThat(table.nodeCountBelow(bdd.nodeFor(xorOfXors)), is(4));
        bdd.dereference(xor12);
        bdd.dereference(xor34);
        bdd.dereference(xorOfXors);
    }

    @Test
    void testCountSatisfyingAssignments() {
        BddImpl bdd = new BddContextImpl(config).bdd();
        int v1 = bdd.createVariable();
        int v2 = bdd.createVariable();
        bdd.createVariable();
        bdd.createVariable();

        int and = bdd.and(v1, v2);
        int equivalence = bdd.equivalence(v1, v2);

        assertThat(bdd.countSatisfyingAssignments(bdd.falseFunction()).longValueExact(), is(0L));
        assertThat(bdd.countSatisfyingAssignments(bdd.trueFunction()).longValueExact(), is(16L));
        assertThat(bdd.countSatisfyingAssignments(v1).longValueExact(), is(8L));
        assertThat(bdd.countSatisfyingAssignments(and).longValueExact(), is(4L));
        assertThat(bdd.countSatisfyingAssignments(equivalence).longValueExact(), is(8L));
    }

    @SuppressWarnings("ReuseOfLocalVariable")
    @Test
    void testCompose() {
        BddImpl bdd = new BddContextImpl(config).bdd();
        int v1 = bdd.createVariable();
        int nv1 = bdd.not(v1);
        int v2 = bdd.createVariable();
        int v3 = bdd.createVariable();

        int v2orv3 = bdd.or(v2, v3);
        int v1andv2 = bdd.and(v1, v2);
        int v1andv2orv3 = bdd.and(v1, bdd.reference(v2orv3));
        int nv1andv2orv3 = bdd.and(nv1, bdd.reference(v2orv3));

        int composition = bdd.reference(bdd.compose(v1andv2, new int[] {v1, v2, v3}));
        assertThat(v1andv2, is(composition));
        composition = bdd.compose(v1andv2, new int[] {v1, v2orv3, v3});
        assertThat(composition, is(v1andv2orv3));
        composition = bdd.compose(composition, new int[] {nv1});
        assertThat(composition, is(nv1andv2orv3));
        composition = bdd.compose(composition, new int[] {nv1});
        assertThat(composition, is(v1andv2orv3));
        composition = bdd.compose(v1andv2, new int[] {v2, v2});
        assertThat(composition, is(v2));
        bdd.dereference(composition);
    }

    @Test
    void testIfThenElse() {
        BddImpl bdd = new BddContextImpl(config).bdd();
        int v1 = bdd.createVariable();
        int v2 = bdd.createVariable();
        int v1andv2 = bdd.and(v1, v2);
        assertThat(bdd.ifThenElse(v1, v1, v1), is(v1));
        assertThat(bdd.ifThenElse(v1, v1andv2, v1andv2), is(v1andv2));
        assertThat(bdd.ifThenElse(v1, v1andv2, v2), is(v2));
        assertThat(bdd.ifThenElse(v1, v2, bdd.falseFunction()), is(bdd.and(v1, v2)));
        assertThat(bdd.ifThenElse(v1, bdd.trueFunction(), v2), is(bdd.or(v1, v2)));
        assertThat(bdd.ifThenElse(v1, bdd.not(v2), v2), is(bdd.xor(v1, v2)));
        assertThat(bdd.ifThenElse(v1, bdd.falseFunction(), bdd.trueFunction()), is(bdd.not(v1)));
        assertThat(bdd.ifThenElse(v1, v2, bdd.not(v2)), is(bdd.equivalence(v1, v2)));
    }

    @Test
    void testMember() {
        BddImpl bdd = new BddContextImpl(config).bdd();
        int v1 = bdd.createVariable();
        int v2 = bdd.createVariable();

        int p1 = bdd.reference(bdd.and(v1, v2));
        int p2 = bdd.reference(bdd.or(v1, v2));
        assertThat(p1, not(p2));
        int p3 = bdd.reference(bdd.and(bdd.not(v1), v2));
        bdd.not(v1);
        assertThat(p1, not(p3));
        int p4 = bdd.reference(bdd.and(bdd.not(v2), v1));
        assertThat(p1, not(p4));

        BitSet valuation = new BitSet(2);
        valuation.set(1);
        assertThat(bdd.evaluate(p1, valuation), is(false));
        assertThat(bdd.evaluate(p2, valuation), is(true));
        assertThat(bdd.evaluate(p3, valuation), is(true));
        assertThat(bdd.evaluate(p4, valuation), is(false));
    }

    @Test
    void testMinimalSolutionsForConstants() {
        BddImpl bdd = new BddContextImpl(config).bdd();

        List<BitSet> falseSolutions = Lists.newArrayList();
        bdd.forEachPath(bdd.falseFunction(), path -> falseSolutions.add(BitSets.copyOf(path.assignment)));
        assertThat(falseSolutions, is(Collections.emptyList()));

        List<BitSet> trueSolutions = Lists.newArrayList();
        bdd.forEachPath(bdd.trueFunction(), path -> trueSolutions.add(BitSets.copyOf(path.assignment)));
        assertThat(trueSolutions, is(Collections.singletonList(new BitSet(0))));
    }

    @Test
    void testSupport() {
        BddImpl bdd = new BddContextImpl(config).bdd();
        int v1 = bdd.createVariable();
        int v2 = bdd.createVariable();
        int v3 = bdd.createVariable();
        int v4 = bdd.createVariable();
        int v5 = bdd.createVariable();

        assertThat(bdd.support(v1), is(buildBitSet("100")));
        assertThat(bdd.support(v2), is(buildBitSet("010")));
        assertThat(bdd.support(v3), is(buildBitSet("001")));

        List<Integer> variables = Arrays.asList(v1, v2, v3, v4, v5);

        // The snippet below builds various BDDs by evaluating every possible subset of variables,
        // combining the variables in this subset with different operations and then checking that the
        // support of each combination equals the variables of the subset.
        List<Integer> subset = new ArrayList<>(variables.size());
        for (int i = 1; i < 1 << variables.size(); i++) {
            BitSet subsetBitSet = buildBitSet(i, variables.size());
            subsetBitSet.stream().forEach(setBit -> subset.add(variables.get(setBit)));

            Iterator<Integer> variableIterator = subset.iterator();
            int variable = variableIterator.next();
            int and = variable;
            int or = variable;
            int xor = variable;
            int imp = variable;
            int equiv = variable;
            while (variableIterator.hasNext()) {
                variable = variableIterator.next();
                and = bdd.and(and, variable);
                or = bdd.or(or, variable);
                xor = bdd.xor(xor, variable);
                imp = bdd.implication(imp, variable);
                equiv = bdd.equivalence(equiv, variable);
            }
            assertThat(bdd.support(and), is(subsetBitSet));
            assertThat(bdd.support(or), is(subsetBitSet));
            assertThat(bdd.support(xor), is(subsetBitSet));
            assertThat(bdd.support(imp), is(subsetBitSet));
            assertThat(bdd.support(equiv), is(subsetBitSet));
            subset.clear();
        }
    }

    @Test
    void testWorkStack() {
        BddImpl bdd = new BddContextImpl(config).bdd();
        NodeTable table = bdd.table();

        int v1 = bdd.createVariable();
        int v2 = bdd.createVariable();
        int temporaryNode = table.pushToWorkStack(bdd.and(v1, v2));
        bdd.gc();
        assertThat(bdd.isValidFunction(temporaryNode), is(true));
        table.popFromWorkStack();
        bdd.gc();
        assertThat(bdd.isValidFunction(temporaryNode), is(false));
    }

    @Test
    void testUniverseCursor() {
        BddImpl bdd = new BddContextImpl(config).bdd();
        bdd.createVariables(5);
        Set<BitSet> solutions = new HashSet<>();
        for (Cursor<BitSet> cursor = bdd.solutionCursor(bdd.trueFunction()); cursor.valid(); cursor.advance()) {
            solutions.add(BitSets.copyOf(cursor.current()));
        }
        assertThat(solutions.size(), is(1 << 5));
    }

    @Test
    void testConjunctionCursor() {
        BddImpl bdd = new BddContextImpl(config).bdd();
        bdd.createVariables(5);
        BitSet conjunction = new BitSet(5);
        conjunction.set(0, 5);
        bdd.solutionCursor(bdd.conjunction(conjunction));
        Set<BitSet> solutions = new HashSet<>();
        for (Cursor<BitSet> cursor = bdd.solutionCursor(bdd.trueFunction()); cursor.valid(); cursor.advance()) {
            solutions.add(BitSets.copyOf(cursor.current()));
        }
        assertThat(solutions.size(), is(1 << 5));
    }

    @Test
    void testConcurrentAccessChecked() throws InterruptedException {
        Bdd bdd = new BddContextImpl(config).bdd();
        bdd.createVariables(2);
        int node = bdd.reference(bdd.disjunction(0, 1));

        CountDownLatch holdingGuard = new CountDownLatch(1);
        CountDownLatch releaseGuard = new CountDownLatch(1);
        AtomicBoolean otherThreadRejected = new AtomicBoolean(false);

        Thread other = new Thread(() -> {
            //noinspection ErrorNotRethrown
            try {
                holdingGuard.await();
                bdd.implies(bdd.trueFunction(), bdd.falseFunction());
            } catch (AssertionError e) {
                otherThreadRejected.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                releaseGuard.countDown();
            }
        });
        other.start();

        bdd.forEachSolution(node, solution -> {
            holdingGuard.countDown();
            try {
                releaseGuard.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        other.join();

        assertThat(otherThreadRejected.get(), is(true));
    }

    @Test
    void testDeadNodeApproximation() {
        BddImpl bdd = new BddContextImpl(config).bdd();
        NodeTable table = bdd.table();

        int v1 = bdd.createVariable();
        int v2 = bdd.createVariable();
        int v3 = bdd.createVariable();
        int or = bdd.reference(bdd.implication(bdd.and(v1, v2), v3));
        int ite = bdd.reference(bdd.ifThenElse(v2, v3, bdd.trueFunction()));

        bdd.gc();
        bdd.dereference(ite);
        assertThat(table.approximateDeadNodeCount(), is(1));
        assertThat(bdd.isValidFunction(ite), is(true));
        int freed = bdd.gc();
        assertThat(freed, is(0));
        assertThat(table.approximateDeadNodeCount(), is(0));
        bdd.dereference(or);
        assertThat(table.approximateDeadNodeCount(), is(1));
        bdd.gc();
        assertThat(bdd.isValidFunction(ite), is(false));
        assertThat(table.referencedNodeCount(), is(3));
    }
}
