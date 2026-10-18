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

/** How much a statistics snapshot reads ({@link StatisticsSource#statistics(StatisticsDetail)}). */
public enum StatisticsDetail {
    /**
     * The counters and gauges the structures keep as they run, and ratios of them: no pass over a table and no
     * write, so a snapshot costs about one map entry per key. The one level another thread may ask for while the
     * diagrams are in use - best effort: the values are read without synchronization and need not be consistent
     * with each other.
     */
    COUNTERS,
    /**
     * Also what only a pass over each node table tells: valid, referenced and saturated nodes, the nodes below the
     * referenced ones and the hash chains. The pass only reads the table (its own visited set, never the mark
     * bits), but it must run on the thread using the diagrams.
     */
    FULL
}
