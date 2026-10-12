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

import static de.tum.in.jbdd.Statistic.counter;
import static de.tum.in.jbdd.Statistic.gauge;
import static de.tum.in.jbdd.Statistic.ratio;

import java.util.List;

final class CacheStatistics {
    private static final Statistic SIZE = gauge("size", "slots of cache {name}");
    private static final Statistic LOAD_FACTOR = gauge("load_factor", "fraction of the slots of cache {name} in use");
    private static final Statistic PUT = counter("put", "entries stored in cache {name}");
    private static final Statistic HIT = counter("hit", "lookups of cache {name} that found their entry");
    private static final Statistic MISS = counter("miss", "lookups of cache {name} that did not");
    private static final Statistic.Ratio HIT_RATIO =
            ratio("hit_ratio", "fraction of the lookups of cache {name} that hit", List.of(HIT), List.of(HIT, MISS));
    private static final Statistic.Ratio HIT_TO_PUT_RATIO =
            ratio("hit_to_put_ratio", "hits per stored entry of cache {name}", List.of(HIT), List.of(PUT));
    private static final Statistic CLEAR_COUNT = counter("clear_count", "times cache {name} was cleared");
    private static final Statistic SPARSE_CLEAR_COUNT =
            counter("sparse_clear_count", "clears of cache {name} that reset only the slots written");
    private static final Statistic PUT_SINCE_CLEAR =
            gauge("put_since_clear", "entries stored in cache {name} since it was last cleared");
    private static final Statistic HIT_SINCE_CLEAR =
            gauge("hit_since_clear", "hits of cache {name} since it was last cleared");
    private static final Statistic MISS_SINCE_CLEAR =
            gauge("miss_since_clear", "misses of cache {name} since it was last cleared");
    private static final Statistic PRUNE_COUNT =
            counter("prune_count", "times cache {name} was pruned of entries naming dead nodes");
    private static final Statistic PRUNED_ENTRIES = counter("pruned_entries", "entries those prunings dropped");
    /** Written by the owner of an ephemeral cache, into the cache's scope. */
    static final Statistic REUSE_COUNT = counter(
            "reuse_count",
            "operations that kept the entries of cache {name}, their parameter being the previous one's");

    private int hitCount = 0;
    private int hitCountSinceClear = 0;
    private int putCount = 0;
    private int putCountSinceClear = 0;
    private int missCount = 0;
    private int missCountSinceClear = 0;
    private int clearCount = 0;
    private int sparseClearCount = 0;
    private int pruningCount = 0;
    private int totalPrunedEntries = 0;

    void hit() {
        hitCount++;
        hitCountSinceClear++;
    }

    void miss() {
        missCount++;
        missCountSinceClear++;
    }

    void put() {
        putCount++;
        putCountSinceClear++;
    }

    void clear() {
        clearCount++;
        hitCountSinceClear = 0;
        putCountSinceClear = 0;
        missCountSinceClear = 0;
    }

    void sparseClear() {
        sparseClearCount++;
    }

    void prune(int prunedEntries) {
        pruningCount++;
        totalPrunedEntries += prunedEntries;
    }

    int putCountSinceClear() {
        return putCountSinceClear;
    }

    void report(StatisticsReport report, int size, double loadFactor) {
        report.put(SIZE, size);
        report.put(LOAD_FACTOR, loadFactor);
        report.put(PUT, putCount);
        report.put(HIT, hitCount);
        report.put(MISS, missCount);
        report.ratio(HIT_RATIO);
        report.ratio(HIT_TO_PUT_RATIO);
        report.put(CLEAR_COUNT, clearCount);
        report.put(SPARSE_CLEAR_COUNT, sparseClearCount);
        report.put(PUT_SINCE_CLEAR, putCountSinceClear);
        report.put(HIT_SINCE_CLEAR, hitCountSinceClear);
        report.put(MISS_SINCE_CLEAR, missCountSinceClear);
        report.put(PRUNE_COUNT, pruningCount);
        report.put(PRUNED_ENTRIES, totalPrunedEntries);
    }
}
