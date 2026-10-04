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

import de.tum.in.jbdd.collections.MutableNatSet;
import de.tum.in.jbdd.collections.NatSet;
import java.util.ArrayList;
import java.util.List;
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
    void testDagOfAMap() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        Values<String> strings = ctx.bddMaps().create();
        BddSet x0 = ctx.bddSets().var(0);
        BddSet x1 = ctx.bddSets().var(1);
        BddMap<String> map = strings.of("none").update(x1, "x1").update(x0, "x0");

        // Folding the snapshot back gives the map; values come in high-first order, each once.
        Dag<String> dag = map.dag();
        List<BddMap<String>> entries = new ArrayList<>();
        List<String> valueOrder = new ArrayList<>();
        for (int entry = 0; entry < dag.size(); entry++) {
            if (dag.kind(entry) == Dag.Kind.VALUE) {
                entries.add(strings.of(dag.value(entry)));
                valueOrder.add(dag.value(entry));
            } else {
                entries.add(strings.ifThenElse(
                        dag.variable(entry), entries.get(dag.high(entry)), entries.get(dag.low(entry))));
            }
        }
        assertEquals(map, entries.get(dag.root(0)));
        assertEquals(List.of("x0", "x1", "none"), valueOrder);
    }
}
