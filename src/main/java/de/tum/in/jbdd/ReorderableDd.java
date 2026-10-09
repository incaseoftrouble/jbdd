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

/**
 * A diagram whose variable order can possibly be changed.
 *
 * <p>A variable's <em>level</em> is its position in the order, which is the identity until something
 * reorders. Reordering rewrites nodes in place, so it never invalidates a function id: a handle keeps
 * denoting the same function, only the diagram representing it changes shape. Variables keep their
 * numbers too - only {@link #levelOfVariable(int)} moves - so nothing a caller holds has to be translated.
 *
 * <p>Everything that <em>changes</em> the order lives on {@link DdVariableOrder}, which is reached
 * through {@link #variableOrder()} - it moves every diagram over those variables at once, so it is not
 * any one diagram's to offer.
 */
public interface ReorderableDd extends NodeBasedDd {
    /** The order this diagram is laid out in, shared with every diagram over the same variables. */
    DdVariableOrder variableOrder();

    /** The position of {@code variable} in the current order. */
    default int levelOfVariable(int variable) {
        return variableOrder().levelOfVariable(variable);
    }

    /** The variable at position {@code level} - the inverse of {@link #levelOfVariable(int)}. */
    default int variableAtLevel(int level) {
        return variableOrder().variableAtLevel(level);
    }
}
