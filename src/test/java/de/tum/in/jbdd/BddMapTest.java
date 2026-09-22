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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.Sets;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.CouplingBetweenObjects")
class BddMapTest {
    @Test
    void testBddSetBasics() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        BddSetFactory sets = ctx.bddSets();
        BddSet x0 = sets.var(0);
        BddSet x1 = sets.var(1);

        assertTrue(sets.empty().isEmpty());
        assertTrue(sets.universe().isUniverse());
        assertFalse(x0.isEmpty());
        assertFalse(x0.isUniverse());
        assertEquals(BitSets.of(0), x0.support());

        BddSet both = x0.intersection(x1);
        BddSet either = x0.union(x1);
        assertTrue(both.subsetOf(x0));
        assertTrue(both.subsetOf(either));
        assertTrue(x0.subsetOf(either));
        assertFalse(either.subsetOf(x0));

        assertTrue(both.contains(BitSets.of(0, 1)));
        assertFalse(both.contains(BitSets.of(0)));
        assertTrue(either.contains(BitSets.of(0)));
        assertFalse(either.contains(BitSets.of()));

        assertEquals(either, x0.complement().intersection(x1.complement()).complement());
        assertTrue(x0.intersects(x1));
        assertFalse(x0.intersection(x1.complement()).intersects(x1));
    }

    /** Every valuation of the first {@code variables} variables. */
    private static List<BitSet> valuations(int variables) {
        List<BitSet> valuations = new java.util.ArrayList<>();
        for (long bits = 0; bits < 1L << variables; bits++) {
            valuations.add(BitSet.valueOf(new long[] {bits}));
        }
        return valuations;
    }

    @Test
    void testFusedSplitAgreesWithSplitThenRelabel() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        BddSetFactory sets = ctx.bddSets();
        Values<String> strings = ctx.bddMaps().create();
        BddMap<String> map = strings.of("c")
                .update(sets.var(0).intersection(sets.var(2)), "a")
                .update(sets.var(1).intersection(sets.var(3)), "b");
        BitSet splitVariables = BitSets.of(0, 1);

        Values<List<String>> fused = ctx.bddMaps().create();
        BddMap<List<String>> direct = map.splitMap(
                splitVariables, fused, residual -> List.copyOf(new java.util.TreeSet<>(residual.values())));
        BddMap<BddMap<String>> meta = map.split(splitVariables, ctx.bddMaps().create());
        for (BitSet valuation : valuations(4)) {
            BddMap<String> residual = meta.evaluate(valuation);
            assertEquals(List.copyOf(new java.util.TreeSet<>(residual.values())), direct.evaluate(valuation));
            assertEquals(map.evaluate(valuation), residual.evaluate(valuation));
        }
    }

    @Test
    void testVariableIfThenElseAgreesWithSetCondition() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        BddSetFactory sets = ctx.bddSets();
        Values<String> strings = ctx.bddMaps().create();
        BddMap<String> then = strings.of("t").update(sets.var(2), "t2");
        BddMap<String> otherwise = strings.of("o").update(sets.var(1).intersection(sets.var(3)), "o2");

        // Variable 0 comes before both - a single node - and variable 2 does not - the general construction.
        for (int variable : new int[] {0, 2, 4}) {
            BddMap<String> expected = strings.ifThenElse(sets.var(variable), then, otherwise);
            assertSame(expected, strings.ifThenElse(variable, then, otherwise));
        }
    }

    @Test
    void testNaryApplyAgreesWithBinaryFold() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        BddSetFactory sets = ctx.bddSets();
        Values<String> strings = ctx.bddMaps().create();
        List<BddMap<String>> maps = List.of(
                strings.of("").update(sets.var(0), "a"),
                strings.of("").update(sets.var(1).intersection(sets.var(0).complement()), "b"),
                strings.of("c").update(sets.var(2), ""),
                strings.of("d").update(sets.var(0).intersection(sets.var(3)), "e"));

        List<BddMapBinaryOperator<String>> operators = List.of(
                BddMapBinaryOperator.of(String::concat),
                // Lexicographic maximum, whose neutral value is the empty string - monoid claims commutativity.
                BddMapBinaryOperator.monoid((left, right) -> left.compareTo(right) >= 0 ? left : right, ""),
                BddMapBinaryOperator.commutative((left, right) -> left.compareTo(right) >= 0 ? left : right));
        for (BddMapBinaryOperator<String> operator : operators) {
            for (int count = 1; count <= maps.size(); count++) {
                List<BddMap<String>> operands = maps.subList(0, count);
                BddMap<String> folded = operands.get(0);
                for (BddMap<String> operand : operands.subList(1, count)) {
                    folded = folded.apply(operand, operator);
                }
                assertSame(folded, strings.apply(operands, operator));
            }
        }
        assertEquals(
                strings.of(""),
                strings.apply(List.of(), BddMapBinaryOperator.monoid((left, right) -> left + right, "")));
        assertThrows(
                IllegalArgumentException.class,
                () -> strings.apply(List.of(), BddMapBinaryOperator.of(String::concat)));
    }

    @Test
    void testStringMapBasics() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        Values<String> strings = ctx.bddMaps().create();
        BddSet x0 = ctx.bddSets().var(0);

        BddMap<String> constant = strings.of("lo");
        assertTrue(constant.isConstant());
        assertEquals(Set.of("lo"), constant.values());
        assertEquals("lo", constant.evaluate(BitSets.of()));

        BddMap<String> updated = constant.update(x0, "hi-there");
        assertFalse(updated.isConstant());
        assertEquals(Set.of("lo", "hi-there"), updated.values());
        assertEquals(BitSets.of(0), updated.support());

        assertEquals("hi-there", updated.evaluate(BitSets.of(0)));
        assertEquals("lo", updated.evaluate(BitSets.of()));

        assertEquals(x0, updated.domainOf("hi-there"));
        assertTrue(updated.domainOf("nonexistent").isEmpty());

        // only "hi-there" (8 chars) qualifies, not "lo" (2 chars)
        assertEquals(x0, updated.where(s -> s.length() > 3));
    }

    @Test
    void testStringMapApplyAndMap() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        Values<String> strings = ctx.bddMaps().create();
        BddSet x0 = ctx.bddSets().var(0);

        BddMap<String> updated = strings.of("lo").update(x0, "hi-there");

        BddMap<String> upper = updated.map(String::toUpperCase);
        assertEquals("LO", upper.evaluate(BitSets.of()));
        assertEquals("HI-THERE", upper.evaluate(BitSets.of(0)));

        BddMap<String> combined = updated.apply(upper, (a, b) -> String.format("%s/%s", a, b));
        assertEquals("lo/LO", combined.evaluate(BitSets.of()));
        assertEquals("hi-there/HI-THERE", combined.evaluate(BitSets.of(0)));

        // monoid-aware apply must agree with plain apply - "" is a genuine neutral element for concatenation
        BddMap<String> empty = strings.of("");
        BddMapBinaryOperator<String> concatMonoid = BddMapBinaryOperator.monoid(String::concat, "");
        BddMap<String> viaMonoid = updated.apply(empty, concatMonoid);
        assertEquals(updated.evaluate(BitSets.of()), viaMonoid.evaluate(BitSets.of()));
        assertEquals(updated.evaluate(BitSets.of(0)), viaMonoid.evaluate(BitSets.of(0)));
    }

    @Test
    void testStringMapAgreementAndDifference() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        Values<String> strings = ctx.bddMaps().create();
        BddSet x0 = ctx.bddSets().var(0);
        BddSet x1 = ctx.bddSets().var(1);

        // left: x0 ? "hi-there" : "lo"
        // right: x1 ? "hi-there" : "lo"
        // agree exactly when x0 == x1 (both "lo" or both "hi-there")
        BddMap<String> left = strings.of("lo").update(x0, "hi-there");
        BddMap<String> right = strings.of("lo").update(x1, "hi-there");

        BddSet agree = left.agreement(right);
        assertTrue(agree.contains(BitSets.of()));
        assertTrue(agree.contains(BitSets.of(0, 1)));
        assertFalse(agree.contains(BitSets.of(0)));
        assertFalse(agree.contains(BitSets.of(1)));

        BddSet diff = left.difference(right);
        assertEquals(agree.complement(), diff);
        assertFalse(diff.contains(BitSets.of()));
        assertFalse(diff.contains(BitSets.of(0, 1)));
        assertTrue(diff.contains(BitSets.of(0)));
        assertTrue(diff.contains(BitSets.of(1)));
    }

    @Test
    void testStringMapRelabelVariables() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        Values<String> strings = ctx.bddMaps().create();
        BddSet x0 = ctx.bddSets().var(0);

        BddMap<String> updated = strings.of("lo").update(x0, "hi-there");
        BddMap<String> renamed = updated.relabelVariables(i -> i == 0 ? 2 : i);

        assertEquals(BitSets.of(2), renamed.support());
        assertEquals("hi-there", renamed.evaluate(BitSets.of(2)));
        assertEquals("lo", renamed.evaluate(BitSets.of()));
        // no longer depends on x0 at all
        assertEquals("lo", renamed.evaluate(BitSets.of(0)));

        // a negative target is rejected, not silently misinterpreted
        assertThrows(IllegalArgumentException.class, () -> updated.relabelVariables(i -> -1));
    }

    @Test
    void testSetOfStringMapBasics() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        Values<Set<String>> setMaps = ctx.bddMaps().create();
        BddSet x0 = ctx.bddSets().var(0);

        Set<String> a = Set.of("a");
        Set<String> ab = Set.of("a", "b");

        BddMap<Set<String>> m = setMaps.of(a).update(x0, ab);
        assertEquals(a, m.evaluate(BitSets.of()));
        assertEquals(ab, m.evaluate(BitSets.of(0)));
        assertEquals(Set.of(a, ab), m.values());
        assertEquals(x0, m.where(s -> s.size() > 1));

        BddMap<Set<String>> union = m.map(s -> {
            Set<String> withC = new HashSet<>(s);
            withC.add("c");
            return Set.copyOf(withC);
        });
        assertEquals(Set.of("a", "c"), union.evaluate(BitSets.of()));
        assertEquals(Set.of("a", "b", "c"), union.evaluate(BitSets.of(0)));
    }

    @Test
    void testCreateRelabelingStringToSetOfString() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        Values<String> strings = ctx.bddMaps().create();
        BddSet x0 = ctx.bddSets().var(0);
        BddSet x1 = ctx.bddSets().var(1);

        BddMap<String> original = strings.of("lo").update(x0, "hi-there").update(x1, "greetings");

        // x -> {x} is trivially injective: distinct strings give distinct singleton sets.
        BddMap.Relabeler<String, Set<String>> relabeler = strings.createRelabeling(Set::of);
        BddMap<Set<String>> relabeled = relabeler.relabel(original);
        assertSame(relabeled.valueDomain(), relabeler.into());

        // the relabeled map matches Set.of(original value) at every corner of the (x0,x1) domain.
        for (BitSet assignment : List.of(new BitSet(), BitSets.of(0), BitSets.of(1), BitSets.of(0, 1))) {
            assertEquals(Set.of(original.evaluate(assignment)), relabeled.evaluate(assignment));
        }
        assertEquals(Set.of(Set.of("lo"), Set.of("hi-there"), Set.of("greetings")), relabeled.values());

        // the original map must still work correctly after being relabeled - relabeling must not have
        // consumed or corrupted it.
        assertEquals("lo", original.evaluate(BitSets.of()));
        assertEquals("hi-there", original.evaluate(BitSets.of(0)));
        assertEquals("greetings", original.evaluate(BitSets.of(1)));
        // "hi-there" only survives where x0 holds AND the later x1 update didn't overwrite it.
        assertEquals(x0.difference(x1), original.domainOf("hi-there"));

        // the relabeled map supports its own independent operations too.
        BddSet multiWord = relabeled.where(s -> s.iterator().next().contains("-"));
        assertEquals(x0.difference(x1), multiWord);
    }

    @Test
    void testRelabelIntoAcrossDomains() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        Values<Set<String>> setMaps = ctx.bddMaps().create();
        Values<String> strings = ctx.bddMaps().create();
        BddSet x0 = ctx.bddSets().var(0);
        BddSet x1 = ctx.bddSets().var(1);

        // an existing numbering that already has its own maps in it.
        BddMap<Set<String>> existing = setMaps.of(Set.of("seed")).update(x0, Set.of("seed", "extra"));

        BddMap<String> toInject = strings.of("lo").update(x1, "hi-there");
        BddMap<Set<String>> injected = setMaps.relabelInto(toInject, Set::of);
        assertSame(injected.valueDomain(), existing.valueDomain());

        assertEquals(Set.of("lo"), injected.evaluate(BitSets.of()));
        assertEquals(Set.of("hi-there"), injected.evaluate(BitSets.of(1)));

        // since it landed in the SAME numbering as `existing`, it can be combined with it directly.
        //noinspection RedundantTypeArguments
        BddMap<Set<String>> combined = existing.apply(injected, (a, b) -> Set.copyOf(Sets.<String>union(a, b)));
        assertEquals(Set.of("seed", "lo"), combined.evaluate(BitSets.of()));
        assertEquals(Set.of("seed", "extra", "lo"), combined.evaluate(BitSets.of(0)));
        assertEquals(Set.of("seed", "hi-there"), combined.evaluate(BitSets.of(1)));
        assertEquals(Set.of("seed", "extra", "hi-there"), combined.evaluate(BitSets.of(0, 1)));

        // the original (pre-injection) String map still works fine too.
        assertEquals("lo", toInject.evaluate(BitSets.of()));
        assertEquals("hi-there", toInject.evaluate(BitSets.of(1)));
    }

    @Test
    void testCartesianProductOfStringMaps() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        Values<String> strings = ctx.bddMaps().create();
        BddSet x0 = ctx.bddSets().var(0);

        BddMap<String> a = strings.of("a0").update(x0, "a1");
        BddMap<String> b = strings.of("b0").update(x0, "b1");

        Values<List<String>> lists = ctx.bddMaps().create();
        BddMap<List<String>> product = strings.cartesianProduct(List.of(a, b), lists);

        // Distinct tuples stay distinct - the traversal fills one buffer, so what lands in the numbering
        // has to be a copy of it rather than the buffer itself.
        assertEquals(Set.of(List.of("a0", "b0"), List.of("a1", "b1")), product.values());
        assertEquals(List.of("a0", "b0"), product.evaluate(BitSets.of()));
        assertEquals(List.of("a1", "b1"), product.evaluate(BitSets.of(0)));
    }

    @Test
    void testMapsOverDifferentValuesStayDistinct() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        BddMapFactory maps = ctx.bddMaps();
        Values<String> first = maps.create();
        Values<String> second = maps.create();

        // Both numberings hand out raw index 0 first, so these two maps are the very same MTBDD terminal
        // and are told apart only by the numbering they carry.
        BddMap<String> a = first.of("a");
        BddMap<String> b = second.of("b");
        assertEquals(((GcReferenceManager.DdContainer) a).function(), ((GcReferenceManager.DdContainer) b).function());

        assertNotSame(a, b);
        assertNotEquals(a, b);
        assertEquals("a", a.evaluate(BitSets.of()));
        assertEquals("b", b.evaluate(BitSets.of()));
        assertSame(first, a.valueDomain());
        assertSame(second, b.valueDomain());

        // within one numbering, a map is still canonical
        assertSame(a, first.of("a"));
        assertSame(b, second.of("b"));
    }

    @Test
    void testAdoptBridgesTwoNumberings() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        Values<String> first = ctx.bddMaps().create();
        Values<String> second = ctx.bddMaps().create();
        BddSet x0 = ctx.bddSets().var(0);
        BddSet x1 = ctx.bddSets().var(1);

        BddMap<String> a = first.of("lo").update(x0, "hi-there");
        BddMap<String> b = second.of("lo").update(x1, "hi-there");

        BddMap<String> adopted = first.adopt(b);
        assertSame(first, adopted.valueDomain());
        for (BitSet assignment : List.of(new BitSet(), BitSets.of(0), BitSets.of(1), BitSets.of(0, 1))) {
            assertEquals(b.evaluate(assignment), adopted.evaluate(assignment));
        }

        // and now the operations that rejected `b` work
        assertEquals(x0.symmetricDifference(x1).complement(), a.agreement(adopted));
        assertEquals("lo/lo", a.apply(adopted, (l, r) -> l + "/" + r).evaluate(BitSets.of()));

        // adopting into the map's own numbering is the identity, down to the function id
        assertSame(b, second.adopt(b));
        // the two numberings assigned "lo" and "hi-there" the same indices, so the shape carries over
        // untouched and the adopted map is the very same MTBDD function
        assertEquals(
                ((GcReferenceManager.DdContainer) b).function(), ((GcReferenceManager.DdContainer) adopted).function());

        // b itself is untouched
        assertSame(second, b.valueDomain());
        assertEquals("hi-there", b.evaluate(BitSets.of(1)));
    }

    @Test
    void testCombiningMapsAcrossValuesIsRejected() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        Values<String> first = ctx.bddMaps().create();
        Values<String> second = ctx.bddMaps().create();
        BddSet x0 = ctx.bddSets().var(0);

        BddMap<String> a = first.of("lo").update(x0, "hi-there");
        BddMap<String> b = second.of("lo").update(x0, "hi-there");

        // agreement is deliberately not in this list any more - see testWhereWorksAcrossNumberings.
        assertThrows(AssertionError.class, () -> a.apply(b, String::concat));
        assertThrows(AssertionError.class, () -> a.apply(b, BddMapBinaryOperator.monoid(String::concat, "")));
        assertThrows(AssertionError.class, () -> first.ifThenElse(x0, a, b));
    }

    /** Every valuation of variables 0..1, which is enough to pin a map built over them. */
    private static List<BitSet> valuations() {
        return List.of(new BitSet(), BitSets.of(0), BitSets.of(1), BitSets.of(0, 1));
    }

    @Test
    void testWhereWorksAcrossNumberings() {
        /* The whole point of routing agreement through where: two numberings assign their own indices
         * from zero, so the raw terminals are incomparable and the comparison has to resolve each side
         * through its own numbering. Nothing has to be adopted first. */
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        Values<String> first = ctx.bddMaps().create();
        Values<String> second = ctx.bddMaps().create();
        BddSet x0 = ctx.bddSets().var(0);
        BddSet x1 = ctx.bddSets().var(1);

        // "lo" is index 0 in first and index 1 in second, so raw equality would get this wrong.
        BddMap<String> a = first.of("lo").update(x0, "hi");
        BddMap<String> b = second.of("hi").update(x1, "lo");

        BddSet agree = a.agreement(b);
        for (BitSet assignment : valuations()) {
            assertEquals(
                    a.evaluate(assignment).equals(b.evaluate(assignment)),
                    agree.contains(assignment),
                    "disagreed at " + assignment);
        }
        assertEquals(agree.complement(), a.difference(b));
    }

    @Test
    void testWhereComparesDifferentValueTypes() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        Values<String> words = ctx.bddMaps().create();
        Values<Integer> lengths = ctx.bddMaps().create();
        BddSet x0 = ctx.bddSets().var(0);
        BddSet x1 = ctx.bddSets().var(1);

        BddMap<String> word = words.of("lo").update(x0, "there");
        BddMap<Integer> length = lengths.of(2).update(x1, 5);

        BddSet matches = word.where(length, (w, l) -> w.length() == l);
        for (BitSet assignment : valuations()) {
            assertEquals(
                    word.evaluate(assignment).length() == length.evaluate(assignment),
                    matches.contains(assignment),
                    "disagreed at " + assignment);
        }
    }

    @Test
    void testWhereExploitsDeclaredProperties() {
        // A declared property may only change the work done, never the answer.
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        Values<Integer> numbers = ctx.bddMaps().create();
        BddSet x0 = ctx.bddSets().var(0);
        BddSet x1 = ctx.bddSets().var(1);

        BddMap<Integer> a = numbers.of(1).update(x0, 4);
        BddMap<Integer> b = numbers.of(4).update(x1, 6);

        BiPredicate<Integer, Integer> sameParity = (l, r) -> (l % 2) == (r % 2);
        BddSet plain = a.where(b, sameParity);
        BddSet declared = a.where(b, BddMapBinaryPredicate.equivalence(sameParity));

        assertEquals(plain, declared);
        for (BitSet assignment : valuations()) {
            assertEquals(
                    (a.evaluate(assignment) % 2) == (b.evaluate(assignment) % 2),
                    plain.contains(assignment),
                    "disagreed at " + assignment);
        }
    }

    @Test
    void testWhereChecksTheClaimedProperties() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        Values<Integer> numbers = ctx.bddMaps().create();
        BddSet x0 = ctx.bddSets().var(0);

        BddMap<Integer> a = numbers.of(1).update(x0, 2);
        BddMap<Integer> b = numbers.of(2).update(x0, 1);

        // "less than" is neither, and claiming either has to be caught rather than silently believed.
        assertThrows(AssertionError.class, () -> a.where(b, BddMapBinaryPredicate.reflexive((l, r) -> l < r)));
        assertThrows(AssertionError.class, () -> a.where(b, BddMapBinaryPredicate.symmetric((l, r) -> l < r)));
    }

    @Test
    void testPredicateOverASupertypeIsAccepted() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        Values<String> strings = ctx.bddMaps().create();
        BddSet x0 = ctx.bddSets().var(0);

        BddMap<String> a = strings.of("lo").update(x0, "hi");
        BddMap<String> b = strings.of("lo").update(x0, "lo");

        // Declared over Object, used on a map of Strings.
        BddMapBinaryPredicate<Object> sameObject = BddMapBinaryPredicate.equivalence(Object::equals);
        BddSet agree = a.where(b, sameObject);
        assertTrue(agree.contains(BitSets.of()));
        assertFalse(agree.contains(BitSets.of(0)));
        assertEquals(a.agreement(b), agree);
    }

    @Test
    void testInverse() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        BddSetFactory sets = ctx.bddSets();
        BddSet x0 = sets.var(0);
        BddSet x1 = sets.var(1);
        Values<String> values = ctx.bddMaps().create();

        BddMap<String> map = values.of("a").update(x0, "b").update(x0.intersection(x1), "c");
        Map<String, BddSet> inverse = map.inverse();
        assertEquals(map.values(), inverse.keySet());
        for (String value : map.values()) {
            assertEquals(map.domainOf(value), inverse.get(value));
        }
        assertEquals(Map.of("a", sets.universe()), values.of("a").inverse());
    }

    @Test
    void testShannonDecomposition() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        BddSetFactory sets = ctx.bddSets();
        BddSet x0 = sets.var(0);
        BddSet x1 = sets.var(1);
        Values<String> values = ctx.bddMaps().create();

        BddMap<String> map = values.ifThenElse(x0, values.of("c").update(x1, "a"), values.of("b"));
        assertEquals(OptionalInt.of(0), map.decisionVariable());
        assertEquals(values.of("c").update(x1, "a"), map.high());
        assertEquals(values.of("b"), map.low());
        assertEquals(OptionalInt.of(1), map.high().decisionVariable());
        assertEquals(OptionalInt.empty(), map.low().decisionVariable());
        assertThrows(IllegalStateException.class, () -> map.low().high());
    }

    @Test
    void testAgreementAcrossNumberings() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        BddSetFactory sets = ctx.bddSets();
        BddSet x0 = sets.var(0);
        BddSet x1 = sets.var(1);
        Values<String> first = ctx.bddMaps().create();
        Values<String> second = ctx.bddMaps().create();
        // Numbered in a different order, so the raw terminals differ.
        second.of("b");

        BddMap<String> left = first.of("a").update(x0, "b");
        BddMap<String> right = second.of("a").update(x0, "b");
        assertNotEquals(left, right);
        assertTrue(left.agreesWith(right));
        assertTrue(right.agreesWith(left));
        assertTrue(left.agreesWith(first.of("a").update(x0, "b")));

        BddMap<String> differing = second.of("a").update(x0.intersection(x1), "b");
        assertFalse(left.agreesWith(differing));
        assertFalse(left.agreesWith(first.of("a").update(x0.intersection(x1), "b")));

        // A predicate across value types: the string is never longer than the number says.
        Values<Integer> lengths = ctx.bddMaps().create();
        BddMap<Integer> bound = lengths.of(1);
        assertTrue(left.allMatch(bound, (value, length) -> value.length() <= length));
        assertFalse(left.update(x1, "bb").allMatch(bound, (value, length) -> value.length() <= length));
        assertTrue(left.allMatch(left, BddMapBinaryPredicate.reflexive(String::equals)));
    }
}
