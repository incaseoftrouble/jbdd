package de.tum.in.jbdd;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.infra.Blackhole;

public class SyntheticSetBenchmark extends BaseBddBenchmark {
    @Benchmark
    public static void nQueens(BddFactoryState state, Blackhole bh) {
        bh.consume(BddBuilder.makeQueensSet(state.factory(), 11));
    }
}
