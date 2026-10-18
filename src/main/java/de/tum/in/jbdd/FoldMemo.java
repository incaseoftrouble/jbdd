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

import de.tum.in.jbdd.collections.IntObjectHashMap;
import org.jspecify.annotations.Nullable;

/** A fold's results per node. A null result is held as a marker, so that a missing one is told apart from it. */
final class FoldMemo<R extends @Nullable Object> {
    private static final Object NONE = new Object();

    private final IntObjectHashMap<Object> results = new IntObjectHashMap<>();

    /** The stored result of {@code node} as {@link #unmask} takes it, {@code null} if there is none. */
    @Nullable
    Object lookup(int node) {
        return results.get(node);
    }

    /** Stores {@code result} for {@code node} and returns it as {@link #lookup} would. */
    Object put(int node, R result) {
        Object stored = result == null ? NONE : result;
        results.put(node, stored);
        return stored;
    }

    // NONE, a marker compared by identity, stands for a null the folder returned: R is nullable wherever it comes back.
    @SuppressWarnings({"unchecked", "NullAway", "ObjectEquality"})
    R unmask(Object stored) {
        return (R) (stored == NONE ? null : stored);
    }
}
