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
 * A partial valuation of a boolean function's variables read off terminal values: for the value the operand replacing
 * a variable takes, the truth value it assigns to that variable, or {@link Truth#UNDECIDED} if it assigns none. Used by
 * {@link MultiTerminalDecisionDiagram#residualProduct}.
 */
@FunctionalInterface
public interface PartialValuation {
    /** What an operand's value assigns to the variable the operand replaces. */
    enum Truth {
        /** The variable is assigned false. */
        FALSE,
        /** The variable is assigned true. */
        TRUE,
        /** The variable is left undecided: unassigned. */
        UNDECIDED;

        /** The truth value {@code value}. */
        public static Truth of(boolean value) {
            return value ? TRUE : FALSE;
        }
    }

    /** The truth value the terminal {@code value} of the operand replacing {@code variable} assigns to it. */
    Truth valueOf(int variable, int value);

    /** Decides no variable. */
    static PartialValuation undecided() {
        return (variable, value) -> Truth.UNDECIDED;
    }

    /** A partial valuation read off the values of a numbering, as {@link PartialValuation} reads raw terminals. */
    @FunctionalInterface
    interface Of<V> {
        /** The truth value the operand replacing {@code variable} assigns to it when taking {@code value}. */
        Truth valueOf(int variable, V value);
    }
}
