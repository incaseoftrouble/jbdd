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

import de.tum.in.jbdd.StatisticDescription.Kind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StatisticsTest {
    private static void assertDescribed(StatisticsSource source) {
        Map<String, Object> statistics = source.statistics();
        Map<String, Object> counters = source.statistics(StatisticsDetail.COUNTERS);
        Map<String, StatisticDescription> descriptions = source.describeStatistics();
        assertEquals(statistics.keySet(), descriptions.keySet());
        assertTrue(statistics.keySet().containsAll(counters.keySet()));
        descriptions.forEach((key, description) -> {
            for (String part : description.numerator()) {
                assertTrue(statistics.containsKey(part), key + ": no part " + part);
            }
            for (String part : description.denominator()) {
                assertTrue(statistics.containsKey(part), key + ": no part " + part);
            }
        });
    }

    @Test
    void everyKeyOfAContextAndAnMddIsDescribed() {
        for (String name : List.of("", "named")) {
            BddConfiguration configuration =
                    ImmutableBddConfiguration.builder().name(name).build();
            BinaryFactoryContext context = BinaryFactoryContext.create(configuration, 3);
            context.bddSets().var(0).intersection(context.bddSets().var(1));
            assertDescribed(context);
            assertDescribed(BddFactory.buildMdd(configuration));
        }
    }

    @Test
    void descriptionsCarryTheScope() {
        BddConfiguration configuration =
                ImmutableBddConfiguration.builder().name("named").build();
        Map<String, StatisticDescription> descriptions =
                BinaryFactoryContext.create(configuration, 3).describeStatistics();

        StatisticDescription hits = descriptions.get("named_cache_and_exists_hit_ratio");
        assertEquals(Kind.RATIO, hits.kind());
        assertEquals(List.of("named_cache_and_exists_hit"), hits.numerator());
        assertEquals(List.of("named_cache_and_exists_hit", "named_cache_and_exists_miss"), hits.denominator());
        assertEquals("fraction of the lookups of cache and_exists that hit", hits.text());

        StatisticDescription reorderCollections = descriptions.get("named_bdd_node_table_reorder_gc_count");
        assertEquals(Kind.COUNTER, reorderCollections.kind());
        StatisticDescription peak = descriptions.get("named_mtbdd_node_table_peak_live_nodes");
        assertEquals(Kind.MAXIMUM, peak.kind());
        assertEquals(Kind.GAUGE, descriptions.get("set_wrapper_count").kind());
        assertEquals("live map wrappers", descriptions.get("map_wrapper_count").text());
    }
}
