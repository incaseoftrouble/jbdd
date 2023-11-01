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
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A collection of simple tests for the BDD class.
 */
@SuppressWarnings("UseOfClone")
public class BddTest {
    private static final BddConfiguration config =
            ImmutableBddConfiguration.builder().build();

    private static BitSet buildBitSet(String values) {
        BitSet bitSet = new BitSet(values.length());
        char[] characters = values.toCharArray();
        for (int i = 0; i < characters.length; i++) {
            assert characters[i] == (int) '0' || characters[i] == (int) '1';
            bitSet.set(i, characters[i] == (int) '1');
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

    /**
     * This is a remainder of the original JDD.
     */
    @Test
    public void internalTest() {
        BddImpl bdd = new BddImpl(true, config);
        int v1 = bdd.createVariable();
        int v2 = bdd.createVariable();
        int v3 = bdd.createVariable();
        int v4 = bdd.createVariable();

        // check deadnodes counter
        int dum = bdd.reference(bdd.and(v3, v2));
        assertThat(bdd.approximateDeadNodeCount(), is(0));
        bdd.dereference(dum);
        assertThat(bdd.approximateDeadNodeCount(), is(1));

        // test garbage collection:
        int g1 = bdd.and(v3, v2);
        int g2 = bdd.reference(bdd.or(g1, v1));
        assertThat(bdd.forceGc(), is(0));
        bdd.dereference(g2);

        // bdd.show_table();
        assertThat(bdd.forceGc(), is(2));
        bdd.forceGc(); // Should free g1 and g2

        int nv1 = bdd.reference(bdd.not(v1));
        int nv2 = bdd.reference(bdd.not(v2));

        // and, or, not
        int n1 = bdd.reference(bdd.and(v1, v2));
        int orn12 = bdd.reference(bdd.or(nv1, nv2));
        int n2 = bdd.reference(bdd.not(orn12));
        assertThat(n1, is(n2));

        // XOR:
        int h1 = bdd.reference(bdd.and(v1, nv2));
        int h2 = bdd.reference(bdd.and(v2, nv1));
        int x1 = bdd.reference(bdd.or(h1, h2));
        bdd.dereference(h1);
        bdd.dereference(h2);
        int x2 = bdd.reference(bdd.xor(v1, v2));
        assertThat(x1, is(x2));
        bdd.dereference(x1);
        bdd.dereference(x2);

        // equivalence
        int b1 = bdd.or(n1, bdd.and(bdd.not(v1), bdd.not(v2)));
        int b2 = bdd.equivalence(v1, v2);
        assertThat(b1, is(b2));
        assertThat(bdd.isWorkStackEmpty(), is(true));

        // nodeCount
        assertThat(bdd.nodeCount(bdd.trueNode()), is(0));
        assertThat(bdd.nodeCount(bdd.falseNode()), is(0));
        assertThat(bdd.nodeCount(v1), is(1));
        assertThat(bdd.nodeCount(nv2), is(1));
        assertThat(bdd.nodeCount(bdd.and(v1, v2)), is(2));
        assertThat(bdd.nodeCount(bdd.xor(v1, v2)), is(3));

        // approximateNodeCount
        assertThat(bdd.approximateNodeCount(bdd.trueNode()), is(0));
        assertThat(bdd.approximateNodeCount(bdd.falseNode()), is(0));
        assertThat(bdd.approximateNodeCount(v1), is(1));
        assertThat(bdd.approximateNodeCount(nv2), is(1));
        assertThat(bdd.approximateNodeCount(bdd.and(v1, v2)), is(2));
        assertThat(bdd.approximateNodeCount(bdd.xor(v1, v2)), is(3));

        int qs1 = bdd.reference(bdd.xor(v1, v2));
        int qs2 = bdd.reference(bdd.xor(v3, v4));
        int qs3 = bdd.reference(bdd.xor(qs1, qs2));
        assertThat(bdd.approximateNodeCount(qs1), is(3));
        assertThat(bdd.approximateNodeCount(qs2), is(3));
        assertThat(bdd.approximateNodeCount(qs3), is(15));
        assertThat(bdd.nodeCount(qs3), is(7));
        bdd.dereference(qs1);
        bdd.dereference(qs2);
        bdd.dereference(qs3);

        // satcount
        assertThat(bdd.countSatisfyingAssignments(bdd.falseNode()).longValueExact(), is(0L));
        assertThat(bdd.countSatisfyingAssignments(bdd.trueNode()).longValueExact(), is(16L));
        assertThat(bdd.countSatisfyingAssignments(v1).longValueExact(), is(8L));
        assertThat(bdd.countSatisfyingAssignments(n1).longValueExact(), is(4L));
        assertThat(bdd.countSatisfyingAssignments(b1).longValueExact(), is(8L));
    }

    @SuppressWarnings("ReuseOfLocalVariable")
    @Test
    public void testCompose() {
        BddImpl bdd = new BddImpl(true, config);
        int v1 = bdd.createVariable();
        int nv1 = bdd.not(v1);
        int v2 = bdd.createVariable();
        int v3 = bdd.createVariable();

        int v2orv3 = bdd.or(v2, v3);
        int v1andv2 = bdd.and(v1, v2);
        int v1andv2orv3 = bdd.and(v1, bdd.reference(v2orv3));
        int nv1andv2orv3 = bdd.and(nv1, bdd.reference(v2orv3));

        int composition = bdd.compose(v1andv2, new int[] {v1, v2, v3});
        assertThat(v1andv2, is(composition));
        composition = bdd.compose(v1andv2, new int[] {v1, v2orv3, v3});
        assertThat(composition, is(v1andv2orv3));
        composition = bdd.compose(composition, new int[] {nv1});
        assertThat(composition, is(nv1andv2orv3));
        composition = bdd.compose(composition, new int[] {nv1});
        assertThat(composition, is(v1andv2orv3));
        composition = bdd.compose(v1andv2, new int[] {v2, v2});
        assertThat(composition, is(v2));
    }

    @Test
    public void testIfThenElse() {
        BddImpl bdd = new BddImpl(true, config);
        int v1 = bdd.createVariable();
        int v2 = bdd.createVariable();
        int v1andv2 = bdd.and(v1, v2);
        assertThat(bdd.ifThenElse(v1, v1, v1), is(v1));
        assertThat(bdd.ifThenElse(v1, v1andv2, v1andv2), is(v1andv2));
        assertThat(bdd.ifThenElse(v1, v1andv2, v2), is(v2));
        assertThat(bdd.ifThenElse(v1, v2, bdd.falseNode()), is(bdd.and(v1, v2)));
        assertThat(bdd.ifThenElse(v1, bdd.trueNode(), v2), is(bdd.or(v1, v2)));
        assertThat(bdd.ifThenElse(v1, bdd.not(v2), v2), is(bdd.xor(v1, v2)));
        assertThat(bdd.ifThenElse(v1, bdd.falseNode(), bdd.trueNode()), is(bdd.not(v1)));
        assertThat(bdd.ifThenElse(v1, v2, bdd.not(v2)), is(bdd.equivalence(v1, v2)));
    }

    @Test
    public void testMember() {
        BddImpl bdd = new BddImpl(true, config);
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

    @SuppressWarnings("UseOfClone")
    @Test
    public void testMinimalSolutionsForConstants() {
        BddImpl bdd = new BddImpl(true, config);

        List<BitSet> falseSolutions = Lists.newArrayList();
        bdd.forEachPath(bdd.falseNode(), set -> falseSolutions.add((BitSet) set.clone()));
        assertThat(falseSolutions, is(Collections.emptyList()));

        List<BitSet> trueSolutions = Lists.newArrayList();
        bdd.forEachPath(bdd.trueNode(), set -> trueSolutions.add((BitSet) set.clone()));
        assertThat(trueSolutions, is(Collections.singletonList(new BitSet(0))));
    }

    @Test
    public void testSupport() {
        BddImpl bdd = new BddImpl(true, config);
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
    public void testWorkStack() {
        BddImpl bdd = new BddImpl(true, config);
        int v1 = bdd.createVariable();
        int v2 = bdd.createVariable();
        int temporaryNode = bdd.pushToWorkStack(bdd.and(v1, v2));
        bdd.forceGc();
        assertThat(bdd.isNodeValidOrLeaf(temporaryNode), is(true));
        bdd.popWorkStack();
        bdd.forceGc();
        assertThat(bdd.isNodeValidOrLeaf(temporaryNode), is(false));
    }

    @Test
    public void testUniverseIterator() {
        BddImpl bdd = new BddImpl(true, config);
        bdd.createVariables(5);
        Set<BitSet> solutions = new HashSet<>();
        bdd.solutionIterator(bdd.trueNode()).forEachRemaining(val -> solutions.add((BitSet) val.clone()));
        assertThat(solutions.size(), is(1 << 5));
    }

    @Test
    public void testConjunctionIterator() {
        BddImpl bdd = new BddImpl(true, config);
        bdd.createVariables(5);
        BitSet conjunction = new BitSet(5);
        conjunction.set(0, 5);
        bdd.solutionIterator(bdd.conjunction(conjunction));
        Set<BitSet> solutions = new HashSet<>();
        bdd.solutionIterator(bdd.trueNode()).forEachRemaining(val -> solutions.add((BitSet) val.clone()));
        assertThat(solutions.size(), is(1 << 5));
    }

    @Test
    public void testConcurrentAccessChecked() {
        Bdd bdd = new CheckedBdd(new BddImpl(false, config));
        bdd.createVariables(2);
        int node = bdd.reference(bdd.disjunction(0, 1));
        assertThrows(
                IllegalStateException.class,
                () -> bdd.forEachSolution(node, solution -> bdd.implies(bdd.trueNode(), bdd.falseNode())));
    }

    @Test
    public void testDeadNodeApproximation() {
        BddImpl bdd = new BddImpl(true, config);
        int v1 = bdd.createVariable();
        int v2 = bdd.createVariable();
        int v3 = bdd.createVariable();
        int or = bdd.reference(bdd.implication(bdd.and(v1, v2), v3));
        int ite = bdd.reference(bdd.ifThenElse(v2, v3, bdd.trueNode()));

        bdd.forceGc();
        bdd.dereference(ite);
        assertThat(bdd.approximateDeadNodeCount(), is(1));
        assertThat(bdd.isNodeValidOrLeaf(ite), is(true));
        int freed = bdd.forceGc();
        assertThat(freed, is(0));
        assertThat(bdd.approximateDeadNodeCount(), is(0));
        bdd.dereference(or);
        assertThat(bdd.approximateDeadNodeCount(), is(1));
        bdd.forceGc();
        assertThat(bdd.isNodeValidOrLeaf(ite), is(false));
        assertThat(bdd.referencedNodeCount(), is(6));
    }
}
