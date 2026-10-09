/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2017-2023 Tobias Meggendorfer.
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

@SuppressWarnings("MethodReturnAlwaysConstant")
@Value.Immutable
public class BddConfiguration extends NodeTableConfiguration {
    public static final int DEFAULT_CACHE_SIZE_DIVIDER = 32;

    /** An optional, human-readable name for this configuration's instance: it prefixes the keys of its
     * statistics, so several can be read side by side; empty by default. */
    @Value.Default
    public String name() {
        return "";
    }

    /** The initial size of the BDD's node table, which grows as needed. */
    @Value.Default
    public int initialSize() {
        return 1024;
    }

    /**
     * Whether the Bdd maintains structures required for reordering.
     *
     * <p>Building it is one linear pass, so a workload that reorders occasionally is better off not
     * keeping it. Set this when reordering is frequent enough that rebuilding dominates.
     */
    @Value.Default
    public boolean keepReorderingStructures() {
        return false;
    }

    /**
     * Initial node-table size of the companion MTBDD (see {@code MtBddImpl}); defaults to
     * {@link #initialSize()}.
     */
    @Value.Default
    public int mtbddInitialSize() {
        return initialSize();
    }

    /**
     * The operation caches, as a fraction of the node table they serve: a binary, ternary or ephemeral cache has
     * {@code table size / cacheSizeDivider()} bins, a unary one half that, a registered operation's an eighth, and
     * they grow with the table. The one knob of the caches - a larger divider trades hits for memory.
     */
    @Value.Default
    public int cacheSizeDivider() {
        return DEFAULT_CACHE_SIZE_DIVIDER;
    }
}
