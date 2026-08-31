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

import static java.util.Map.entry;

import java.util.Map;

final class CacheStatistics {
    private int hitCount = 0;
    private int hitCountSinceClear = 0;
    private int putCount = 0;
    private int putCountSinceClear = 0;
    private int missCount = 0;
    private int missCountSinceClear = 0;
    private int clearCount = 0;
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

    void prune(int prunedEntries) {
        pruningCount++;
        totalPrunedEntries += prunedEntries;
    }

    int putCountSinceClear() {
        return putCountSinceClear;
    }

    Map<String, Object> data() {
        double hitToPutRatio = (double) hitCount / Math.max(putCount, 1);
        double hitRatio = (double) hitCount / Math.max(hitCount + missCount, 1);

        return Map.ofEntries(
                entry("put", putCount),
                entry("hit", hitCount),
                entry("miss", missCount),
                entry("hit_ratio", hitRatio),
                entry("hit_to_put_ratio", hitToPutRatio),
                entry("clear_count", clearCount),
                entry("put_since_clear", putCountSinceClear),
                entry("hit_since_clear", hitCountSinceClear),
                entry("miss_since_clear", missCountSinceClear),
                entry("prune_count", pruningCount),
                entry("pruned_entries", totalPrunedEntries));
    }
}
