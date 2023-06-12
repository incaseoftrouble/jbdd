/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2018-2023 Tobias Meggendorfer.
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

final class HashUtil {
    // Note: These are tremendously stupid hash functions, however this is called so often
    // that the reduction in computation time seems to be very much worth it

    static final int PRIME = 0x1000193;

    private HashUtil() {}

    static int hash(int key) {
        return key;
    }

    static int hash(int firstKey, int secondKey, int thirdKey) {
        return firstKey + secondKey + thirdKey;
    }

    static int hash(byte firstKey, int secondKey, int thirdKey) {
        return (PRIME * firstKey) + secondKey + thirdKey;
    }
}
