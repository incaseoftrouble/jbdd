/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2017-2026 Tobias Meggendorfer.
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

import de.tum.in.jbdd.collections.MutableNatSet;
import de.tum.in.jbdd.collections.NatSet;
import de.tum.in.jbdd.collections.NatSetBenchmark;
import de.tum.in.jbdd.collections.NatSetBenchmark.Shape;
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

/**
 * The cube tests over a pool of {@link #POOL} random cubes, the supports and assignments drawn in
 * {@link NatSetBenchmark}'s shapes, so a time is per cube, over cubes that differ.
 */
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@BenchmarkMode(Mode.AverageTime)
@State(Scope.Benchmark)
public class CubeBenchmark {
    static final int POOL = 1024;

    @SuppressWarnings("NullAway.Init")
    @Param({"SINGLETON", "TINY", "SMALL", "MEDIUM", "SPARSE"})
    private Shape shape;

    /** The supports. */
    private final NatSet[] sets = new NatSet[POOL];
    /** Another set of the same shape per entry: the valuation, as far as the support goes. */
    private final NatSet[] others = new NatSet[POOL];
    /** Per entry, the cube fixing the entry's variables as the other set does. */
    private final Cube[] cubes = new Cube[POOL];
    /** Per entry, the same cube fixing fewer of them (every other entry), which it implies. */
    private final Cube[] weaker = new Cube[POOL];

    @Setup(Level.Trial)
    public void setUp() {
        Random random = new Random(42L);
        for (int entry = 0; entry < POOL; entry++) {
            sets[entry] = NatSet.of(NatSetBenchmark.draw(random, shape.size(random), shape.span));
            others[entry] = NatSet.of(NatSetBenchmark.draw(random, shape.size(random), shape.span));
            cubes[entry] = Cube.of(others[entry], sets[entry]);
            MutableNatSet fewer = MutableNatSet.create();
            sets[entry].forEach(variable -> {
                if (random.nextInt(3) > 0) {
                    fewer.set(variable);
                }
            });
            weaker[entry] = Cube.of(others[entry], fewer);
        }
    }

    /** Whether a valuation satisfies a cube: the other set, which it fixes alike, or the entry. */
    @Benchmark
    @OperationsPerInvocation(POOL)
    public int contains() {
        int sum = 0;
        for (int entry = 0; entry < POOL; entry++) {
            sum += cubes[entry].contains(entry % 2 == 0 ? others[entry] : sets[entry]) ? 1 : 0;
        }
        return sum;
    }

    /** Whether a cube implies a weaker one of its own (every other entry) or another entry's cube. */
    @Benchmark
    @OperationsPerInvocation(POOL)
    public int implies() {
        int sum = 0;
        for (int entry = 0; entry < POOL; entry++) {
            sum += cubes[entry].implies(entry % 2 == 0 ? weaker[entry] : cubes[(entry + 1) % POOL]) ? 1 : 0;
        }
        return sum;
    }

    /** Whether two cubes intersect: an entry's own weaker cube (every other entry) or another entry's cube. */
    @Benchmark
    @OperationsPerInvocation(POOL)
    public int intersects() {
        int sum = 0;
        for (int entry = 0; entry < POOL; entry++) {
            sum += cubes[entry].intersects(entry % 2 == 0 ? weaker[entry] : cubes[(entry + 1) % POOL]) ? 1 : 0;
        }
        return sum;
    }
}
