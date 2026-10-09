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

import java.math.BigInteger;
import java.util.Arrays;

final class Util {
    private Util() {}

    static int mod(int value, int modulus) {
        int val = value % modulus;
        return val < 0 ? val + modulus : val;
    }

    static boolean binarySymmetricWellOrdered(int node1, int node2) {
        return node1 <= node2;
    }

    static int[] protectNodes(DecisionDiagram dd, int[] resolvedMapping) {
        int[] toProtect = new int[resolvedMapping.length];
        int count = 0;
        for (int node : resolvedMapping) {
            assert dd.isValidFunction(node);
            if (!dd.isUnmanaged(node)) {
                toProtect[count] = node;
                count++;
                dd.reference(node);
            }
        }
        return count == toProtect.length ? toProtect : Arrays.copyOf(toProtect, count);
    }

    /** The index of {@code key} in the ascending {@code array}, which contains it. */
    static int indexOfSorted(int[] array, int key) {
        int low = 0;
        int high = array.length - 1;
        // A binary search down to a range below this length, which a linear scan then beats.
        while (high - low >= 32) {
            int middle = (low + high) >>> 1;
            int value = array[middle];
            if (value < key) {
                low = middle + 1;
            } else if (value > key) {
                high = middle - 1;
            } else {
                return middle;
            }
        }
        for (int index = low; index <= high; index++) {
            if (array[index] == key) {
                return index;
            }
        }
        throw new IllegalArgumentException(key + " not in array");
    }

    static double ratio(long value, long total) {
        return total == 0 ? 0.0 : value / (double) total;
    }

    /**
     * {@code numerator / denominator} for {@code 0 <= numerator <= denominator}, correctly rounded unless below
     * {@code 2^-1022}: a quotient of at least 55 bits with the remainder as sticky bit rounds as the exact value
     * does.
     */
    static double quotient(BigInteger numerator, BigInteger denominator) {
        assert numerator.signum() >= 0 && denominator.compareTo(numerator) >= 0 && denominator.signum() > 0;
        if (numerator.signum() == 0) {
            return 0.0d;
        }
        int shift = denominator.bitLength() - numerator.bitLength() + 55;
        BigInteger[] quotientAndRemainder = numerator.shiftLeft(shift).divideAndRemainder(denominator);
        BigInteger quotient =
                quotientAndRemainder[1].signum() == 0 ? quotientAndRemainder[0] : quotientAndRemainder[0].setBit(0);
        return Math.scalb(quotient.doubleValue(), -shift);
    }
}
