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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorkTest {
    private static BinaryFactoryContext context() {
        return BinaryFactoryContext.create(ImmutableBddConfiguration.builder().build(), 4);
    }

    @Test
    void anOperationCountsItsStepsAndNoneACacheOrATerminalAnswers() {
        BinaryFactoryContext context = context();
        BddSetFactory sets = context.bddSets();
        BddSet left = sets.var(0).union(sets.var(1));
        BddSet right = sets.var(2).union(sets.var(3));

        long before = context.work();
        BddSet intersection = left.intersection(right);
        long computed = context.work();
        assertTrue(computed > before);

        assertEquals(intersection, left.intersection(right));
        assertEquals(left, left.intersection(sets.universe()));
        assertEquals(computed, context.work());
    }

    @Test
    void adoptingCountsInTheAdoptingContext() {
        BinaryFactoryContext source = context();
        BddSet set = source.bddSets().var(0).intersection(source.bddSets().var(3));
        long sourceWork = source.work();

        BinaryFactoryContext target = context();
        long before = target.work();
        target.bddSets().adopt(set, variable -> variable);
        assertTrue(target.work() > before);
        assertEquals(sourceWork, source.work());
    }

    @Test
    void multiTerminalOperationsCount() {
        BinaryFactoryContext context = context();
        Values<Integer> values = context.bddMaps().create();
        BddMap<Integer> map = values.ifThenElse(0, values.of(1), values.of(2));
        BddMap<Integer> other = values.ifThenElse(1, values.of(3), values.of(4));

        long before = context.work();
        values.apply(List.of(map, other), BddMapBinaryOperator.of(Integer::sum));
        assertTrue(context.work() > before);
    }
}
