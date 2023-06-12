/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2023 Tobias Meggendorfer.
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

final class BitSets {
    private BitSets() {}

    @SuppressWarnings("UseOfClone")
    static BitSet copyOf(BitSet set) {
        return (BitSet) set.clone();
    }

    static boolean isSubset(BitSet set, BitSet of) {
        if (set.cardinality() > of.cardinality()) {
            return false;
        }
        BitSet copy = copyOf(set);
        copy.andNot(of);
        return copy.isEmpty();
    }

    static int[] toArray(BitSet set) {
        int[] array = new int[set.cardinality()];
        int pos = 0;
        for (int bit = set.nextSetBit(0); bit >= 0; bit = set.nextSetBit(bit + 1)) {
            array[pos] = bit;
            pos += 1;
        }
        return array;
    }
}
