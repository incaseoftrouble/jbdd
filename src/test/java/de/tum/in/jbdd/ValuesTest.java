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
}
