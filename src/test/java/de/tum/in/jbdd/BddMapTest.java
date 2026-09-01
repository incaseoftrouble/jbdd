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
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

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
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> updated.relabelVariables(i -> -1));
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
            Set<String> withC = new java.util.HashSet<>(s);
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

        assertThrows(AssertionError.class, () -> a.agreement(b));
        assertThrows(AssertionError.class, () -> a.apply(b, String::concat));
        assertThrows(AssertionError.class, () -> a.apply(b, BddMapBinaryOperator.monoid(String::concat, "")));
        assertThrows(AssertionError.class, () -> first.ifThenElse(x0, a, b));
    }
}
