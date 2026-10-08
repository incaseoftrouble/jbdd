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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.tum.in.jbdd.collections.IntObjectHashMap;
import de.tum.in.jbdd.collections.MutableNatSet;
import de.tum.in.jbdd.collections.NatSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The operations that are about the numbering rather than about one map: where a result lands
 * ({@link BddMap#splitMap}, {@link Values#cartesianProductMap}) and how one is re-typed
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
        BddMap<BddMap<String>> meta = map.split(NatSetFixtures.of(0), residuals);

        // The meta-map decides on variable 0 only; each of its values is the residual over variable 1.
        assertEquals(NatSetFixtures.of(0), meta.support());
        assertEquals(2, meta.values().size());
        assertEquals("00", meta.evaluate(NatSetFixtures.of()).evaluate(NatSetFixtures.of()));
        assertEquals("01", meta.evaluate(NatSetFixtures.of()).evaluate(NatSetFixtures.of(1)));
        assertEquals("10", meta.evaluate(NatSetFixtures.of(0)).evaluate(NatSetFixtures.of()));
        assertEquals("11", meta.evaluate(NatSetFixtures.of(0)).evaluate(NatSetFixtures.of(1)));
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
        assertEquals("a0+b0", product.evaluate(NatSetFixtures.of()));
        assertEquals("a1+b1", product.evaluate(NatSetFixtures.of(0)));
    }

    @Test
    void testResidualProductWithoutOperands() {
        // Nothing replaced: one pair, the function itself with no essential values, everywhere.
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        Values<Integer> numbers = ctx.bddMaps().create();
        BddSet function = ctx.bddSets().var(0).union(ctx.bddSets().var(1));
        Values<String> pairs = ctx.bddMaps().create();
        BddMap<String> product = numbers.residualProduct(
                function,
                new IntObjectHashMap<>(),
                (variable, value) -> PartialValuation.Truth.UNDECIDED,
                pairs,
                (residual, values) -> residual.equals(function) + " " + values.size());
        assertTrue(product.isConstant());
        assertEquals(Set.of("true 0"), product.values());
    }

    @Test
    void testResidualProductOverAnOperatorOfAnotherContext() {
        // residualProduct's documented example, with the function - the operator - in a context of its own.
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        BinaryFactoryContext operatorContext = BinaryFactoryContext.create();
        Values<Integer> numbers = ctx.bddMaps().create();
        BddSet x0 = ctx.bddSets().var(0);
        BddSet x1 = ctx.bddSets().var(1);
        IntObjectHashMap<BddMap<Integer>> operands = new IntObjectHashMap<>();
        operands.put(2, numbers.of(7).update(x0, 0));
        operands.put(3, numbers.of(5).update(x1, 1));
        operands.put(4, numbers.of(0).update(x1, 9));
        BddSetFactory variables = operatorContext.bddSets();
        BddSet function = variables.var(2).intersection(variables.var(3).union(variables.var(4)));
        Values<String> pairs = ctx.bddMaps().create();
        List<BddSet> residuals = new ArrayList<>();
        BddMap<String> product = numbers.residualProduct(
                function,
                operands,
                (variable, value) ->
                        value <= 1 ? PartialValuation.Truth.of(value == 1) : PartialValuation.Truth.UNDECIDED,
                pairs,
                (residual, values) -> {
                    residuals.add(residual);
                    return residual.support() + " " + values.size() + " " + values.get(2) + " " + values.get(3);
                });

        assertSame(pairs, product.valueDomain());
        assertEquals("[] 0 null null", product.evaluate(NatSetFixtures.of(0)));
        assertEquals("[2] 1 7 null", product.evaluate(NatSetFixtures.of(1)));
        assertEquals("[2, 3] 2 7 5", product.evaluate(NatSetFixtures.of()));
        assertEquals(3, residuals.size());
        assertSame(variables, residuals.get(0).factory());
        assertTrue(residuals.contains(variables.var(2).intersection(variables.var(3))));
        // A cube of each pair is an assignment of the operands' context reaching it.
        Map<String, Cube> cubes = product.cubes();
        assertEquals(product.values(), cubes.keySet());
        cubes.forEach((pair, cube) -> assertEquals(pair, product.evaluate(cube.assignment())));
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
        assertEquals("lo", retyped.evaluate(NatSetFixtures.of()));
        assertEquals("hi", retyped.evaluate(NatSetFixtures.of(0)));

        // The two numberings are distinct, so this is a cross-numbering comparison - which is fine.
        assertEquals(ctx.bddSets().universe(), map.where(retyped, String::contentEquals));
    }

    @Test
    void testAdoptFromAnotherContext() {
        BinaryFactoryContext source = BinaryFactoryContext.create();
        BddSet x0 = source.bddSets().var(0);
        BddSet x2 = source.bddSets().var(2);
        BddMap<String> map = source.bddMaps()
                .<String>create()
                .of("none")
                .update(x0, "x0")
                .update(x2, "x2")
                .update(x0.intersection(x2), "both");

        /* Variables shift by one, and each value becomes a set of the target, built by the value mapping itself -
         * which merges "x0" with "x2" and "none" with "both". */
        BinaryFactoryContext target = BinaryFactoryContext.create();
        Values<BddSet> sets = target.bddMaps().create();
        BddMap<BddSet> adopted = sets.adopt(
                map, variable -> variable + 1, value -> target.bddSets().var(value.length()));

        assertSame(sets, adopted.valueDomain());
        assertEquals(NatSetFixtures.of(1, 3), adopted.support());
        assertEquals(2, adopted.values().size());
        for (NatSet assignment :
                List.of(NatSetFixtures.of(), NatSetFixtures.of(0), NatSetFixtures.of(2), NatSetFixtures.of(0, 2))) {
            MutableNatSet shifted = MutableNatSet.create();
            NatSetFixtures.forEach(assignment, variable -> shifted.set(variable + 1));
            assertEquals(target.bddSets().var(map.evaluate(assignment).length()), adopted.evaluate(shifted));
        }
    }

    @Test
    void testFoldOfAMap() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        Values<String> strings = ctx.bddMaps().create();
        BddSet x0 = ctx.bddSets().var(0);
        BddSet x1 = ctx.bddSets().var(1);
        BddMap<String> map = strings.of("none").update(x1, "x1").update(x0, "x0");

        // The values once each, high first; null results memoized like any other.
        List<String> values = new ArrayList<>();
        assertNull(map.fold(
                new BddMap.Folder<String, @Nullable Object>() { // NOPMD - a diamond here infers Object
                    @Override
                    public @Nullable Object value(String value) {
                        values.add(value);
                        return null;
                    }

                    @Override
                    public @Nullable Object decision(int variable, @Nullable Object high, @Nullable Object low) {
                        return null;
                    }
                }));
        assertEquals(List.of("x0", "x1", "none"), values);

        // A map built inside the walk.
        assertEquals(map, map.fold(new BddMap.Folder<String, BddMap<String>>() {
            @Override
            public BddMap<String> value(String value) {
                return strings.of(value);
            }

            @Override
            public BddMap<String> decision(int variable, BddMap<String> high, BddMap<String> low) {
                return strings.ifThenElse(variable, high, low);
            }
        }));
    }
}
