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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.common.collect.Streams;
import de.tum.in.jbdd.Generator.Info;
import de.tum.in.jbdd.Generator.UnaryDataPoint;
import de.tum.in.jbdd.collections.IntIntHashMap;
import de.tum.in.jbdd.collections.MutableNatSet;
import de.tum.in.jbdd.collections.NatSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.IntBinaryOperator;
import java.util.function.IntUnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@SuppressWarnings({
    "AccessingNonPublicFieldOfAnotherObject",
    "StaticCollection",
    "NewClassNamingConvention",
    "PMD.ClassNamingConventions",
    "PMD.CouplingBetweenObjects"
})
@TestInstance(Lifecycle.PER_CLASS)
@ExtendWith(FailFastExtension.class)
class MtBddTheories {
    private static final Logger logger = Logger.getLogger(MtBddTheories.class.getName());
    /* See BddTheories#SKIP_CHECK_RANDOM_BOUND: scaled so a shrunk run keeps the expected number of
     * checks rather than their per-theory frequency. */
    private static final int SKIP_CHECK_RANDOM_BOUND = TestProfile.scaled(200);
    private static final int MAX_FAILED_SAMPLES = 20;

    private static final int MAX_ASSIGNMENT_VARIABLES = 12;
    private static final int QUADRATIC_MAX_ASSIGNMENT_VARIABLES = 6;
    private static final double FACTOR = 0.25;

    private static final int variableCount = 16;
    private static final int boolTreeDepth = 16;
    private static final int boolTreeWidth = 20;
    private static final int boolUnaryCount = TestProfile.scaled((int) (FACTOR * 120));
    private static final int boolBinaryCount = TestProfile.scaled((int) (FACTOR * 60));
    private static final int intSeedConstants = 24;
    private static final int intPoolTargetSize = TestProfile.scaled((int) (FACTOR * 300));
    private static final int intPoolGrowthIterations = TestProfile.scaled((int) (FACTOR * 800));
    private static final int intUnaryCount = TestProfile.scaled((int) (FACTOR * 1000));
    private static final int intBinaryCount = TestProfile.scaled((int) (FACTOR * 1000));
    private static final int intTernaryCount = TestProfile.scaled((int) (FACTOR * 800));
    private static final int intConditionalCount = TestProfile.scaled((int) (FACTOR * 800));
    private static final int valueRange = 2048;
    private static final int valueMod = 997;

    /* Three variants, because the three fail differently: never reordered (level == variable throughout,
     * the control), reordered with the bookkeeping rebuilt per reorder, and reordered with the bookkeeping
     * kept across operations. The MTBDD shares its companion BDD's order, so reordering either moves the
     * nodes of both. */
    private static final List<Context> contexts;

    private static final List<Context> reorderStressed;

    // Variable indices, so the same for every context.
    private static final MutableNatSet splitVariables;
    private static final MutableNatSet restrictedVariables;
    private static final MutableNatSet restrictedVariableValues;

    private static final Collection<IntUnaryDataPoint> intUnary;
    private static final Collection<IntBinaryDataPoint> intBinary;
    private static final Collection<IntTernaryDataPoint> intTernary;
    private static final Collection<IntConditionalDataPoint> intConditional;

    /* Every so many theories, not at random: skipCheckRandom is a per-instance field and JUnit builds a
     * fresh instance per test, so drawing from it gives the same answer every time. Checking the tables
     * costs several times a round of swaps, hence only every REORDER_CHECK_EVERY rounds - see
     * BddTheories#occasionallyReorder, which this mirrors. */
    private static final int REORDER_EVERY = TestProfile.scaled(200, 20);
    private static final int REORDER_SWAPS = 4;
    private static final int REORDER_CHECK_EVERY = 5;
    private static final AtomicInteger THEORIES_RUN = new AtomicInteger();
    private static final Random reorderRandom = new Random(1L);

    private static final List<NamedBinaryOp> BINARY_OPS = List.of(
            new NamedBinaryOp("sum", (a, b) -> (a + b) % valueMod),
            new NamedBinaryOp("max", Math::max),
            new NamedBinaryOp("first", (a, b) -> a),
            new NamedBinaryOp("weighted", (a, b) -> (a * 7 + b * 3 + 1) % valueMod));
    private static final List<NamedUnaryOp> UNARY_OPS = List.of(
            new NamedUnaryOp("identity", a -> a),
            new NamedUnaryOp("increment", a -> (a + 1) % valueMod),
            new NamedUnaryOp("square", a -> (int) (((long) a * a) % valueMod)));
    private static final List<NamedMonoidOp> MONOID_OPS = List.of(
            new NamedMonoidOp("sum", Integer::sum, 0, NamedMonoidOp.NONE),
            new NamedMonoidOp("max", Math::max, 0, valueRange),
            new NamedMonoidOp("min", Math::min, valueRange, 0));

    /* The variables the ternary data points' operands replace in residual products. They are among the operands'
     * variables on purpose: a residual speaks about the function's variables only. */
    private static final int[] SLOT_VARIABLES = {3, 7, 11};

    private static final List<NamedValuation> VALUATIONS = List.of(
            new NamedValuation("by residue", (variable, value) -> truth(value % 3)),
            new NamedValuation("per variable", (variable, value) -> truth((value + variable) % 4)),
            new NamedValuation("undecided", PartialValuation.undecided()),
            new NamedValuation("total", (variable, value) -> truth(value % 2)));
    // Truth tables over three slots, bit s_0 + 2 s_1 + 4 s_2: false, true, s_0, s_0 & s_1 & s_2, s_0 & (s_1 | s_2),
    // the majority and the parity.
    private static final int[] SLOT_TABLES = {0, 255, 0b10101010, 0b10000000, 0b10101000, 0b11101000, 0b10010110};
    private static final int CONJUNCTION_TABLE = 0b10000000;
    // An operator diagram of its own: the operator never shares the operands' variables or their order.
    private static final BddImpl OPERATOR_DIAGRAM = operatorDiagram();

    private final Random skipCheckRandom = new Random(0L);

    static {
        contexts = List.of(
                new Context("mtbdd", false),
                new Context("mtbdd-reordered", false),
                new Context("mtbdd-reordered-keeping", true));
        reorderStressed = List.of(contexts.get(1), contexts.get(2));

        splitVariables = MutableNatSet.dense(variableCount);
        for (int v = 0; v < variableCount; v += 2) {
            splitVariables.set(v);
        }

        restrictedVariables = MutableNatSet.dense(variableCount);
        restrictedVariableValues = MutableNatSet.dense(variableCount);
        for (int v = 0; v < variableCount; v += 2) {
            restrictedVariables.set(v);
            restrictedVariableValues.set(v, v % 4 == 0);
        }

        intUnary = flatten(context -> context.intUnary);
        intBinary = flatten(context -> context.intBinary);
        intTernary = flatten(context -> context.intTernary);
        intConditional = flatten(context -> context.intConditional);
    }

    private static <T> Collection<T> flatten(Function<Context, Collection<T>> select) {
        List<T> all = new ArrayList<>();
        for (Context context : contexts) {
            all.addAll(select.apply(context));
        }
        return all;
    }

    private static List<IntPoolEntry> buildIntPool(Context context, Random random, Info<BddImpl> boolInfo) {
        MtBddImpl mt = context.mt;
        List<IntPoolEntry> pool = new ArrayList<>();

        for (int i = 0; i < intSeedConstants; i++) {
            int value = random.nextBoolean() ? random.nextInt(64) : random.nextInt(valueRange);
            int function = mt.reference(mt.of(value));
            pool.add(new IntPoolEntry(function, IntSyntaxTree.constant(value)));
        }
        for (int v = 0; v < variableCount; v++) {
            int trueValue = random.nextBoolean() ? random.nextInt(64) : random.nextInt(valueRange);
            int falseValue = random.nextBoolean() ? random.nextInt(64) : random.nextInt(valueRange);
            int trueChild = mt.reference(mt.of(trueValue));
            int falseChild = mt.reference(mt.of(falseValue));
            int function = mt.reference(mt.of(v, trueChild, falseChild));
            mt.dereference(trueChild);
            mt.dereference(falseChild);
            pool.add(new IntPoolEntry(
                    function,
                    IntSyntaxTree.variableSplit(
                            v, IntSyntaxTree.constant(trueValue), IntSyntaxTree.constant(falseValue))));
        }

        List<UnaryDataPoint<BddImpl>> conditions = new ArrayList<>(boolInfo.unaryDataPoints);
        for (int iteration = 0; iteration < intPoolGrowthIterations && pool.size() < intPoolTargetSize; iteration++) {
            IntPoolEntry left = pool.get(random.nextInt(pool.size()));
            IntPoolEntry created;
            switch (random.nextInt(4)) {
                case 0: {
                    IntPoolEntry right = pool.get(random.nextInt(pool.size()));
                    NamedBinaryOp namedOp = BINARY_OPS.get(random.nextInt(BINARY_OPS.size()));
                    int function = mt.reference(mt.apply(left.function, right.function, namedOp.op));
                    created = new IntPoolEntry(
                            function, IntSyntaxTree.apply(left.tree, right.tree, namedOp.op, namedOp.label));
                    break;
                }
                case 1: {
                    NamedUnaryOp namedOp = UNARY_OPS.get(random.nextInt(UNARY_OPS.size()));
                    int function = mt.reference(mt.map(left.function, namedOp.op));
                    created = new IntPoolEntry(function, IntSyntaxTree.map(left.tree, namedOp.op, namedOp.label));
                    break;
                }
                case 2: {
                    IntPoolEntry elseEntry = pool.get(random.nextInt(pool.size()));
                    UnaryDataPoint<BddImpl> condition = conditions.get(random.nextInt(conditions.size()));
                    int function = mt.reference(mt.ifThenElse(condition.function, left.function, elseEntry.function));
                    created = new IntPoolEntry(
                            function, IntSyntaxTree.ifThenElse(condition.tree, left.tree, elseEntry.tree));
                    break;
                }
                default:
                    UnaryDataPoint<BddImpl> condition = conditions.get(random.nextInt(conditions.size()));
                    int value = random.nextInt(valueRange);
                    int function = mt.reference(mt.update(left.function, condition.function, value));
                    created = new IntPoolEntry(function, IntSyntaxTree.update(condition.tree, value, left.tree));
                    break;
            }
            pool.add(created);
        }
        return pool;
    }

    private static Collection<IntUnaryDataPoint> sampleUnary(
            Context context, List<IntPoolEntry> pool, int count, Random random) {
        Set<Integer> used = new LinkedHashSet<>();
        List<IntUnaryDataPoint> result = new ArrayList<>();
        int failed = 0;
        while (result.size() < count && failed < MAX_FAILED_SAMPLES) {
            int index = random.nextInt(pool.size());
            if (used.add(index)) {
                result.add(new IntUnaryDataPoint(context, pool.get(index)));
                failed = 0;
            } else {
                failed++;
            }
        }
        return result;
    }

    private static Collection<IntBinaryDataPoint> sampleBinary(
            Context context, List<IntPoolEntry> pool, int count, Random random) {
        Set<Long> used = new LinkedHashSet<>();
        List<IntBinaryDataPoint> result = new ArrayList<>();
        int failed = 0;
        while (result.size() < count && failed < MAX_FAILED_SAMPLES) {
            int i = random.nextInt(pool.size());
            int j = random.nextInt(pool.size());
            if (used.add(((long) i << 32) | (j & 0xFFFFFFFFL))) {
                result.add(new IntBinaryDataPoint(context, pool.get(i), pool.get(j)));
                failed = 0;
            } else {
                failed++;
            }
        }
        return result;
    }

    private static Collection<IntTernaryDataPoint> sampleTernary(
            Context context, List<IntPoolEntry> pool, int count, Random random) {
        List<IntTernaryDataPoint> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(new IntTernaryDataPoint(
                    context,
                    pool.get(random.nextInt(pool.size())),
                    pool.get(random.nextInt(pool.size())),
                    pool.get(random.nextInt(pool.size()))));
        }
        return result;
    }

    private static Collection<IntConditionalDataPoint> sampleConditional(
            Context context, List<IntPoolEntry> pool, Info<BddImpl> boolInfo, int count, Random random) {
        List<UnaryDataPoint<BddImpl>> conditions = new ArrayList<>(boolInfo.unaryDataPoints);
        List<IntConditionalDataPoint> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(new IntConditionalDataPoint(
                    context,
                    conditions.get(random.nextInt(conditions.size())),
                    pool.get(random.nextInt(pool.size())),
                    pool.get(random.nextInt(pool.size()))));
        }
        return result;
    }

    private static Iterable<boolean[]> assignmentsOver(int maxVariables, NatSet... variableSets) {
        return ScopedAssignments.of(variableCount, maxVariables, variableSets);
    }

    private static Iterable<boolean[]> assignmentsOver(NatSet... variableSets) {
        return assignmentsOver(MAX_ASSIGNMENT_VARIABLES, variableSets);
    }

    static Stream<IntUnaryDataPoint> intUnary() {
        return intUnary.stream();
    }

    static Stream<IntBinaryDataPoint> intBinary() {
        return intBinary.stream();
    }

    static Stream<IntTernaryDataPoint> intTernary() {
        return intTernary.stream();
    }

    static Stream<IntConditionalDataPoint> intConditional() {
        return intConditional.stream();
    }

    @BeforeAll
    static void dummy() {
        // Dummy method to separate static initialization from actual test running times.
        logger.log(Level.FINE, "Before class");
    }

    @AfterAll
    static void check() {
        checkAll();
        // See BddTheories#check: a stress that silently became a no-op is worse than no stress.
        for (Context context : reorderStressed) {
            assertThat(context.name + " never left the identity order", isReordered(context.mt), is(true));
        }
    }

    private static boolean isReordered(MtBddImpl mt) {
        for (int variable = 0; variable < mt.numberOfVariables(); variable++) {
            if (mt.levelOfVariable(variable) != variable) {
                return true;
            }
        }
        return false;
    }

    private static void checkAll() {
        for (Context context : contexts) {
            assertThat(context.name, context.mt.check(), is(true));
        }
    }

    @AfterAll
    static void statistics() {
        for (Context context : contexts) {
            logger.log(Level.INFO, Statistics.formatStatistics(context.ddContext.statistics()));
        }
    }

    @AfterEach
    void clearCaches() {
        if (skipCheckRandom.nextInt(100) == 0) {
            for (Context context : contexts) {
                context.mt.invalidateCache();
            }
        }
    }

    /* Stresses the variable order against everything the theories hold: a swap rewrites nodes in place, so
     * every data point's function id has to keep denoting the same function afterwards - which the next
     * theory, and the table check, then verify. What matters is *having* a non-identity order, which is
     * what catches level/variable confusions; a handful of swaps gives that far more cheaply than a full
     * reorder(). reorder() itself is covered by ReorderTest. */
    @AfterEach
    void occasionallyReorder() {
        if (THEORIES_RUN.incrementAndGet() % REORDER_EVERY != 0) {
            return;
        }
        for (Context context : reorderStressed) {
            for (int i = 0; i < REORDER_SWAPS; i++) {
                context.ddContext.variableOrder().siftDown(reorderRandom.nextInt(variableCount - 1));
            }
        }
        if (THEORIES_RUN.get() / REORDER_EVERY % REORDER_CHECK_EVERY == 0) {
            checkAll();
        }
    }

    @AfterEach
    void checkInvariants() {
        if (skipCheckRandom.nextInt(SKIP_CHECK_RANDOM_BOUND) == 0) {
            checkAll();
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testOfWithEqualChildrenCollapses(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        int function = mt.of(0, dataPoint.function, dataPoint.function);
        assertThat(function, is(dataPoint.function));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intBinary")
    void testBinaryApplyEvaluateAgreement(IntBinaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        assumeTrue(mt.isValidFunction(dataPoint.left) && mt.isValidFunction(dataPoint.right));
        MutableNatSet relevant =
                NatSetFixtures.union(dataPoint.leftTree.containedVariables(), dataPoint.rightTree.containedVariables());
        for (NamedBinaryOp namedOp : BINARY_OPS) {
            int applied = mt.reference(mt.apply(dataPoint.left, dataPoint.right, namedOp.op));
            for (boolean[] assignment : assignmentsOver(relevant)) {
                int expected = namedOp.op.applyAsInt(
                        dataPoint.leftTree.evaluate(assignment), dataPoint.rightTree.evaluate(assignment));
                assertThat(mt.evaluate(applied, assignment), is(expected));
            }
            mt.dereference(applied);
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intTernary")
    void testNaryApplyEvaluateAgreement(IntTernaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        int[] functions = {dataPoint.first, dataPoint.second, dataPoint.third};
        int applied =
                mt.reference(mt.apply(functions, values -> (values[0] + values[1] * 2 + values[2] * 3) % valueMod));
        MutableNatSet relevant = MutableNatSet.copyOf(dataPoint.firstTree.containedVariables());
        relevant.or(dataPoint.secondTree.containedVariables());
        relevant.or(dataPoint.thirdTree.containedVariables());
        for (boolean[] assignment : assignmentsOver(relevant)) {
            int expected = (dataPoint.firstTree.evaluate(assignment)
                            + dataPoint.secondTree.evaluate(assignment) * 2
                            + dataPoint.thirdTree.evaluate(assignment) * 3)
                    % valueMod;
            assertThat(mt.evaluate(applied, assignment), is(expected));
        }
        mt.dereference(applied);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testMapEvaluateAgreement(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        MutableNatSet relevant = MutableNatSet.copyOf(dataPoint.tree.containedVariables());
        for (NamedUnaryOp namedOp : UNARY_OPS) {
            int mapped = mt.reference(mt.map(dataPoint.function, namedOp.op));
            for (boolean[] assignment : assignmentsOver(relevant)) {
                assertThat(
                        mt.evaluate(mapped, assignment),
                        is(namedOp.op.applyAsInt(dataPoint.tree.evaluate(assignment))));
            }
            mt.dereference(mapped);
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testApplyOneArgumentMatchesMap(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        // Unary apply is just map

        IntUnaryOperator op = a -> (a * 3 + 1) % valueMod;
        int viaMap = mt.reference(mt.map(dataPoint.function, op));
        int viaApply = mt.reference(mt.apply(new int[] {dataPoint.function}, values -> op.applyAsInt(values[0])));
        assertThat(viaApply, is(viaMap));
        mt.dereference(viaMap, viaApply);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intBinary")
    void testNaryApplyTwoArgumentsMatchesBinaryApply(IntBinaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        // 2-ary apply is just normal apply (this is probably trivial as the implementation delegates, but it tests the
        // delegation is correct)

        for (NamedBinaryOp namedOp : BINARY_OPS) {
            int viaBinary = mt.reference(mt.apply(dataPoint.left, dataPoint.right, namedOp.op));
            int viaNary = mt.reference(mt.apply(
                    new int[] {dataPoint.left, dataPoint.right},
                    values -> namedOp.op.applyAsInt(values[0], values[1])));
            assertThat(viaNary, is(viaBinary));
            mt.dereference(viaBinary, viaNary);
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intBinary")
    void testBinaryApplyAlgebraicVariantsAgree(IntBinaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        // applyCommutative/applyMonoid/applyAbsorbing are all just opt-in shortcuts on top of plain apply -
        // for a genuine commutative monoid (with or without an absorbing element), every variant must
        // produce the exact same canonical function as plain apply.
        for (NamedMonoidOp namedOp : MONOID_OPS) {
            int plain = mt.reference(mt.apply(dataPoint.left, dataPoint.right, namedOp.op));
            int commutative = mt.reference(mt.applyCommutative(dataPoint.left, dataPoint.right, namedOp.op));
            int monoid = mt.reference(mt.applyMonoid(dataPoint.left, dataPoint.right, namedOp.op, namedOp.neutral));
            assertThat(namedOp.label, commutative, is(plain));
            assertThat(namedOp.label, monoid, is(plain));
            if (namedOp.absorbing != NamedMonoidOp.NONE) {
                int absorbing =
                        mt.reference(mt.applyAbsorbing(dataPoint.left, dataPoint.right, namedOp.op, namedOp.absorbing));
                int both = mt.reference(mt.applyMonoid(
                        dataPoint.left, dataPoint.right, namedOp.op, namedOp.neutral, namedOp.absorbing));
                assertThat(namedOp.label, absorbing, is(plain));
                assertThat(namedOp.label, both, is(plain));
                mt.dereference(absorbing, both);
            }
            mt.dereference(plain, commutative, monoid);
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testBinaryApplyNeutralShortcutIsExact(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        // The neutral shortcut must return the OTHER operand unchanged (same raw function, not just an
        // equivalent one) even when that operand is a large, non-constant subtree - not just when both
        // sides happen to already be constants.
        for (NamedMonoidOp namedOp : MONOID_OPS) {
            int neutral = mt.reference(mt.of(namedOp.neutral));
            int viaRight = mt.reference(mt.applyMonoid(dataPoint.function, neutral, namedOp.op, namedOp.neutral));
            int viaLeft = mt.reference(mt.applyMonoid(neutral, dataPoint.function, namedOp.op, namedOp.neutral));
            assertThat(namedOp.label, viaRight, is(dataPoint.function));
            assertThat(namedOp.label, viaLeft, is(dataPoint.function));
            mt.dereference(neutral, viaRight, viaLeft);
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testBinaryApplyAbsorbingShortcutIsExact(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        // The absorbing shortcut must return the absorbing constant itself immediately, regardless of the
        // other (possibly large, non-constant) operand's structure or position.
        for (NamedMonoidOp namedOp : MONOID_OPS) {
            if (namedOp.absorbing == NamedMonoidOp.NONE) {
                continue;
            }
            int absorbingConst = mt.reference(mt.of(namedOp.absorbing));
            int viaRight =
                    mt.reference(mt.applyAbsorbing(dataPoint.function, absorbingConst, namedOp.op, namedOp.absorbing));
            int viaLeft =
                    mt.reference(mt.applyAbsorbing(absorbingConst, dataPoint.function, namedOp.op, namedOp.absorbing));
            assertThat(namedOp.label, viaRight, is(absorbingConst));
            assertThat(namedOp.label, viaLeft, is(absorbingConst));
            mt.dereference(absorbingConst, viaRight, viaLeft);
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intTernary")
    void testNaryApplyAlgebraicVariantsAgree(IntTernaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        int[] functions = {dataPoint.first, dataPoint.second, dataPoint.third};
        for (NamedMonoidOp namedOp : MONOID_OPS) {
            int plain = mt.reference(mt.apply(functions, namedOp::applyNary));
            int monoid = mt.reference(mt.applyMonoid(functions, namedOp::applyNary, namedOp.neutral));
            assertThat(namedOp.label, monoid, is(plain));
            if (namedOp.absorbing != NamedMonoidOp.NONE) {
                int absorbing = mt.reference(mt.applyAbsorbing(functions, namedOp::applyNary, namedOp.absorbing));
                int both =
                        mt.reference(mt.applyMonoid(functions, namedOp::applyNary, namedOp.neutral, namedOp.absorbing));
                assertThat(namedOp.label, absorbing, is(plain));
                assertThat(namedOp.label, both, is(plain));
                mt.dereference(absorbing, both);
            }
            mt.dereference(plain, monoid);
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testNaryApplyNeutralShortcutIsExact(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        // All-but-one operands constant-equal to neutral must collapse to the one survivor, unchanged, even
        // when the survivor is a large non-constant subtree.
        for (NamedMonoidOp namedOp : MONOID_OPS) {
            int neutral = mt.reference(mt.of(namedOp.neutral));
            for (int survivorPosition = 0; survivorPosition < 3; survivorPosition++) {
                int[] functions = {neutral, neutral, neutral};
                functions[survivorPosition] = dataPoint.function;
                int result = mt.reference(mt.applyMonoid(functions, namedOp::applyNary, namedOp.neutral));
                assertThat(namedOp.label + "@" + survivorPosition, result, is(dataPoint.function));
                mt.dereference(result);
            }
            // fully-neutral degenerate case: no survivor at all.
            int[] allNeutral = {neutral, neutral, neutral};
            int result = mt.reference(mt.applyMonoid(allNeutral, namedOp::applyNary, namedOp.neutral));
            assertThat(namedOp.label, result, is(neutral));
            mt.dereference(neutral, result);
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intBinary")
    void testNaryApplyAbsorbingShortcutIsExact(IntBinaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        // The absorbing shortcut must fire regardless of which position holds the absorbing constant, and
        // regardless of the other (possibly large, non-constant) operands.
        for (NamedMonoidOp namedOp : MONOID_OPS) {
            if (namedOp.absorbing == NamedMonoidOp.NONE) {
                continue;
            }
            int absorbingConst = mt.reference(mt.of(namedOp.absorbing));
            for (int absorbingPosition = 0; absorbingPosition < 3; absorbingPosition++) {
                int[] functions = {dataPoint.left, dataPoint.right, dataPoint.left};
                functions[absorbingPosition] = absorbingConst;
                int result = mt.reference(mt.applyAbsorbing(functions, namedOp::applyNary, namedOp.absorbing));
                assertThat(namedOp.label + "@" + absorbingPosition, result, is(absorbingConst));
                mt.dereference(result);
            }
            mt.dereference(absorbingConst);
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testApplyDoesNotAssumeIdempotence(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        int applied = mt.reference(mt.apply(dataPoint.function, dataPoint.function, (a, b) -> (a + b) % valueMod));
        for (boolean[] assignment : assignmentsOver(dataPoint.tree.containedVariables())) {
            int value = dataPoint.tree.evaluate(assignment);
            assertThat(mt.evaluate(applied, assignment), is((value + value) % valueMod));
        }
        mt.dereference(applied);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testNaryApplyIsPositionSensitive(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        int applied = mt.reference(
                mt.apply(new int[] {dataPoint.function, dataPoint.function}, values -> values[0] * 31 + values[1] + 1));
        for (boolean[] assignment : assignmentsOver(dataPoint.tree.containedVariables())) {
            int value = dataPoint.tree.evaluate(assignment);
            assertThat(mt.evaluate(applied, assignment), is(value * 31 + value + 1));
        }
        mt.dereference(applied);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intBinary")
    void testAgreementMatchesEvaluateEquality(IntBinaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        BddImpl bdd = dataPoint.context.bdd;
        int agreement = bdd.reference(mt.agreement(dataPoint.left, dataPoint.right));
        MutableNatSet relevant =
                NatSetFixtures.union(dataPoint.leftTree.containedVariables(), dataPoint.rightTree.containedVariables());
        for (boolean[] assignment : assignmentsOver(relevant)) {
            boolean expected = dataPoint.leftTree.evaluate(assignment) == dataPoint.rightTree.evaluate(assignment);
            assertThat(bdd.evaluate(agreement, assignment), is(expected));
        }
        bdd.dereference(agreement);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intBinary")
    void testAllMatchMatchesApplyBoolean(IntBinaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        BddImpl bdd = dataPoint.context.bdd;
        MutableNatSet relevant =
                NatSetFixtures.union(dataPoint.leftTree.containedVariables(), dataPoint.rightTree.containedVariables());
        // Every combination of the claims the traversal exploits: both, reflexive, neither, symmetric.
        List<MtBddBinaryPredicate> predicates = List.of(
                MtBddBinaryPredicate.equality(),
                MtBddBinaryPredicate.of((a, b) -> a <= b, false, true),
                MtBddBinaryPredicate.of((a, b) -> a < b, false, false),
                MtBddBinaryPredicate.of((a, b) -> (a + b) % 2 == 0, true, false));
        for (MtBddBinaryPredicate predicate : predicates) {
            boolean expected = true;
            for (boolean[] assignment : assignmentsOver(relevant)) {
                if (!predicate.test(
                        dataPoint.leftTree.evaluate(assignment), dataPoint.rightTree.evaluate(assignment))) {
                    expected = false;
                    break;
                }
            }
            assertThat(mt.allMatch(dataPoint.left, dataPoint.right, predicate), is(expected));
            // A second time, from a warm cache.
            assertThat(mt.allMatch(dataPoint.left, dataPoint.right, predicate), is(expected));
            assertThat(mt.applyBoolean(dataPoint.left, dataPoint.right, predicate) == bdd.trueFunction(), is(expected));
        }
        assertThat(mt.allMatch(dataPoint.left, dataPoint.left, MtBddBinaryPredicate.equality()), is(true));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testAgreementReflexive(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        BddImpl bdd = dataPoint.context.bdd;
        assertThat(mt.agreement(dataPoint.function, dataPoint.function), is(bdd.trueFunction()));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intBinary")
    void testAgreementSymmetric(IntBinaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        BddImpl bdd = dataPoint.context.bdd;
        int forward = bdd.reference(mt.agreement(dataPoint.left, dataPoint.right));
        int backward = mt.agreement(dataPoint.right, dataPoint.left);
        assertThat(forward, is(backward));
        bdd.dereference(forward);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testMapBooleanMatchesPredicateThreshold(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        BddImpl bdd = dataPoint.context.bdd;
        var values = mt.valuesOf(dataPoint.function);
        int threshold = values.intStream().skip(values.size() / 2).findFirst().orElse(25);
        int mapped = bdd.reference(mt.mapBoolean(dataPoint.function, v -> v < threshold));
        for (boolean[] assignment : assignmentsOver(dataPoint.tree.containedVariables())) {
            assertThat(bdd.evaluate(mapped, assignment), is(dataPoint.tree.evaluate(assignment) < threshold));
        }
        bdd.dereference(mapped);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testMapBooleanMatchesPredicateMod(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        BddImpl bdd = dataPoint.context.bdd;
        int mapped = bdd.reference(mt.mapBoolean(dataPoint.function, v -> v % 2 == 0));
        for (boolean[] assignment : assignmentsOver(dataPoint.tree.containedVariables())) {
            assertThat(bdd.evaluate(mapped, assignment), is(dataPoint.tree.evaluate(assignment) % 2 == 0));
        }
        bdd.dereference(mapped);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testMapBooleanConstantPredicates(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        BddImpl bdd = dataPoint.context.bdd;
        assertThat(mt.mapBoolean(dataPoint.function, v -> true), is(bdd.trueFunction()));
        assertThat(mt.mapBoolean(dataPoint.function, v -> false), is(bdd.falseFunction()));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intBinary")
    void testAgreementMapBooleanCrossCheck(IntBinaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        BddImpl bdd = dataPoint.context.bdd;
        int viaAgreement = bdd.reference(mt.agreement(dataPoint.left, dataPoint.right));
        int equalityValued = mt.reference(mt.apply(dataPoint.left, dataPoint.right, (a, b) -> a == b ? 1 : 0));
        int viaMapBoolean = bdd.reference(mt.mapBoolean(equalityValued, v -> v == 1));
        // Both describe "where left and right agree"
        assertThat(viaAgreement, is(viaMapBoolean));
        mt.dereference(equalityValued);
        bdd.dereference(viaAgreement, viaMapBoolean);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testInvertSupportMatchesValuesOf(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        MultiTerminalDecisionDiagram.FunctionToFunctionMap inverse = mt.invert(dataPoint.function);
        assertThat(inverse.codomain(), is(mt.valuesOf(dataPoint.function)));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testInvertFunctionForMatchesEvaluate(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        BddImpl bdd = dataPoint.context.bdd;
        MultiTerminalDecisionDiagram.FunctionToFunctionMap inverse = mt.invert(dataPoint.function);
        NatSet values = mt.valuesOf(dataPoint.function);
        for (int value = values.nextSetBit(0); value >= 0; value = values.nextSetBit(value + 1)) {
            int bddFunction = bdd.reference(inverse.functionFor(value));
            for (boolean[] assignment : assignmentsOver(dataPoint.tree.containedVariables())) {
                assertThat(bdd.evaluate(bddFunction, assignment), is(dataPoint.tree.evaluate(assignment) == value));
            }
            bdd.dereference(bddFunction);
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testInvertMatchesMapBoolean(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        BddImpl bdd = dataPoint.context.bdd;
        MultiTerminalDecisionDiagram.FunctionToFunctionMap inverse = mt.invert(dataPoint.function);
        NatSet values = mt.valuesOf(dataPoint.function);
        for (int value = values.nextSetBit(0); value >= 0; value = values.nextSetBit(value + 1)) {
            int fixedValue = value;
            int viaInvert = bdd.reference(inverse.functionFor(value));
            int viaMapBoolean = bdd.reference(mt.mapBoolean(dataPoint.function, v -> v == fixedValue));
            assertThat(viaInvert, is(viaMapBoolean));
            bdd.dereference(viaInvert, viaMapBoolean);
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testInvertOutsideSupportIsFalse(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        BddImpl bdd = dataPoint.context.bdd;
        MultiTerminalDecisionDiagram.FunctionToFunctionMap inverse = mt.invert(dataPoint.function);
        NatSet values = mt.valuesOf(dataPoint.function);
        int outside = values.isEmpty() ? 0 : values.length();
        assertThat(values.contains(outside), is(false));
        assertThat(inverse.functionFor(outside), is(bdd.falseFunction()));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intConditional")
    void testIfThenElseMatchesEvaluate(IntConditionalDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        int result = mt.reference(
                mt.ifThenElse(dataPoint.condition.function, dataPoint.then.function, dataPoint.els.function));
        MutableNatSet relevant = MutableNatSet.copyOf(dataPoint.condition.tree.containedVariables());
        relevant.or(dataPoint.then.tree.containedVariables());
        relevant.or(dataPoint.els.tree.containedVariables());
        for (boolean[] assignment : assignmentsOver(relevant)) {
            int expected = dataPoint.condition.tree.evaluate(assignment)
                    ? dataPoint.then.tree.evaluate(assignment)
                    : dataPoint.els.tree.evaluate(assignment);
            assertThat(mt.evaluate(result, assignment), is(expected));
        }
        mt.dereference(result);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intConditional")
    void testIfThenElseTrueFalseCorollaries(IntConditionalDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        BddImpl bdd = dataPoint.context.bdd;
        assertThat(
                mt.ifThenElse(bdd.trueFunction(), dataPoint.then.function, dataPoint.els.function),
                is(dataPoint.then.function));
        assertThat(
                mt.ifThenElse(bdd.falseFunction(), dataPoint.then.function, dataPoint.els.function),
                is(dataPoint.els.function));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intConditional")
    void testUpdateMatchesEvaluate(IntConditionalDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        int value = 17;
        int result = mt.reference(mt.update(dataPoint.then.function, dataPoint.condition.function, value));
        MutableNatSet relevant = MutableNatSet.copyOf(dataPoint.condition.tree.containedVariables());
        relevant.or(dataPoint.then.tree.containedVariables());
        for (boolean[] assignment : assignmentsOver(relevant)) {
            int expected =
                    dataPoint.condition.tree.evaluate(assignment) ? value : dataPoint.then.tree.evaluate(assignment);
            assertThat(mt.evaluate(result, assignment), is(expected));
        }
        mt.dereference(result);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testUpdateCorollaries(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        BddImpl bdd = dataPoint.context.bdd;
        int value = 23;
        assertThat(mt.update(dataPoint.function, bdd.falseFunction(), value), is(dataPoint.function));
        int updatedEverywhere = mt.reference(mt.update(dataPoint.function, bdd.trueFunction(), value));
        int direct = mt.reference(mt.of(value));
        assertThat(updatedEverywhere, is(direct));
        mt.dereference(updatedEverywhere, direct);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testComposeMatchesEvaluate(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        int[] rotationMapping = dataPoint.context.rotationMapping;
        int composed = mt.reference(mt.compose(dataPoint.function, rotationMapping));
        MutableNatSet relevant = MutableNatSet.dense(variableCount);
        dataPoint.tree.containedVariables().intStream().forEach(v -> relevant.set((v + 1) % variableCount));
        if (relevant.isEmpty()) {
            assertThat(mt.isConstant(dataPoint.function), is(true));
            assertThat(composed, is(dataPoint.function));
        } else {
            boolean[] rotated = new boolean[variableCount];
            for (boolean[] assignment : assignmentsOver(relevant)) {
                System.arraycopy(assignment, 1, rotated, 0, variableCount - 1);
                rotated[variableCount - 1] = assignment[0];
                assertThat(mt.evaluate(composed, assignment), is(dataPoint.tree.evaluate(rotated)));
            }
        }
        mt.dereference(composed);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testComposeAllPlaceholderIsIdentity(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        int[] placeholders = new int[variableCount];
        Arrays.fill(placeholders, mt.placeholder());
        assertThat(mt.compose(dataPoint.function, placeholders), is(dataPoint.function));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testRestrictMatchesCompose(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        BddImpl bdd = dataPoint.context.bdd;
        int viaRestrict =
                mt.reference(mt.restrict(dataPoint.function, Cube.of(restrictedVariableValues, restrictedVariables)));
        int[] mapping = new int[variableCount];
        for (int v = 0; v < variableCount; v++) {
            if (restrictedVariables.contains(v)) {
                mapping[v] = restrictedVariableValues.contains(v) ? bdd.trueFunction() : bdd.falseFunction();
            } else {
                mapping[v] = mt.placeholder();
            }
        }
        int viaCompose = mt.reference(mt.compose(dataPoint.function, mapping));
        assertThat(viaRestrict, is(viaCompose));
        mt.dereference(viaRestrict, viaCompose);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intConditional")
    void testSimplifyAgreesWhereDomainHolds(IntConditionalDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        int simplified = mt.reference(mt.simplify(dataPoint.then.function, dataPoint.condition.function));
        MutableNatSet relevant = MutableNatSet.copyOf(dataPoint.condition.tree.containedVariables());
        relevant.or(dataPoint.then.tree.containedVariables());
        for (boolean[] assignment : assignmentsOver(relevant)) {
            if (dataPoint.condition.tree.evaluate(assignment)) {
                assertThat(mt.evaluate(simplified, assignment), is(dataPoint.then.tree.evaluate(assignment)));
            }
        }
        mt.dereference(simplified);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testSimplifyWithTrueDomainIsIdentity(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        BddImpl bdd = dataPoint.context.bdd;
        assertThat(mt.simplify(dataPoint.function, bdd.trueFunction()), is(dataPoint.function));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intConditional")
    void testConstrainAgreesWhereDomainHolds(IntConditionalDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        BddImpl bdd = dataPoint.context.bdd;
        assumeTrue(dataPoint.condition.function != bdd.falseFunction());

        int constrained = mt.reference(mt.constrain(dataPoint.then.function, dataPoint.condition.function));
        MutableNatSet relevant = MutableNatSet.copyOf(dataPoint.condition.tree.containedVariables());
        relevant.or(dataPoint.then.tree.containedVariables());
        for (boolean[] assignment : assignmentsOver(relevant)) {
            if (dataPoint.condition.tree.evaluate(assignment)) {
                assertThat(mt.evaluate(constrained, assignment), is(dataPoint.then.tree.evaluate(assignment)));
            }
        }
        mt.dereference(constrained);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testConstrainWithTrueDomainIsIdentity(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        BddImpl bdd = dataPoint.context.bdd;
        assertThat(mt.constrain(dataPoint.function, bdd.trueFunction()), is(dataPoint.function));
    }

    // The assignment a witness denotes: its fixed variables as given, every other one false.
    private static boolean[] assignmentOf(Cube witness) {
        boolean[] assignment = new boolean[variableCount];
        witness.assignment().forEach((int variable) -> assignment[variable] = true);
        return assignment;
    }

    // Every value of the codomain has a cube, and every cube reaches its value; the marked walk finds the same cubes as
    // the generic one, in ascending order.
    private static void assertCubes(MtBddImpl mt, int function, NatSet codomain) {
        MultiTerminalDecisionDiagram.ValueCubes cubes = mt.cubes(function, codomain);
        assertThat(cubes.codomain(), is(codomain));
        assertReaching(mt, function, cubes);
        MultiTerminalDecisionDiagram.ValueCubes generic =
                PathCubes.cubes(mt, function, codomain::contains, codomain.size());
        codomain.forEach((int value) -> assertThat(cubes.cubeFor(value), is(generic.cubeFor(value))));
    }

    private static void assertReaching(MtBddImpl mt, int function, MultiTerminalDecisionDiagram.ValueCubes cubes) {
        int[] previous = {-1};
        cubes.forEach((cube, value) -> {
            assertThat(value > previous[0], is(true));
            previous[0] = value;
            assertThat(mt.evaluate(function, assignmentOf(cube)), is(value));
            assertThat(cubes.cubeFor(value), is(cube));
        });
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testShortestCubesAreShortest(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        int function = dataPoint.function;
        IntIntHashMap shortest = new IntIntHashMap();
        mt.forEachPath(function, (path, value) -> {
            if (path.support().size() < shortest.get(value, Integer.MAX_VALUE)) {
                shortest.put(value, path.support().size());
            }
        });
        NatSet values = mt.valuesOf(function);
        MultiTerminalDecisionDiagram.ValueCubes all = mt.cubes(function);
        assertThat(all.codomain(), is(values));
        assertReaching(mt, function, all);
        MultiTerminalDecisionDiagram.ValueCubes cubes = mt.shortestCubes(function, values);
        assertThat(cubes.codomain(), is(values));
        assertReaching(mt, function, cubes);
        cubes.forEach((cube, value) -> assertThat(cube.support().size(), is(shortest.get(value, -1))));
        // Over a predicate: the even values, and one shortest cube to any of them.
        MultiTerminalDecisionDiagram.ValueCubes even = mt.cubes(function, (int value) -> value % 2 == 0);
        values.forEach((int value) -> assertThat(even.codomain().contains(value), is(value % 2 == 0)));
        assertReaching(mt, function, even);
        assertThat(mt.shortestCubes(function, (int value) -> value % 2 == 0).codomain(), is(even.codomain()));
        int fewest = values.intStream()
                .filter(value -> value % 2 == 0)
                .map(value -> shortest.get(value, -1))
                .min()
                .orElse(-1);
        Optional<Cube> anyEven = mt.shortestCube(function, (int value) -> value % 2 == 0);
        assertThat(anyEven.map(cube -> cube.support().size()).orElse(-1), is(fewest));
        anyEven.ifPresent(cube -> assertThat(mt.evaluate(function, assignmentOf(cube)) % 2, is(0)));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testCubesOfInverseAndSplitReachTheirValues(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        MultiTerminalDecisionDiagram.FunctionToFunctionMap inverse = mt.invert(dataPoint.function);
        assertCubes(mt, dataPoint.function, inverse.codomain());
        MultiTerminalDecisionDiagram.FunctionToFunctionMap split = mt.split(dataPoint.function, splitVariables);
        int meta = mt.reference(split.function());
        // The codomain is what the meta-function takes, nothing a combination merged away.
        assertThat(split.codomain(), is(mt.valuesOf(meta)));
        assertCubes(mt, meta, split.codomain());
        // A path of the meta-function decides on split variables only.
        mt.cubes(meta, split.codomain())
                .forEach((cube, index) -> assertThat(splitVariables.containsAll(cube.support()), is(true)));
        mt.dereference(meta);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intTernary")
    void testCubesOfTheCartesianProductReachTheirTuples(IntTernaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        int[] functions = {dataPoint.first, dataPoint.second, dataPoint.third};
        IntSyntaxTree[] trees = {dataPoint.firstTree, dataPoint.secondTree, dataPoint.thirdTree};
        MultiTerminalDecisionDiagram.FunctionToFunctionsMap product = mt.cartesianProduct(functions);
        int function = mt.reference(product.function());
        assertCubes(mt, function, product.codomain());
        MultiTerminalDecisionDiagram.ValueCubes cubes = mt.cubes(function, product.codomain());
        product.codomain().forEach((int index) -> {
            boolean[] assignment = assignmentOf(cubes.cubeFor(index));
            for (int i = 0; i < trees.length; i++) {
                assertThat(product.functionFor(index)[i], is(trees[i].evaluate(assignment)));
            }
        });
        mt.dereference(function);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testSplitRecombination(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        MultiTerminalDecisionDiagram.FunctionToFunctionMap split = mt.split(dataPoint.function, splitVariables);
        MutableNatSet relevant = NatSetFixtures.union(dataPoint.tree.containedVariables(), splitVariables);
        for (boolean[] assignment : assignmentsOver(relevant)) {
            int index = mt.evaluate(split.function(), assignment);
            int recombined = split.functionFor(index);
            assertThat(mt.evaluate(recombined, assignment), is(dataPoint.tree.evaluate(assignment)));
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testSplitMetaFunctionSupportSubset(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        MultiTerminalDecisionDiagram.FunctionToFunctionMap split = mt.split(dataPoint.function, splitVariables);
        NatSet metaSupport = mt.support(split.function());
        assertThat(NatSetFixtures.isSubset(metaSupport, splitVariables), is(true));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testSplitResidualDisjointFromSplitVariables(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        MultiTerminalDecisionDiagram.FunctionToFunctionMap split = mt.split(dataPoint.function, splitVariables);
        int index = mt.evaluate(split.function(), new boolean[variableCount]);
        int residual = split.functionFor(index);
        NatSet residualSupport = mt.support(residual);
        assertThat(residualSupport.intersects(splitVariables), is(false));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intTernary")
    void testCartesianProductRecombination(IntTernaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        int[] functions = {dataPoint.first, dataPoint.second, dataPoint.third};
        MultiTerminalDecisionDiagram.FunctionToFunctionsMap product = mt.cartesianProduct(functions);
        MutableNatSet relevant = NatSetFixtures.union(
                dataPoint.firstTree.containedVariables(),
                dataPoint.secondTree.containedVariables(),
                dataPoint.thirdTree.containedVariables());
        for (boolean[] assignment : assignmentsOver(relevant)) {
            int index = mt.evaluate(product.function(), assignment);
            int[] tuple = product.functionFor(index);
            assertThat(tuple[0], is(dataPoint.firstTree.evaluate(assignment)));
            assertThat(tuple[1], is(dataPoint.secondTree.evaluate(assignment)));
            assertThat(tuple[2], is(dataPoint.thirdTree.evaluate(assignment)));
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intTernary")
    void testCartesianProductQuotientUniqueness(IntTernaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        int[] functions = {dataPoint.first, dataPoint.second, dataPoint.third};
        MultiTerminalDecisionDiagram.FunctionToFunctionsMap product = mt.cartesianProduct(functions);
        MutableNatSet relevant = NatSetFixtures.union(
                dataPoint.firstTree.containedVariables(),
                dataPoint.secondTree.containedVariables(),
                dataPoint.thirdTree.containedVariables());
        List<boolean[]> list = new ArrayList<>();
        for (boolean[] assignment : assignmentsOver(QUADRATIC_MAX_ASSIGNMENT_VARIABLES, relevant)) {
            list.add(Arrays.copyOf(assignment, assignment.length));
        }
        Random random = new Random(Arrays.hashCode(functions));
        for (int i = 0; i < list.size(); i++) {
            boolean[] a1 = list.get(i);

            for (int j = i + 1; j < list.size(); j++) {
                if (random.nextDouble() > 64.0 / list.size()) {
                    continue;
                }
                boolean[] a2 = list.get(j);
                boolean sameIndex = mt.evaluate(product.function(), a1) == mt.evaluate(product.function(), a2);
                boolean sameTuple = dataPoint.firstTree.evaluate(a1) == dataPoint.firstTree.evaluate(a2)
                        && dataPoint.secondTree.evaluate(a1) == dataPoint.secondTree.evaluate(a2)
                        && dataPoint.thirdTree.evaluate(a1) == dataPoint.thirdTree.evaluate(a2);
                assertThat(sameIndex, is(sameTuple));
            }
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testReferenceDereferenceBalance(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        int before = mt.nodeReferenceCount(dataPoint.function);
        mt.reference(dataPoint.function);
        assertThat(
                mt.nodeReferenceCount(dataPoint.function),
                is(mt.isSaturatedNode(dataPoint.function) ? before : before + 1));
        mt.dereference(dataPoint.function);
        assertThat(mt.nodeReferenceCount(dataPoint.function), is(before));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intBinary")
    void testConsume(IntBinaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        int left = mt.reference(dataPoint.left);
        int right = mt.reference(dataPoint.right);
        int result = mt.apply(left, right, (a, b) -> (a + b) % valueMod);
        int consumed = mt.consume(result, left, right);
        assertThat(consumed, is(result));
        assertThat(mt.nodeReferenceCount(result) >= 1 || mt.isConstant(result), is(true));
        mt.dereference(consumed);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testUpdateWith(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        int fun = mt.reference(dataPoint.function);
        fun = mt.updateWith(mt.map(fun, a -> (a + 1) % valueMod), fun);
        for (boolean[] assignment : assignmentsOver(dataPoint.tree.containedVariables())) {
            assertThat(mt.evaluate(fun, assignment), is((dataPoint.tree.evaluate(assignment) + 1) % valueMod));
        }
        mt.dereference(fun);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intBinary")
    void testApplyToConstant(IntBinaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        int applied = mt.reference(mt.apply(dataPoint.left, dataPoint.right, (a, b) -> 0));
        assertThat(mt.isConstant(applied), is(true));
        assertThat(mt.support(applied).isEmpty(), is(true));
        mt.dereference(applied);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testSupportSubsetOfContainedVariables(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        assertThat(
                NatSetFixtures.isSubset(mt.support(dataPoint.function), dataPoint.tree.containedVariables()), is(true));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testPlaceholderDistinctFromAnyRealFunction(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        assertThat(dataPoint.function, not(is(mt.placeholder())));
    }

    // 0 false, 1 true, anything else undefined.
    private static PartialValuation.Truth truth(int value) {
        return value == 0
                ? PartialValuation.Truth.FALSE
                : value == 1 ? PartialValuation.Truth.TRUE : PartialValuation.Truth.UNDECIDED;
    }

    private static BddImpl operatorDiagram() {
        BddImpl diagram = new DdContextImpl(
                        ImmutableBddConfiguration.builder().name("operators").build())
                .bdd();
        diagram.createVariables(variableCount);
        return diagram;
    }

    // The function of the slots with the given truth table, referenced.
    private static int slotFunction(BddImpl bdd, int[] slotVariables, int truthTable) {
        int function = bdd.falseFunction();
        for (int minterm = 0; minterm < 1 << slotVariables.length; minterm++) {
            if ((truthTable & (1 << minterm)) == 0) {
                continue;
            }
            int cube = bdd.trueFunction();
            for (int slot = 0; slot < slotVariables.length; slot++) {
                int literal = bdd.variableFunction(slotVariables[slot]);
                cube = bdd.updateWith(bdd.and(cube, (minterm & (1 << slot)) == 0 ? bdd.not(literal) : literal), cube);
            }
            function = bdd.updateWith(bdd.or(function, cube), function);
            bdd.dereference(cube);
        }
        return function;
    }

    private static int[] slotTables(IntTernaryDataPoint dataPoint) {
        Random random = new Random(dataPoint.first * 31L + dataPoint.second * 17L + dataPoint.third);
        int[] tables = Arrays.copyOf(SLOT_TABLES, SLOT_TABLES.length + 2);
        tables[SLOT_TABLES.length] = random.nextInt(256);
        tables[SLOT_TABLES.length + 1] = random.nextInt(256);
        return tables;
    }

    // References the function and every residual, which a residual product hands out unprotected.
    private static void reference(MtBddImpl mt, MultiTerminalDecisionDiagram.ResidualProduct product) {
        reference(mt, mt.bdd(), product);
    }

    private static void reference(
            MtBddImpl mt, BinaryDecisionDiagram operatorDiagram, MultiTerminalDecisionDiagram.ResidualProduct product) {
        mt.reference(product.function());
        product.codomain().forEach((int value) -> operatorDiagram.reference(product.residualFor(value)));
    }

    private static void dereference(MtBddImpl mt, MultiTerminalDecisionDiagram.ResidualProduct product) {
        dereference(mt, mt.bdd(), product);
    }

    private static void dereference(
            MtBddImpl mt, BinaryDecisionDiagram operatorDiagram, MultiTerminalDecisionDiagram.ResidualProduct product) {
        mt.dereference(product.function());
        product.codomain().forEach((int value) -> operatorDiagram.dereference(product.residualFor(value)));
    }

    private static MutableNatSet ternaryVariables(IntTernaryDataPoint dataPoint) {
        return NatSetFixtures.union(
                dataPoint.firstTree.containedVariables(),
                dataPoint.secondTree.containedVariables(),
                dataPoint.thirdTree.containedVariables());
    }

    // The operands replacing the given variables, every other variable left as it is.
    private static int[] replacing(MtBddImpl mt, int[] variables, int[] operands, int count) {
        int[] mapping = new int[variableCount];
        Arrays.fill(mapping, mt.placeholder());
        for (int i = 0; i < count; i++) {
            mapping[variables[i]] = operands[i];
        }
        return mapping;
    }

    private static int[] absent() {
        int[] values = new int[variableCount];
        Arrays.fill(values, MultiTerminalDecisionDiagram.ResidualProduct.ABSENT);
        return values;
    }

    // All three slots replaced, and the last one left as it is, so that it stays in every residual.
    @ParameterizedTest(name = "{index}")
    @MethodSource("intTernary")
    void testResidualProductAgreesWithItsDefinition(IntTernaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        BddImpl bdd = dataPoint.context.bdd;
        int[] operands = {dataPoint.first, dataPoint.second, dataPoint.third};
        IntSyntaxTree[] trees = {dataPoint.firstTree, dataPoint.secondTree, dataPoint.thirdTree};
        for (BddImpl operatorDiagram : List.of(bdd, OPERATOR_DIAGRAM)) {
            for (int replaced = operands.length - 1; replaced <= operands.length; replaced++) {
                int[] mapping = replacing(mt, SLOT_VARIABLES, operands, replaced);
                for (int table : slotTables(dataPoint)) {
                    int function = slotFunction(operatorDiagram, SLOT_VARIABLES, table);
                    for (NamedValuation valuation : VALUATIONS) {
                        MultiTerminalDecisionDiagram.ResidualProduct product = mt.residualProduct(
                                MultiTerminalDecisionDiagram.Operator.of(operatorDiagram, function),
                                mapping,
                                valuation.valuation);
                        reference(mt, operatorDiagram, product);
                        assertCubes(mt, product.function(), product.codomain());
                        for (boolean[] assignment : assignmentsOver(ternaryVariables(dataPoint))) {
                            int pair = mt.evaluate(product.function(), assignment);
                            MutableNatSet restricted = MutableNatSet.create();
                            MutableNatSet values = MutableNatSet.create();
                            int[] operandValues = new int[replaced];
                            for (int slot = 0; slot < replaced; slot++) {
                                operandValues[slot] = trees[slot].evaluate(assignment);
                                PartialValuation.Truth truth =
                                        valuation.valuation.valueOf(SLOT_VARIABLES[slot], operandValues[slot]);
                                if (truth != PartialValuation.Truth.UNDECIDED) {
                                    restricted.set(SLOT_VARIABLES[slot]);
                                    values.set(SLOT_VARIABLES[slot], truth == PartialValuation.Truth.TRUE);
                                }
                            }
                            int residual = operatorDiagram.reference(
                                    operatorDiagram.restrict(function, Cube.of(values, restricted)));
                            String label = valuation.label + " " + table + " " + replaced;
                            assertThat(label, product.residualFor(pair), is(residual));
                            NatSet support = operatorDiagram.support(residual);
                            int[] expected = absent();
                            for (int slot = 0; slot < replaced; slot++) {
                                if (support.contains(SLOT_VARIABLES[slot])) {
                                    expected[SLOT_VARIABLES[slot]] = operandValues[slot];
                                }
                            }
                            assertThat(label, product.valuesFor(pair), is(expected));
                            operatorDiagram.dereference(residual);
                        }
                        dereference(mt, operatorDiagram, product);
                    }
                    operatorDiagram.dereference(function);
                }
            }
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intTernary")
    void testResidualProductIsItsChainOfOperations(IntTernaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        BddImpl bdd = dataPoint.context.bdd;
        int[] operands = {dataPoint.first, dataPoint.second, dataPoint.third};
        for (BddImpl operatorDiagram : List.of(bdd, OPERATOR_DIAGRAM)) {
            for (int replaced = operands.length - 1; replaced <= operands.length; replaced++) {
                int[] mapping = replacing(mt, SLOT_VARIABLES, operands, replaced);
                for (int table : slotTables(dataPoint)) {
                    int function = slotFunction(operatorDiagram, SLOT_VARIABLES, table);
                    for (NamedValuation valuation : VALUATIONS) {
                        MultiTerminalDecisionDiagram.ResidualProduct product = mt.residualProduct(
                                MultiTerminalDecisionDiagram.Operator.of(operatorDiagram, function),
                                mapping,
                                valuation.valuation);
                        reference(mt, operatorDiagram, product);
                        MultiTerminalDecisionDiagram.ResidualProduct chain = ResidualProducts.ofCartesianProduct(
                                mt,
                                MultiTerminalDecisionDiagram.Operator.of(operatorDiagram, function),
                                mapping,
                                valuation.valuation);
                        reference(mt, operatorDiagram, chain);
                        // Both number their pairs as they meet them: the functions are the same up to the numbering.
                        assertThat(
                                product.codomain().size(), is(chain.codomain().size()));
                        for (boolean[] assignment : assignmentsOver(ternaryVariables(dataPoint))) {
                            int pair = mt.evaluate(product.function(), assignment);
                            int chainPair = mt.evaluate(chain.function(), assignment);
                            assertThat(product.residualFor(pair), is(chain.residualFor(chainPair)));
                            assertThat(product.valuesFor(pair), is(chain.valuesFor(chainPair)));
                        }
                        dereference(mt, operatorDiagram, chain);
                        dereference(mt, operatorDiagram, product);
                    }
                    operatorDiagram.dereference(function);
                }
            }
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intTernary")
    void testResidualProductGeneralizesTheCartesianProduct(IntTernaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        BddImpl bdd = dataPoint.context.bdd;
        int[] operands = {dataPoint.first, dataPoint.second, dataPoint.third};
        int conjunction = slotFunction(bdd, SLOT_VARIABLES, CONJUNCTION_TABLE);
        MultiTerminalDecisionDiagram.ResidualProduct product = mt.residualProduct(
                MultiTerminalDecisionDiagram.Operator.of(bdd, conjunction),
                replacing(mt, SLOT_VARIABLES, operands, operands.length),
                PartialValuation.undecided());
        reference(mt, product);
        MultiTerminalDecisionDiagram.FunctionToFunctionsMap tuples = mt.cartesianProduct(operands);
        int tuplesFunction = mt.reference(tuples.function());
        assertThat(product.codomain().size(), is(tuples.codomain().size()));
        for (boolean[] assignment : assignmentsOver(ternaryVariables(dataPoint))) {
            int pair = mt.evaluate(product.function(), assignment);
            assertThat(product.residualFor(pair), is(conjunction));
            int[] tuple = tuples.functionFor(mt.evaluate(tuplesFunction, assignment));
            int[] expected = absent();
            for (int slot = 0; slot < operands.length; slot++) {
                expected[SLOT_VARIABLES[slot]] = tuple[slot];
            }
            assertThat(product.valuesFor(pair), is(expected));
        }
        mt.dereference(tuplesFunction);
        dereference(mt, product);
        bdd.dereference(conjunction);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intTernary")
    void testResidualProductGeneralizesTheMonoidApply(IntTernaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        BddImpl bdd = dataPoint.context.bdd;
        // Small values, so that the neutral and the absorbing value occur among the operands' values.
        int[] operands = new int[3];
        int[] given = {dataPoint.first, dataPoint.second, dataPoint.third};
        for (int i = 0; i < operands.length; i++) {
            operands[i] =
                    mt.reference(mt.map(given[i], value -> value % 4 == 0 ? 0 : value % 4 == 1 ? valueRange : value));
        }
        int[] mapping = replacing(mt, SLOT_VARIABLES, operands, operands.length);
        int conjunction = slotFunction(bdd, SLOT_VARIABLES, CONJUNCTION_TABLE);
        for (NamedMonoidOp namedOp : MONOID_OPS) {
            PartialValuation valuation = (variable, value) -> value == namedOp.neutral
                    ? PartialValuation.Truth.TRUE
                    : namedOp.absorbing != NamedMonoidOp.NONE && value == namedOp.absorbing
                            ? PartialValuation.Truth.FALSE
                            : PartialValuation.Truth.UNDECIDED;
            MultiTerminalDecisionDiagram.ResidualProduct product =
                    mt.residualProduct(MultiTerminalDecisionDiagram.Operator.of(bdd, conjunction), mapping, valuation);
            reference(mt, product);
            int[] folded = new int[product.codomain().size()];
            for (int pair = 0; pair < folded.length; pair++) {
                if (product.residualFor(pair) == bdd.falseFunction()) {
                    folded[pair] = namedOp.absorbing;
                    continue;
                }
                int accumulated = namedOp.neutral;
                for (int value : product.valuesFor(pair)) {
                    if (value != MultiTerminalDecisionDiagram.ResidualProduct.ABSENT) {
                        accumulated = namedOp.op.applyAsInt(accumulated, value);
                    }
                }
                folded[pair] = accumulated;
            }
            int mapped = mt.reference(mt.map(product.function(), pair -> folded[pair]));
            int applied = mt.reference(
                    namedOp.absorbing == NamedMonoidOp.NONE
                            ? mt.applyMonoid(operands, namedOp::applyNary, namedOp.neutral)
                            : mt.applyMonoid(operands, namedOp::applyNary, namedOp.neutral, namedOp.absorbing));
            assertThat(namedOp.label, mapped, is(applied));
            mt.dereference(mapped, applied);
            dereference(mt, product);
        }
        bdd.dereference(conjunction);
        mt.dereference(operands);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intTernary")
    void testResidualProductGeneralizesTheComposition(IntTernaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        BddImpl bdd = dataPoint.context.bdd;
        int[] operands = {dataPoint.first, dataPoint.second, dataPoint.third};
        int[] operandMapping = replacing(mt, SLOT_VARIABLES, operands, operands.length);
        NamedValuation total = VALUATIONS.get(VALUATIONS.size() - 1);
        int[] mapping = new int[variableCount];
        Arrays.fill(mapping, bdd.placeholder());
        for (int slot = 0; slot < operands.length; slot++) {
            int variable = SLOT_VARIABLES[slot];
            mapping[variable] = bdd.reference(mt.mapBoolean(
                    operands[slot], value -> total.valuation.valueOf(variable, value) == PartialValuation.Truth.TRUE));
        }
        for (int table : slotTables(dataPoint)) {
            int function = slotFunction(bdd, SLOT_VARIABLES, table);
            MultiTerminalDecisionDiagram.ResidualProduct product = mt.residualProduct(
                    MultiTerminalDecisionDiagram.Operator.of(bdd, function), operandMapping, total.valuation);
            reference(mt, product);
            product.codomain().forEach((int pair) -> {
                assertThat(bdd.isConstant(product.residualFor(pair)), is(true));
                assertThat(product.valuesFor(pair), is(absent()));
            });
            int holds = bdd.reference(
                    mt.mapBoolean(product.function(), pair -> product.residualFor(pair) == bdd.trueFunction()));
            int composed = bdd.reference(bdd.compose(function, mapping));
            assertThat(String.valueOf(table), holds, is(composed));
            bdd.dereference(holds, composed);
            dereference(mt, product);
            bdd.dereference(function);
        }
        for (int variable : SLOT_VARIABLES) {
            bdd.dereference(mapping[variable]);
        }
    }

    // The boolean part as composition and split, which needs replaced variables outside the operands' supports.
    @ParameterizedTest(name = "{index}")
    @MethodSource("intTernary")
    void testResidualProductIsTheSplitComposition(IntTernaryDataPoint dataPoint) {
        int[] operands = {dataPoint.first, dataPoint.second, dataPoint.third};
        MutableNatSet operandVariables = ternaryVariables(dataPoint);
        MutableNatSet free = MutableNatSet.copyOf(NatSet.range(0, variableCount));
        free.andNot(operandVariables);
        assumeTrue(free.size() >= operands.length);
        int[] slotVariables = new int[operands.length];
        int nextSlot = 0;
        for (int variable = 0; nextSlot < slotVariables.length; variable++) {
            if (free.contains(variable)) {
                slotVariables[nextSlot] = variable;
                nextSlot += 1;
            }
        }
        MutableNatSet splitVariables = MutableNatSet.copyOf(NatSet.range(0, variableCount));
        for (int slotVariable : slotVariables) {
            splitVariables.clear(slotVariable);
        }
        NamedValuation valuation = VALUATIONS.get(0);
        MtBddImpl mt = dataPoint.context.mt;
        BddImpl bdd = dataPoint.context.bdd;
        int[] operandMapping = replacing(mt, slotVariables, operands, operands.length);
        int[] mapping = new int[variableCount];
        Arrays.fill(mapping, bdd.placeholder());
        for (int slot = 0; slot < operands.length; slot++) {
            int variable = slotVariables[slot];
            int holds = bdd.reference(mt.mapBoolean(
                    operands[slot],
                    value -> valuation.valuation.valueOf(variable, value) == PartialValuation.Truth.TRUE));
            int undefined = bdd.reference(mt.mapBoolean(
                    operands[slot],
                    value -> valuation.valuation.valueOf(variable, value) == PartialValuation.Truth.UNDECIDED));
            int keeps = bdd.reference(bdd.and(undefined, bdd.variableFunction(variable)));
            mapping[variable] = bdd.reference(bdd.or(holds, keeps));
            bdd.dereference(holds, undefined, keeps);
        }
        for (int table : slotTables(dataPoint)) {
            int function = slotFunction(bdd, slotVariables, table);
            MultiTerminalDecisionDiagram.ResidualProduct product = mt.residualProduct(
                    MultiTerminalDecisionDiagram.Operator.of(bdd, function), operandMapping, valuation.valuation);
            reference(mt, product);
            int composed = bdd.reference(bdd.compose(function, mapping));
            MultiTerminalDecisionDiagram.FunctionToFunctionMap split = mt.splitBdd(composed, splitVariables);
            int splitFunction = mt.reference(split.function());
            split.codomain().forEach((int index) -> bdd.reference(split.functionFor(index)));
            assertCubes(mt, splitFunction, split.codomain());
            for (boolean[] assignment : assignmentsOver(operandVariables)) {
                assertThat(
                        product.residualFor(mt.evaluate(product.function(), assignment)),
                        is(split.functionFor(mt.evaluate(splitFunction, assignment))));
            }
            split.codomain().forEach((int index) -> bdd.dereference(split.functionFor(index)));
            mt.dereference(splitFunction);
            bdd.dereference(composed);
            dereference(mt, product);
            bdd.dereference(function);
        }
        for (int slotVariable : slotVariables) {
            bdd.dereference(mapping[slotVariable]);
        }
    }

    /** One fully built diagram plus the data points sampled from it. */
    static final class Context {
        final String name;
        /* The order is the context's, so the stress drives it there - one context, one order, both
         * diagrams moving together. */
        final DdContextImpl ddContext;
        final BddImpl bdd;
        final MtBddImpl mt;
        final int[] rotationMapping;

        final Collection<IntUnaryDataPoint> intUnary;
        final Collection<IntBinaryDataPoint> intBinary;
        final Collection<IntTernaryDataPoint> intTernary;
        final Collection<IntConditionalDataPoint> intConditional;

        Context(String name, boolean keepReorderingStructures) {
            this.name = name;
            BddConfiguration config = ImmutableBddConfiguration.builder()
                    .name(name)
                    .keepReorderingStructures(keepReorderingStructures)
                    .build();
            ddContext = new DdContextImpl(config);
            bdd = ddContext.bdd();
            // Same seeds for every context, so the three hold structurally identical diagrams and a
            // divergence between them is the order and nothing else.
            Info<BddImpl> boolInfo = Generator.fill(
                    bdd, 0, variableCount, boolTreeDepth, boolTreeWidth, boolUnaryCount, boolBinaryCount, 0);
            mt = ddContext.mtBdd();

            List<IntPoolEntry> pool = buildIntPool(this, new Random(1), boolInfo);
            Random sampleRandom = new Random(2);
            intUnary = sampleUnary(this, pool, intUnaryCount, sampleRandom);
            intBinary = sampleBinary(this, pool, intBinaryCount, sampleRandom);
            intTernary = sampleTernary(this, pool, intTernaryCount, sampleRandom);
            intConditional = sampleConditional(this, pool, boolInfo, intConditionalCount, sampleRandom);

            rotationMapping = new int[variableCount];
            for (int v = 0; v < variableCount; v++) {
                rotationMapping[v] = bdd.variableFunction((v + 1) % variableCount);
            }

            long nonConstant = Streams.concat(
                            intUnary.stream().map(point -> point.function),
                            intBinary.stream().flatMap(point -> Stream.of(point.left, point.right)),
                            intTernary.stream().flatMap(point -> Stream.of(point.first, point.second, point.third)),
                            intConditional.stream()
                                    .flatMap(point -> Stream.of(point.then.function, point.els.function)))
                    .distinct()
                    .filter(p -> !mt.isConstant(p))
                    .count();
            logger.log(
                    Level.INFO,
                    "Filled MtBdd {0}: {1} nodes ({2} referenced, {7} non-constant), {3} unary, {4} binary, {5} ternary, {6} conditional",
                    new Object[] {
                        name,
                        mt.nodeCount(),
                        mt.referencedNodeCount(),
                        intUnary.size(),
                        intBinary.size(),
                        intTernary.size(),
                        intConditional.size(),
                        nonConstant,
                    });
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static final class IntPoolEntry {
        final int function;
        final IntSyntaxTree tree;

        IntPoolEntry(int function, IntSyntaxTree tree) {
            this.function = function;
            this.tree = tree;
        }
    }

    static final class IntUnaryDataPoint {
        final Context context;
        final int function;
        final IntSyntaxTree tree;

        IntUnaryDataPoint(Context context, IntPoolEntry entry) {
            this.context = context;
            this.function = entry.function;
            this.tree = entry.tree;
        }

        @Override
        public String toString() {
            return context + ": " + tree;
        }
    }

    static final class IntBinaryDataPoint {
        final Context context;
        final int left;
        final IntSyntaxTree leftTree;
        final int right;
        final IntSyntaxTree rightTree;

        IntBinaryDataPoint(Context context, IntPoolEntry left, IntPoolEntry right) {
            this.context = context;
            this.left = left.function;
            this.leftTree = left.tree;
            this.right = right.function;
            this.rightTree = right.tree;
        }

        @Override
        public String toString() {
            return String.format("%s: %s ### %s", context, leftTree, rightTree);
        }
    }

    static final class IntTernaryDataPoint {
        final Context context;
        final int first;
        final IntSyntaxTree firstTree;
        final int second;
        final IntSyntaxTree secondTree;
        final int third;
        final IntSyntaxTree thirdTree;

        IntTernaryDataPoint(Context context, IntPoolEntry first, IntPoolEntry second, IntPoolEntry third) {
            this.context = context;
            this.first = first.function;
            this.firstTree = first.tree;
            this.second = second.function;
            this.secondTree = second.tree;
            this.third = third.function;
            this.thirdTree = third.tree;
        }

        @Override
        public String toString() {
            return String.format("%s: %s ### %s ### %s", context, firstTree, secondTree, thirdTree);
        }
    }

    static final class IntConditionalDataPoint {
        final Context context;
        final UnaryDataPoint<BddImpl> condition;
        final IntPoolEntry then;
        final IntPoolEntry els;

        IntConditionalDataPoint(
                Context context, UnaryDataPoint<BddImpl> condition, IntPoolEntry then, IntPoolEntry els) {
            this.context = context;
            this.condition = condition;
            this.then = then;
            this.els = els;
        }

        @Override
        public String toString() {
            return String.format("%s: if %s then %s else %s", context, condition.tree, then.tree, els.tree);
        }
    }

    private static final class NamedBinaryOp {
        final String label;
        final IntBinaryOperator op;

        NamedBinaryOp(String label, IntBinaryOperator op) {
            this.label = label;
            this.op = op;
        }
    }

    private static final class NamedUnaryOp {
        final String label;
        final IntUnaryOperator op;

        NamedUnaryOp(String label, IntUnaryOperator op) {
            this.label = label;
            this.op = op;
        }
    }

    /** A genuine commutative monoid (plus, for max/min, a genuine absorbing element) - unlike
     * {@link NamedBinaryOp}, {@link #neutral}/{@link #absorbing} are real algebraic properties the op is
     * required to satisfy over the shared int pool's actual value range (see {@link #MONOID_OPS}), not
     * asserted by the library itself (see {@code MtBddBinaryOperator}'s javadoc: unchecked preconditions -
     * though {@code checkLaws} does empirically spot-check them at every leaf encountered). {@link
     * #applyNary} lifts the binary op to n-ary via fold from {@link #neutral} - by construction, the same
     * neutral/absorbing laws carry over to it automatically. */
    private static final class NamedValuation {
        final String label;
        final PartialValuation valuation;

        NamedValuation(String label, PartialValuation valuation) {
            this.label = label;
            this.valuation = valuation;
        }
    }

    private static final class NamedMonoidOp {
        static final int NONE = -1;

        final String label;
        final IntBinaryOperator op;
        final int neutral;
        final int absorbing;

        NamedMonoidOp(String label, IntBinaryOperator op, int neutral, int absorbing) {
            this.label = label;
            this.op = op;
            this.neutral = neutral;
            this.absorbing = absorbing;
        }

        int applyNary(int[] values) {
            int acc = neutral;
            for (int value : values) {
                acc = op.applyAsInt(acc, value);
            }
            return acc;
        }
    }
}
