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
    // TODO Check performance differences on different benchmarks with slight variations

    // Taken from https://planetmath.org/goodhashtableprimes
    static final int P1 = 6291469; // NOPMD
    static final int P2 = 12582917; // NOPMD
    static final int P3 = 25165843; // NOPMD

    private HashUtil() {}

    static int hash(int key) {
        return key;
    }

    static int hash(int firstKey, int secondKey, int thirdKey) {
        // return firstKey + P1 * secondKey + thirdKey;
        return P3 * (P2 * (P1 * firstKey + secondKey) + thirdKey);
    }

    static int hash(byte primeKey, int secondKey, int thirdKey) {
        return P1 * (primeKey * secondKey + thirdKey);
    }

    public static int hash(int firstKey, int secondKey) {
        return P2 * (P1 * firstKey + secondKey);
    }

    public static int hash(int firstKey, boolean secondKey) {
        return P1 * (firstKey + Boolean.hashCode(secondKey));
    }
}
