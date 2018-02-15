/*
 * Copyright (C) 2017 (See AUTHORS)
 *
 * This file is part of JBDD.
 *
 * JBDD is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JBDD is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with JBDD.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.tum.in.jbdd;

final class MathUtil {
  private static final int BYTE_MASK = BitUtil.intMaskLength(Byte.SIZE);
  private static final int FNV_OFFSET = 0x811c9dc5;
  private static final int FNV_PRIME = 0x1000193;

  private MathUtil() {
  }

  @SuppressWarnings("MagicNumber")
  private static int fnv1aRound(int hash, int number) {
    return (hash ^ ((number >>> 24) & BYTE_MASK)
        ^ ((number >>> 16) & BYTE_MASK)
        ^ ((number >>> 8) & BYTE_MASK)
        ^ (number & BYTE_MASK)) * FNV_PRIME;
  }

  private static int fnv1aRound(int hash, long number) {
    //noinspection NumericCastThatLosesPrecision
    return fnv1aRound(fnv1aRound(hash, (int) (number >>> Integer.SIZE)), (int) number);
  }

  public static int hash(long firstKey, int secondKey) {
    return fnv1aRound(fnv1aRound(FNV_OFFSET, firstKey), secondKey);
  }

  public static int hash(long firstKey, long secondKey) {
    return fnv1aRound(fnv1aRound(FNV_OFFSET, firstKey), secondKey);
  }

  public static int hash(int key) {
    return fnv1aRound(FNV_OFFSET, key);
  }

  public static int hash(long key) {
    return fnv1aRound(FNV_OFFSET, key);
  }

  public static int hash(int key, int[] keys) {
    int hash = fnv1aRound(FNV_OFFSET, key);
    for (int arrayKey : keys) {
      hash = fnv1aRound(hash, arrayKey);
    }
    return hash;
  }

  public static int nextPrime(int prime) {
    int nextPrime = Math.max(3, prime | 1);

    while (!PrimeTest.isPrime((long) nextPrime)) {
      nextPrime += 2;
    }

    return nextPrime;
  }
}
