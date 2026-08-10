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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * A collection of simple smoke tests for {@link MtBddImpl}, in the same spirit as {@link BddTest} - not
 * a full theory suite (that will follow separately, mirroring {@code BddTheories} once the interface is
 * complete), just basic cross-checks of the pieces implemented so far.
 */
class MtBddTest {
    private static final BddConfiguration config =
            ImmutableBddConfiguration.builder().build();
    private static final int[] EMPTY_INTS = new int[0];
    private static final boolean[] EMPTY_BOOL = new boolean[0];

    private static BitSet fullSupport(int numVars) {
        BitSet support = new BitSet(numVars);
        support.set(0, numVars);
        return support;
    }

    @Test
    void testOfConstantEvaluatesToValueEverywhere() {
        BddImpl bdd = new BddImpl(config);
        int numVars = 3;
        bdd.createVariables(numVars);
        MtBddImpl mt = bdd.mtbdd();

        int f = mt.of(42);
        assertTrue(mt.isConstant(f));
        for (int mask = 0; mask < (1 << numVars); mask++) {
            assertEquals(42, mt.evaluate(f, maskToAssignment(mask, numVars)));
        }
    }

    @Test
    void testOfCollapsesEqualChildren() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariable();
        MtBddImpl mt = bdd.mtbdd();

        int leaf = mt.of(5);
        assertEquals(leaf, mt.of(0, leaf, leaf));
    }

    @Test
    void testOfChildOrderMatchesTrueHighFalseLow() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariable();
        MtBddImpl mt = bdd.mtbdd();

        int trueChild = mt.of(1);
        int falseChild = mt.of(2);
        int f = mt.of(0, trueChild, falseChild);

        assertEquals(1, mt.evaluate(f, new boolean[] {true}));
        assertEquals(2, mt.evaluate(f, new boolean[] {false}));
    }

    @Test
    void testHighOfAndLowOfMatchTrueHighFalseLow() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariable();
        MtBddImpl mt = bdd.mtbdd();

        int trueChild = mt.of(1);
        int falseChild = mt.of(2);
        int f = mt.of(0, trueChild, falseChild);

        // highOf/lowOf must hand back exactly the true/false children of() was built from.
        assertEquals(trueChild, mt.highOf(f));
        assertEquals(falseChild, mt.lowOf(f));
    }

    @Test
    void testSizeCountsDecisionNodesOnly() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariables(2);
        MtBddImpl mt = bdd.mtbdd();

        // Two nested ite's -> two decision nodes (outer + inner); leaves never count.
        int f = mt.of(0, mt.of(3), mt.of(1, mt.of(5), mt.of(7)));
        assertEquals(2, mt.size(f));

        // outer + trueBranch + falseBranch = 3 decision nodes, even though the two branch nodes share
        // both their leaves.
        int shared = buildSharedLeafFunction(mt, 1, 2);
        assertEquals(3, mt.size(shared));

        // A bare constant has no decision node at all.
        assertEquals(0, mt.size(mt.of(5)));
    }

    @Test
    void testReferenceCounting() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariable();
        MtBddImpl mt = bdd.mtbdd();

        // Constants live in the separate value-refcount array, not the NodeTable - must behave identically
        // to a decision node from the caller's point of view: starts at 0, tracks +1/-1 exactly.
        int constant = mt.of(7);
        assertEquals(0, mt.nodeReferenceCount(constant));
        mt.reference(constant);
        mt.reference(constant);
        assertEquals(2, mt.nodeReferenceCount(constant));
        mt.dereference(constant);
        assertEquals(1, mt.nodeReferenceCount(constant));

        int node = mt.of(0, mt.of(1), mt.of(2));
        assertEquals(0, mt.nodeReferenceCount(node));
        mt.reference(node);
        assertEquals(1, mt.nodeReferenceCount(node));
        mt.dereference(node);
        assertEquals(0, mt.nodeReferenceCount(node));
    }

    @Test
    void testEvaluateOverMultiLevelFunction() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariables(2);
        MtBddImpl mt = bdd.mtbdd();

        // f = ite(v0, 3, ite(v1, 5, 7))
        int f = mt.of(0, mt.of(3), mt.of(1, mt.of(5), mt.of(7)));

        // v0 alone picks 3 regardless of v1; otherwise v1 picks between the inner ite's two leaves.
        assertEquals(3, mt.evaluate(f, new boolean[] {true, false}));
        assertEquals(3, mt.evaluate(f, new boolean[] {true, true}));
        assertEquals(5, mt.evaluate(f, new boolean[] {false, true}));
        assertEquals(7, mt.evaluate(f, new boolean[] {false, false}));

        // Same traversal, BitSet-assignment overload: v0=false, v1=true -> matches the boolean[] case above.
        BitSet bitSetAssignment = new BitSet(2);
        bitSetAssignment.set(1);
        assertEquals(5, mt.evaluate(f, bitSetAssignment));
    }

    @Test
    void testBinaryApplyDoesNotAssumeIdempotence() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariable();
        MtBddImpl mt = bdd.mtbdd();

        int f = mt.of(0, mt.of(3), mt.of(4));
        int doubled = mt.apply(f, f, Integer::sum);
        assertEquals(6, mt.evaluate(doubled, new boolean[] {true}));
        assertEquals(8, mt.evaluate(doubled, new boolean[] {false}));

        // Both operands constant -> the base case, no decision node involved at all.
        int constantCombination = mt.apply(mt.of(2), mt.of(3), (a, b) -> a * b);
        assertEquals(6, mt.evaluate(constantCombination, new boolean[] {true}));
    }

    @Test
    void testMap() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariable();
        MtBddImpl mt = bdd.mtbdd();

        // map() rewrites every leaf independently: 3->6, 4->8 (twice each leaf, not the whole function).
        int f = mt.of(0, mt.of(3), mt.of(4));
        int doubled = mt.map(f, x -> x * 2);
        assertEquals(6, mt.evaluate(doubled, new boolean[] {true}));
        assertEquals(8, mt.evaluate(doubled, new boolean[] {false}));

        // A bare constant is itself a (trivial) leaf.
        int constant = mt.map(mt.of(10), x -> x + 1);
        assertEquals(11, mt.evaluate(constant, new boolean[] {true}));
    }

    @Test
    void testNaryApply() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariables(2);
        MtBddImpl mt = bdd.mtbdd();

        int f = mt.of(0, mt.of(3), mt.of(1, mt.of(5), mt.of(7)));
        int g = mt.of(0, mt.of(10), mt.of(20));
        int h = mt.of(100);

        // 3-ary sum: each assignment's leaves from f, g, h added up (h is constant, always 100).
        int sum3 = mt.apply(new int[] {f, g, h}, values -> values[0] + values[1] + values[2]);
        assertEquals(3 + 10 + 100, mt.evaluate(sum3, new boolean[] {true, false}));
        assertEquals(5 + 20 + 100, mt.evaluate(sum3, new boolean[] {false, true}));
        assertEquals(7 + 20 + 100, mt.evaluate(sum3, new boolean[] {false, false}));

        // Same operand used more than once must not collapse a non-idempotent combiner.
        int triple = mt.apply(new int[] {f, f, f}, values -> values[0] * 100 + values[1] * 10 + values[2]);
        assertEquals(333, mt.evaluate(triple, new boolean[] {true, false}));
        assertEquals(555, mt.evaluate(triple, new boolean[] {false, true}));
        assertEquals(777, mt.evaluate(triple, new boolean[] {false, false}));

        // Single-element array delegates to map().
        int doubled = mt.apply(new int[] {f}, values -> values[0] * 2);
        assertEquals(6, mt.evaluate(doubled, new boolean[] {true, false}));

        // Cross-check the general n-ary path against the dedicated binary apply on a bunch of pairs.
        int binarySum = mt.apply(f, g, Integer::sum);
        int naryPairSum = mt.apply(new int[] {f, g}, values -> values[0] + values[1]);
        for (boolean b0 : new boolean[] {true, false}) {
            for (boolean b1 : new boolean[] {true, false}) {
                boolean[] a = {b0, b1};
                assertEquals(mt.evaluate(binarySum, a), mt.evaluate(naryPairSum, a));
            }
        }
    }

    @Test
    void testAnyAssignmentAndCountAssignmentsAgreeWithEvaluate() {
        BddImpl bdd = new BddImpl(config);
        int numVars = 3;
        bdd.createVariables(numVars);
        MtBddImpl mt = bdd.mtbdd();

        int f = mt.of(0, mt.of(1, mt.of(1), mt.of(2)), mt.of(2, mt.of(3), mt.of(4)));

        // anyAssignment must find a witness exactly when some assignment brute-force-satisfies the
        // predicate.
        int matching = 0;
        for (int mask = 0; mask < (1 << numVars); mask++) {
            boolean[] assignment = maskToAssignment(mask, numVars);
            if (mt.evaluate(f, assignment) == 2) {
                matching++;
            }
        }
        assertTrue(matching > 0);

        // The witness anyAssignment returns must itself actually evaluate to a matching value.
        IntPredicate isTwo = v -> v == 2;
        assertTrue(mt.anyAssignment(f, isTwo).isPresent());
        BitSet witness = mt.anyAssignment(f, isTwo).get();
        assertEquals(2, mt.evaluate(f, witness));

        // f never reaches 999 (its only leaves are 1..4), so no witness can exist.
        assertFalse(mt.anyAssignment(f, v -> v == 999).isPresent());

        // countAssignments must equal the brute-force tally computed above.
        assertEquals(matching, mt.countAssignments(f, isTwo).intValueExact());
    }

    @Test
    void testAssignmentIteratorMatchesBruteForce() {
        BddImpl bdd = new BddImpl(config);
        int numVars = 4;
        bdd.createVariables(numVars);
        MtBddImpl mt = bdd.mtbdd();

        // f only depends on v0, v1 - v2, v3 are genuine don't-cares.
        int f = mt.of(0, mt.of(1, mt.of(1), mt.of(2)), mt.of(1, mt.of(2), mt.of(3)));

        // A selective, an inclusive-of-most-leaves, and a match-everything predicate.
        checkAgainstBruteForce(mt, f, v -> v == 2, numVars, fullSupport(numVars));
        checkAgainstBruteForce(mt, f, v -> v % 2 != 0, numVars, fullSupport(numVars));
        checkAgainstBruteForce(mt, f, v -> true, numVars, fullSupport(numVars));

        // f's leaves are 1..3, so nothing can match a predicate that's always false.
        assertFalse(mt.assignmentIterator(f, v -> false).hasNext());

        // Constant function: every full assignment matches iff the predicate matches the one value.
        int constant = mt.of(2);
        checkAgainstBruteForce(mt, constant, v -> v == 2, numVars, fullSupport(numVars));
        assertFalse(mt.assignmentIterator(constant, v -> v != 2).hasNext());

        // A support smaller than the full variable set, but still a superset of f's real dependencies.
        BitSet minimalSupport = new BitSet(numVars);
        minimalSupport.set(0);
        minimalSupport.set(1);
        checkAgainstBruteForce(mt, f, v -> v == 2, numVars, minimalSupport);
    }

    private static boolean[] maskToAssignment(int mask, int numVars) {
        boolean[] assignment = new boolean[numVars];
        for (int i = 0; i < numVars; i++) {
            assignment[i] = (mask & (1 << i)) != 0;
        }
        return assignment;
    }

    // Ground truth is a brute-force scan of every assignment via evaluate(); the iterator's output is
    // correct iff it produces exactly that set, with no duplicates.
    private static void checkAgainstBruteForce(
            MtBddImpl mt, int function, IntPredicate values, int numVars, BitSet support) {
        Set<BitSet> expected = new HashSet<>();
        int supportSize = support.cardinality();
        int[] supportVars = new int[supportSize];
        int idx = 0;
        for (int i = support.nextSetBit(0); i >= 0; i = support.nextSetBit(i + 1)) {
            supportVars[idx] = i;
            idx += 1;
        }
        for (int mask = 0; mask < (1 << supportSize); mask++) {
            boolean[] assignment = new boolean[numVars];
            for (int i = 0; i < supportSize; i++) {
                assignment[supportVars[i]] = (mask & (1 << i)) != 0;
            }
            if (values.test(mt.evaluate(function, assignment))) {
                BitSet bs = new BitSet(numVars);
                for (int i = 0; i < numVars; i++) {
                    if (assignment[i]) {
                        bs.set(i);
                    }
                }
                expected.add(bs);
            }
        }

        List<BitSet> actualList = new ArrayList<>();
        Iterator<BitSet> iterator = mt.assignmentIterator(function, values, support);
        while (iterator.hasNext()) {
            actualList.add(BitSets.copyOf(iterator.next()));
        }
        Set<BitSet> actual = new HashSet<>(actualList);

        assertEquals(actualList.size(), actual.size(), "duplicate assignment produced by iterator");
        assertEquals(expected, actual);
    }

    @Test
    void testGarbageCollectionUnderPressureFromApplyAndValues() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariables(3);
        MtBddImpl mt = bdd.mtbdd();

        // Nothing returned by of()/apply()/... is automatically referenced - a nested expression like
        // of(v, of(...), of(...)) only protects each of()'s own direct arguments for the duration of that
        // call, not a sibling argument that already finished evaluating while the next one is built (and
        // may trigger table growth/GC). So every intermediate result that needs to survive past another
        // construction call must be explicitly reference()'d first and dereference()'d once consumed,
        // exactly like the internal recursions do via the work stack.
        int leaf1 = mt.reference(mt.of(1));
        int leaf2 = mt.reference(mt.of(2));
        int branch1 = mt.reference(mt.of(1, leaf1, leaf2));
        mt.dereference(leaf1);
        mt.dereference(leaf2);

        int leaf3 = mt.reference(mt.of(3));
        int leaf4 = mt.reference(mt.of(4));
        int branch2 = mt.reference(mt.of(2, leaf3, leaf4));
        mt.dereference(leaf3);
        mt.dereference(leaf4);

        int f = mt.of(0, branch1, branch2);
        mt.dereference(branch1);
        mt.dereference(branch2);
        mt.reference(f);

        // Repeatedly build fresh nodes and values, well past the initial table/value-array capacity, to
        // force ensureCapacity()'s grow/GC path (and the value-array growth in reference()) to run
        // repeatedly, then confirm the referenced structure is still intact and correct afterward.
        for (int i = 0; i < 3000; i++) {
            int leafI = mt.reference(mt.of(i));
            int leafI1 = mt.reference(mt.of(i + 1));
            int leafI2 = mt.reference(mt.of(i + 2));
            int inner = mt.reference(mt.of(1, leafI1, leafI2));
            mt.dereference(leafI1);
            mt.dereference(leafI2);
            int g = mt.of(0, leafI, inner);
            mt.dereference(leafI);
            mt.dereference(inner);

            int combined = mt.apply(new int[] {f, g}, v -> v[0] + v[1]);
            mt.reference(combined);
            mt.dereference(combined);
        }

        // f itself was never touched by the loop above (only fresh g's/combined were) - it must still
        // evaluate exactly as it did before any GC/growth happened.
        assertEquals(2, mt.evaluate(f, new boolean[] {true, false, false}));
        assertEquals(4, mt.evaluate(f, new boolean[] {false, true, false}));
        assertNotEquals(mt.placeholder(), f);
        checkAgainstBruteForce(mt, f, v -> v >= 2, 3, fullSupport(3));
    }

    @Test
    void testEmptyNaryApplyReturnsPlaceholder() {
        BddImpl bdd = new BddImpl(config);
        MtBddImpl mt = bdd.mtbdd();
        assertEquals(mt.placeholder(), mt.apply(EMPTY_INTS, values -> 42));
    }

    private static int buildSharedLeafFunction(MtBddImpl mt, int value1, int value2) {
        int leafA = mt.of(value1);
        int leafB = mt.of(value2);
        int trueBranch = mt.of(1, leafA, leafB);
        int falseBranch = mt.of(1, leafB, leafA);
        return mt.of(0, trueBranch, falseBranch);
    }

    @Test
    void testForEachPathVisitsEveryPathAndAgreesWithEvaluate() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariables(2);
        MtBddImpl mt = bdd.mtbdd();

        int f = buildSharedLeafFunction(mt, 1, 2);

        List<BinaryPath> paths = new ArrayList<>();
        List<Integer> values = new ArrayList<>();
        mt.forEachPath(f, (path, value) -> {
            paths.add(path.copy());
            values.add(value);
        });

        // Every combination of (v0, v1) is a distinct path here (the function has no don't-cares), and
        // paths through a shared leaf must still be reported separately.
        assertEquals(4, paths.size());
        Set<BitSet> distinctAssignments = new HashSet<>();
        for (int i = 0; i < paths.size(); i++) {
            BinaryPath path = paths.get(i);
            assertEquals(2, path.support().cardinality());
            boolean[] assignment = {path.assignment().get(0), path.assignment().get(1)};
            assertEquals((int) values.get(i), mt.evaluate(f, assignment));
            distinctAssignments.add(path.copyAssignment());
        }
        assertEquals(4, distinctAssignments.size());
    }

    @Test
    void testForEachPathOnConstant() {
        BddImpl bdd = new BddImpl(config);
        MtBddImpl mt = bdd.mtbdd();

        List<BinaryPath> paths = new ArrayList<>();
        List<Integer> values = new ArrayList<>();
        mt.forEachPath(mt.of(42), (path, value) -> {
            paths.add(path.copy());
            values.add(value);
        });

        // No decision node at all -> exactly one path, with an empty support (nothing was tested).
        assertEquals(1, paths.size());
        assertEquals(42, values.get(0));
        assertTrue(paths.get(0).support().isEmpty());
    }

    @Test
    void testForEachValueDedupesSharedLeaves() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariables(2);
        MtBddImpl mt = bdd.mtbdd();

        // Unlike forEachPath (4 distinct paths, previous test), forEachValue must report each of the two
        // underlying leaves exactly once, even though each is reached via two different parent nodes.
        int f = buildSharedLeafFunction(mt, 1, 2);

        Set<Integer> seen = new HashSet<>();
        mt.forEachValue(f, seen::add);
        assertEquals(Set.of(1, 2), seen);

        Set<Integer> constantSeen = new HashSet<>();
        mt.forEachValue(mt.of(99), constantSeen::add);
        assertEquals(Set.of(99), constantSeen);
    }

    @Test
    void testAllValuesMatchAndAnyValueMatches() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariables(2);
        MtBddImpl mt = bdd.mtbdd();

        // Co-domain is exactly {2, 4}: both even (allValuesMatch true), neither alone is (false), each is
        // individually present (anyValueMatches true), 3 is absent (false).
        int f = buildSharedLeafFunction(mt, 2, 4);

        assertTrue(mt.allValuesMatch(f, v -> v % 2 == 0));
        assertFalse(mt.allValuesMatch(f, v -> v == 2));
        assertTrue(mt.anyValueMatches(f, v -> v == 2));
        assertTrue(mt.anyValueMatches(f, v -> v == 4));
        assertFalse(mt.anyValueMatches(f, v -> v == 3));

        // A bare constant's co-domain is just the one value.
        int constant = mt.of(7);
        assertTrue(mt.allValuesMatch(constant, v -> v == 7));
        assertFalse(mt.allValuesMatch(constant, v -> v == 8));
        assertTrue(mt.anyValueMatches(constant, v -> v == 7));
        assertFalse(mt.anyValueMatches(constant, v -> v == 8));
    }

    /**
     * Builds ite(0, deepChain, immediateLeaf): a single decision node whose low branch (the false child,
     * per of()'s convention) is one leaf, and whose high branch is a long chain of {@code depth} further
     * decision nodes with distinct leaves. Since allValuesMatch/anyValueMatches explore low before high,
     * a predicate that already resolves on immediateLeaf must never touch the chain at all.
     */
    private static int buildRootWithShallowLowAndDeepHighChain(MtBddImpl mt, int depth, int immediateLeaf) {
        int chain = mt.of(depth - 1, mt.of(100), mt.of(101));
        for (int v = depth - 2; v >= 1; v--) {
            chain = mt.of(v, chain, mt.of(200 + v));
        }
        return mt.of(0, chain, mt.of(immediateLeaf));
    }

    @Test
    void testAllValuesMatchShortCircuitsWithoutVisitingWholeTree() {
        BddImpl bdd = new BddImpl(config);
        int depth = 20;
        bdd.createVariables(depth + 1);
        MtBddImpl mt = bdd.mtbdd();

        int f = buildRootWithShallowLowAndDeepHighChain(mt, depth, 999);

        // 999 (the low branch, visited first) already violates v == 1 - the deep chain behind the high
        // branch must never be reached, so the predicate fires exactly once.
        int[] calls = {0};
        boolean result = mt.allValuesMatch(f, v -> {
            calls[0]++;
            return v == 1;
        });
        assertFalse(result);
        assertEquals(1, calls[0]);
    }

    @Test
    void testAnyValueMatchesShortCircuitsWithoutVisitingWholeTree() {
        BddImpl bdd = new BddImpl(config);
        int depth = 20;
        bdd.createVariables(depth + 1);
        MtBddImpl mt = bdd.mtbdd();

        int f = buildRootWithShallowLowAndDeepHighChain(mt, depth, 1);

        // 1 (the low branch, visited first) already matches v == 1 - the deep chain behind the high branch
        // must never be reached, so the predicate fires exactly once.
        int[] calls = {0};
        boolean result = mt.anyValueMatches(f, v -> {
            calls[0]++;
            return v == 1;
        });
        assertTrue(result);
        assertEquals(1, calls[0]);
    }

    @Test
    void testAgreementMatchesBruteForceEqualityOfValues() {
        BddImpl bdd = new BddImpl(config);
        int numVars = 3;
        bdd.createVariables(numVars);
        MtBddImpl mt = bdd.mtbdd();

        int f = mt.of(0, mt.of(1, mt.of(1), mt.of(2)), mt.of(2, mt.of(3), mt.of(4)));
        int g = mt.of(0, mt.of(1, mt.of(9), mt.of(2)), mt.of(1, mt.of(3), mt.of(4)));
        int agree = mt.agreement(f, g);

        // agree is a Bdd function; it must be true at exactly the assignments where f and g (both MTBDD
        // functions) evaluate to the same value.
        for (int mask = 0; mask < (1 << numVars); mask++) {
            boolean[] assignment = maskToAssignment(mask, numVars);
            boolean expected = mt.evaluate(f, assignment) == mt.evaluate(g, assignment);
            assertEquals(expected, bdd.evaluate(agree, assignment));
        }
    }

    @Test
    void testMapBooleanMatchesPredicateOverEvaluate() {
        BddImpl bdd = new BddImpl(config);
        int numVars = 3;
        bdd.createVariables(numVars);
        MtBddImpl mt = bdd.mtbdd();

        int f = mt.of(0, mt.of(1, mt.of(1), mt.of(2)), mt.of(2, mt.of(3), mt.of(4)));
        int matchesEven = mt.mapBoolean(f, v -> v % 2 == 0);

        for (int mask = 0; mask < (1 << numVars); mask++) {
            boolean[] assignment = maskToAssignment(mask, numVars);
            boolean expected = mt.evaluate(f, assignment) % 2 == 0;
            assertEquals(expected, bdd.evaluate(matchesEven, assignment));
        }
    }

    @Test
    void testUpdateOverridesExactlyWhereAssignmentsHolds() {
        BddImpl bdd = new BddImpl(config);
        int numVars = 3;
        bdd.createVariables(numVars);
        MtBddImpl mt = bdd.mtbdd();

        int f = mt.of(0, mt.of(1, mt.of(1), mt.of(2)), mt.of(2, mt.of(3), mt.of(4)));
        int assignments = bdd.and(bdd.variableFunction(0), bdd.variableFunction(1)); // v0 && v1, a Bdd fn
        int updated = mt.update(f, assignments, 999);

        // Wherever the Bdd assignments function holds, updated must read 999 instead of f's own value;
        // everywhere else it must still agree with f exactly.
        for (int mask = 0; mask < (1 << numVars); mask++) {
            boolean[] assignment = maskToAssignment(mask, numVars);
            int expected = bdd.evaluate(assignments, assignment) ? 999 : mt.evaluate(f, assignment);
            assertEquals(expected, mt.evaluate(updated, assignment));
        }

        // assignments == false is a no-op (never overrides anything) - the original function comes back
        // unchanged, not just equivalent.
        assertEquals(f, mt.update(f, bdd.falseFunction(), 999));

        // assignments == true overrides everywhere, collapsing the result to the constant.
        int allOverridden = mt.update(f, bdd.trueFunction(), 999);
        for (int mask = 0; mask < (1 << numVars); mask++) {
            assertEquals(999, mt.evaluate(allOverridden, maskToAssignment(mask, numVars)));
        }
    }

    @Test
    void testComposeMatchesSubstitutionOfEvaluatedMapping() {
        BddImpl bdd = new BddImpl(config);
        int numVars = 3;
        bdd.createVariables(numVars);
        MtBddImpl mt = bdd.mtbdd();

        // f depends only on the first two formal variables (x0, x1).
        int f = mt.of(0, mt.of(1, mt.of(1), mt.of(2)), mt.of(1, mt.of(3), mt.of(4)));

        int[] mapping = new int[numVars];
        mapping[0] = bdd.variableFunction(2); // x0 -> x2
        mapping[1] = bdd.not(bdd.variableFunction(1)); // x1 -> !x1
        mapping[2] = mt.placeholder(); // x2 -> x2 (identity)

        int composed = mt.compose(f, mapping);
        // compose() placeholder-fills mapping in place, so every entry is now a real Bdd function -
        // evaluating mapping[i] at an assignment gives exactly the value substituted for variable i.
        for (int mask = 0; mask < (1 << numVars); mask++) {
            boolean[] assignment = maskToAssignment(mask, numVars);
            boolean[] substituted = new boolean[numVars];
            for (int i = 0; i < numVars; i++) {
                substituted[i] = bdd.evaluate(mapping[i], assignment);
            }
            assertEquals(mt.evaluate(f, substituted), mt.evaluate(composed, assignment));
        }

        // An all-identity mapping changes nothing, returning f itself unchanged.
        int[] identityMapping = new int[numVars];
        Arrays.fill(identityMapping, mt.placeholder());
        assertEquals(f, mt.compose(f, identityMapping));
    }

    @Test
    void testRestrictMatchesComposeWithConstantMapping() {
        BddImpl bdd = new BddImpl(config);
        int numVars = 3;
        bdd.createVariables(numVars);
        MtBddImpl mt = bdd.mtbdd();

        int f = mt.of(0, mt.of(1, mt.of(1), mt.of(2)), mt.of(1, mt.of(3), mt.of(4)));

        BitSet restrictedVariables = new BitSet(numVars);
        restrictedVariables.set(0);
        BitSet restrictedValues = new BitSet(numVars);
        restrictedValues.set(0); // x0 := true
        int restricted = mt.restrict(f, restrictedVariables, restrictedValues);

        // restricted(x1, x2) must agree with f at x0 forced to true, regardless of what x0 is in the
        // (otherwise-irrelevant, since restricted no longer depends on it) assignment passed in.
        for (int mask = 0; mask < (1 << numVars); mask++) {
            boolean[] assignment = maskToAssignment(mask, numVars);
            boolean[] withX0True = assignment.clone();
            withX0True[0] = true;
            assertEquals(mt.evaluate(f, withX0True), mt.evaluate(restricted, assignment));
        }

        // Restricting nothing changes nothing, returning f itself unchanged.
        assertEquals(f, mt.restrict(f, new BitSet(numVars), new BitSet(numVars)));
    }

    @Test
    void testInvertMapsEachValueToItsAgreementBddFunction() {
        BddImpl bdd = new BddImpl(config);
        int numVars = 3;
        bdd.createVariables(numVars);
        MtBddImpl mt = bdd.mtbdd();

        int f = mt.of(0, mt.of(1, mt.of(1), mt.of(2)), mt.of(2, mt.of(3), mt.of(4)));
        MtBdd.Inverse inverse = mt.invert(f);

        // invert()'s co-domain must be exactly f's actual co-domain (values 1..4 here, nothing else).
        assertEquals(mt.valuesOf(f), inverse.codomain());

        // For every achieved value, functionFor(value) must be true at exactly the assignments where f
        // evaluates to that value - the same characterization mapBoolean(f, v -> v == value) gives.
        for (int mask = 0; mask < (1 << numVars); mask++) {
            boolean[] assignment = maskToAssignment(mask, numVars);
            int value = mt.evaluate(f, assignment);
            for (int other = 1; other <= 4; other++) {
                assertEquals(other == value, bdd.evaluate(inverse.functionFor(other), assignment));
            }
        }

        // A value f never takes on maps to false everywhere (the "undefined" default), not a crash.
        assertEquals(bdd.falseFunction(), inverse.functionFor(999));
    }

    @Test
    void testInvertUsesMapPathForLargeValueDomains() {
        BddImpl bdd = new BddImpl(config);
        int numVars = 6;
        bdd.createVariables(numVars);
        MtBddImpl mt = bdd.mtbdd();

        // 64 distinct values (0..63), comfortably above invert()'s array/map size threshold - forces the
        // Map-based path (the smaller test above, with only 4 values, only ever exercises the array path).
        int f = buildValueFunction(mt, numVars, 0, 0);
        MtBdd.Inverse inverse = mt.invert(f);

        assertEquals(mt.valuesOf(f), inverse.codomain());
        for (int mask = 0; mask < (1 << numVars); mask++) {
            boolean[] assignment = maskToAssignment(mask, numVars);
            // buildValueFunction's own convention: f's value at an assignment is that assignment's mask.
            assertEquals(mask, mt.evaluate(f, assignment));
            // functionFor(mask) must therefore hold at exactly this assignment, and nowhere else.
            for (int other = 0; other < (1 << numVars); other++) {
                assertEquals(mask == other, bdd.evaluate(inverse.functionFor(mask), maskToAssignment(other, numVars)));
            }
        }
    }

    @Test
    void testSplitHandlesConstantResidualFunctions() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariable();
        MtBddImpl mt = bdd.mtbdd();

        // f depends only on the split variable itself, so once it is fixed, the residual "h" is a bare
        // constant - a negative MTBDD function id. A plain int split() could not express this as a leaf
        // value (of() requires value >= 0), which is exactly why split() returns a ToFunctionMap instead.
        int f = mt.of(0, mt.of(10), mt.of(20));
        BitSet splitVariables = new BitSet(1);
        splitVariables.set(0);

        MtBdd.FunctionToFunctionMap result = mt.split(f, splitVariables);
        int g = result.function();

        int indexWhenTrue = mt.evaluate(g, new boolean[] {true});
        int indexWhenFalse = mt.evaluate(g, new boolean[] {false});
        assertEquals(10, mt.evaluate(result.functionFor(indexWhenTrue), EMPTY_BOOL));
        assertEquals(20, mt.evaluate(result.functionFor(indexWhenFalse), EMPTY_BOOL));
    }

    @Test
    void testSplitResidualFunctionsRecombineToOriginalFunction() {
        BddImpl bdd = new BddImpl(config);
        int numVars = 4;
        bdd.createVariables(numVars);
        MtBddImpl mt = bdd.mtbdd();

        // f(x0,x1,x2,x3) evaluates to the assignment's own bit pattern - depends on every variable.
        int f = buildValueFunction(mt, numVars, 0, 0);

        // Split on the "even" variables x0, x2, mirroring the interface doc's example.
        BitSet splitVariables = new BitSet(numVars);
        splitVariables.set(0);
        splitVariables.set(2);
        MtBdd.FunctionToFunctionMap result = mt.split(f, splitVariables);
        int g = result.function();

        for (int mask = 0; mask < (1 << numVars); mask++) {
            boolean[] assignment = maskToAssignment(mask, numVars);
            // split()'s contract: g's value at this assignment selects an h that agrees with f here.
            int index = mt.evaluate(g, assignment);
            int h = result.functionFor(index);
            assertEquals(mt.evaluate(f, assignment), mt.evaluate(h, assignment));
        }

        // g must not actually depend on the non-split variables x1, x3: fixing x0, x2 and toggling the
        // rest must always yield the same index.
        for (int splitMask = 0; splitMask < 4; splitMask++) {
            boolean x0 = (splitMask & 1) != 0;
            boolean x2 = (splitMask & 2) != 0;
            int expectedIndex = -1;
            for (int mask = 0; mask < (1 << numVars); mask++) {
                boolean[] assignment = maskToAssignment(mask, numVars);
                if (assignment[0] != x0 || assignment[2] != x2) {
                    continue;
                }
                int index = mt.evaluate(g, assignment);
                if (expectedIndex == -1) {
                    expectedIndex = index;
                } else {
                    assertEquals(expectedIndex, index);
                }
            }
        }
    }

    private static int buildValueFunction(MtBddImpl mt, int numVars, int variable, int prefix) {
        if (variable == numVars) {
            return mt.of(prefix);
        }
        int low = buildValueFunction(mt, numVars, variable + 1, prefix);
        int high = buildValueFunction(mt, numVars, variable + 1, prefix | (1 << variable));
        return mt.of(variable, high, low);
    }

    @Test
    void testCartesianProductDeduplicatesRepeatedTuples() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariables(2);
        MtBddImpl mt = bdd.mtbdd();

        // f1(x0,x1): (T,T)->1 (T,F)->2 (F,T)->2 (F,F)->1
        int f1 = mt.of(0, mt.of(1, mt.of(1), mt.of(2)), mt.of(1, mt.of(2), mt.of(1)));
        // f2(x0,x1): (T,T)->9 (T,F)->8 (F,T)->8 (F,F)->9
        int f2 = mt.of(0, mt.of(1, mt.of(9), mt.of(8)), mt.of(1, mt.of(8), mt.of(9)));

        MtBdd.FunctionToFunctionsMap product = mt.cartesianProduct(new int[] {f1, f2});
        int g = product.function();

        // (T,T) and (F,F) both produce the tuple [1,9]; (T,F) and (F,T) both produce [2,8] - this needs
        // real content-based deduplication, not the accidental array-identity aliasing a naive
        // Map<int[], ...> would fall prey to (apply()'s leaf array is one shared, mutated buffer, not a
        // fresh array per leaf - see computeApply/apply).
        int indexTT = mt.evaluate(g, new boolean[] {true, true});
        int indexFF = mt.evaluate(g, new boolean[] {false, false});
        int indexTF = mt.evaluate(g, new boolean[] {true, false});
        int indexFT = mt.evaluate(g, new boolean[] {false, true});
        assertEquals(indexTT, indexFF);
        assertEquals(indexTF, indexFT);
        assertNotEquals(indexTT, indexTF);

        assertArrayEquals(new int[] {1, 9}, product.functionFor(indexTT));
        assertArrayEquals(new int[] {2, 8}, product.functionFor(indexTF));

        // Exactly the two distinct tuples were recorded, nothing more.
        assertEquals(2, product.codomain().cardinality());
    }

    @Test
    void testCartesianProductAgreesWithComponentFunctions() {
        BddImpl bdd = new BddImpl(config);
        int numVars = 3;
        bdd.createVariables(numVars);
        MtBddImpl mt = bdd.mtbdd();

        int f1 = mt.of(0, mt.of(1, mt.of(1), mt.of(2)), mt.of(2, mt.of(3), mt.of(4)));
        int f2 = mt.of(1, mt.of(5), mt.of(2, mt.of(6), mt.of(7)));
        MtBdd.FunctionToFunctionsMap product = mt.cartesianProduct(new int[] {f1, f2});
        int g = product.function();

        // For every assignment, g must select an index whose tuple matches each component's own value.
        for (int mask = 0; mask < (1 << numVars); mask++) {
            boolean[] assignment = maskToAssignment(mask, numVars);
            int[] tuple = product.functionFor(mt.evaluate(g, assignment));
            assertEquals(mt.evaluate(f1, assignment), tuple[0]);
            assertEquals(mt.evaluate(f2, assignment), tuple[1]);
        }
    }

    @Test
    void testCartesianProductMemoIsPerCall() {
        BddImpl bdd = new BddImpl(config);
        int numVars = 3;
        bdd.createVariables(numVars);
        MtBddImpl mt = bdd.mtbdd();

        int p = mt.of(2, mt.of(1), mt.of(2));
        int q = mt.of(2, mt.of(3), mt.of(4));
        int f2 = mt.of(2, mt.of(7), mt.of(8));
        // The (p, f2) and (q, f2) operand pairs are each reached twice (via x0/x1), so the operation cache
        // is genuinely hit inside one call...
        int f1 = mt.of(0, mt.of(1, p, q), mt.of(1, q, p));
        // ...and this one reaches the very same pairs, but in the opposite order - so the second call
        // interns the same tuples under *different* indices. A cache surviving across calls would hand
        // back the first call's node, whose leaves index a bijection that no longer exists.
        int swapped = mt.of(0, mt.of(1, q, p), mt.of(1, p, q));

        assertCartesianProductAgrees(mt, f1, f2, numVars);
        assertCartesianProductAgrees(mt, swapped, f2, numVars);
        assertCartesianProductAgrees(mt, f1, f2, numVars);
    }

    private static void assertCartesianProductAgrees(MtBddImpl mt, int f1, int f2, int numVars) {
        MtBdd.FunctionToFunctionsMap product = mt.cartesianProduct(new int[] {f1, f2});
        int g = product.function();
        for (int mask = 0; mask < (1 << numVars); mask++) {
            boolean[] assignment = maskToAssignment(mask, numVars);
            int[] tuple = product.functionFor(mt.evaluate(g, assignment));
            assertEquals(mt.evaluate(f1, assignment), tuple[0]);
            assertEquals(mt.evaluate(f2, assignment), tuple[1]);
        }
        // Four distinct (component, component) tuples exist here; the memo must not merge or duplicate any.
        assertEquals(4, product.codomain().cardinality());
    }

    @Test
    void testCartesianProductEmptyFunctionsReturnsPlaceholder() {
        BddImpl bdd = new BddImpl(config);
        MtBddImpl mt = bdd.mtbdd();

        // Mirrors apply()'s own convention for 0 operands: not a meaningful function, just the sentinel.
        MtBdd.FunctionToFunctionsMap product = mt.cartesianProduct(EMPTY_INTS);
        assertEquals(mt.placeholder(), product.function());
        assertTrue(product.codomain().isEmpty());
    }

    @Test
    void testSimplifyPreservesFunctionWhereDomainHolds() {
        BddImpl bdd = new BddImpl(config);
        int numVars = 3;
        bdd.createVariables(numVars);
        MtBddImpl mt = bdd.mtbdd();

        int f = mt.of(0, mt.of(1, mt.of(1), mt.of(2)), mt.of(2, mt.of(3), mt.of(4)));
        int domain = bdd.or(bdd.variableFunction(0), bdd.variableFunction(1)); // x0 || x1
        int simplified = mt.simplify(f, domain);

        // simplify()'s contract only promises agreement where domain holds - check exactly that.
        for (int mask = 0; mask < (1 << numVars); mask++) {
            boolean[] assignment = maskToAssignment(mask, numVars);
            if (bdd.evaluate(domain, assignment)) {
                assertEquals(mt.evaluate(f, assignment), mt.evaluate(simplified, assignment));
            }
        }
    }

    @Test
    void testSimplifyEliminatesVariableUnconstrainedByDomain() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariables(2);
        MtBddImpl mt = bdd.mtbdd();

        // f depends on both x0 and x1, but domain only cares about x0=true - the x0=false branch is
        // entirely free, so generalized cofactor ("restrict") eliminates x0 outright, keeping only the
        // x0=true branch's structure (one decision node on x1, not the original two).
        int f = mt.of(0, mt.of(1, mt.of(1), mt.of(2)), mt.of(1, mt.of(3), mt.of(4)));
        int domain = bdd.variableFunction(0); // x0
        int simplified = mt.simplify(f, domain);

        assertEquals(1, mt.size(simplified));
        // simplified must agree with f wherever domain (x0) holds, i.e. at x0=true.
        assertEquals(mt.evaluate(f, new boolean[] {true, true}), mt.evaluate(simplified, new boolean[] {true, true}));
        assertEquals(mt.evaluate(f, new boolean[] {true, false}), mt.evaluate(simplified, new boolean[] {true, false}));
        // x0 was eliminated: simplified no longer depends on it at all.
        assertEquals(
                mt.evaluate(simplified, new boolean[] {true, true}),
                mt.evaluate(simplified, new boolean[] {false, true}));
    }

    @Test
    void testSimplifyNeverDependsOnAVariableOutsideFunctionsOwnSupport() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariables(3);
        MtBddImpl mt = bdd.mtbdd();

        // f depends only on x1 and x2 - never on x0.
        int f = mt.of(
                1,
                mt.of(2, mt.of(100), mt.of(200)), // x1 = true: if x2 then 100 else 200
                mt.of(2, mt.of(300), mt.of(400))); // x1 = false: if x2 then 300 else 400

        // domain's top variable (x0) is strictly above f's own (x1), and neither of domain's x0-cofactors
        // is FALSE (domainLow = !x2, domainHigh = x2) - exactly the case that must widen the domain
        // (domainLow | domainHigh) and keep recursing on the very same f, rather than building a new
        // decision on x0 the way constrain (below) legitimately does. Here the widened domain happens to
        // collapse to TRUE (!x2 | x2), so a correct simplify must return f completely unchanged.
        int domain = bdd.equivalence(bdd.variableFunction(0), bdd.variableFunction(2));
        int simplified = mt.simplify(f, domain);

        assertEquals(f, simplified);
        assertFalse(mt.support(simplified).get(0));
    }

    @Test
    void testConstrainMayDependOnAVariableOutsideFunctionsOwnSupport() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariables(3);
        MtBddImpl mt = bdd.mtbdd();

        // Same f and domain as testSimplifyNeverDependsOnAVariableOutsideFunctionsOwnSupport - unlike
        // simplify, constrain's canonical "nearest satisfying assignment" semantics genuinely need to
        // distinguish domain's x0=false (!x2) and x0=true (x2) branches to stay exact off the domain too,
        // so the result may end up depending on x0 even though f itself never did.
        int f = mt.of(1, mt.of(2, mt.of(100), mt.of(200)), mt.of(2, mt.of(300), mt.of(400)));
        int domain = bdd.equivalence(bdd.variableFunction(0), bdd.variableFunction(2));
        int constrained = mt.constrain(f, domain);

        assertTrue(mt.support(constrained).get(0));

        // constrain's contract only promises agreement where domain holds - check exactly that.
        for (int mask = 0; mask < (1 << 3); mask++) {
            boolean[] assignment = maskToAssignment(mask, 3);
            if (bdd.evaluate(domain, assignment)) {
                assertEquals(mt.evaluate(f, assignment), mt.evaluate(constrained, assignment));
            }
        }
    }

    @Test
    void testSimplifyWithTrueDomainReturnsFunctionUnchanged() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariables(2);
        MtBddImpl mt = bdd.mtbdd();

        int f = mt.of(0, mt.of(1, mt.of(1), mt.of(2)), mt.of(1, mt.of(3), mt.of(4)));

        // domain == true: everything matters, nothing can be simplified away - f comes back unchanged.
        assertEquals(f, mt.simplify(f, bdd.trueFunction()));
    }

    @Test
    void testSimplifyWithFalseDomainCollapsesToASingleConstant() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariables(2);
        MtBddImpl mt = bdd.mtbdd();

        int f = mt.of(0, mt.of(1, mt.of(1), mt.of(2)), mt.of(1, mt.of(3), mt.of(4)));

        // domain == false: nothing is constrained, so any function is a valid answer - the smallest one
        // (a bare constant, not the original multi-node structure) must be picked instead of f verbatim.
        int simplified = mt.simplify(f, bdd.falseFunction());
        assertEquals(0, mt.size(simplified));
        assertTrue(mt.isConstant(simplified));
    }

    // A function of x0 and x1 with four distinct leaves, the standard operand of the simplify tests below.
    private static int buildFourLeafFunction(MtBddImpl mt, int base) {
        return mt.of(0, mt.of(1, mt.of(base), mt.of(base + 1)), mt.of(1, mt.of(base + 2), mt.of(base + 3)));
    }

    @Test
    void testApplySimplifyAgreesWithApplyWhereDomainHolds() {
        BddImpl bdd = new BddImpl(config);
        int numVars = 3;
        bdd.createVariables(numVars);
        MtBddImpl mt = bdd.mtbdd();

        int f = buildFourLeafFunction(mt, 1);
        int g = mt.of(2, mt.of(10), mt.of(20));
        MtBddBinaryOperator sum = MtBddBinaryOperator.commutative(Integer::sum);

        int applied = mt.apply(f, g, sum);
        int domain = bdd.or(bdd.variableFunction(0), bdd.variableFunction(1)); // x0 || x1
        int simplified = mt.applySimplify(f, g, sum, domain);

        // applySimplify only promises agreement with apply where the domain holds - check exactly that.
        for (int mask = 0; mask < (1 << numVars); mask++) {
            boolean[] assignment = maskToAssignment(mask, numVars);
            if (bdd.evaluate(domain, assignment)) {
                assertEquals(mt.evaluate(applied, assignment), mt.evaluate(simplified, assignment));
            }
        }

        // domain == true is the plain apply, down to the very same node.
        assertEquals(applied, mt.applySimplify(f, g, sum, bdd.trueFunction()));

        // domain == false leaves everything unconstrained, so a bare constant must come back.
        assertTrue(mt.isConstant(mt.applySimplify(f, g, sum, bdd.falseFunction())));
    }

    @Test
    void testApplySimplifyEliminatesVariableUnconstrainedByDomain() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariables(2);
        MtBddImpl mt = bdd.mtbdd();

        int f = buildFourLeafFunction(mt, 1);
        int g = mt.of(0, mt.of(10), mt.of(20));
        MtBddBinaryOperator sum = MtBddBinaryOperator.commutative(Integer::sum);

        // The domain forces x0, so the x0=false branch of both operands is entirely free: a correct
        // applySimplify never even evaluates the operator there and drops the decision on x0 outright,
        // leaving only the x0=true residual (one node testing x1).
        int domain = bdd.variableFunction(0);
        int simplified = mt.applySimplify(f, g, sum, domain);

        assertEquals(1, mt.size(simplified));
        assertFalse(mt.support(simplified).get(0));
        assertEquals(1 + 10, mt.evaluate(simplified, new boolean[] {true, true}));
        assertEquals(2 + 10, mt.evaluate(simplified, new boolean[] {true, false}));
    }

    @Test
    void testApplySimplifyUsesOperatorShortcutsUnderADomain() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariables(2);
        MtBddImpl mt = bdd.mtbdd();

        int f = buildFourLeafFunction(mt, 1);
        MtBddBinaryOperator product = MtBddBinaryOperator.monoid((a, b) -> a * b, 1, 0);
        int domain = bdd.variableFunction(0);

        // The absorbing element prunes both subtrees, domain or not.
        assertEquals(mt.of(0), mt.applySimplify(f, mt.of(0), product, domain));
        // The neutral element hands back the other operand - simplified against the domain, i.e. exactly
        // what simplify() alone would have produced.
        assertEquals(mt.simplify(f, domain), mt.applySimplify(f, mt.of(1), product, domain));
    }

    @Test
    void testRegisteredApplyAndApplySimplifyMatchTheDirectCalls() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariables(3);
        MtBddImpl mt = bdd.mtbdd();

        int f = buildFourLeafFunction(mt, 1);
        int g = mt.of(2, mt.of(10), mt.of(20));
        MtBddBinaryOperator sum = MtBddBinaryOperator.commutative(Integer::sum);
        int domain = bdd.or(bdd.variableFunction(0), bdd.variableFunction(1));

        RegisteredOperation.Binary registeredApply = mt.registerApply(sum);
        assertEquals(mt.apply(f, g, sum), registeredApply.applyAsInt(f, g));

        RegisteredOperation.Ternary registered = mt.registerApplySimplify(sum);
        assertEquals(mt.applySimplify(f, g, sum, domain), registered.applyAsInt(f, g, domain));
        // Invoked twice, the private cache is now warm - the answer must not change.
        assertEquals(mt.applySimplify(f, g, sum, domain), registered.applyAsInt(f, g, domain));
        assertEquals(mt.apply(f, g, sum), registered.applyAsInt(f, g, bdd.trueFunction()));
        assertTrue(mt.isConstant(registered.applyAsInt(f, g, bdd.falseFunction())));
    }

    @Test
    void testMapSimplifyAgreesWithMapWhereDomainHolds() {
        BddImpl bdd = new BddImpl(config);
        int numVars = 2;
        bdd.createVariables(numVars);
        MtBddImpl mt = bdd.mtbdd();

        int f = buildFourLeafFunction(mt, 1);
        int mapped = mt.map(f, x -> x * 2);
        int domain = bdd.variableFunction(0);
        int simplified = mt.mapSimplify(f, x -> x * 2, domain);

        for (int mask = 0; mask < (1 << numVars); mask++) {
            boolean[] assignment = maskToAssignment(mask, numVars);
            if (bdd.evaluate(domain, assignment)) {
                assertEquals(mt.evaluate(mapped, assignment), mt.evaluate(simplified, assignment));
            }
        }

        // As in applySimplify: the free x0=false branch is dropped rather than mapped.
        assertEquals(1, mt.size(simplified));
        assertFalse(mt.support(simplified).get(0));

        assertEquals(mapped, mt.mapSimplify(f, x -> x * 2, bdd.trueFunction()));
        assertTrue(mt.isConstant(mt.mapSimplify(f, x -> x * 2, bdd.falseFunction())));
    }

    @Test
    void testComposeSimplifyAgreesWithComposeWhereDomainHolds() {
        BddImpl bdd = new BddImpl(config);
        int numVars = 3;
        bdd.createVariables(numVars);
        MtBddImpl mt = bdd.mtbdd();

        int f = buildFourLeafFunction(mt, 1);
        // compose()/analyzeCompose() resolve placeholders in place, so hand every call its own array.
        Supplier<int[]> mapping = () -> new int[] {
            bdd.variableFunction(2), // x0 -> x2
            bdd.not(bdd.variableFunction(1)), // x1 -> !x1
            mt.placeholder() // x2 -> x2
        };

        int composed = mt.compose(f, mapping.get());
        int domain = bdd.or(bdd.variableFunction(1), bdd.variableFunction(2));
        int simplified = mt.composeSimplify(f, mapping.get(), domain);

        for (int mask = 0; mask < (1 << numVars); mask++) {
            boolean[] assignment = maskToAssignment(mask, numVars);
            if (bdd.evaluate(domain, assignment)) {
                assertEquals(mt.evaluate(composed, assignment), mt.evaluate(simplified, assignment));
            }
        }

        assertEquals(composed, mt.composeSimplify(f, mapping.get(), bdd.trueFunction()));
        assertTrue(mt.isConstant(mt.composeSimplify(f, mapping.get(), bdd.falseFunction())));

        // An identity mapping degenerates to a plain simplify.
        int[] identity = new int[numVars];
        Arrays.fill(identity, mt.placeholder());
        assertEquals(mt.simplify(f, domain), mt.composeSimplify(f, identity, domain));
    }

    @Test
    void testComposeSimplifyDropsBranchesTheDomainExcludes() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariables(2);
        MtBddImpl mt = bdd.mtbdd();

        int f = buildFourLeafFunction(mt, 1);
        // x0 maps to itself, x1 to !x1 - so the domain's x0-cofactor really does describe the branch being
        // descended into ("aligned"), and forcing x0 must eliminate it from the result.
        int[] mapping = {mt.placeholder(), bdd.not(bdd.variableFunction(1))};
        int domain = bdd.variableFunction(0);
        int simplified = mt.composeSimplify(f, mapping, domain);

        assertFalse(mt.support(simplified).get(0));
        assertEquals(mt.evaluate(f, new boolean[] {true, false}), mt.evaluate(simplified, new boolean[] {true, true}));
        assertEquals(mt.evaluate(f, new boolean[] {true, true}), mt.evaluate(simplified, new boolean[] {true, false}));
    }

    @Test
    void testRegisteredComposeAndComposeSimplifyMatchTheDirectCalls() {
        BddImpl bdd = new BddImpl(config);
        int numVars = 3;
        bdd.createVariables(numVars);
        MtBddImpl mt = bdd.mtbdd();

        int f = buildFourLeafFunction(mt, 1);
        Supplier<int[]> mapping =
                () -> new int[] {bdd.variableFunction(2), bdd.not(bdd.variableFunction(1)), mt.placeholder()};
        int domain = bdd.or(bdd.variableFunction(1), bdd.variableFunction(2));

        RegisteredOperation.Unary registeredCompose = mt.registerCompose(mapping.get());
        assertEquals(mt.compose(f, mapping.get()), registeredCompose.applyAsInt(f));

        RegisteredOperation.Binary registered = mt.registerComposeSimplify(mapping.get());
        assertEquals(mt.composeSimplify(f, mapping.get(), domain), registered.applyAsInt(f, domain));
        assertEquals(mt.composeSimplify(f, mapping.get(), domain), registered.applyAsInt(f, domain));
        assertEquals(mt.compose(f, mapping.get()), registered.applyAsInt(f, bdd.trueFunction()));
        assertTrue(mt.isConstant(registered.applyAsInt(f, bdd.falseFunction())));
    }

    @Test
    void testPathIteratorReportsValuesAndReusesOnePathObject() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariables(2);
        MtBddImpl mt = bdd.mtbdd();

        int f = buildSharedLeafFunction(mt, 1, 2);

        MtBdd.ValuedIterator<BinaryPath> iterator = mt.pathIterator(f);
        BinaryPath first = null;
        int count = 0;
        while (iterator.hasNext()) {
            BinaryPath path = iterator.next();
            if (first == null) {
                first = path;
            } else {
                // Mutated in place, never re-allocated (the point of handing the value out separately).
                assertSame(first, path);
            }
            boolean[] assignment = {path.assignment().get(0), path.assignment().get(1)};
            assertEquals(mt.evaluate(f, assignment), iterator.value());
            count++;
        }
        assertEquals(4, count);

        // A constant has exactly one, entirely unconstrained path carrying its value.
        MtBdd.ValuedIterator<BinaryPath> constantIterator = mt.pathIterator(mt.of(42));
        assertTrue(constantIterator.hasNext());
        assertTrue(constantIterator.next().support().isEmpty());
        assertEquals(42, constantIterator.value());
        assertFalse(constantIterator.hasNext());
    }

    @Test
    void testAssignmentIteratorReportsTheValueOfEachAssignment() {
        BddImpl bdd = new BddImpl(config);
        int numVars = 4;
        bdd.createVariables(numVars);
        MtBddImpl mt = bdd.mtbdd();

        // Depends on x0, x1 only - x2, x3 are don't-cares, i.e. several assignments share one leaf.
        int f = mt.of(0, mt.of(1, mt.of(1), mt.of(2)), mt.of(1, mt.of(2), mt.of(3)));
        IntPredicate values = v -> v == 2;

        MtBdd.ValuedIterator<BitSet> iterator = mt.assignmentIterator(f, values, fullSupport(numVars));
        int count = 0;
        while (iterator.hasNext()) {
            BitSet assignment = iterator.next();
            assertEquals(mt.evaluate(f, assignment), iterator.value());
            assertTrue(values.test(iterator.value()));
            count++;
        }
        // Two of the four (x0, x1) combinations lead to 2, times the four don't-care combinations.
        assertEquals(8, count);

        // A constant function: every assignment yields the one value.
        MtBdd.ValuedIterator<BitSet> constantIterator = mt.assignmentIterator(mt.of(7), null, fullSupport(numVars));
        assertTrue(constantIterator.hasNext());
        constantIterator.next();
        assertEquals(7, constantIterator.value());
        constantIterator.forEachRemaining(assignment -> {});
    }
}
