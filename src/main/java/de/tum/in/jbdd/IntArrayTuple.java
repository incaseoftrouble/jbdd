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

import java.util.Arrays;

// Wrapper class to use int arrays as hash keys
final class IntArrayTuple {
    private final int[] values;
    private final int hash;

    IntArrayTuple(int[] values) {
        this.values = values;
        this.hash = Arrays.hashCode(values);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof IntArrayTuple
                && hash == ((IntArrayTuple) o).hash
                && Arrays.equals(values, ((IntArrayTuple) o).values);
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
