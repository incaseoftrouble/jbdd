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
  private MathUtil() {
  }

  static int nextPrime(int prime) {
    int nextPrime = Math.max(3, prime | 1);

    while (!PrimeTest.isPrime(nextPrime)) {
      nextPrime += 2;
    }

    return nextPrime;
  }

  static int min(int one, int two, int three) {
    if (one <= two) {
      return Math.min(one, three);
    }
    return Math.min(two, three);
  }
}
