/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2024 Tobias Meggendorfer.
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

public interface BooleanDecisionDiagram extends DecisionDiagram {
    /**
     * Returns the function obtained by fixing the topmost variable to true in the {@code function}.
     */
    int highOf(int function);

    /**
     * Returns the function obtained by fixing the topmost variable to false in the {@code function}.
     */
    int lowOf(int function);

    /**
     * The variables actually consulted while evaluating {@code function} at {@code assignment} - a
     * witness for this specific valuation (the path taken), which can be much smaller than the full
     * {@link #support(int)} (which accounts for every path). O(depth) instead of O(size).
     */
    default BitSet supportAt(int function, BitSet assignment) {
        BitSet result = new BitSet();
        int current = function;
        while (!isConstant(current)) {
            int variable = decisionVariable(current);
            result.set(variable);
            current = assignment.get(variable) ? highOf(current) : lowOf(current);
        }
        return result;
    }
}
