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
