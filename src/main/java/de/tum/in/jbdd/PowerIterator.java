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

import java.util.Arrays;
import java.util.BitSet;
import java.util.Iterator;
import java.util.NoSuchElementException;

final class PowerIterator implements Iterator<BitSet> {
    private final BitSet iteration;
    private final int[] base;
    private int numSetBits = -1;

    public PowerIterator(int size) {
        base = new int[size];
        Arrays.setAll(base, i -> i);
        iteration = new BitSet(size);
    }

    PowerIterator(BitSet base) {
        this.base = BitSets.toArray(base);
        iteration = new BitSet(base.length());
    }

    @Override
    public boolean hasNext() {
        return numSetBits < base.length;
    }

    @Override
    public BitSet next() {
        if (numSetBits == -1) {
            numSetBits = 0;
            return iteration;
        }

        if (numSetBits == base.length) {
            throw new NoSuchElementException("No next element");
        }

        for (int index : base) {
            if (iteration.get(index)) {
                iteration.clear(index);
                numSetBits -= 1;
            } else {
                iteration.set(index);
                numSetBits += 1;
                break;
            }
        }

        return iteration;
    }
}
