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

import java.util.BitSet;

/**
 * Hears about the variable order moving. Registered with the order rather than with a diagram, and so
 * told exactly once however many diagrams the change moved - unlike a {@link NodeTableObserver}, which
 * fires per table and has to say which one.
 */
interface VariableOrderObserver {
    /**
     * The variable order changed, once for however many adjacent swaps it took. Node ids and their
     * meanings survive, so anything stated purely about them still holds; what does not is anything that
     * folded a level comparison into a value, and anything holding a level.
     *
     * <p>{@code movedVariables} is exactly the variables whose level differs between the two arrays, so a
     * listener concerned with only some of them can test that first. All three arguments describe the
     * order as of now and must not be modified.
     */
    default void orderChanged(int[] previousVariableToLevel, int[] currentVariableToLevel, BitSet movedVariables) {
        // Nothing by default
    }

    /**
     * {@code count} variables were created at {@code level} and the ones after it, pushing everything
     * from there down that much deeper. Fired by every variable creation, an append at the bottom
     * included - that is an insertion at the level the first of them lands on, with nothing below it to
     * push - so this is also how a listener hears that the variable count changed.
     *
     * <p>Every level <em>comparison</em> survives an insertion, since both sides shift by the same rule,
     * so only a <em>stored</em> level has to move, by {@code count} if it was at or below {@code level}.
     */
    default void variablesInserted(int level, int count) {
        // Nothing by default
    }
}
