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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The operations that are about the numbering rather than about one map: where a result lands
 * ({@link BddMap#split}, {@link Values#cartesianProductMap}) and how one is re-typed
 * ({@link Values#createRelabeling}).
 */
class ValuesTest {
    @Test
    void testSplitIntoResiduals() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        Values<String> strings = ctx.bddMaps().create();
        BddSet x0 = ctx.bddSets().var(0);
        BddSet x1 = ctx.bddSets().var(1);

        BddMap<String> map = strings.of("00")
                .update(x0.intersection(x1.complement()), "10")
                .update(x0.complement().intersection(x1), "01")
                .update(x0.intersection(x1), "11");

        Values<BddMap<String>> residuals = ctx.bddMaps().create();
        BddMap<BddMap<String>> meta = map.split(BitSets.of(0), residuals);

        // The meta-map decides on variable 0 only; each of its values is the residual over variable 1.
        assertEquals(BitSets.of(0), meta.support());
        assertEquals(2, meta.values().size());
        assertEquals("00", meta.evaluate(BitSets.of()).evaluate(BitSets.of()));
        assertEquals("01", meta.evaluate(BitSets.of()).evaluate(BitSets.of(1)));
        assertEquals("10", meta.evaluate(BitSets.of(0)).evaluate(BitSets.of()));
        assertEquals("11", meta.evaluate(BitSets.of(0)).evaluate(BitSets.of(1)));
    }

    @Test
    void testCartesianProductCanMergeTuples() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        Values<String> strings = ctx.bddMaps().create();
        BddSet x0 = ctx.bddSets().var(0);

        BddMap<String> a = strings.of("a0").update(x0, "a1");
        BddMap<String> b = strings.of("b0").update(x0, "b1");

        // The tuples are folded on the way in, so no numbering over List<String> ever exists.
        Values<String> joined = ctx.bddMaps().create();
        BddMap<String> product = strings.cartesianProductMap(List.of(a, b), joined, tuple -> String.join("+", tuple));

        assertSame(joined, product.valueDomain());
        assertEquals("a0+b0", product.evaluate(BitSets.of()));
        assertEquals("a1+b1", product.evaluate(BitSets.of(0)));
    }

    @Test
    void testRelabelingToASupertype() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        Values<String> strings = ctx.bddMaps().create();
        BddSet x0 = ctx.bddSets().var(0);

        BddMap<String> map = strings.of("lo").update(x0, "hi");

        /* BddMap is invariant, so a BddMap<String> is not a BddMap<CharSequence>. A cast is injective,
         * hence the relabeling that bridges the two copies the numbering verbatim rather than traversing
         * anything, and every map's function id carries over unchanged. */
        BddMap.Relabeler<String, CharSequence> toCharSequences = strings.createRelabeling(CharSequence.class::cast);
        BddMap<CharSequence> retyped = toCharSequences.relabel(map);

        assertSame(toCharSequences.into(), retyped.valueDomain());
        assertNotSame(strings, retyped.valueDomain());
        assertEquals(
                ((GcReferenceManager.DdContainer) map).function(),
                ((GcReferenceManager.DdContainer) retyped).function());
        assertEquals("lo", retyped.evaluate(BitSets.of()));
        assertEquals("hi", retyped.evaluate(BitSets.of(0)));

        // The two numberings are distinct, so this is a cross-numbering comparison - which is fine.
        assertEquals(ctx.bddSets().universe(), map.where(retyped, String::contentEquals));
    }

    @Test
    void testRegisteredApplyAgreesWithThePlainOne() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        Values<Integer> numbers = ctx.bddMaps().create();
        BddSet x0 = ctx.bddSets().var(0);
        BddSet x1 = ctx.bddSets().var(1);

        BddMap<Integer> a = numbers.of(0).update(x0, 1);
        BddMap<Integer> b = numbers.of(0).update(x1, 2);

        // A monoid, so the registered operation resolves - and pins - the neutral value's terminal.
        BddMapBinaryOperator<Integer> sum = BddMapBinaryOperator.monoid(Integer::sum, 0);
        BddMap.Operator<Integer> registered = numbers.registerApply(sum);

        // Maps are canonical per (function, numbering), so agreeing means being the very same object.
        assertSame(a.apply(b, sum), registered.apply(a, b));
        assertSame(b.apply(a, sum), registered.apply(b, a));
        assertEquals(3, registered.apply(a, b).evaluate(BitSets.of(0, 1)));
        assertEquals(1, registered.apply(a, b).evaluate(BitSets.of(0)));
        assertEquals(0, registered.apply(a, b).evaluate(BitSets.of()));

        // Repeated use is the point of registering; it has to keep answering the same.
        for (int i = 0; i < 100; i++) {
            assertSame(a.apply(b, sum), registered.apply(a, b));
        }

        // Being a BinaryOperator is what makes it usable where the JDK wants one.
        assertSame(a.apply(b, sum), Stream.of(a, b).reduce(numbers.of(0), registered));
        registered.release();
    }

    @Test
    void testFacadeRegistrationsAgreeWithThePlainOperations() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        BddSetFactory sets = ctx.bddSets();
        Values<Integer> numbers = ctx.bddMaps().create();
        Values<String> texts = ctx.bddMaps().create();
        BddSet x0 = sets.var(0);
        BddSet x1 = sets.var(1);

        BddMap<Integer> a = numbers.of(0).update(x0, 1);
        BddMap<Integer> b = numbers.of(0).update(x1, 2);

        // Every registration that is still a facade has to answer exactly like the operation it forwards
        // to - which is what keeps the switch to a cache-owning implementation a non-event.
        assertSame(
                a.map(Object::toString, texts),
                numbers.registerMap(Object::toString, texts).apply(a));
        assertSame(
                a.apply(b, (l, r) -> l + "/" + r, texts),
                numbers.registerCombine(numbers, (Integer l, Integer r) -> l + "/" + r, texts)
                        .apply(a, b));
        assertEquals(
                a.where(value -> value > 0),
                numbers.registerWhere(value -> value > 0).apply(a));
        assertEquals(
                a.where(b, BddMapBinaryPredicate.equality()),
                numbers.registerWhere(BddMapBinaryPredicate.<Integer>equality()).apply(a, b));

        BddSet[] mapping = {x1, null};
        BddMap.Operator<Integer> sum = numbers.registerApply(BddMapBinaryOperator.monoid(Integer::sum, 0));
        BddMap.VariableReplacer replacer = ctx.bddMaps().registerReplaceVariables(mapping);
        /* The domain-carrying forms are pinned to their contract rather than to the facade's exact output:
         * "agrees on the domain, unspecified elsewhere" leaves a real implementation free to answer
         * differently outside it, and this assertion has to survive that switch. */
        assertTrue(a.apply(b, Integer::sum).agreement(sum.applyIn(a, b, x0)).containsAll(x0));
        assertTrue(
                a.replaceVariables(mapping).agreement(replacer.replaceIn(a, x0)).containsAll(x0));

        BitSet quantified = BitSets.of(0);
        assertEquals(
                x0.intersection(x1).exists(quantified),
                sets.registerExists(quantified).apply(x0.intersection(x1)));
        assertEquals(
                x0.relabelVariables(i -> i + 1),
                sets.registerRelabelVariables(i -> i + 1).apply(x0));
        assertEquals(
                x0.replaceVariables(i -> i == 0 ? x1 : null),
                sets.registerReplaceVariables(i -> i == 0 ? x1 : null).apply(x0));
    }

    @Test
    void testRegisteredReplacerServesEveryNumbering() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        BddSetFactory sets = ctx.bddSets();
        Values<String> strings = ctx.bddMaps().create();
        Values<Integer> numbers = ctx.bddMaps().create();
        BddSet x0 = sets.var(0);
        BddSet x1 = sets.var(1);

        // x0 := x1, everything else untouched.
        BddSet[] mapping = {x1, null};
        BddMap.VariableReplacer replacer = ctx.bddMaps().registerReplaceVariables(mapping);

        BddMap<String> text = strings.of("lo").update(x0, "hi");
        BddMap<Integer> count = numbers.of(0).update(x0.intersection(x1), 1);

        // Terminals are never looked at, so one replacer serves both numberings and each result stays
        // over the numbering it came from.
        assertSame(text.replaceVariables(mapping), replacer.replace(text));
        assertSame(count.replaceVariables(mapping), replacer.replace(count));
        assertSame(strings, replacer.replace(text).valueDomain());
        assertSame(numbers, replacer.replace(count).valueDomain());

        assertEquals("hi", replacer.replace(text).evaluate(BitSets.of(1)));
        assertEquals("lo", replacer.replace(text).evaluate(BitSets.of(0)));
        assertEquals(1, replacer.replace(count).evaluate(BitSets.of(1)));
        replacer.release();
    }
}
