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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The object layer's registered handles ({@link RegisteredOperation}): that each one answers exactly like
 * the operation it stands for, however much bookkeeping it owns, and keeps doing so once its own cache is
 * warm.
 */
class RegisteredOperationsTest {
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
    void testRegistrationsAgreeWithThePlainOperations() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        BddSetFactory sets = ctx.bddSets();
        Values<Integer> numbers = ctx.bddMaps().create();
        Values<String> texts = ctx.bddMaps().create();
        BddSet x0 = sets.var(0);
        BddSet x1 = sets.var(1);

        BddMap<Integer> a = numbers.of(0).update(x0, 1);
        BddMap<Integer> b = numbers.of(0).update(x1, 2);

        // Every registration has to answer exactly like the operation it stands for, however much
        // bookkeeping it owns - and has to keep doing so once its own cache is warm.
        BddMap.Mapper<Integer, String> mapper = numbers.registerMap(Object::toString, texts);
        assertSame(a.map(Object::toString, texts), mapper.apply(a));
        assertSame(a.map(Object::toString, texts), mapper.apply(a));

        BddMap.Combiner<Integer, Integer, String> combiner =
                numbers.registerCombine(numbers, (Integer l, Integer r) -> l + "/" + r, texts);
        assertSame(a.apply(b, (l, r) -> l + "/" + r, texts), combiner.apply(a, b));
        assertSame(a.apply(b, (l, r) -> l + "/" + r, texts), combiner.apply(a, b));

        BddMap.Selector<Integer> selector = numbers.registerWhere(value -> value > 0);
        assertEquals(a.where(value -> value > 0), selector.apply(a));
        assertEquals(a.where(value -> value > 0), selector.apply(a));

        BddMap.Relation<Integer> equal = numbers.registerWhere(BddMapBinaryPredicate.equality());
        assertEquals(a.where(b, BddMapBinaryPredicate.equality()), equal.apply(a, b));
        BddMap.Relation<Integer> below = numbers.registerWhere(BddMapBinaryPredicate.of((l, r) -> l < r));
        assertEquals(a.where(b, BddMapBinaryPredicate.of((Integer l, Integer r) -> l < r)), below.apply(a, b));
        assertEquals(a.where(b, BddMapBinaryPredicate.of((Integer l, Integer r) -> l < r)), below.apply(a, b));

        mapper.release();
        combiner.release();
        selector.release();
        below.release();

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
        BddSet.Quantifier exists = sets.registerExists(quantified);
        assertEquals(x0.intersection(x1).exists(quantified), exists.apply(x0.intersection(x1)));
        assertEquals(x0.intersection(x1).exists(quantified), exists.apply(x0.intersection(x1)));
        exists.release();

        /* A handle is built before it sees a set, so it declares the variables it replaces instead of
         * spelling out a mapping over every variable there happens to be - the rest are left alone. */
        BitSet replaced = BitSets.of(0);
        BddSet.VariableReplacer relabeler = sets.registerRelabelVariables(replaced, i -> i + 1);
        assertEquals(x0.relabelVariables(i -> i + 1), relabeler.apply(x0));
        assertEquals(x0.relabelVariables(i -> i + 1), relabeler.apply(x0));

        BddSet.VariableReplacer setReplacer = sets.registerReplaceVariables(replaced, i -> x1);
        assertEquals(x0.replaceVariables(i -> i == 0 ? x1 : sets.var(i)), setReplacer.apply(x0));
        // A variable the handle does not declare is left alone, whatever the argument's support is.
        BddSet x2 = sets.var(2);
        assertEquals(x1.intersection(x2), setReplacer.apply(x0.intersection(x2)));
        relabeler.release();
        setReplacer.release();
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

    @Test
    void testRegistrationsThatReplaceNothingAreTheSharedIdentity() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        Bdd bdd = ctx.bdd();
        BddSetFactory sets = ctx.bddSets();
        bdd.createVariables(3);

        // Nothing to replace: an empty mapping, and one that sends every variable to itself.
        assertSame(RegisteredOperation.identity(), bdd.registerCompose(new int[0]));
        assertSame(
                RegisteredOperation.identity(),
                bdd.registerCompose(new int[] {bdd.variableFunction(0), bdd.variableFunction(1)}));
        assertSame(RegisteredOperation.identity(), bdd.registerExists(new BitSet()));
        assertSame(RegisteredOperation.identity(), ctx.mtBdd().registerCompose(new int[0]));

        // A real replacement must not collapse to it.
        assertNotSame(RegisteredOperation.identity(), bdd.registerCompose(new int[] {bdd.trueFunction()}));

        // The identity still has to behave like one.
        int function = bdd.and(bdd.variableFunction(0), bdd.variableFunction(1));
        assertEquals(function, RegisteredOperation.identity().applyAsInt(function));

        // And the object layer passes the recognition through, rather than wrapping a no-op call.
        BitSet none = new BitSet();
        assertSame(BddSet.VariableReplacer.identity(), sets.registerRelabelVariables(none, variable -> variable));
        assertSame(
                BddSet.VariableReplacer.identity(),
                sets.registerRelabelVariables(BitSets.of(0, 1), variable -> variable));
        assertNotSame(BddSet.VariableReplacer.identity(), sets.registerRelabelVariables(BitSets.of(0), variable -> 2));

        BddSet set = sets.var(0).intersection(sets.var(1));
        assertSame(set, BddSet.VariableReplacer.identity().apply(set));
    }
}
