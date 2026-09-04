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

import java.util.BitSet;
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
 * What it costs to hand out a solution or a path by variable while the walk works by level. Three
 * strategies for solutions and two for paths, all over the same {@link BddImpl.PathWalk}:
 *
 * <ul>
 *   <li>REBUILD - the image of the whole level set is recomputed on every step.
 *   <li>COUNTER - the free-variable counter mirrors the bits it flips; a path move still rebuilds.
 *   <li>WALK - the walk mirrors its own writes too, so nothing is ever rebuilt.
 * </ul>
 *
 * <p>On the identity order all three degenerate to the same loop, which is the control: it says what the
 * mirroring costs when there is nothing to mirror.
 */
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(2)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Mode.AverageTime)
public class EnumerationBenchmark {
    private static BitSet levelsOf(BddImpl bdd, BitSet variables) {
        BitSet levels = new BitSet(bdd.numberOfVariables());
        BitSets.map(variables, levels, bdd::level);
        return levels;
    }

    /** {@link BitSets#increment}, mirroring each flipped bit into {@code translated}. */
    private static boolean increment(BddImpl bdd, BitSet levels, BitSet free, BitSet translated) {
        for (int level = free.nextSetBit(0); level >= 0; level = free.nextSetBit(level + 1)) {
            int variable = bdd.variableAtLevel(level);
            if (levels.get(level)) {
                levels.clear(level);
                translated.clear(variable);
            } else {
                levels.set(level);
                translated.set(variable);
                return true;
            }
        }
        return false;
    }

    @Benchmark
    public void solutionsRebuild(EnumerationState state, Blackhole bh) {
        BddImpl bdd = state.bdd();
        boolean translating = bdd.reordered();
        BitSet supportLevels = translating ? levelsOf(bdd, state.support()) : state.support();
        BitSet translated = translating ? new BitSet(bdd.numberOfVariables()) : null;
        BitSet free = new BitSet(bdd.numberOfVariables());

        BddImpl.PathWalk walk = new BddImpl.PathWalk(bdd, state.function(), bdd.trueFunction());
        if (!walk.onPath()) {
            return;
        }
        BitSets.difference(free, supportLevels, walk.pathSupportLevels());
        while (true) {
            if (translated == null) {
                bh.consume(walk.levelAssignment());
            } else {
                BitSets.map(walk.levelAssignment(), translated, bdd::variableAtLevel);
                bh.consume(translated);
            }
            if (BitSets.increment(walk.levelAssignment(), free)) {
                continue;
            }
            if (!walk.advance()) {
                return;
            }
            BitSets.difference(free, supportLevels, walk.pathSupportLevels());
        }
    }

    @Benchmark
    public void solutionsCounter(EnumerationState state, Blackhole bh) {
        BddImpl bdd = state.bdd();
        boolean translating = bdd.reordered();
        BitSet supportLevels = translating ? levelsOf(bdd, state.support()) : state.support();
        BitSet translated = translating ? new BitSet(bdd.numberOfVariables()) : null;
        BitSet free = new BitSet(bdd.numberOfVariables());

        BddImpl.PathWalk walk = new BddImpl.PathWalk(bdd, state.function(), bdd.trueFunction());
        if (!walk.onPath()) {
            return;
        }
        BitSets.difference(free, supportLevels, walk.pathSupportLevels());
        if (translated != null) {
            BitSets.map(walk.levelAssignment(), translated, bdd::variableAtLevel);
        }
        while (true) {
            bh.consume(translated == null ? walk.levelAssignment() : translated);
            if (translated == null
                    ? BitSets.increment(walk.levelAssignment(), free)
                    : increment(bdd, walk.levelAssignment(), free, translated)) {
                continue;
            }
            if (!walk.advance()) {
                return;
            }
            BitSets.difference(free, supportLevels, walk.pathSupportLevels());
            if (translated != null) {
                BitSets.map(walk.levelAssignment(), translated, bdd::variableAtLevel);
            }
        }
    }

    @Benchmark
    public void solutionsWalk(EnumerationState state, Blackhole bh) {
        BddImpl bdd = state.bdd();
        boolean translating = bdd.reordered();
        BitSet supportLevels = translating ? levelsOf(bdd, state.support()) : state.support();
        BitSet translated = translating ? new BitSet(bdd.numberOfVariables()) : null;
        BitSet free = new BitSet(bdd.numberOfVariables());

        BddImpl.PathWalk walk = new BddImpl.PathWalk(bdd, state.function(), bdd.trueFunction(), translated, null);
        if (!walk.onPath()) {
            return;
        }
        BitSets.difference(free, supportLevels, walk.pathSupportLevels());
        while (true) {
            bh.consume(translated == null ? walk.levelAssignment() : translated);
            if (translated == null
                    ? BitSets.increment(walk.levelAssignment(), free)
                    : increment(bdd, walk.levelAssignment(), free, translated)) {
                continue;
            }
            if (!walk.advance()) {
                return;
            }
            BitSets.difference(free, supportLevels, walk.pathSupportLevels());
        }
    }

    @Benchmark
    public void pathsRebuild(EnumerationState state, Blackhole bh) {
        BddImpl bdd = state.bdd();
        boolean translating = bdd.reordered();
        BitSet assignment = translating ? new BitSet(bdd.numberOfVariables()) : null;
        BitSet support = translating ? new BitSet(bdd.numberOfVariables()) : null;

        BddImpl.PathWalk walk = new BddImpl.PathWalk(bdd, state.function(), bdd.trueFunction());
        while (walk.onPath()) {
            if (assignment == null) {
                bh.consume(walk.levelAssignment());
                bh.consume(walk.pathSupportLevels());
            } else {
                BitSets.map(walk.levelAssignment(), assignment, bdd::variableAtLevel);
                BitSets.map(walk.pathSupportLevels(), support, bdd::variableAtLevel);
                bh.consume(assignment);
                bh.consume(support);
            }
            walk.advance();
        }
    }

    @Benchmark
    public void pathsWalk(EnumerationState state, Blackhole bh) {
        BddImpl bdd = state.bdd();
        boolean translating = bdd.reordered();
        BitSet assignment = translating ? new BitSet(bdd.numberOfVariables()) : null;
        BitSet support = translating ? new BitSet(bdd.numberOfVariables()) : null;

        BddImpl.PathWalk walk = new BddImpl.PathWalk(bdd, state.function(), bdd.trueFunction(), assignment, support);
        while (walk.onPath()) {
            if (assignment == null) {
                bh.consume(walk.levelAssignment());
                bh.consume(walk.pathSupportLevels());
            } else {
                bh.consume(assignment);
                bh.consume(support);
            }
            walk.advance();
        }
    }
}
