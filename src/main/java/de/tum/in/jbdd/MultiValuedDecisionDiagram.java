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

/**
 * What a multi-valued decision diagram can compute: boolean functions over variables that each take one of
 * {@code domain} values, {@code 0} to {@code domain - 1}. An assignment is an {@code int[]} indexed by variable; a
 * path is one too, {@code -1} where it leaves a variable free.
 */
public interface MultiValuedDecisionDiagram extends BooleanTerminalDecisionDiagram<int[], int[]> {
    /** Creates a variable with {@code domain} values (at least two) and returns its number. */
    int declareVariable(int domain);

    /** The function that is true exactly where {@code variable} takes a value {@code values} marks. */
    int makeVariableFunction(int variable, boolean[] values);

    /** The function obtained by fixing the topmost variable of the (non-constant) {@code function} to {@code value}. */
    int follow(int function, int value);

    /** {@code function} with every variable {@code v} fixed to {@code values[v]}, those with {@code -1} left free. */
    int restrict(int function, int[] values);
}
