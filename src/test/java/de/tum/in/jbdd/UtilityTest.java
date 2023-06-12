package de.tum.in.jbdd;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.util.BitSet;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

public class UtilityTest {
    @Test
    public void testPowerIterator() {
        Random random = new Random(0);
        int size = 12;
        BitSet set = new BitSet(size);
        for (int i = 0; i < size; i++) {
            if (random.nextBoolean()) {
                set.set(i);
            }
        }
        PowerIterator iterator = new PowerIterator(set);
        AtomicLong counter = new AtomicLong();
        iterator.forEachRemaining(i -> counter.incrementAndGet());
        assertThat(counter.get(), is(1L << set.cardinality()));
    }
}
