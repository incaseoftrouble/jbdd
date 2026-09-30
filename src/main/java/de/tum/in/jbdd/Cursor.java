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

import java.util.function.Consumer;

/**
 * A walk over an enumeration that hands out the state it is standing on, rather than a copy of it.
 *
 * <p>The shape is
 *
 * <pre>{@code
 * for (Cursor<BitSet> cursor = bdd.solutionCursor(function); cursor.valid(); cursor.advance()) {
 *     use(cursor.current());
 * }
 * }</pre>
 *
 * <p>A cursor is positioned as soon as it is created - there is no step to take before the first element,
 * and an enumeration with nothing in it simply starts invalid. {@link #current()} is defined exactly while
 * {@link #valid()} holds, and only until the next {@link #advance()}: what it returns is reused, and after
 * a reordering it may be a translation buffer rather than the walk's own set. Copy it if it has to outlive
 * one step.
 */
public interface Cursor<E> {
    /** Whether the cursor stands on an element. False once the walk is over, or if it never had one. */
    boolean valid();

    /**
     * The element the cursor stands on.
     *
     * <p>Only defined while {@link #valid()} holds, and only until the next {@link #advance()}.
     */
    E current();

    /**
     * Moves to the next element.
     *
     * @return What {@link #valid()} now reports - {@code false} once the walk is over.
     */
    boolean advance();

    /**
     * Hands every element from here on to {@code action}, leaving the cursor invalid.
     *
     * <p>The analogue of {@link java.util.Iterator#forEachRemaining}, and the whole of the walk when
     * called on a fresh cursor. The caveat of {@link #current()} carries over: what {@code action}
     * receives is the walk's own state and is reused by the next step, so anything outliving the call
     * has to be copied out of it.
     */
    default void forEachRemaining(Consumer<? super E> action) {
        while (valid()) {
            action.accept(current());
            advance();
        }
    }
}
