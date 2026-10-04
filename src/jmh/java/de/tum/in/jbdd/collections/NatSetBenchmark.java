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
package de.tum.in.jbdd.collections;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * The set operations a synthesis tool spends its time in: a pool of {@link #POOL} random sets of one shape, each
 * benchmark one operation over the whole pool (so the time is per set, over sets that differ, not one set the branch
 * predictor has learned). Shapes follow what such a tool builds (counted on one: 99.7% of its immutable sets lie
 * below 64, so every non-empty one is a single word):
 *
 * <ul>
 *   <li>SINGLETON - one element below 64.
 *   <li>TINY - mostly empty or one element, a few with up to four, below 16.
 *   <li>SMALL - 2 to 8 elements below 64, the bulk of what it creates (state keys, supports, valuations).
 *   <li>MEDIUM - 17 to 32 elements below 64.
 *   <li>SPARSE - 2 to 16 elements below 4096, held as an array: supports in a large context.
 * </ul>
 */
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@BenchmarkMode(Mode.AverageTime)
@State(Scope.Benchmark)
public class NatSetBenchmark {
    static final int POOL = 1024;

    public enum Shape {
        SINGLETON(64),
        TINY(16),
        SMALL(64),
        MEDIUM(64),
        SPARSE(4096);

        final int span;

        Shape(int span) {
            this.span = span;
        }

        int size(Random random) {
            switch (this) {
                case SINGLETON:
                    return 1;
                case TINY:
                    int draw = random.nextInt(20);
                    return draw < 8 ? 0 : draw < 15 ? 1 : draw < 18 ? 2 : 3 + random.nextInt(2);
                case SMALL:
                    return 2 + random.nextInt(7);
                case MEDIUM:
                    return 17 + random.nextInt(16);
                default: // SPARSE
                    return 2 + random.nextInt(15);
            }
        }
    }

    @SuppressWarnings("NullAway.Init")
    @Param({"SINGLETON", "TINY", "SMALL", "MEDIUM", "SPARSE"})
    private Shape shape;

    /** The pool. */
    private final NatSet[] sets = new NatSet[POOL];
    /** Another set of the same shape per pool entry. */
    private final NatSet[] others = new NatSet[POOL];
    /** Per entry, a subset of it (every other entry) or an unrelated set. */
    private final NatSet[] candidates = new NatSet[POOL];
    /** A tiny set (TINY's sizes) per entry, half of the time made of the entry's elements. */
    private final NatSet[] tinySets = new NatSet[POOL];
    /** Equal to the entry, built separately. */
    private final NatSet[] copies = new NatSet[POOL];
    /** The entries' elements, unsorted. */
    private final int[][] elements = new int[POOL][];

    private final MutableNatSet[] mutables = new MutableNatSet[POOL];
    /** The entry (every other one) or another set, as mutable sets that once held an element far above the span. */
    private final MutableNatSet[] grown = new MutableNatSet[POOL];
    /** An element below the span, in the entry half of the time. */
    private final int[] probes = new int[POOL];

    private final Map<NatSet, Integer> index = new HashMap<>();
    /** Per entry, the cube fixing the entry's variables as the other set does, and one fixing fewer of them. */
    private final Cube[] cubes = new Cube[POOL];

    private final Cube[] weaker = new Cube[POOL];
    /** A large, half full set, as a diagram's mark set; made in the setup, as anything touching the collections. */
    @SuppressWarnings("NullAway.Init")
    private MutableNatSet large;

    private static int[] draw(Random random, int size, int span) {
        MutableNatSet drawn = MutableNatSet.dense(span);
        while (drawn.size() < size) {
            drawn.set(random.nextInt(span));
        }
        int[] array = drawn.toIntArray();
        for (int position = array.length - 1; position > 0; position--) {
            int other = random.nextInt(position + 1);
            int swap = array[position];
            array[position] = array[other];
            array[other] = swap;
        }
        return array;
    }

    @Setup(Level.Trial)
    public void setUp() {

        large = MutableNatSet.dense(1 << 16);
        for (int element = 0; element < 1 << 16; element += 2) {
            large.set(element);
        }
        Random random = new Random(42L);
        for (int entry = 0; entry < POOL; entry++) {
            elements[entry] = draw(random, shape.size(random), shape.span);
            sets[entry] = NatSet.of(elements[entry]);
            others[entry] = NatSet.of(draw(random, shape.size(random), shape.span));
            copies[entry] = NatSet.of(elements[entry].clone());
            mutables[entry] = MutableNatSet.copyOf(sets[entry]);
            grown[entry] = MutableNatSet.copyOf(entry % 2 == 0 ? sets[entry] : others[entry]);
            grown[entry].set(2 * shape.span + 100);
            grown[entry].clear(2 * shape.span + 100);
            int[] own = elements[entry];
            probes[entry] = own.length > 0 && random.nextBoolean()
                    ? own[random.nextInt(own.length)]
                    : random.nextInt(shape.span);
            if (entry % 2 == 0) {
                MutableNatSet subset = MutableNatSet.create();
                for (int element : own) {
                    if (random.nextInt(3) > 0) {
                        subset.set(element);
                    }
                }
                candidates[entry] = NatSet.copyOf(subset);
            } else {
                candidates[entry] = NatSet.of(draw(random, shape.size(random), shape.span));
            }
            int tinySize = (shape == Shape.SINGLETON ? shape : Shape.TINY).size(random);
            if (random.nextBoolean() && own.length >= tinySize) {
                tinySets[entry] = NatSet.of(Arrays.copyOf(own, tinySize));
            } else {
                tinySets[entry] = NatSet.of(draw(random, tinySize, shape.span));
            }
            index.put(sets[entry], entry);
            cubes[entry] = Cube.of(others[entry], sets[entry]);
            weaker[entry] = Cube.of(others[entry], candidates[entry].intersection(sets[entry]));
        }
    }

    /** The smallest element, -1 if none. */
    @Benchmark
    @OperationsPerInvocation(POOL)
    public int first() {
        int sum = 0;
        for (NatSet set : sets) {
            sum += set.isEmpty() ? -1 : set.first();
        }
        return sum;
    }

    @Benchmark
    @OperationsPerInvocation(POOL)
    public int contains() {
        int sum = 0;
        for (int entry = 0; entry < POOL; entry++) {
            sum += sets[entry].contains(probes[entry]) ? 1 : 0;
        }
        return sum;
    }

    @Benchmark
    @OperationsPerInvocation(POOL)
    public int containsAll() {
        int sum = 0;
        for (int entry = 0; entry < POOL; entry++) {
            sum += sets[entry].containsAll(candidates[entry]) ? 1 : 0;
        }
        return sum;
    }

    @Benchmark
    @OperationsPerInvocation(POOL)
    public int containsAllTiny() {
        int sum = 0;
        for (int entry = 0; entry < POOL; entry++) {
            sum += sets[entry].containsAll(tinySets[entry]) ? 1 : 0;
        }
        return sum;
    }

    @Benchmark
    @OperationsPerInvocation(POOL)
    public void intersectionTiny(Blackhole bh) {
        for (int entry = 0; entry < POOL; entry++) {
            bh.consume(tinySets[entry].intersection(sets[entry]));
        }
    }

    @Benchmark
    @OperationsPerInvocation(POOL)
    public int intersects() {
        int sum = 0;
        for (int entry = 0; entry < POOL; entry++) {
            sum += sets[entry].intersects(others[entry]) ? 1 : 0;
        }
        return sum;
    }

    @Benchmark
    @OperationsPerInvocation(POOL)
    public void intersection(Blackhole bh) {
        for (int entry = 0; entry < POOL; entry++) {
            bh.consume(sets[entry].intersection(others[entry]));
        }
    }

    @Benchmark
    @OperationsPerInvocation(POOL)
    public void union(Blackhole bh) {
        for (int entry = 0; entry < POOL; entry++) {
            bh.consume(sets[entry].union(others[entry]));
        }
    }

    /** Deduplicating edges and states: a hash lookup with an equal key that is another object. */
    @Benchmark
    @OperationsPerInvocation(POOL)
    public int hashLookup() {
        int sum = 0;
        for (NatSet copy : copies) {
            sum += index.getOrDefault(copy, -1);
        }
        return sum;
    }

    @Benchmark
    @OperationsPerInvocation(POOL)
    public int forEach() {
        int[] sum = {0};
        for (NatSet set : sets) {
            set.forEach(element -> sum[0] += element);
        }
        return sum[0];
    }

    @Benchmark
    @OperationsPerInvocation(POOL)
    public int iterator() {
        int sum = 0;
        for (NatSet set : sets) {
            for (var iterator = set.iterator(); iterator.hasNext(); ) {
                sum += iterator.nextInt();
            }
        }
        return sum;
    }

    @Benchmark
    @OperationsPerInvocation(POOL)
    public void toIntArray(Blackhole bh) {
        for (NatSet set : sets) {
            bh.consume(set.toIntArray());
        }
    }

    @Benchmark
    @OperationsPerInvocation(POOL)
    public void of(Blackhole bh) {
        for (int[] array : elements) {
            bh.consume(NatSet.of(array));
        }
    }

    @Benchmark
    @OperationsPerInvocation(POOL)
    public void buildAndFreeze(Blackhole bh) {
        for (int[] array : elements) {
            MutableNatSet building = MutableNatSet.create();
            for (int element : array) {
                building.set(element);
            }
            bh.consume(NatSet.copyOf(building));
        }
    }

    @Benchmark
    @OperationsPerInvocation(POOL)
    public int mutableContains() {
        int sum = 0;
        for (int entry = 0; entry < POOL; entry++) {
            sum += mutables[entry].contains(probes[entry]) ? 1 : 0;
        }
        return sum;
    }

    /** Mutable sets against equal or different ones whose words run past their last element. */
    @Benchmark
    @OperationsPerInvocation(POOL)
    public int mutableEquals() {
        int sum = 0;
        for (int entry = 0; entry < POOL; entry++) {
            sum += mutables[entry].equals(grown[entry]) ? 1 : 0;
        }
        return sum;
    }

    @Benchmark
    @OperationsPerInvocation(POOL)
    public void difference(Blackhole bh) {
        for (int entry = 0; entry < POOL; entry++) {
            bh.consume(sets[entry].difference(others[entry]));
        }
    }

    @Benchmark
    @OperationsPerInvocation(POOL)
    public int order() {
        int sum = 0;
        for (int entry = 0; entry < POOL; entry++) {
            sum += NatSet.ORDER.compare(sets[entry], entry % 2 == 0 ? copies[entry] : others[entry]);
        }
        return sum;
    }

    /** Whether a valuation satisfies a cube: the other set, which it fixes alike, or the entry. */
    @Benchmark
    @OperationsPerInvocation(POOL)
    public int cubeContains() {
        int sum = 0;
        for (int entry = 0; entry < POOL; entry++) {
            sum += cubes[entry].contains(entry % 2 == 0 ? others[entry] : sets[entry]) ? 1 : 0;
        }
        return sum;
    }

    @Benchmark
    @OperationsPerInvocation(POOL)
    public int cubeImplies() {
        int sum = 0;
        for (int entry = 0; entry < POOL; entry++) {
            sum += cubes[entry].implies(entry % 2 == 0 ? weaker[entry] : cubes[(entry + 1) % POOL]) ? 1 : 0;
        }
        return sum;
    }

    /** A small set flipped in a large one and back: what the size bookkeeping of a bulk operation costs. */
    @Benchmark
    @OperationsPerInvocation(POOL)
    public int largeXor() {
        for (NatSet set : sets) {
            large.xor(set);
            large.xor(set);
        }
        return large.size();
    }

    /** Adding an element and removing it again, which leaves the pool as it was. */
    @Benchmark
    @OperationsPerInvocation(POOL)
    public int mutableToggle() {
        int sum = 0;
        for (int entry = 0; entry < POOL; entry++) {
            MutableNatSet set = mutables[entry];
            int probe = probes[entry];
            if (set.contains(probe)) {
                set.clear(probe);
                set.set(probe);
            } else {
                set.set(probe);
                set.clear(probe);
            }
            sum += set.size();
        }
        return sum;
    }
}
