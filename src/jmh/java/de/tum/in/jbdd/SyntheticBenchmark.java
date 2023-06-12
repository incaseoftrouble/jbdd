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

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.infra.Blackhole;

public class SyntheticBenchmark extends BaseBddBenchmark {
    @Benchmark
    public static void nQueens(BenchBddState state, Blackhole bh) {
        bh.consume(BddBuilder.makeQueens(state.bdd(), 11));
    }

    @Benchmark
    public static void binaryAdder(BenchBddState state, Blackhole bh) {
        bh.consume(BddBuilder.makeAdder(state.bdd(), 1024));
    }
}
