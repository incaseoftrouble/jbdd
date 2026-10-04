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

import com.google.errorprone.annotations.FormatMethod;
import de.tum.in.jbdd.collections.NatSet;

final class Preconditions {
    private Preconditions() {}

    static void checkState(boolean state) {
        if (!state) {
            throw new IllegalStateException("");
        }
    }

    @FormatMethod
    static void checkState(boolean state, String formatString, Object... format) {
        if (!state) {
            throw new IllegalStateException(String.format(formatString, format));
        }
    }

    /**
     * Quantification takes existing variables only: its shortcut for a set of all of them compares sizes, which this
     * makes sound. One comparison, so a check rather than an assertion.
     */
    static void checkVariablesExist(NatSet variables, int numberOfVariables) {
        if (variables.length() > numberOfVariables) {
            throw new IllegalArgumentException(
                    "variable " + variables.last() + " does not exist (" + numberOfVariables + " variables)");
        }
    }
}
