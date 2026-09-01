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
package de.tum.in.jbdd;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Compares several node-hash schemes on both raw computation speed ({@code @Benchmark} methods below)
 * and hash-chain quality ({@link #main}, a plain collision-rate analysis. Duplicates the relevant slice
 * of {@code NodeTable}'s hashing/chaining logic.
 */
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@BenchmarkMode(Mode.AverageTime)
public class HashSchemeBenchmark {
    static int simpleHash(int variable, int low, int high) {
        int hashCode = low < 0 ? -low + high + variable : low + high + variable;
        return hashCode < 0 ? ~hashCode : hashCode;
    }

    static int fibonacciHash(int variable, int low, int high) {
        int h = variable;
        h = h * 0x9E3779B1 + low;
        h = h * 0x9E3779B1 + high;
        return h & Integer.MAX_VALUE;
    }

    static int finalizerHash(int variable, int low, int high) {
        int h = simpleHash(variable, low, high);
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return h & Integer.MAX_VALUE;
    }

    static int murmur3Hash(int variable, int low, int high) {
        int h = 0;
        h = murmur3MixBlock(h, variable);
        h = murmur3MixBlock(h, low);
        h = murmur3MixBlock(h, high);
        h ^= 12; // 3 blocks * 4 bytes
        return murmur3Fmix32(h) & Integer.MAX_VALUE;
    }

    private static int murmur3MixBlock(int h, int block) {
        int b = block;
        b *= 0xCC9E2D51;
        b = Integer.rotateLeft(b, 15);
        b *= 0x1B873593;
        return Integer.rotateLeft(h ^ b, 13) * 5 + 0xE6546B64;
    }

    private static int murmur3Fmix32(int h) {
        int hh = h;
        hh ^= hh >>> 16;
        hh *= 0x85EBCA6B;
        hh ^= hh >>> 13;
        hh *= 0xC2B2AE35;
        hh ^= hh >>> 16;
        return hh;
    }

    static int fnv1aHash(int variable, int low, int high) {
        int h = 0x811C9DC5; // FNV offset basis
        h = (h ^ variable) * 0x01000193; // FNV prime
        h = (h ^ low) * 0x01000193;
        h = (h ^ high) * 0x01000193;
        return h & Integer.MAX_VALUE;
    }

    static int wangHash(int variable, int low, int high) {
        int h = simpleHash(variable, low, high);
        h = (h ^ 61) ^ (h >>> 16);
        h += h << 3;
        h ^= h >>> 4;
        h *= 0x27D4EB2D;
        h ^= h >>> 15;
        return h & Integer.MAX_VALUE;
    }

    @State(Scope.Benchmark)
    public static class TripleState {
        int[] variable;
        int[] low;
        int[] high;

        @Setup(Level.Trial)
        public void setUp() {
            Triples triples = Triples.fromAdder(1024);
            variable = triples.variable;
            low = triples.low;
            high = triples.high;
        }
    }

    @Benchmark
    public static void current(TripleState state, Blackhole bh) {
        bh.consume(sumOf(state, HashSchemeBenchmark::simpleHash));
    }

    @Benchmark
    public static void fibonacci(TripleState state, Blackhole bh) {
        bh.consume(sumOf(state, HashSchemeBenchmark::fibonacciHash));
    }

    @Benchmark
    public static void finalizer(TripleState state, Blackhole bh) {
        bh.consume(sumOf(state, HashSchemeBenchmark::finalizerHash));
    }

    @Benchmark
    public static void murmur3(TripleState state, Blackhole bh) {
        bh.consume(sumOf(state, HashSchemeBenchmark::murmur3Hash));
    }

    @Benchmark
    public static void fnv1a(TripleState state, Blackhole bh) {
        bh.consume(sumOf(state, HashSchemeBenchmark::fnv1aHash));
    }

    @Benchmark
    public static void wang(TripleState state, Blackhole bh) {
        bh.consume(sumOf(state, HashSchemeBenchmark::wangHash));
    }

    private static int sumOf(TripleState state, TripleHash hash) {
        int[] variable = state.variable;
        int[] low = state.low;
        int[] high = state.high;
        int acc = 0;
        for (int i = 0; i < variable.length; i++) {
            acc ^= hash.hash(variable[i], low[i], high[i]);
        }
        return acc;
    }

    @FunctionalInterface
    private interface TripleHash {
        int hash(int variable, int low, int high);
    }

    private static final class Triples {
        final int[] variable;
        final int[] low;
        final int[] high;

        Triples(int[] variable, int[] low, int[] high) {
            this.variable = variable;
            this.low = low;
            this.high = high;
        }

        /**
         * Builds a real BDD (the existing binary-adder synthetic benchmark, same shape
         * RandomBenchmark/SyntheticBenchmark already use) and reads back every live node's
         * (variable, low, high) triple straight from its {@code NodeTable} - representative
         * construction traffic, not synthetic guesses.
         */
        static Triples fromAdder(int bits) {
            BddImpl bdd = new BddContextImpl(ImmutableBddConfiguration.builder().build()).bdd();
            BddBuilder.makeAdder(bdd, bits);

            NodeTable.Binary table = (NodeTable.Binary) bdd.table();
            List<int[]> collected = new ArrayList<>();
            for (int node = 1; node < table.size(); node++) {
                if (table.isValidDecisionNode(node)) {
                    collected.add(new int[] {table.variable(node), table.low(node), table.high(node)});
                }
            }

            int[] variable = new int[collected.size()];
            int[] low = new int[collected.size()];
            int[] high = new int[collected.size()];
            for (int i = 0; i < collected.size(); i++) {
                int[] triple = collected.get(i);
                variable[i] = triple[0];
                low[i] = triple[1];
                high[i] = triple[2];
            }
            return new Triples(variable, low, high);
        }
    }

    /**
     * Simulates inserting every triple into a {@code NodeTable}-shaped open-chained hash table (same
     * sizing convention: {@code Primes.nextPrime}, one bucket per table row) for each candidate hash
     * function, and reports the resulting chain-length distribution - the quantity that actually
     * determines {@code makeNode}'s lookup cost, which a raw "distinct hash values" count doesn't
     * capture on its own.
     */
    private static void report(String name, Triples triples, TripleHash hash) {
        int n = triples.variable.length;
        int tableSize = Primes.nextPrime(Math.max(1, n));
        int[] chainLength = new int[tableSize];
        for (int i = 0; i < n; i++) {
            int h = hash.hash(triples.variable[i], triples.low[i], triples.high[i]);
            chainLength[Math.floorMod(h, tableSize)]++;
        }

        int max = 0;
        int usedBuckets = 0;
        long sumOfSquares = 0;
        for (int length : chainLength) {
            if (length > 0) {
                usedBuckets++;
            }
            max = Math.max(max, length);
            sumOfSquares += (long) length * length;
        }
        double avgOverUsedBuckets = usedBuckets == 0 ? 0 : (double) n / usedBuckets;
        // Expected chain length actually walked by a lookup for a random key, weighted by chain length
        // itself (landing in a longer chain is proportionally more likely, and proportionally more
        // expensive to walk): sum(length^2) / n, the standard measure for this - plain unweighted
        // average-over-used-buckets underrepresents exactly the long chains that hurt the most.
        double expectedWalkLength = n == 0 ? 0 : (double) sumOfSquares / n;

        System.out.printf( // NOPMD
                "%-10s buckets_used=%d/%d max_chain=%d avg_chain(used_buckets)=%.3f expected_walk=%.3f%n",
                name, usedBuckets, tableSize, max, avgOverUsedBuckets, expectedWalkLength);
    }

    public static void main(String[] args) {
        Triples triples = Triples.fromAdder(1024);
        System.out.printf("Collected %d live decision nodes from a 1024-bit adder%n", triples.variable.length); // NOPMD

        report("current", triples, HashSchemeBenchmark::simpleHash);
        report("fibonacci", triples, HashSchemeBenchmark::fibonacciHash);
        report("finalizer", triples, HashSchemeBenchmark::finalizerHash);
        report("murmur3", triples, HashSchemeBenchmark::murmur3Hash);
        report("fnv1a", triples, HashSchemeBenchmark::fnv1aHash);
        report("wang", triples, HashSchemeBenchmark::wangHash);
    }
}
