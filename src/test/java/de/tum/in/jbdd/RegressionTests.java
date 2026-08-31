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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.ref.WeakReference;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.function.IntBinaryOperator;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

/**
 * A collection of tests motivated by regressions.
 */
class RegressionTests {
    private static final BddConfiguration config =
            ImmutableBddConfiguration.builder().build();

    private static void forceJvmGarbageCollection() {
        // Force to clear weak references
        Object sentinel = new Object();
        WeakReference<Object> reference = new WeakReference<>(sentinel);
        sentinel = null; // NOPMD - deliberately dropping the only strong reference
        for (int i = 0; i < 100 && reference.get() != null; i++) {
            //noinspection CallToSystemGC
            System.gc(); // NOPMD
        }
        assertNull(reference.get(), "Could not force a JVM garbage collection");
    }

    private static boolean assertionsEnabled() {
        boolean[] enabled = new boolean[1];
        assert setTrue(enabled);
        return enabled[0];
    }

    private static boolean setTrue(boolean[] flag) {
        flag[0] = true;
        return true;
    }

    private static boolean[] assignment(boolean... values) {
        return values;
    }

    @Test
    void testReferenceOverflow() {
        BddImpl bdd = new BddImpl(config);
        int v1 = bdd.createVariable();
        int v2 = bdd.createVariable();
        int and = bdd.and(v1, v2);

        for (int i = 0; i < Integer.MAX_VALUE; i++) {
            bdd.reference(and);
        }

        for (int i = 0; i < Integer.MAX_VALUE; i++) {
            bdd.dereference(and);
        }
    }

    @Test
    void testIteratorUniquePath() {
        BddImpl bdd = new BddImpl(config);
        int v1 = bdd.createVariable();
        int v2 = bdd.createVariable();
        int and = bdd.and(v1, v2);

        bdd.solutionIterator(and).forEachRemaining(bitSet -> {});
    }

    @Test
    void testCheckSucceedsOnAFreshTable() {
        // biggestValidNode / biggestReferencedNode are PLACEHOLDER while nothing has been built yet, and
        // slot 0 is never a node - check() must not demand that it be a valid, referenced one.
        BddImpl bdd = new BddImpl(config);
        assertTrue(bdd.check());
        assertTrue(bdd.mtbdd().check());
    }

    @Test
    void testHashArrayWithASingleKey() {
        assertEquals(HashUtil.hash(7, 11), HashUtil.hashArray(7, 11));
    }

    // A diagram's own cache-invalidation hook must survive a JVM garbage collection. It is owned by the
    // diagram and referenced by nothing else, so registering it weakly let the JVM drop it - after which
    // node-table GC stopped pruning the operation caches entirely, and an entry whose result node had been
    // reclaimed and whose id had been recycled silently returned a different function.

    @Test
    void testBooleanCacheIsPrunedAfterAJvmGarbageCollection() {
        BddImpl bdd = new BddImpl(config);
        int[] v = bdd.createVariables(6);
        forceJvmGarbageCollection();

        // Nothing references this, so the following forceGc reclaims its node - but the and-cache still
        // holds (v0, v1) -> node.
        bdd.and(v[0], v[1]);
        assertEquals(1, bdd.forceGc());

        // Re-use the freed slot for an unrelated function.
        int reused = bdd.reference(bdd.and(v[2], v[3]));

        int again = bdd.and(v[0], v[1]);
        assertNotEquals(reused, again);
        assertTrue(bdd.evaluate(again, assignment(true, true, false, false, false, false)));
        assertFalse(bdd.evaluate(again, assignment(true, false, false, false, false, false)));
        assertFalse(bdd.evaluate(again, assignment(false, false, true, true, false, false)));
    }

    @Test
    void testMtBddCacheIsPrunedAfterAJvmGarbageCollection() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariables(3);
        MtBddImpl mt = bdd.mtbdd();
        forceJvmGarbageCollection();

        // One operator instance throughout, so the ephemeral apply cache is never invalidated for that
        // reason and the test really exercises the GC hook.
        IntBinaryOperator sum = Integer::sum;
        int left = mt.reference(mt.of(0, mt.of(1), mt.of(2)));
        int right = mt.reference(mt.of(1, mt.of(10), mt.of(20)));

        // Unreferenced, so the following forceGc reclaims it while the apply cache still maps to it.
        mt.apply(left, right, sum);
        mt.forceGc();

        // Re-use the freed slots for unrelated structure.
        int filler = mt.reference(mt.of(2, mt.of(100), mt.of(200)));

        int again = mt.apply(left, right, sum);
        assertNotEquals(filler, again);
        assertEquals(11, mt.evaluate(again, assignment(true, true, false)));
        assertEquals(21, mt.evaluate(again, assignment(true, false, false)));
        assertEquals(12, mt.evaluate(again, assignment(false, true, false)));
        assertEquals(22, mt.evaluate(again, assignment(false, false, false)));
        assertTrue(mt.check());
    }

    @Test
    void testMtBddForceGcReclaimsUnreferencedNodesAndValues() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariables(2);
        MtBddImpl mt = bdd.mtbdd();

        int kept = mt.reference(mt.of(0, mt.of(1), mt.of(2)));
        int referencedBareValue = mt.reference(mt.of(99));
        // Neither referenced nor reachable from anything referenced.
        mt.of(77);
        mt.of(1, mt.of(5), mt.of(6));

        // Values 1, 2, 5, 6, 77, 99 are allocated; only 1, 2 (below `kept`) and 99 (referenced) survive.
        int countBefore = mt.nodeCount();
        // Used to fail outright: reclaimUnmarkedNodes asserts that nothing is marked, but leaf marks are
        // only cleared by the leaf sweep, which ran afterwards.
        assertDoesNotThrow(mt::forceGc);
        assertEquals(3, countBefore - mt.nodeCount());

        assertTrue(mt.isValidFunction(kept));
        assertTrue(mt.isValidFunction(referencedBareValue));
        assertEquals(2, mt.evaluate(kept, assignment(false, false)));
        assertEquals(1, mt.evaluate(kept, assignment(true, false)));
        assertTrue(mt.check());
    }

    // Assignment counts are keyed on the node alone, but count over [decisionVariable, numberOfVariables),
    // so creating a variable makes every cached entry wrong.

    @Test
    void testSatisfactionCountIsRecomputedAfterCreatingVariables() {
        BddImpl bdd = new BddImpl(config);
        int[] v = bdd.createVariables(2);
        int and = bdd.reference(bdd.and(v[0], v[1]));

        assertEquals(BigInteger.ONE, bdd.countSatisfyingAssignments(and));
        bdd.createVariables(2);
        assertEquals(BigInteger.valueOf(4), bdd.countSatisfyingAssignments(and));

        int domain = bdd.reference(bdd.variableFunction(0));
        assertEquals(BigInteger.valueOf(4), bdd.countSatisfyingAssignmentsIn(and, domain));
        bdd.createVariables(1);
        assertEquals(BigInteger.valueOf(8), bdd.countSatisfyingAssignmentsIn(and, domain));
    }

    @Test
    void testMtBddAssignmentCountIsRecomputedAfterCreatingVariables() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariables(2);
        MtBddImpl mt = bdd.mtbdd();
        // 1 exactly where both variables hold, 0 elsewhere.
        int function = mt.reference(mt.of(0, mt.of(1, mt.of(1), mt.of(0)), mt.of(0)));

        // One predicate instance throughout, so nothing else invalidates the count cache.
        IntPredicate isOne = value -> value == 1;
        assertEquals(BigInteger.ONE, mt.countAssignments(function, isOne));
        bdd.createVariables(2);
        assertEquals(BigInteger.valueOf(4), mt.countAssignments(function, isOne));
    }

    @Test
    void testBddMapValueIndexSurvivesAValueSweep() {
        BddImpl bdd = new BddImpl(config);
        BddSetFactoryImpl sets = new BddSetFactoryImpl(bdd);
        BddMapFactoryImpl<String> maps = new BddMapFactoryImpl<>(sets);
        MtBddImpl mt = bdd.mtbdd();
        bdd.createVariables(2);

        BddMap<String> kept = maps.of("kept");
        for (int i = 0; i < 32; i++) {
            maps.of("transient" + i);
        }

        forceJvmGarbageCollection();
        // Any further protect() drains the reference queue, dereferencing the collected wrappers.
        maps.of("kept");
        // Reclaims the now-unreferenced values, which drives ValueIndex#sweepValues - whose invariant
        // assertion used to demand the complement of what it actually maintains.
        assertDoesNotThrow(mt::forceGc);

        assertEquals("kept", kept.evaluate(BitSets.of()));
        assertEquals(java.util.Set.of("kept"), kept.values());
        assertTrue(mt.check());
    }

    @Test
    void testAssignmentIteratorRejectsAnInterleavedQuery() {
        // The iterator is lazy and reads a memo that is keyed on the last predicate handed to the diagram.
        // Interleaving is not supported; it used to silently drop solutions, now it fails loudly.
        assumeTrue(assertionsEnabled(), "Guarded by an assertion");

        BddImpl bdd = new BddImpl(config);
        bdd.createVariables(3);
        MtBddImpl mt = bdd.mtbdd();
        int a = mt.reference(mt.of(1, mt.of(5), mt.of(7)));
        int b = mt.reference(mt.of(1, mt.of(5), mt.of(8)));
        int function = mt.reference(mt.of(0, a, b));

        IntPredicate isFive = value -> value == 5;
        int solutions = 0;
        for (Iterator<BitSet> iterator = mt.assignmentIterator(function, isFive); iterator.hasNext(); ) {
            iterator.next();
            solutions++;
        }
        assertEquals(4, solutions);

        Iterator<BitSet> iterator = mt.assignmentIterator(function, isFive);
        iterator.next();
        mt.anyAssignment(function, value -> value == 100);
        assertThrows(AssertionError.class, () -> {
            while (iterator.hasNext()) {
                iterator.next();
            }
        });
    }

    @Test
    void testSplitRelabeledCallsTheRelabelerOncePerDistinctResidual() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariables(3);
        MtBddImpl mt = bdd.mtbdd();

        // Three residuals over the non-split variable 2, arranged so that the middle one is reached from
        // two different leaves of the meta-function - it used to be relabeled once per such leaf, because
        // the mapping pass short-circuits constants before its cache lookup.
        int first = mt.reference(mt.of(2, mt.of(10), mt.of(11)));
        int second = mt.reference(mt.of(2, mt.of(12), mt.of(13)));
        int third = mt.reference(mt.of(2, mt.of(14), mt.of(15)));
        int low = mt.reference(mt.of(1, second, first));
        int high = mt.reference(mt.of(1, third, second));
        int function = mt.reference(mt.of(0, high, low));

        BitSet splitVariables = BitSets.of(0, 1);
        List<Integer> relabeled = new ArrayList<>();
        int result = mt.reference(mt.splitRelabeled(function, splitVariables, residual -> {
            relabeled.add(residual);
            return 100 + relabeled.size();
        }));

        assertEquals(3, relabeled.size());
        assertEquals(relabeled.size(), new HashSet<>(relabeled).size());

        // Sanity: the relabeled meta-function still distinguishes exactly the three residuals.
        assertEquals(3, mt.valuesOf(result).cardinality());
        assertEquals(
                mt.evaluate(result, assignment(false, true, false)),
                mt.evaluate(result, assignment(true, false, false)));
        assertNotEquals(
                mt.evaluate(result, assignment(false, false, false)),
                mt.evaluate(result, assignment(true, true, false)));
    }

    @Test
    void testUpdateMatchesIfThenElseOnTheOriginalFunction() {
        // Pins the semantics MtBdd#update's own default implementation got wrong (it passed the condition
        // where the original function belongs).
        BddImpl bdd = new BddImpl(config);
        int[] v = bdd.createVariables(2);
        MtBddImpl mt = bdd.mtbdd();

        int function = mt.reference(mt.of(0, mt.of(3), mt.of(4)));
        int condition = bdd.reference(v[1]);
        int value = 9;

        int expected = mt.reference(mt.ifThenElse(condition, mt.of(value), function));
        assertEquals(expected, mt.update(function, condition, value));
        assertEquals(function, mt.update(function, bdd.falseFunction(), value));
        assertEquals(mt.of(value), mt.update(function, bdd.trueFunction(), value));
    }

    @Test
    void testRegisterComposeDoesNotShareTheCallersMapping() {
        BddImpl bdd = new BddImpl(config);
        int[] v = bdd.createVariables(3);
        int function = bdd.reference(bdd.and(v[0], v[2]));

        int[] mapping = {v[1], bdd.placeholder(), bdd.placeholder()};
        RegisteredOperation.Unary compose = bdd.registerCompose(mapping);
        // Unlike compose(), registering must not write the resolved placeholders back into the caller's
        // array - and must not keep using it afterwards either.
        assertEquals(bdd.placeholder(), mapping[1]);
        assertEquals(bdd.placeholder(), mapping[2]);

        int expected = bdd.reference(compose.applyAsInt(function));
        Arrays.fill(mapping, bdd.trueFunction());
        assertEquals(expected, compose.applyAsInt(function));
    }

    @Test
    void testMtBddRegisterComposeShortcuts() {
        BddImpl bdd = new BddImpl(config);
        int[] v = bdd.createVariables(3);
        MtBddImpl mt = bdd.mtbdd();
        int function = mt.reference(mt.of(0, mt.of(2, mt.of(1), mt.of(2)), mt.of(3)));

        int[] identity = new int[3];
        Arrays.fill(identity, mt.placeholder());
        assertEquals(function, mt.registerCompose(identity).applyAsInt(function));
        assertEquals(mt.placeholder(), identity[0]);

        int[] constants = {bdd.trueFunction(), bdd.falseFunction(), bdd.placeholder()};
        BitSet restricted = BitSets.of(0, 1);
        BitSet restrictedValues = BitSets.of(0);
        assertEquals(
                mt.restrict(function, restricted, restrictedValues),
                mt.registerCompose(constants).applyAsInt(function));

        int[] general = {v[1], v[0], bdd.placeholder()};
        RegisteredOperation.Unary compose = mt.registerCompose(general);
        assertEquals(bdd.placeholder(), general[2]);
        int expected = mt.reference(compose.applyAsInt(function));
        Arrays.fill(general, bdd.trueFunction());
        assertEquals(expected, compose.applyAsInt(function));
    }

    @Test
    void testRegisterComposeWithATinyCacheBudget() {
        // The desired cache size is the table size divided by two configured dividers, which can round
        // down to zero - a cache still needs at least one bin.
        BddConfiguration tiny = ImmutableBddConfiguration.builder()
                .registeredOperationDivider(1 << 20)
                .build();
        BddImpl bdd = new BddImpl(tiny);
        int[] v = bdd.createVariables(3);
        int function = bdd.reference(bdd.and(v[0], v[2]));

        RegisteredOperation.Unary compose = assertDoesNotThrow(() -> bdd.registerCompose(new int[] {v[1], v[2], v[0]}));
        assertDoesNotThrow(() -> compose.applyAsInt(function));

        MtBddImpl mt = bdd.mtbdd();
        int mtFunction = mt.reference(mt.of(0, mt.of(1), mt.of(2)));
        RegisteredOperation.Unary mtCompose =
                assertDoesNotThrow(() -> mt.registerCompose(new int[] {v[1], v[2], v[0]}));
        assertDoesNotThrow(() -> mtCompose.applyAsInt(mtFunction));
        RegisteredOperation.Binary mtApply =
                assertDoesNotThrow(() -> mt.registerApply(MtBddBinaryOperator.of(Integer::sum)));
        assertDoesNotThrow(() -> mtApply.applyAsInt(mtFunction, mtFunction));
    }

    @Test
    void testRegisteredComposeNeverNeedsTheDomainCache() {
        // A plain registered compose is only ever invoked with a TRUE domain and must therefore keep the
        // domain TRUE all the way down: it carries no domain-keyed cache to look anything else up in. (The
        // recursion used to narrow the domain to the current branch condition regardless, which both
        // needed that cache and, since it wasn't allocated, tripped an assertion on the very first use.)
        BddImpl bdd = new BddImpl(config);
        int[] v = bdd.createVariables(4);
        int function = bdd.reference(bdd.or(bdd.and(v[0], v[2]), bdd.and(v[1], v[3])));
        int[] mapping = {bdd.and(v[1], v[3]), bdd.placeholder(), bdd.not(v[3]), bdd.placeholder()};

        RegisteredOperation.Unary compose = bdd.registerCompose(mapping.clone());
        int registered = bdd.reference(assertDoesNotThrow(() -> compose.applyAsInt(function)));

        // Must agree with the ordinary compose, which does narrow (and has both caches).
        assertEquals(bdd.compose(function, mapping.clone()), registered);

        // And with the domain-carrying variant at a TRUE domain.
        RegisteredOperation.Binary composeSimplify = bdd.registerComposeSimplify(mapping.clone());
        assertEquals(registered, composeSimplify.applyAsInt(function, bdd.trueFunction()));
    }

    @Test
    void testXorSimplifyCarriesTheDomainIntoTheSecondOperandsBranch() {
        // When the two operands' top variables differ, the recursion used to fall back to the plain
        // (domain-less) xor, abandoning simplification for that whole subtree - unlike its `and`
        // counterpart, which carries the domain. Correct either way, just needlessly large.
        BddImpl bdd = new BddImpl(config);
        int[] v = bdd.createVariables(3);

        // xor(v0, v2) restricted to !v2 is just v0.
        int domain = bdd.reference(bdd.not(v[2]));
        int simplified = bdd.reference(bdd.xorSimplify(v[0], v[2], domain));
        assertEquals(v[0], simplified);

        // ... and it still agrees with the exact xor everywhere the domain holds.
        int exact = bdd.reference(bdd.xor(v[0], v[2]));
        assertTrue(bdd.size(simplified) < bdd.size(exact));
        for (int mask = 0; mask < 8; mask++) {
            boolean[] a = assignment((mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0);
            if (bdd.evaluate(domain, a)) {
                assertEquals(bdd.evaluate(exact, a), bdd.evaluate(simplified, a), Arrays.toString(a));
            }
        }
    }

    @Test
    void testMtBddConstrainIsTheGeneralizedCofactor() {
        // constrain is precomposition with the projection onto the domain, which is codomain-agnostic -
        // so on an MTBDD it must still be the canonical representative of "agrees on the domain", and
        // must commute with pointwise operations. simplify satisfies neither; it is the control here.
        BddImpl bdd = new BddImpl(config);
        int[] v = bdd.createVariables(3);
        MtBddImpl mt = bdd.mtbdd();

        int f = mt.reference(mt.of(0, mt.of(1, mt.of(1), mt.of(2)), mt.of(3)));
        int other = mt.reference(mt.of(2, mt.of(7), mt.of(8)));
        int domain = bdd.reference(bdd.or(v[0], v[1]));

        // g agrees with f exactly on the domain, and differs outside it.
        int g = mt.reference(mt.ifThenElse(domain, f, other));
        assertNotEquals(f, g);

        // Canonical: same cofactor for everything that agrees on the domain.
        assertEquals(mt.constrain(f, domain), mt.constrain(g, domain));

        // Exact on the domain, for both.
        for (int mask = 0; mask < 8; mask++) {
            boolean[] a = assignment((mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0);
            if (bdd.evaluate(domain, a)) {
                assertEquals(mt.evaluate(f, a), mt.evaluate(mt.constrain(f, domain), a));
                assertEquals(mt.evaluate(f, a), mt.evaluate(mt.simplify(f, domain), a));
            }
        }

        // Homomorphic under any pointwise operation.
        IntBinaryOperator op = (x, y) -> (x * 3 + y) % 7;
        int combinedThenCofactored = mt.reference(mt.constrain(mt.reference(mt.apply(f, g, op)), domain));
        int cofactoredThenCombined = mt.reference(mt.apply(mt.constrain(f, domain), mt.constrain(g, domain), op));
        assertEquals(combinedThenCofactored, cofactoredThenCombined);

        // The cofactor's co-domain is exactly the set of values f takes on the domain.
        BitSet imageOnDomain = new BitSet();
        for (int mask = 0; mask < 8; mask++) {
            boolean[] a = assignment((mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0);
            if (bdd.evaluate(domain, a)) {
                imageOnDomain.set(mt.evaluate(f, a));
            }
        }
        assertEquals(imageOnDomain, mt.valuesOf(mt.constrain(f, domain)));

        // simplify keeps the result's dependencies within f's own; constrain need not.
        assertTrue(BitSets.isSubset(mt.support(mt.simplify(f, domain)), mt.support(f)));
    }

    @Test
    void testMtBddConstrainRejectsAnEmptyDomain() {
        // The generalized cofactor is precomposition with the projection onto the domain, which does not
        // exist when the domain is empty - a precondition, not a value to pick. simplify is unaffected:
        // there everything is don't-care, so any constant is a valid answer.
        assumeTrue(assertionsEnabled(), "Guarded by an assertion");

        BddImpl bdd = new BddImpl(config);
        bdd.createVariables(2);
        MtBddImpl mt = bdd.mtbdd();
        int f = mt.reference(mt.of(0, mt.of(1), mt.of(2)));

        assertThrows(AssertionError.class, () -> mt.constrain(f, bdd.falseFunction()));
        assertTrue(mt.isConstant(mt.simplify(f, bdd.falseFunction())));
    }

    @Test
    void testComposeSimplifyDoesNotCofactorTheDomainOnAReplacedVariable() {
        // The composed function branches on the *replacement*, so f's cofactors on a replaced variable are
        // not aligned with the domain's cofactors on it: the low branch governs wherever the replacement is
        // false, which is unrelated to where the variable is. Cofactoring the domain here dropped exactly
        // the part of the domain the discarded branch was responsible for.
        BddImpl bdd = new BddImpl(config);
        int[] v = bdd.createVariables(3);

        // f branches on v0, and v0 is replaced by v1 - so the domain v0 (same top variable) must be
        // carried into both branches whole.
        int f = bdd.reference(bdd.ifThenElse(v[0], v[2], bdd.not(v[2])));
        int[] mapping = {v[1], bdd.placeholder(), bdd.placeholder()};
        int domain = bdd.reference(v[0]);

        int plain = bdd.reference(bdd.compose(f, mapping.clone()));
        int simplified = bdd.reference(assertDoesNotThrow(() -> bdd.composeSimplify(f, mapping.clone(), domain)));

        for (int mask = 0; mask < 8; mask++) {
            boolean[] a = assignment((mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0);
            if (bdd.evaluate(domain, a)) {
                assertEquals(bdd.evaluate(plain, a), bdd.evaluate(simplified, a), Arrays.toString(a));
            }
        }

        // Cofactoring *is* sound where the variable maps to itself - v1 here, which the domain also tests.
        int selfMapped = bdd.reference(bdd.ifThenElse(v[1], v[2], bdd.not(v[2])));
        int selfDomain = bdd.reference(bdd.and(v[1], v[2]));
        int selfPlain = bdd.reference(bdd.compose(selfMapped, mapping.clone()));
        int selfSimplified = bdd.reference(bdd.composeSimplify(selfMapped, mapping.clone(), selfDomain));
        for (int mask = 0; mask < 8; mask++) {
            boolean[] a = assignment((mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0);
            if (bdd.evaluate(selfDomain, a)) {
                assertEquals(bdd.evaluate(selfPlain, a), bdd.evaluate(selfSimplified, a), Arrays.toString(a));
            }
        }
    }

    @Test
    void testComposeWithAShorterAllConstantMappingLeavesTheRestAlone() {
        // A mapping shorter than numberOfVariables() leaves the remaining variables unchanged, so an
        // all-constant mapping does not make the result constant. It used to be answered by evaluating the
        // function against the mapping, reading every uncovered variable as false.
        BddImpl bdd = new BddImpl(config);
        int[] v = bdd.createVariables(3);
        int function = bdd.reference(bdd.and(v[0], bdd.or(v[1], v[2])));

        // v0 := TRUE, v1 := FALSE; v2 untouched. Expected: v2.
        int composed = bdd.reference(bdd.compose(function, new int[] {bdd.trueFunction(), bdd.falseFunction()}));
        assertEquals(v[2], composed);
        assertFalse(bdd.isConstant(composed));

        RegisteredOperation.Unary registered = bdd.registerCompose(new int[] {bdd.trueFunction(), bdd.falseFunction()});
        assertEquals(composed, registered.applyAsInt(function));

        // The fully-covering mapping does collapse to a constant, via the same path.
        assertEquals(
                bdd.trueFunction(),
                bdd.compose(function, new int[] {bdd.trueFunction(), bdd.falseFunction(), bdd.trueFunction()}));
    }

    @Test
    void testAgreementOfAFunctionWithItselfIsTrue() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariables(3);
        MtBddImpl mt = bdd.mtbdd();
        int shared = mt.reference(mt.of(2, mt.of(7), mt.of(8)));
        int function = mt.reference(mt.of(0, mt.of(1, shared, mt.of(9)), shared));

        assertEquals(bdd.trueFunction(), mt.agreement(function, function));
        assertEquals(bdd.trueFunction(), mt.agreement(shared, shared));
        assertEquals(bdd.trueFunction(), mt.agreement(mt.of(7), mt.of(7)));
        assertNotEquals(bdd.trueFunction(), mt.agreement(function, shared));
    }

    @Test
    void testNaryOperatorUnwrapsToTheCachedUnaryAndBinaryImplementations() {
        BddImpl bdd = new BddImpl(config);
        bdd.createVariables(2);
        MtBddImpl mt = bdd.mtbdd();
        int left = mt.reference(mt.of(0, mt.of(1), mt.of(2)));
        int right = mt.reference(mt.of(1, mt.of(10), mt.of(20)));

        // Arity 1 and 2 carry their own adapters, so one operator instance reused across calls keeps the
        // (identity-keyed, ephemeral) map/apply caches warm instead of invalidating them every time.
        MtBddNaryOperator increment = MtBddNaryOperator.of(1, values -> values[0] + 1);
        assertInstanceOf(MtBddNaryOperator.Unary.class, increment);
        int mapped = mt.reference(mt.apply(new int[] {left}, increment));
        assertEquals(mapped, mt.apply(new int[] {left}, increment));
        assertEquals(mapped, mt.map(left, value -> value + 1));

        MtBddNaryOperator sum = MtBddNaryOperator.commutative(2, values -> values[0] + values[1]);
        assertInstanceOf(MtBddNaryOperator.Binary.class, sum);
        int applied = mt.reference(mt.apply(new int[] {left, right}, sum));
        assertEquals(applied, mt.apply(new int[] {left, right}, sum));
        assertEquals(applied, mt.applyCommutative(left, right, Integer::sum));
        // Commutativity carries over into the binary operator's own argument-order canonicalization.
        assertEquals(applied, mt.apply(new int[] {right, left}, sum));

        assertEquals(11, mt.evaluate(applied, assignment(true, true)));
        assertEquals(22, mt.evaluate(applied, assignment(false, false)));

        MtBddNaryOperator ternary = MtBddNaryOperator.of(3, values -> values[0] + values[1] + values[2]);
        assertFalse(ternary instanceof MtBddNaryOperator.Unary || ternary instanceof MtBddNaryOperator.Binary);
        assertDoesNotThrow(() -> mt.apply(new int[] {left, right, left}, ternary));
    }

    @Test
    void testSimplifyKeepsItsDomainAliveAcrossBddGarbageCollection() {
        // simplify widens its domain with bdd.computeOr, which allocates Bdd nodes and can therefore
        // trigger a Bdd GC mid-recursion; the domain has to be protected on the Bdd work stack for that.
        // Stress rather than proof: it forces many collections while the domain is held only by a local.
        BddImpl bdd = new BddImpl(config);
        int[] v = bdd.createVariables(8);
        MtBddImpl mt = bdd.mtbdd();

        int function = mt.reference(mt.of(0, mt.of(3, mt.of(1), mt.of(2)), mt.of(6, mt.of(3), mt.of(4))));

        for (int i = 0; i < 512; i++) {
            // Deliberately unreferenced, and built from throw-away structure so the Bdd table churns.
            int domain = bdd.or(bdd.and(v[1], v[(i % 3) + 4]), bdd.and(v[2], v[7]));
            int simplified = mt.reference(mt.simplify(function, domain));

            for (int mask = 0; mask < (1 << 4); mask++) {
                boolean[] assignment = new boolean[8];
                assignment[0] = (mask & 1) != 0;
                assignment[3] = (mask & 2) != 0;
                assignment[6] = (mask & 4) != 0;
                assignment[1] = (mask & 8) != 0;
                if (bdd.evaluate(domain, assignment)) {
                    assertEquals(mt.evaluate(function, assignment), mt.evaluate(simplified, assignment));
                }
            }
            mt.dereference(simplified);
        }
        assertTrue(bdd.check());
        assertTrue(mt.check());
    }
}
