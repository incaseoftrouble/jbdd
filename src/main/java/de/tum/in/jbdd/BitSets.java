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
