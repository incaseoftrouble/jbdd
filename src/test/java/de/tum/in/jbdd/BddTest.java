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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.common.collect.Lists;
import de.tum.in.jbdd.collections.Cursor;
import de.tum.in.jbdd.collections.MutableNatSet;
import de.tum.in.jbdd.collections.NatSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
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

    private static NatSet buildBitSet(String values) {
        MutableNatSet bitSet = MutableNatSet.dense(values.length());
        char[] characters = values.toCharArray();
        for (int i = 0; i < characters.length; i++) {
            assert characters[i] == '0' || characters[i] == '1';
            bitSet.set(i, characters[i] == '1');
        }
        return bitSet;
    }

    private static NatSet buildBitSet(int bits, int size) {
        MutableNatSet bitSet = MutableNatSet.dense(size);
        for (int i = 0; i < size; i++) {
            if ((bits & (1 << i)) != 0) {
                bitSet.set(i);
            }
        }
        return bitSet;
    }

    @Test
    void testDeadNodeCounter() {
        BddImpl bdd = new DdContextImpl(config).bdd();
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
        BddImpl bdd = new DdContextImpl(config).bdd();
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
        BddImpl bdd = new DdContextImpl(config).bdd();
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
        BddImpl bdd = new DdContextImpl(config).bdd();
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
        BddImpl bdd = new DdContextImpl(config).bdd();
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
        BddImpl bdd = new DdContextImpl(config).bdd();
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
        BddImpl bdd = new DdContextImpl(config).bdd();
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

    @Test
    void testSatisfyingFractionDoesNotDependOnTheVariableCount() {
        BddImpl bdd = new DdContextImpl(config).bdd();
        int v1 = bdd.createVariable();
        int v2 = bdd.createVariable();
        int and = bdd.reference(bdd.and(v1, v2));
        int equivalence = bdd.reference(bdd.equivalence(v1, v2));

        assertThat(bdd.satisfyingFraction(bdd.falseFunction()), is(0.0d));
        assertThat(bdd.satisfyingFraction(bdd.trueFunction()), is(1.0d));
        assertThat(bdd.satisfyingFraction(v1), is(0.5d));
        assertThat(bdd.satisfyingFraction(and), is(0.25d));
        assertThat(bdd.satisfyingFraction(bdd.not(and)), is(0.75d));
        assertThat(bdd.satisfyingFraction(equivalence), is(0.5d));

        bdd.createVariables(5);
        assertThat(bdd.satisfyingFraction(and), is(0.25d));
        bdd.gc();
        assertThat(bdd.satisfyingFraction(and), is(0.25d));
        List<NatSet> reversed = new ArrayList<>();
        for (int variable = bdd.numberOfVariables() - 1; variable >= 0; variable--) {
            MutableNatSet block = MutableNatSet.create();
            block.set(variable);
            reversed.add(block);
        }
        bdd.variableOrder().reorderTo(reversed);
        assertThat(bdd.satisfyingFraction(and), is(0.25d));
        assertThat(bdd.satisfyingFraction(bdd.not(equivalence)), is(0.5d));
    }

    @Test
    void testInfluencesAreTheFlipsOfEachVariableUnderAnyOrder() {
        BddImpl bdd = new DdContextImpl(config).bdd();
        int a = bdd.createVariable();
        int b = bdd.createVariable();
        int c = bdd.createVariable();
        bdd.createVariable();
        // a | (b & c): a decides wherever b & c is false, b where a is false and c true, c alike; d never.
        int function = bdd.reference(bdd.or(a, bdd.and(b, c)));
        // a ^ b is not unate: flipping either always flips it, although its cofactors have equal fractions.
        int parity = bdd.reference(bdd.xor(a, b));
        double[] expected = {0.75d, 0.25d, 0.25d, 0.0d};
        double[] parityExpected = {1.0d, 1.0d, 0.0d, 0.0d};

        assertThat(bdd.influences(function), is(expected));
        assertThat(bdd.influences(bdd.not(function)), is(expected));
        assertThat(bdd.influences(parity), is(parityExpected));
        assertThat(bdd.influences(bdd.trueFunction()), is(new double[4]));

        List<NatSet> reversed = new ArrayList<>();
        for (int variable = bdd.numberOfVariables() - 1; variable >= 0; variable--) {
            MutableNatSet block = MutableNatSet.create();
            block.set(variable);
            reversed.add(block);
        }
        bdd.variableOrder().reorderTo(reversed);
        assertThat(bdd.influences(function), is(expected));
        assertThat(bdd.influences(parity), is(parityExpected));
        bdd.createVariables(2);
        assertThat(bdd.influences(function), is(new double[] {0.75d, 0.25d, 0.25d, 0.0d, 0.0d, 0.0d}));
    }

    @Test
    void testInfluencesKeepSmallValuesPrecise() {
        // The conjunction of 200 negated literals: each variable flips it only on the one assignment of the others that
        // satisfies the rest, 2^-199 of all - a 1 - x anywhere would give 0.
        BddImpl bdd = new DdContextImpl(config).bdd();
        int variables = 200;
        int[] nodes = bdd.createVariables(variables);
        int disjunction = bdd.falseFunction();
        for (int node : nodes) {
            disjunction = bdd.updateWith(bdd.or(disjunction, node), disjunction);
        }
        double[] influences = bdd.influences(bdd.not(disjunction));
        for (int variable = 0; variable < variables; variable++) {
            assertThat(influences[variable], is(Math.scalb(1.0d, 1 - variables)));
        }
    }

    @Test
    void testSatisfyingFractionKeepsSmallComplementsPrecise() {
        // The conjunction of negated literals is stored as the complement of the disjunction, whose fraction is
        // 1 - 2^-200, i.e. 1.0 as a double: deriving the conjunction's as 1 - x would give 0.
        BddImpl bdd = new DdContextImpl(config).bdd();
        int variables = 200;
        int[] nodes = bdd.createVariables(variables);
        int disjunction = bdd.falseFunction();
        for (int node : nodes) {
            disjunction = bdd.updateWith(bdd.or(disjunction, node), disjunction);
        }
        int conjunction = bdd.not(disjunction);
        assertThat(bdd.isPositive(conjunction), is(false));

        assertThat(bdd.satisfyingFraction(conjunction), is(Math.scalb(1.0d, -variables)));
        assertThat(bdd.satisfyingFraction(disjunction), is(1.0d));
        assertThat(bdd.satisfyingFraction(bdd.and(bdd.not(nodes[0]), bdd.not(nodes[1]))), is(0.25d));
    }

    @Test
    void testSatisfyingFractionInCountsATinyDomainExactly() {
        // A cube over 1100 variables is 2^-1100 of all assignments, below any double: the probabilities within it
        // are still plain.
        BddImpl bdd = new DdContextImpl(config).bdd();
        int[] nodes = bdd.createVariables(1102);
        int domain = bdd.trueFunction();
        for (int i = 2; i < nodes.length; i++) {
            domain = bdd.updateWith(bdd.and(domain, nodes[i]), domain);
        }
        assertThat(bdd.satisfyingFraction(domain), is(0.0d));

        int function = bdd.reference(bdd.and(nodes[0], nodes[1]));
        assertThat(bdd.satisfyingFractionIn(function, domain), is(0.25d));
        assertThat(bdd.satisfyingFractionIn(bdd.not(function), domain), is(0.75d));
        assertThat(bdd.satisfyingFractionIn(nodes[5], domain), is(1.0d));
        assertThat(bdd.satisfyingFractionIn(bdd.not(nodes[5]), domain), is(0.0d));
        assertThrowsExactly(
                IllegalArgumentException.class, () -> bdd.satisfyingFractionIn(function, bdd.falseFunction()));
    }

    @Test
    void testSatisfyingFractionInATinyDomain() {
        // A domain of 3 * 2^-1502 of all assignments, far below what a double holds: x_0 .. x_1499 all true, and
        // one of x_1500, x_1501. Deciding the pinned variables at either end of the order.
        for (boolean pinnedFirst : new boolean[] {true, false}) {
            BddImpl bdd = new DdContextImpl(config).bdd();
            int[] variables = bdd.createVariables(1502);
            int offset = pinnedFirst ? 0 : 2;
            int pinned = bdd.reference(bdd.trueFunction());
            for (int index = 0; index < 1500; index++) {
                pinned = bdd.updateWith(bdd.and(pinned, variables[offset + index]), pinned);
            }
            int first = variables[pinnedFirst ? 1500 : 0];
            int second = variables[pinnedFirst ? 1501 : 1];
            int domain = bdd.reference(bdd.and(pinned, bdd.reference(bdd.or(first, second))));
            int both = bdd.reference(bdd.and(first, second));

            assertThat(bdd.satisfyingFraction(domain), is(0.0d));
            assertThat(bdd.satisfyingFractionIn(both, domain), is(1.0d / 3.0d));
            assertThat(bdd.satisfyingFractionIn(bdd.not(both), domain), is(2.0d / 3.0d));
            assertThat(bdd.satisfyingFractionIn(first, domain), is(2.0d / 3.0d));
            assertThat(bdd.satisfyingFractionIn(bdd.trueFunction(), domain), is(1.0d));
            assertThat(bdd.satisfyingFractionIn(domain, domain), is(1.0d));
            assertThat(bdd.satisfyingFractionIn(bdd.not(domain), domain), is(0.0d));
            assertThat(bdd.satisfyingFractionIn(bdd.falseFunction(), domain), is(0.0d));
        }
    }

    @SuppressWarnings("ReuseOfLocalVariable")
    @Test
    void testCompose() {
        BddImpl bdd = new DdContextImpl(config).bdd();
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

    /**
     * Replacements depending on variables the function tests above the replaced ones: {@code f = (a_0 | ... |
     * a_n-1) | (o1 & o2)} with {@code o1 -> OR_i (a_i xor x_i)}, {@code o2 -> OR_i (a_i xor y_i)}, the a above
     * o1, o2 above the x and y. The result is {@code (OR a) | ((OR x) & (OR y))}, linear; composing o1 & o2 without
     * the path (where every a is false) builds the conjunction of the replacements, exponential in this order.
     */
    @Test
    void testComposeCarriesThePathToTheReplacements() {
        int n = 14;
        BddImpl bdd = new DdContextImpl(config).bdd();
        int[] a = bdd.createVariables(n);
        int o1 = bdd.createVariable();
        int o2 = bdd.createVariable();
        int[] x = bdd.createVariables(n);
        int[] y = bdd.createVariables(n);

        int anyA = bdd.reference(bdd.falseFunction());
        int anyX = bdd.reference(bdd.falseFunction());
        int anyY = bdd.reference(bdd.falseFunction());
        int g1 = bdd.reference(bdd.falseFunction());
        int g2 = bdd.reference(bdd.falseFunction());
        for (int i = 0; i < n; i++) {
            anyA = bdd.updateWith(bdd.or(anyA, a[i]), anyA);
            anyX = bdd.updateWith(bdd.or(anyX, x[i]), anyX);
            anyY = bdd.updateWith(bdd.or(anyY, y[i]), anyY);
            g1 = bdd.updateWith(bdd.or(g1, bdd.xor(a[i], x[i])), g1);
            g2 = bdd.updateWith(bdd.or(g2, bdd.xor(a[i], y[i])), g2);
        }
        int f = bdd.reference(bdd.or(anyA, bdd.and(o1, o2)));
        int expected = bdd.reference(bdd.or(anyA, bdd.and(anyX, anyY)));

        int[] mapping = new int[3 * n + 2];
        Arrays.fill(mapping, bdd.placeholder());
        mapping[bdd.decisionVariable(o1)] = g1;
        mapping[bdd.decisionVariable(o2)] = g2;

        long before = bdd.table().createdNodeCount();
        assertThat(bdd.compose(f, mapping), is(expected));
        assertThat(bdd.table().createdNodeCount() - before < 100L * n, is(true));

        RegisteredOperation.Unary registered = bdd.registerCompose(mapping);
        before = bdd.table().createdNodeCount();
        assertThat(registered.applyAsInt(f), is(expected));
        assertThat(bdd.table().createdNodeCount() - before < 100L * n, is(true));
        registered.release();
    }

    @Test
    void testComposeAlongThePathDropsAMappingWhoseReplacementWasRecycled() {
        // As RegressionTests#testComposeCacheDropsAMappingWhoseReplacementWasRecycled, with a replacement reading
        // v2, which the function decides above v7: the composition restricts the replacement along the path, and
        // entries keyed on it must not answer an equal-looking mapping whose replacement id now names another
        // function.
        BddImpl bdd = new DdContextImpl(config).bdd();
        int[] v = bdd.createVariables(8);
        int function =
                bdd.reference(bdd.and(new int[] {bdd.xor(v[0], v[3]), bdd.xor(v[4], v[5]), bdd.xor(v[2], v[6]), v[7]}));
        int replacement = bdd.reference(bdd.and(v[1], v[2]));
        int[] mapping = new int[8];
        Arrays.fill(mapping, bdd.placeholder());
        mapping[7] = replacement;
        int stale = bdd.reference(bdd.compose(function, mapping.clone()));

        bdd.dereference(replacement);
        bdd.gc();
        assertThat(bdd.isValidFunction(replacement), is(false));
        for (long mask = 1; mask < 1L << 8 && !bdd.isValidFunction(replacement); mask++) {
            bdd.reference(bdd.of(Cube.negative(NatSetFixtures.valueOf(mask))));
        }
        assumeTrue(bdd.isValidFunction(replacement), "The freed id was never handed out again");

        int high = bdd.reference(bdd.restrict(function, Cube.literal(7, true)));
        int low = bdd.reference(bdd.restrict(function, Cube.literal(7, false)));
        int expected = bdd.reference(bdd.ifThenElse(replacement, high, low));
        assumeTrue(expected != stale);
        assertThat(bdd.compose(function, mapping.clone()), is(expected));
    }

    @Test
    void testRegisteredExistsMatchesTheDirectCall() {
        BddImpl bdd = new DdContextImpl(config).bdd();
        int[] v = bdd.createVariables(3);
        int f = bdd.reference(bdd.and(bdd.or(v[0], v[1]), bdd.xor(v[1], v[2])));

        MutableNatSet quantified = MutableNatSet.copyOf(buildBitSet("010"));
        RegisteredOperation.Unary exists = bdd.registerExists(quantified);
        assertThat(exists.applyAsInt(f), is(bdd.exists(f, quantified)));
        // Invoked twice, the private cache is now warm - the answer must not change.
        assertThat(exists.applyAsInt(f), is(bdd.exists(f, quantified)));
        // The set is read at registration, so changing it afterwards must not be noticed.
        quantified.set(0);
        assertThat(exists.applyAsInt(f), is(bdd.exists(f, buildBitSet("010"))));

        assertThat(bdd.registerExists(MutableNatSet.create()).applyAsInt(f), is(f));
        assertThat(bdd.registerExists(buildBitSet("111")).applyAsInt(f), is(bdd.trueFunction()));
        assertThat(bdd.check(), is(true));
    }

    @Test
    void testRegisteredAndExistsMatchesTheDirectCall() {
        BddImpl bdd = new DdContextImpl(config).bdd();
        int[] v = bdd.createVariables(3);
        int f = bdd.reference(bdd.or(v[0], v[1]));
        int g = bdd.reference(bdd.xor(v[1], v[2]));

        MutableNatSet quantified = MutableNatSet.copyOf(buildBitSet("010"));
        RegisteredOperation.Binary andExists = bdd.registerAndExists(quantified);
        int expected = bdd.andExists(f, g, quantified);
        assertThat(andExists.applyAsInt(f, g), is(expected));
        // Invoked twice, the private cache is now warm - the answer must not change.
        assertThat(andExists.applyAsInt(g, f), is(expected));
        // The set is read at registration, so changing it afterwards must not be noticed.
        quantified.set(0);
        assertThat(andExists.applyAsInt(f, g), is(bdd.andExists(f, g, buildBitSet("010"))));

        assertThat(bdd.registerAndExists(MutableNatSet.create()).applyAsInt(f, g), is(bdd.and(f, g)));
        assertThat(bdd.registerAndExists(buildBitSet("111")).applyAsInt(f, g), is(bdd.trueFunction()));
        int contradiction = bdd.reference(bdd.and(v[1], v[2]));
        assertThat(bdd.andExists(contradiction, g, buildBitSet("111")), is(bdd.falseFunction()));
        assertThat(bdd.andExists(contradiction, g, buildBitSet("100")), is(bdd.falseFunction()));
        assertThat(bdd.check(), is(true));
    }

    @Test
    void testAndExistsComputesPreimagesUnderCollections() {
        // The minimal table collects and grows inside the recursions, which is what the work stack has to survive.
        BddImpl bdd = new DdContextImpl(
                        ImmutableBddConfiguration.builder().initialSize(1).build())
                .bdd();
        int bits = 10;
        // Current-state x_i is variable 2i, next-state x'_i is 2i + 1.
        bdd.createVariables(2 * bits);
        MutableNatSet next = MutableNatSet.create();
        for (int i = 0; i < bits; i++) {
            next.set(2 * i + 1);
        }

        // A twisted shift register: x'_i <-> x_{i-1}, x'_0 <-> !x_{n-1}.
        int relation = bdd.reference(bdd.trueFunction());
        for (int i = 0; i < bits; i++) {
            int source = i == 0 ? bdd.not(bdd.variableFunction(2 * bits - 2)) : bdd.variableFunction(2 * i - 2);
            int step = bdd.reference(bdd.equivalence(bdd.variableFunction(2 * i + 1), source));
            relation = bdd.consume(bdd.and(relation, step), relation, step);
        }

        RegisteredOperation.Binary preimage = bdd.registerAndExists(next);
        Random random = new Random(0L);
        for (int round = 0; round < 60; round++) {
            int target = bdd.reference(bdd.falseFunction());
            for (int cube = 0; cube < 1 + random.nextInt(6); cube++) {
                int conjunction = bdd.reference(bdd.trueFunction());
                for (int i = 0; i < bits; i++) {
                    if (random.nextInt(3) == 0) {
                        int literal = bdd.variableFunction(2 * i + 1);
                        int signed = random.nextBoolean() ? literal : bdd.not(literal);
                        conjunction = bdd.updateWith(bdd.and(conjunction, signed), conjunction);
                    }
                }
                target = bdd.consume(bdd.or(target, conjunction), target, conjunction);
            }

            MutableNatSet quantified = MutableNatSet.create();
            if (round % 3 == 0) {
                quantified.or(next);
            } else {
                for (int variable = 0; variable < 2 * bits; variable++) {
                    if (random.nextInt(3) == 0) {
                        quantified.set(variable);
                    }
                }
            }
            int product = bdd.reference(bdd.and(relation, target));
            int expected = bdd.reference(bdd.exists(product, quantified));
            bdd.dereference(product);

            assertEquals(expected, bdd.andExists(relation, target, quantified));
            // A plain exists over another set in between drops the shared exists entries.
            bdd.exists(target, buildBitSet(random.nextInt(1 << 8), 8));
            assertEquals(expected, bdd.andExists(target, relation, quantified));
            if (round % 3 == 0) {
                assertEquals(expected, preimage.applyAsInt(relation, target));
            }
            int disjunction = bdd.reference(bdd.or(relation, target));
            int expectedForall = bdd.reference(bdd.forall(disjunction, quantified));
            bdd.dereference(disjunction);
            assertEquals(expectedForall, bdd.orForall(target, relation, quantified));

            bdd.dereference(target, expected, expectedForall);
            if (round % 5 == 0) {
                bdd.gc();
            }
        }
        assertThat(bdd.check(), is(true));
    }

    @Test
    void testIfThenElse() {
        BddImpl bdd = new DdContextImpl(config).bdd();
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
        BddImpl bdd = new DdContextImpl(config).bdd();
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

        MutableNatSet valuation = MutableNatSet.dense(2);
        valuation.set(1);
        assertThat(bdd.evaluate(p1, valuation), is(false));
        assertThat(bdd.evaluate(p2, valuation), is(true));
        assertThat(bdd.evaluate(p3, valuation), is(true));
        assertThat(bdd.evaluate(p4, valuation), is(false));
    }

    @Test
    void testMinimalSolutionsForConstants() {
        BddImpl bdd = new DdContextImpl(config).bdd();

        List<NatSet> falseSolutions = Lists.newArrayList();
        bdd.forEachPath(bdd.falseFunction(), path -> falseSolutions.add(NatSetFixtures.copyOf(path.assignment())));
        assertThat(falseSolutions, is(Collections.emptyList()));

        List<NatSet> trueSolutions = Lists.newArrayList();
        bdd.forEachPath(bdd.trueFunction(), path -> trueSolutions.add(NatSetFixtures.copyOf(path.assignment())));
        assertThat(trueSolutions, is(Collections.singletonList(MutableNatSet.dense(0))));
    }

    @Test
    void testSupport() {
        BddImpl bdd = new DdContextImpl(config).bdd();
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
            NatSet subsetBitSet = buildBitSet(i, variables.size());
            subsetBitSet.intStream().forEach(setBit -> subset.add(variables.get(setBit)));

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
        BddImpl bdd = new DdContextImpl(config).bdd();
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
        BddImpl bdd = new DdContextImpl(config).bdd();
        bdd.createVariables(5);
        Set<NatSet> solutions = new HashSet<>();
        for (Cursor<NatSet> cursor = bdd.solutionCursor(bdd.trueFunction()); cursor.valid(); cursor.advance()) {
            solutions.add(NatSetFixtures.copyOf(cursor.current()));
        }
        assertThat(solutions.size(), is(1 << 5));
    }

    @Test
    void testConjunctionCursor() {
        BddImpl bdd = new DdContextImpl(config).bdd();
        bdd.createVariables(5);
        MutableNatSet conjunction = MutableNatSet.dense(5);
        conjunction.set(0, 5);
        bdd.solutionCursor(bdd.conjunction(conjunction));
        Set<NatSet> solutions = new HashSet<>();
        for (Cursor<NatSet> cursor = bdd.solutionCursor(bdd.trueFunction()); cursor.valid(); cursor.advance()) {
            solutions.add(NatSetFixtures.copyOf(cursor.current()));
        }
        assertThat(solutions.size(), is(1 << 5));
    }

    @Test
    void testConcurrentAccessChecked() throws InterruptedException {
        Bdd bdd = new DdContextImpl(config).bdd();
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
        BddImpl bdd = new DdContextImpl(config).bdd();
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

    /**
     * The n-ary conjunction against a pairwise fold on random operand sets, with duplicates, complements and
     * constants.
     */
    @Test
    void testNaryConjunctionAgreesWithTheFold() {
        BddImpl bdd = new DdContextImpl(config).bdd();
        int[] v = bdd.createVariables(10);
        Random random = new Random(5);
        List<Integer> pool = new ArrayList<>();
        for (int variable : v) {
            pool.add(variable);
            pool.add(bdd.not(variable));
        }
        for (int i = 0; i < 40; i++) {
            int left = pool.get(random.nextInt(pool.size()));
            int right = pool.get(random.nextInt(pool.size()));
            int kind = random.nextInt(3);
            int function = kind == 0 ? bdd.and(left, right) : kind == 1 ? bdd.or(left, right) : bdd.xor(left, right);
            pool.add(bdd.reference(function));
            pool.add(bdd.reference(bdd.not(function)));
        }
        pool.add(bdd.trueFunction());
        // Operands with distinct top variables, one per variable, so that both the n-ary and the pairwise path run.
        List<Integer> distinct = new ArrayList<>();
        for (int i = 0; i < v.length; i++) {
            int below = i + 1 < v.length
                    ? pool.get(2 * (i + 1) + random.nextInt(pool.size() - 2 * (i + 1)))
                    : bdd.trueFunction();
            distinct.add(bdd.reference(bdd.ifThenElse(v[i], below, bdd.not(below))));
        }
        for (int round = 0; round < 200; round++) {
            int count = 1 + random.nextInt(12);
            int[] operands = new int[count];
            if (round % 2 == 0) {
                for (int i = 0; i < count; i++) {
                    operands[i] = pool.get(random.nextInt(pool.size()));
                }
            } else {
                for (int i = 0; i < count; i++) {
                    int function = distinct.get(random.nextInt(distinct.size()));
                    operands[i] = random.nextBoolean() ? function : bdd.not(function);
                }
            }
            if (random.nextInt(10) == 0) {
                operands[random.nextInt(count)] = bdd.falseFunction();
            }
            int fold = bdd.trueFunction();
            for (int operand : operands) {
                fold = bdd.updateWith(bdd.and(fold, operand), fold);
            }
            int nary = bdd.and(operands);
            assertEquals(fold, nary, "conjunction of " + Arrays.toString(operands));
            assertEquals(bdd.not(bdd.and(complemented(operands))), bdd.or(operands));
            bdd.dereference(fold);
        }
    }

    private static int[] complemented(int[] functions) {
        int[] result = functions.clone();
        for (int i = 0; i < result.length; i++) {
            result[i] = -result[i];
        }
        return result;
    }
}
