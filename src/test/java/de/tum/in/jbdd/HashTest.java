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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

public class HashTest {
    @Test
    public void testCollisionRate() {
        Random random = new Random(0);

        int iterations = 1024;
        double[] rate = new double[iterations];
        for (int i = 0; i < iterations; i++) {
            int size = Primes.nextPrime(20_000);
            int[] count = new int[size];

            for (int n = 0; n < size; n++) {
                int first = random.nextInt(size);
                int second = random.nextInt(size);
                int third = random.nextInt(size);
                int hash = HashUtil.hash(first, second, third) % size;
                if (hash < 0) {
                    hash += size;
                }
                count[hash] += 1;
            }

            rate[i] = Arrays.stream(count).filter(c -> c > 1).count() / (double) size;
        }
        double average = Arrays.stream(rate).average().orElseThrow();
        assertThat(average < 0.265, is(true));
    }
}
