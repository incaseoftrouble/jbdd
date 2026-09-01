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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class UtilityTest {
    @Test
    void testPowerIterator() {
        Random random = new Random(0);
        int size = 12;
        BitSet set = new BitSet(size);
        for (int i = 0; i < size; i++) {
            if (random.nextBoolean()) {
                set.set(i);
            }
        }
        var iterator = BitSets.powerSetIterator(set);
        AtomicLong counter = new AtomicLong();
        iterator.forEachRemaining(i -> counter.incrementAndGet());
        assertThat(counter.get(), is(1L << set.cardinality()));
    }

    @Test
    void testNextPrimeAgreesAcrossTheTableBoundary() {
        // The tabulated range and the Miller-Rabin search must not disagree at the seam.
        for (int n = 0; n < 9000; n++) {
            int next = Primes.nextPrime(n);
            assertTrue(next >= Math.max(n, 2), "nextPrime(" + n + ") = " + next);
            assertTrue(Primes.isPrime(next), next + " is not prime");
            for (int between = Math.max(n, 2); between < next; between++) {
                assertFalse(Primes.isPrime(between), between + " is a smaller prime than " + next);
            }
        }
    }
}
