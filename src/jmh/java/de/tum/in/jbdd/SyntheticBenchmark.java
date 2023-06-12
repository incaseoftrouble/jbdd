package de.tum.in.jbdd;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.infra.Blackhole;

public class SyntheticBenchmark extends BaseBddBenchmark {
    @Benchmark
    public static void nQueens(BddState state, Blackhole bh) {
        bh.consume(BddBuilder.makeQueens(state.bdd(), 11));
    }

    @Benchmark
    public static void binaryAdder(BddState state, Blackhole bh) {
        bh.consume(BddBuilder.makeAdder(state.bdd(), 1024));
    }
}
