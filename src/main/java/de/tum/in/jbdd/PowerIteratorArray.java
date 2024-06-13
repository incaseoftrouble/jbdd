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

final class PowerIteratorArray implements Iterator<int[]> {
    private final int[] iteration;
    private final int[] domain;
    private final int[] support;
    private int maximalPositions = -1;

    public PowerIteratorArray(int[] domain) {
        assert Arrays.stream(domain).allMatch(i -> i > 1);
        this.domain = domain;
        this.support = new int[domain.length];
        Arrays.setAll(support, i -> i);
        iteration = new int[domain.length];
    }

    public PowerIteratorArray(int[] domain, BitSet support) {
        this.domain = domain;
        this.support = new int[support.cardinality()];
        int pos = 0;
        for (int i = support.nextSetBit(0); i >= 0; i = support.nextSetBit(i + 1)) {
            if (domain[i] > 1) {
                this.support[pos] = i;
                pos += 1;
            }
        }
        iteration = new int[domain.length];
    }

    @Override
    public boolean hasNext() {
        return maximalPositions < support.length;
    }

    @SuppressWarnings("AssignmentOrReturnOfFieldWithMutableType")
    @Override
    public int[] next() {
        if (maximalPositions == -1) {
            maximalPositions = 0;
            return iteration;
        }

        if (maximalPositions == support.length) {
            throw new NoSuchElementException("No next element");
        }

        for (int i : support) {
            if (iteration[i] == domain[i] - 1) {
                iteration[i] = 0;
                maximalPositions -= 1;
            } else {
                iteration[i] += 1;
                if (iteration[i] == domain[i] - 1) {
                    maximalPositions += 1;
                }
                break;
            }
        }

        return iteration;
    }
}
