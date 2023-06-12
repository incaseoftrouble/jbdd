package de.tum.in.jbdd;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.CharBuffer;
import java.util.Objects;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

public class DimacsBenchmark extends BaseBddBenchmark {
    @State(Scope.Benchmark)
    public static class DimacsState extends BddState {
        @Param({})
        private String fileName;

        private String content;

        @Setup(Level.Trial)
        public void setUpOperations() throws IOException {
            StringBuilder content = new StringBuilder();
            CharBuffer buffer = CharBuffer.allocate(50 * 1024);
            String resourceName = "dimacs/" + fileName + ".cnf";
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                    Thread.currentThread().getContextClassLoader().getResourceAsStream(resourceName))))) {
                while (reader.read(buffer) >= 0) {
                    buffer.flip();
                    content.append(buffer);
                }
            }
            this.content = content.toString();
        }

        public BufferedReader reader() {
            return new BufferedReader(new StringReader(content));
        }
    }

    // Do not have any good DIMACS benchmarks right now
    // @Benchmark
    public static void benchmarkDimacs(DimacsState state, Blackhole bh)
            throws IOException, DimacsReader.InvalidFormatException {
        Bdd bdd = state.bdd();
        int node = DimacsReader.loadDimacs(bdd, state.reader());
        bh.consume(node == bdd.falseNode());
    }
}
