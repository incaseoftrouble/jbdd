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

final class IntArrayList {
    private int[] values = new int[8];
    private int size = 0;

    int add(int value) {
        if (size == values.length) {
            values = Arrays.copyOf(values, values.length * 2);
        }
        int index = size;
        values[index] = value;
        size += 1;
        return index;
    }

    int get(int index) {
        assert index >= 0 && index < size;
        return values[index];
    }

    int size() {
        return size;
    }
}
