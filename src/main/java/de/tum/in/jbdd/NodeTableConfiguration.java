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

import org.immutables.value.Value;

/**
 * The parameters a {@link NodeTable} needs to manage its memory, i.e. everything consulted by
 * {@code ensureCapacity()}. Split out from {@link BddConfiguration} so that the table does not depend on
 * the configuration of any particular kind of diagram.
 */
@SuppressWarnings({"MethodReturnAlwaysConstant", "PMD.AbstractClassWithoutAbstractMethod"})
public abstract class NodeTableConfiguration {
    public static final double DEFAULT_NODE_TABLE_GROWTH_FACTOR = 2.0d;
    public static final double DEFAULT_GC_LIVE_NODE_THRESHOLD = 0.5d;

    /** Whether dead nodes should be collected at all, as opposed to only ever growing the table. */
    @Value.Default
    public boolean useGarbageCollection() {
        return true;
    }

    /** The factor by which the node table grows whenever it cannot be collected (far) enough. */
    @Value.Default
    public double growthFactor() {
        return DEFAULT_NODE_TABLE_GROWTH_FACTOR;
    }

    /**
     * The fraction of the node table which may be live for a garbage collection to be preferred over
     * growing the table - effectively a load factor. Nodes are collected once the table is 75% full, so a
     * threshold of, e.g., 0.9 means that a collection may leave only 10% of the table free, i.e. the next
     * collection is due after allocating 15% of the table's size. Since a collection costs a full mark of
     * all live nodes plus a sweep of the whole table, such a high threshold amounts to tens of node visits
     * amortized per created node; the default trades memory for that time.
     */
    @Value.Default
    public double gcLiveNodeThreshold() {
        return DEFAULT_GC_LIVE_NODE_THRESHOLD;
    }
}
