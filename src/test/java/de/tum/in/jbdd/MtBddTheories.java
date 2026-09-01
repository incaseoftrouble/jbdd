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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
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
    private static final int SKIP_CHECK_RANDOM_BOUND = 200;
    private static final int MAX_FAILED_SAMPLES = 20;

    private static final int MAX_ASSIGNMENT_VARIABLES = 12;
    private static final int QUADRATIC_MAX_ASSIGNMENT_VARIABLES = 6;
    private static final double FACTOR = 0.25;

    private static final int variableCount = 16;
    private static final int boolTreeDepth = 16;
    private static final int boolTreeWidth = 20;
    private static final int boolUnaryCount = (int) (FACTOR * 120);
    private static final int boolBinaryCount = (int) (FACTOR * 60);
    private static final int intSeedConstants = 24;
    private static final int intPoolTargetSize = (int) (FACTOR * 300);
    private static final int intPoolGrowthIterations = (int) (FACTOR * 800);
    private static final int intUnaryCount = (int) (FACTOR * 1000);
    private static final int intBinaryCount = (int) (FACTOR * 1000);
    private static final int intTernaryCount = (int) (FACTOR * 800);
    private static final int intConditionalCount = (int) (FACTOR * 800);
    private static final int valueRange = 2048;
    private static final int valueMod = 997;

    /* Three variants, because the three fail differently: never reordered (level == variable throughout,
     * the control), reordered with the bookkeeping rebuilt per reorder, and reordered with the bookkeeping
     * kept across operations. The MTBDD shares its companion BDD's order, so reordering either moves the
     * nodes of both. */
    private static final List<Context> contexts;

    private static final List<Context> reorderStressed;

    // Variable indices, so the same for every context.
    private static final BitSet splitVariables;
    private static final BitSet restrictedVariables;
    private static final BitSet restrictedVariableValues;

    private static final Collection<IntUnaryDataPoint> intUnary;
    private static final Collection<IntBinaryDataPoint> intBinary;
    private static final Collection<IntTernaryDataPoint> intTernary;
    private static final Collection<IntConditionalDataPoint> intConditional;

    /* Every so many theories, not at random: skipCheckRandom is a per-instance field and JUnit builds a
     * fresh instance per test, so drawing from it gives the same answer every time. Checking the tables
     * costs several times a round of swaps, hence only every REORDER_CHECK_EVERY rounds - see
     * BddTheories#occasionallyReorder, which this mirrors. */
    private static final int REORDER_EVERY = 200;
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

    private final Random skipCheckRandom = new Random(0L);

    static {
        contexts = List.of(
                new Context("mtbdd", false),
                new Context("mtbdd-reordered", false),
                new Context("mtbdd-reordered-keeping", true));
        reorderStressed = List.of(contexts.get(1), contexts.get(2));

        splitVariables = new BitSet(variableCount);
        for (int v = 0; v < variableCount; v += 2) {
            splitVariables.set(v);
        }

        restrictedVariables = new BitSet(variableCount);
        restrictedVariableValues = new BitSet(variableCount);
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

    private static List<IntPoolEntry> buildIntPool(Context context, Random random, Info<TestBddImpl> boolInfo) {
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

        List<UnaryDataPoint<TestBddImpl>> conditions = new ArrayList<>(boolInfo.unaryDataPoints);
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
                    UnaryDataPoint<TestBddImpl> condition = conditions.get(random.nextInt(conditions.size()));
                    int function = mt.reference(mt.ifThenElse(condition.function, left.function, elseEntry.function));
                    created = new IntPoolEntry(
                            function, IntSyntaxTree.ifThenElse(condition.tree, left.tree, elseEntry.tree));
                    break;
                }
                default:
                    UnaryDataPoint<TestBddImpl> condition = conditions.get(random.nextInt(conditions.size()));
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
            Context context, List<IntPoolEntry> pool, Info<TestBddImpl> boolInfo, int count, Random random) {
        List<UnaryDataPoint<TestBddImpl>> conditions = new ArrayList<>(boolInfo.unaryDataPoints);
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

    private static Iterable<boolean[]> assignmentsOver(int maxVariables, BitSet... variableSets) {
        return ScopedAssignments.of(variableCount, maxVariables, variableSets);
    }

    private static Iterable<boolean[]> assignmentsOver(BitSet... variableSets) {
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
            if (mt.level(variable) != variable) {
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
            logger.log(Level.INFO, DecisionDiagram.formatStatistics(context.mt.statistics()));
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
                context.ddContext.siftDown(reorderRandom.nextInt(variableCount - 1));
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
        BitSet relevant =
                BitSets.union(dataPoint.leftTree.containedVariables(), dataPoint.rightTree.containedVariables());
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
        BitSet relevant = dataPoint.firstTree.containedVariables();
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
        BitSet relevant = dataPoint.tree.containedVariables();
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
        BitSet relevant =
                BitSets.union(dataPoint.leftTree.containedVariables(), dataPoint.rightTree.containedVariables());
        for (boolean[] assignment : assignmentsOver(relevant)) {
            boolean expected = dataPoint.leftTree.evaluate(assignment) == dataPoint.rightTree.evaluate(assignment);
            assertThat(bdd.evaluate(agreement, assignment), is(expected));
        }
        bdd.dereference(agreement);
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
        int threshold = values.stream().skip(values.size() / 2).findFirst().orElse(25);
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
        MultiTerminalDecisionDiagram.Inverse inverse = mt.invert(dataPoint.function);
        assertThat(inverse.codomain(), is(mt.valuesOf(dataPoint.function)));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testInvertFunctionForMatchesEvaluate(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        BddImpl bdd = dataPoint.context.bdd;
        MultiTerminalDecisionDiagram.Inverse inverse = mt.invert(dataPoint.function);
        BitSet values = mt.valuesOf(dataPoint.function);
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
        MultiTerminalDecisionDiagram.Inverse inverse = mt.invert(dataPoint.function);
        BitSet values = mt.valuesOf(dataPoint.function);
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
        MultiTerminalDecisionDiagram.Inverse inverse = mt.invert(dataPoint.function);
        BitSet values = mt.valuesOf(dataPoint.function);
        int outside = values.isEmpty() ? 0 : values.length();
        assertThat(values.get(outside), is(false));
        assertThat(inverse.functionFor(outside), is(bdd.falseFunction()));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intConditional")
    void testIfThenElseMatchesEvaluate(IntConditionalDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        int result = mt.reference(
                mt.ifThenElse(dataPoint.condition.function, dataPoint.then.function, dataPoint.els.function));
        BitSet relevant = BitSets.of(dataPoint.condition.tree.containedVariables());
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
        BitSet relevant = BitSets.of(dataPoint.condition.tree.containedVariables());
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
        BitSet relevant = new BitSet(variableCount);
        dataPoint.tree.containedVariables().stream().forEach(v -> relevant.set((v + 1) % variableCount));
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
        int viaRestrict = mt.reference(mt.restrict(dataPoint.function, restrictedVariables, restrictedVariableValues));
        int[] mapping = new int[variableCount];
        for (int v = 0; v < variableCount; v++) {
            if (restrictedVariables.get(v)) {
                mapping[v] = restrictedVariableValues.get(v) ? bdd.trueFunction() : bdd.falseFunction();
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
        BitSet relevant = BitSets.of(dataPoint.condition.tree.containedVariables());
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
        BitSet relevant = BitSets.of(dataPoint.condition.tree.containedVariables());
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

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testSplitRecombination(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        MultiTerminalDecisionDiagram.FunctionToFunctionMap split = mt.split(dataPoint.function, splitVariables);
        BitSet relevant = BitSets.union(dataPoint.tree.containedVariables(), splitVariables);
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
        BitSet metaSupport = mt.support(split.function());
        assertThat(BitSets.isSubset(metaSupport, splitVariables), is(true));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testSplitResidualDisjointFromSplitVariables(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        MultiTerminalDecisionDiagram.FunctionToFunctionMap split = mt.split(dataPoint.function, splitVariables);
        int index = mt.evaluate(split.function(), new boolean[variableCount]);
        int residual = split.functionFor(index);
        BitSet residualSupport = mt.support(residual);
        assertThat(residualSupport.intersects(splitVariables), is(false));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intTernary")
    void testCartesianProductRecombination(IntTernaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        int[] functions = {dataPoint.first, dataPoint.second, dataPoint.third};
        MultiTerminalDecisionDiagram.FunctionToFunctionsMap product = mt.cartesianProduct(functions);
        BitSet relevant = BitSets.union(
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
        BitSet relevant = BitSets.union(
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
        assertThat(BitSets.isSubset(mt.support(dataPoint.function), dataPoint.tree.containedVariables()), is(true));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("intUnary")
    void testPlaceholderDistinctFromAnyRealFunction(IntUnaryDataPoint dataPoint) {
        MtBddImpl mt = dataPoint.context.mt;
        assertThat(dataPoint.function, not(is(mt.placeholder())));
    }

    /** One fully built diagram plus the data points sampled from it. */
    static final class Context {
        final String name;
        /* The order is the context's, so the stress drives it there - one context, one order, both
         * diagrams moving together. */
        final BddContextImpl ddContext;
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
            ddContext = new BddContextImpl(config);
            bdd = ddContext.bdd();
            // Same seeds for every context, so the three hold structurally identical diagrams and a
            // divergence between them is the order and nothing else.
            Info<TestBddImpl> boolInfo = Generator.fill(
                    new TestBddImpl(bdd),
                    0,
                    variableCount,
                    boolTreeDepth,
                    boolTreeWidth,
                    boolUnaryCount,
                    boolBinaryCount,
                    0);
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
        final UnaryDataPoint<TestBddImpl> condition;
        final IntPoolEntry then;
        final IntPoolEntry els;

        IntConditionalDataPoint(
                Context context, UnaryDataPoint<TestBddImpl> condition, IntPoolEntry then, IntPoolEntry els) {
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
