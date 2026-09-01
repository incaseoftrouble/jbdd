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
    // Taken from https://planetmath.org/goodhashtableprimes
    static final int P1 = 6291469; // NOPMD
    static final int P2 = 12582917; // NOPMD
    static final int P3 = 25165843; // NOPMD
    static final int P4 = 50331653; // NOPMD

    private HashUtil() {}

    static int hash(int key) {
        // int h = key * 0x9E3779B1;
        // return h & Integer.MAX_VALUE;
        return key;
    }

    static int hash(int firstKey, boolean secondKey) {
        int h = firstKey;
        h = h * 0x9E3779B1 + Boolean.hashCode(secondKey);
        return h & Integer.MAX_VALUE;
    }

    static int hash(int firstKey, int secondKey) {
        int h = firstKey;
        h = h * 0x9E3779B1 + secondKey;
        return h & Integer.MAX_VALUE;
    }

    static int hash(int firstKey, int secondKey, int thirdKey) {
        int h = firstKey;
        h = h * 0x9E3779B1 + secondKey;
        h = h * 0x9E3779B1 + thirdKey;
        return h & Integer.MAX_VALUE;
    }

    static int hash(int firstKey, int secondKey, int thirdKey, int fourthKey) {
        int h = firstKey;
        h = h * 0x9E3779B1 + secondKey;
        h = h * 0x9E3779B1 + thirdKey;
        h = h * 0x9E3779B1 + fourthKey;
        return h & Integer.MAX_VALUE;
    }

    static int hashArray(int first, int... keys) {
        if (keys.length == 0) {
            return hash(first);
        }
        if (keys.length == 1) {
            return hash(first, keys[0]);
        }
        int h = first;
        for (int key : keys) {
            h = h * 0x9E3779B1 + key;
        }
        return h & Integer.MAX_VALUE;
    }
}
