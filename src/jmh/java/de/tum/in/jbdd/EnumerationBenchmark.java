/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2017 Tobias Meggendorfer.
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

import de.tum.in.jbdd.collections.Cursor;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Enumerating a diagram's solutions and paths through the public cursors, over the shapes of
 * {@link EnumerationState}, on the identity order and on one every cursor has to translate.
 */
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(2)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Mode.AverageTime)
public class EnumerationBenchmark {
    private static <E> void drain(Cursor<E> cursor, Blackhole bh) {
        while (cursor.valid()) {
            bh.consume(cursor.current());
            cursor.advance();
        }
    }

    @Benchmark
    public void solutions(EnumerationState state, Blackhole bh) {
        drain(state.bdd().solutionCursor(state.function(), state.support()), bh);
    }

    @Benchmark
    public void solutionsInDomain(EnumerationState state, Blackhole bh) {
        drain(state.bdd().solutionCursorIn(state.function(), state.domain(), state.support()), bh);
    }

    @Benchmark
    public void forEachSolution(EnumerationState state, Blackhole bh) {
        state.bdd().forEachSolution(state.function(), state.support(), bh::consume);
    }

    @Benchmark
    public void paths(EnumerationState state, Blackhole bh) {
        drain(state.bdd().pathCursor(state.function()), bh);
    }
}
