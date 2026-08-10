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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

/**
 * A collection of simple smoke tests for {@link MtBddImpl}, in the same spirit as {@link BddTest} - not
 * a full theory suite (that will follow separately, mirroring {@code BddTheories} once the interface is
 * complete), just basic cross-checks of the pieces implemented so far.
 */
class MtBddTest {
    private static final BddConfiguration config =
            ImmutableBddConfiguration.builder().build();

    private static MtBddImpl create(BddImpl bdd) {
        return new MtBddImpl(bdd);
    }

    private static BitSet fullSupport(int numVars) {
        BitSet support = new BitSet(numVars);
        support.set(0, numVars);
        return support;
    }

    @Test
    void testOfCollapsesEqualChildren() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariable();
        MtBddImpl mt = create(bdd);

        int leaf = mt.of(5);
        // ROBDD reduction: a node with two equal children must collapse to that child, not get built.
        assertEquals(leaf, mt.of(0, leaf, leaf));
    }

    @Test
    void testOfChildOrderMatchesTrueHighFalseLow() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariable();
        MtBddImpl mt = create(bdd);

        int trueChild = mt.of(1);
        int falseChild = mt.of(2);
        int f = mt.of(0, trueChild, falseChild);

        // Regression test: of()'s trueChild must be reachable when the variable is assigned true, and
        // falseChild when false - these were swapped by a bug fixed earlier this session.
        assertEquals(1, mt.evaluate(f, new boolean[] {true}));
        assertEquals(2, mt.evaluate(f, new boolean[] {false}));
    }

    @Test
    void testHighOfAndLowOfMatchTrueHighFalseLow() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariable();
        MtBddImpl mt = create(bdd);

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
        MtBddImpl mt = create(bdd);

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
        MtBddImpl mt = create(bdd);

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
        MtBddImpl mt = create(bdd);

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
        MtBddImpl mt = create(bdd);

        int f = mt.of(0, mt.of(3), mt.of(4));
        // A non-idempotent combiner applied to (f, f) must not collapse to f itself (id equality doesn't
        // imply op(x, x) == x): each leaf must be summed with itself, i.e. doubled: 3+3=6, 4+4=8.
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
        MtBddImpl mt = create(bdd);

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
        MtBddImpl mt = create(bdd);

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
        MtBddImpl mt = create(bdd);

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
        MtBddImpl mt = create(bdd);

        // f only depends on v0, v1 - v2, v3 are genuine don't-cares.
        int f = mt.of(0, mt.of(1, mt.of(1), mt.of(2)), mt.of(1, mt.of(2), mt.of(3)));

        // A selective, an inclusive-of-most-leaves, and a match-everything predicate.
        checkAgainstBruteForce(mt, f, v -> v == 2, numVars, fullSupport(numVars));
        checkAgainstBruteForce(mt, f, v -> v % 2 == 1, numVars, fullSupport(numVars));
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
        MtBddImpl mt = create(bdd);

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
        MtBddImpl mt = create(bdd);
        // 0 operands have no meaningful combination (this codebase's chosen convention, not a general
        // MTBDD requirement), so apply degrades to the "not a node" sentinel rather than calling map.
        assertEquals(mt.placeholder(), mt.apply(new int[0], values -> 42));
    }

    /**
     * Builds a two-variable function whose two leaves (values 1 and 2, or the given pair) are each
     * reached via two different parent nodes - useful for telling apart "distinct reachable values"
     * (forEachValue et al., which must dedupe) from "distinct root-to-leaf paths" (forEachPath, which
     * must not).
     */
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
        MtBddImpl mt = create(bdd);

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
        MtBddImpl mt = create(bdd);

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
        MtBddImpl mt = create(bdd);

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
        MtBddImpl mt = create(bdd);

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
        MtBddImpl mt = create(bdd);

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
        MtBddImpl mt = create(bdd);

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
        MtBddImpl mt = create(bdd);

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
        MtBddImpl mt = create(bdd);

        int f = mt.of(0, mt.of(1, mt.of(1), mt.of(2)), mt.of(2, mt.of(3), mt.of(4)));
        int matchesEven = mt.mapBoolean(f, v -> v % 2 == 0);

        // matchesEven is a Bdd function; it must be true at exactly the assignments where f's own value is
        // even.
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
        MtBddImpl mt = create(bdd);

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
        MtBddImpl mt = create(bdd);

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
        MtBddImpl mt = create(bdd);

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
        MtBddImpl mt = create(bdd);

        int f = mt.of(0, mt.of(1, mt.of(1), mt.of(2)), mt.of(2, mt.of(3), mt.of(4)));
        MtBdd.Inverse inverse = mt.invert(f);

        // invert()'s co-domain must be exactly f's actual co-domain (values 1..4 here, nothing else).
        assertEquals(mt.valuesOf(f), inverse.support());

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
        MtBddImpl mt = create(bdd);

        // 64 distinct values (0..63), comfortably above invert()'s array/map size threshold - forces the
        // Map-based path (the smaller test above, with only 4 values, only ever exercises the array path).
        int f = buildValueFunction(mt, numVars, 0, 0);
        MtBdd.Inverse inverse = mt.invert(f);

        assertEquals(mt.valuesOf(f), inverse.support());
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
        MtBddImpl mt = create(bdd);

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
        assertEquals(10, mt.evaluate(result.functionFor(indexWhenTrue), new boolean[0]));
        assertEquals(20, mt.evaluate(result.functionFor(indexWhenFalse), new boolean[0]));
    }

    @Test
    void testSplitResidualFunctionsRecombineToOriginalFunction() {
        BddImpl bdd = new BddImpl(config);
        int numVars = 4;
        bdd.createVariables(numVars);
        MtBddImpl mt = create(bdd);

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
        MtBddImpl mt = create(bdd);

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
        assertEquals(2, product.support().cardinality());
    }

    @Test
    void testCartesianProductAgreesWithComponentFunctions() {
        BddImpl bdd = new BddImpl(config);
        int numVars = 3;
        bdd.createVariables(numVars);
        MtBddImpl mt = create(bdd);

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
    void testCartesianProductEmptyFunctionsReturnsPlaceholder() {
        BddImpl bdd = new BddImpl(config);
        MtBddImpl mt = create(bdd);

        // Mirrors apply()'s own convention for 0 operands: not a meaningful function, just the sentinel.
        MtBdd.FunctionToFunctionsMap product = mt.cartesianProduct(new int[0]);
        assertEquals(mt.placeholder(), product.function());
        assertTrue(product.support().isEmpty());
    }

    @Test
    void testSimplifyPreservesFunctionWhereDomainHolds() {
        BddImpl bdd = new BddImpl(config);
        int numVars = 3;
        bdd.createVariables(numVars);
        MtBddImpl mt = create(bdd);

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
        MtBddImpl mt = create(bdd);

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
    void testSimplifyWithTrueDomainReturnsFunctionUnchanged() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariables(2);
        MtBddImpl mt = create(bdd);

        int f = mt.of(0, mt.of(1, mt.of(1), mt.of(2)), mt.of(1, mt.of(3), mt.of(4)));

        // domain == true: everything matters, nothing can be simplified away - f comes back unchanged.
        assertEquals(f, mt.simplify(f, bdd.trueFunction()));
    }

    @Test
    void testSimplifyWithFalseDomainCollapsesToASingleConstant() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariables(2);
        MtBddImpl mt = create(bdd);

        int f = mt.of(0, mt.of(1, mt.of(1), mt.of(2)), mt.of(1, mt.of(3), mt.of(4)));

        // domain == false: nothing is constrained, so any function is a valid answer - the smallest one
        // (a bare constant, not the original multi-node structure) must be picked instead of f verbatim.
        int simplified = mt.simplify(f, bdd.falseFunction());
        assertEquals(0, mt.size(simplified));
        assertTrue(mt.isConstant(simplified));
    }
}
