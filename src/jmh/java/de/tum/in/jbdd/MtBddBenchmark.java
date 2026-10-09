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

import de.tum.in.jbdd.collections.MutableNatSet;
import de.tum.in.jbdd.collections.NatSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * The object layer's maps as a synthesis tool uses them: edge trees from state variables and inputs to sets of
 * successors, united under a registered operator, mapped, split on the inputs, paired up, inverted and filtered.
 * Every operation runs over the whole pool of maps, so a time is per map. The values are {@link NatSet}s: a value
 * type whose hash code spreads, as a synthesis tool's state sets do - {@code Set<Integer>}, hashing to the sum of
 * its elements, put the 39,000 distinct unions of this pool into a few hundred buckets of the numbering and cost
 * ten times as much.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class MtBddBenchmark {
    private static final int STATE_VARIABLES = 10;
    private static final int INPUT_VARIABLES = 6;
    private static final int SUCCESSORS = 40;

    @Param({"256"})
    public int maps;

    @SuppressWarnings("NullAway.Init")
    private Values<NatSet> values;

    @SuppressWarnings("NullAway.Init")
    private Values<List<NatSet>> pairs;

    @SuppressWarnings("NullAway.Init")
    private Values<BddMap<NatSet>> residuals;

    @SuppressWarnings("NullAway.Init")
    private BddMapBinaryOperator<NatSet> unionOperator;

    @SuppressWarnings("NullAway.Init")
    private BddMap.Operator<NatSet> union;

    @SuppressWarnings("NullAway.Init")
    private List<BddMap<NatSet>> pool;

    @SuppressWarnings("NullAway.Init")
    private NatSet inputs;

    private final Function<NatSet, NatSet> shift = set -> {
        MutableNatSet shifted = MutableNatSet.dense(SUCCESSORS);
        set.forEach((int element) -> shifted.set((element + 1) % SUCCESSORS));
        return NatSet.copyOf(shifted);
    };

    @Setup(Level.Trial)
    public void setup() {
        BinaryFactoryContext context = BinaryFactoryContext.create();
        context.bdd().createVariables(STATE_VARIABLES + INPUT_VARIABLES);
        BddSetFactory sets = context.bddSets();
        values = context.bddMaps().create();
        pairs = context.bddMaps().create();
        residuals = context.bddMaps().create();
        unionOperator = BddMapBinaryOperator.monoid(NatSet::union, NatSet.of());
        union = values.registerApply(unionOperator);

        MutableNatSet inputVariables = MutableNatSet.dense(STATE_VARIABLES + INPUT_VARIABLES);
        inputVariables.set(STATE_VARIABLES, STATE_VARIABLES + INPUT_VARIABLES);
        inputs = NatSet.copyOf(inputVariables);

        Random random = new Random(maps);
        BddMap<NatSet> empty = values.of(NatSet.of());
        pool = new ArrayList<>(maps);
        for (int i = 0; i < maps; i++) {
            BddMap<NatSet> map = empty;
            // A handful of cubes per map, each over some state and input variables and with a small successor set.
            int cubeCount = 4 + random.nextInt(8);
            for (int j = 0; j < cubeCount; j++) {
                MutableNatSet support = MutableNatSet.dense(STATE_VARIABLES + INPUT_VARIABLES);
                MutableNatSet valuation = MutableNatSet.dense(STATE_VARIABLES + INPUT_VARIABLES);
                for (int variable = 0; variable < STATE_VARIABLES + INPUT_VARIABLES; variable++) {
                    if (random.nextInt(3) > 0) {
                        support.set(variable);
                        valuation.set(variable, random.nextBoolean());
                    }
                }
                MutableNatSet successors = MutableNatSet.dense(SUCCESSORS);
                int count = 1 + random.nextInt(3);
                while (successors.size() < count) {
                    successors.set(random.nextInt(SUCCESSORS));
                }
                map = values.ifThenElse(
                        sets.of(Cube.of(valuation, support)), values.of(NatSet.copyOf(successors)), map);
            }
            pool.add(map);
        }
    }

    /** All maps united under the registered operator, pair by pair, as the edge trees of a state's edges are. */
    @Benchmark
    public void unionFold(Blackhole bh) {
        BddMap<NatSet> result = pool.get(0);
        for (int i = 1; i < pool.size(); i++) {
            result = union.apply(result, pool.get(i));
        }
        bh.consume(result);
    }

    /** All maps united in one n-ary application. */
    @Benchmark
    public void unionAll(Blackhole bh) {
        bh.consume(values.apply(pool, unionOperator));
    }

    /** Every map's values rewritten within the numbering. */
    @Benchmark
    public void mapValues(Blackhole bh) {
        for (BddMap<NatSet> map : pool) {
            bh.consume(map.map(shift));
        }
    }

    /** Every map split on the input variables: the residual per input valuation. */
    @Benchmark
    public void splitOnInputs(Blackhole bh) {
        for (BddMap<NatSet> map : pool) {
            bh.consume(map.split(inputs, residuals));
        }
    }

    /** Consecutive maps paired up, as a product construction does. */
    @Benchmark
    public void pairUp(Blackhole bh) {
        for (int i = 0; i + 1 < pool.size(); i += 2) {
            bh.consume(values.cartesianProduct(List.of(pool.get(i), pool.get(i + 1)), pairs));
        }
    }

    /** Every map inverted: the domain of each value. */
    @Benchmark
    public void inverse(Blackhole bh) {
        for (BddMap<NatSet> map : pool) {
            bh.consume(map.inverse());
        }
    }

    /** The domain on which a map has any successor at all. */
    @Benchmark
    public void whereNonEmpty(Blackhole bh) {
        for (BddMap<NatSet> map : pool) {
            bh.consume(map.where(set -> !set.isEmpty()));
        }
    }
}
