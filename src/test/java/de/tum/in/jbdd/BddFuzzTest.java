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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.tum.in.jbdd.collections.Cursor;
import de.tum.in.jbdd.collections.MutableNatSet;
import de.tum.in.jbdd.collections.NatSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The BDD against truth tables, on a tiny table and tiny or huge caches, with collections, reorderings and garbage
 * between the operations: every function the pool holds is compared as a whole, so a stale cache entry, a node freed
 * under a walk or a level read as a variable shows up as a wrong table. The theories compare against syntax trees
 * per operation; this walks their combinations over one diagram. The operations are the n-ary conjunction and
 * disjunction (and that they read their array only), quantification and the relational product (plain, registered,
 * dual), composition over restrictions and general mappings (plain and simplified, plain and registered),
 * simplification and the generalized cofactor on their domain, and restriction by the cubes a path cursor hands out.
 * Each seed picks the diagram's shape; the rounds scale with {@link TestProfile}.
 */
class BddFuzzTest {
    private static final int SEEDS = 6;
    private static final int ROUNDS = TestProfile.scaled(600, 20);
    private static final double[] LIVE_NODE_THRESHOLDS = {0.0, 0.5, 1.0};

    static Stream<Long> seeds() {
        return IntStream.range(0, SEEDS).mapToObj(seed -> (long) seed);
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void testAgainstTruthTables(long seed) {
        Random random = new Random(seed);
        BddConfiguration configuration = ImmutableBddConfiguration.builder()
                .initialSize(64 + random.nextInt(200))
                .cacheSizeDivider(random.nextBoolean() ? 1 : 1 << 20)
                .gcLiveNodeThreshold(LIVE_NODE_THRESHOLDS[random.nextInt(LIVE_NODE_THRESHOLDS.length)])
                .keepReorderingStructures(random.nextBoolean())
                .build();
        Diagram diagram = new Diagram(random, 5 + random.nextInt(4), configuration);
        for (int round = 0; round < ROUNDS; round++) {
            diagram.round();
        }
        assertTrue(diagram.bdd.check());
    }

    /** A BDD over {@code n} variables, a pool of referenced functions, and the operations compared per round. */
    private static final class Diagram {
        private static final int POOL_SIZE = 8;

        private final Random random;
        private final int n;
        private final DdContext context;
        private final Bdd bdd;
        private final List<Integer> pool = new ArrayList<>();

        Diagram(Random random, int n, BddConfiguration configuration) {
            this.random = random;
            this.n = n;
            this.context = DdContext.create(configuration);
            this.bdd = context.bdd();
            for (int variable = 0; variable < n; variable++) {
                bdd.createVariable();
            }
        }

        void round() {
            while (pool.size() < POOL_SIZE) {
                pool.add(bdd.reference(randomFunction(3)));
            }
            if (random.nextInt(3) == 0) {
                bdd.dereference(pool.remove(random.nextInt(pool.size())));
                pool.add(bdd.reference(randomFunction(3)));
            }
            disturb();
            int function = fromPool();
            int other = fromPool();
            boolean[] functionTable = table(function);
            boolean[] otherTable = table(other);

            checkNaryOperations();
            disturb();
            checkQuantification(function, other, functionTable, otherTable);
            disturb();
            checkComposition(function, other, functionTable, otherTable);
            checkDomainOperations(function, functionTable);
            disturb();
            checkRestrictionByPaths(function, other, functionTable, otherTable);
        }

        // The operations

        private void checkNaryOperations() {
            int count = random.nextInt(7);
            int[] operands = new int[count];
            boolean[] expectedAnd = new boolean[1 << n];
            boolean[] expectedOr = new boolean[1 << n];
            Arrays.fill(expectedAnd, true);
            for (int index = 0; index < count; index++) {
                int kind = random.nextInt(10);
                operands[index] = kind == 0
                        ? bdd.trueFunction()
                        : kind == 1 ? bdd.falseFunction() : kind == 2 ? bdd.not(fromPool()) : fromPool();
                boolean[] operandTable = table(operands[index]);
                expectedAnd = combine(expectedAnd, operandTable, true);
                expectedOr = combine(expectedOr, operandTable, false);
            }
            int[] untouched = operands.clone();
            assertTable(expectedAnd, bdd.and(operands));
            assertArrayEquals(untouched, operands);
            assertTable(expectedOr, bdd.or(operands));
            assertArrayEquals(untouched, operands);
        }

        private void checkQuantification(int function, int other, boolean[] functionTable, boolean[] otherTable) {
            NatSet variables = randomVariables();
            boolean[] conjunction = combine(functionTable, otherTable, true);
            boolean[] disjunction = combine(functionTable, otherTable, false);
            assertTable(quantified(functionTable, variables, true), bdd.exists(function, variables));
            assertTable(quantified(functionTable, variables, false), bdd.forall(function, variables));

            int andExists = bdd.andExists(function, other, variables);
            assertTable(quantified(conjunction, variables, true), andExists);
            int built = bdd.reference(bdd.and(function, other));
            assertEquals(bdd.exists(built, variables), andExists);
            bdd.dereference(built);
            assertTable(quantified(disjunction, variables, false), bdd.orForall(function, other, variables));

            RegisteredOperation.Binary registered = bdd.registerAndExists(variables);
            assertEquals(andExists, registered.applyAsInt(function, other));
            disturb();
            assertEquals(bdd.andExists(function, other, variables), registered.applyAsInt(other, function));
            assertTable(quantified(functionTable, variables, true), registered.applyAsInt(function, function));
            registered.release();
        }

        private void checkComposition(int function, int other, boolean[] functionTable, boolean[] otherTable) {
            boolean general = random.nextInt(3) == 0;
            int[] mapping = new int[random.nextBoolean() ? n : random.nextInt(n + 1)];
            boolean[][] mappingTables = new boolean[n][];
            MutableNatSet restricted = MutableNatSet.create();
            for (int variable = 0; variable < mapping.length; variable++) {
                int kind = random.nextInt(general ? 6 : 4);
                if (kind == 0 || kind == 1) {
                    mapping[variable] = kind == 0 ? bdd.trueFunction() : bdd.falseFunction();
                    mappingTables[variable] = constantTable(kind == 0);
                    restricted.set(variable);
                } else if (kind == 2) {
                    mapping[variable] = bdd.placeholder();
                } else if (kind == 3) {
                    mapping[variable] = bdd.variableFunction(variable);
                } else {
                    mapping[variable] = fromPool();
                    mappingTables[variable] = table(mapping[variable]);
                }
            }
            int[] untouched = mapping.clone();
            boolean[] expected = composed(functionTable, mappingTables);
            assertTable(expected, bdd.compose(function, mapping));
            assertArrayEquals(untouched, mapping);

            int domain = fromPool();
            boolean[] domainTable = table(domain);
            int simplified = bdd.composeSimplify(function, mapping, domain);
            assertAgreesOn(expected, domainTable, simplified);
            if (!general) {
                assertFalse(bdd.support(simplified).intersects(restricted));
            }
            RegisteredOperation.Binary registered = bdd.registerComposeSimplify(mapping);
            disturb();
            assertAgreesOn(expected, domainTable, registered.applyAsInt(function, domain));
            assertAgreesOn(composed(otherTable, mappingTables), domainTable, registered.applyAsInt(other, domain));
            registered.release();
        }

        private void checkDomainOperations(int function, boolean[] functionTable) {
            int domain = fromPool();
            boolean[] domainTable = table(domain);
            assertAgreesOn(functionTable, domainTable, bdd.simplify(function, domain));
            assertAgreesOn(functionTable, domainTable, bdd.constrain(function, domain));
        }

        /** Restricting by a path cursor's cubes, which are the walk's working state and must not become cache keys. */
        private void checkRestrictionByPaths(int function, int other, boolean[] functionTable, boolean[] otherTable) {
            Cursor<Cube> paths = bdd.pathCursor(other);
            for (int step = 0; step < 20 && paths.valid(); step++) {
                Cube cube = paths.current();
                boolean[][] cubeTables = new boolean[n][];
                cube.forEachLiteral((variable, value) -> cubeTables[variable] = constantTable(value));
                assertTable(composed(functionTable, cubeTables), bdd.restrict(function, cube));
                for (boolean satisfied : composed(otherTable, cubeTables)) {
                    assertTrue(satisfied, "A path cube does not satisfy its function");
                }
                paths.advance();
            }
        }

        // Disturbances between the operations

        private void disturb() {
            switch (random.nextInt(12)) {
                case 0:
                case 1:
                    bdd.gc();
                    break;
                case 2:
                case 3:
                    context.variableOrder().siftDown(random.nextInt(n - 1));
                    break;
                case 4:
                    context.variableOrder().reorder();
                    break;
                case 5:
                    context.variableOrder().reorderToIdentity();
                    break;
                case 6:
                    for (int index = 0; index < 20; index++) {
                        randomFunction(3);
                    }
                    break;
                default:
                    break;
            }
        }

        // Random functions

        private int fromPool() {
            return pool.get(random.nextInt(pool.size()));
        }

        private int randomFunction(int depth) {
            if (depth == 0 || random.nextInt(4) == 0) {
                int kind = random.nextInt(12);
                if (kind == 0) {
                    return bdd.trueFunction();
                }
                if (kind == 1) {
                    return bdd.falseFunction();
                }
                if (kind < 5 && !pool.isEmpty()) {
                    return fromPool();
                }
                int variable = bdd.variableFunction(random.nextInt(n));
                return random.nextBoolean() ? variable : bdd.not(variable);
            }
            int left = bdd.reference(randomFunction(depth - 1));
            int right = bdd.reference(randomFunction(depth - 1));
            int result;
            switch (random.nextInt(6)) {
                case 0:
                    result = bdd.and(left, right);
                    break;
                case 1:
                    result = bdd.or(left, right);
                    break;
                case 2:
                    result = bdd.xor(left, right);
                    break;
                case 3:
                    result = bdd.not(bdd.and(left, right));
                    break;
                case 4: {
                    int condition = bdd.reference(randomFunction(depth - 1));
                    result = bdd.ifThenElse(condition, left, right);
                    bdd.dereference(condition);
                    break;
                }
                default:
                    result = bdd.implication(left, right);
                    break;
            }
            bdd.dereference(left, right);
            return result;
        }

        private NatSet randomVariables() {
            MutableNatSet variables = MutableNatSet.create();
            int kind = random.nextInt(5);
            if (kind == 1) {
                variables.set(0, n);
            } else if (kind > 1) {
                for (int variable = 0; variable < n; variable++) {
                    if (random.nextBoolean()) {
                        variables.set(variable);
                    }
                }
            }
            return variables;
        }

        // Truth tables: entry i is the function at the assignment whose bit v is variable v.

        private boolean[] table(int function) {
            boolean[] table = new boolean[1 << n];
            for (int assignment = 0; assignment < table.length; assignment++) {
                table[assignment] = bdd.evaluate(function, assignment(assignment));
            }
            return table;
        }

        private boolean[] assignment(int index) {
            boolean[] assignment = new boolean[n];
            for (int variable = 0; variable < n; variable++) {
                assignment[variable] = ((index >> variable) & 1) != 0;
            }
            return assignment;
        }

        private boolean[] constantTable(boolean value) {
            boolean[] table = new boolean[1 << n];
            Arrays.fill(table, value);
            return table;
        }

        private static boolean[] combine(boolean[] first, boolean[] second, boolean conjunction) {
            boolean[] result = new boolean[first.length];
            for (int index = 0; index < first.length; index++) {
                result[index] = conjunction ? first[index] && second[index] : first[index] || second[index];
            }
            return result;
        }

        private boolean[] quantified(boolean[] table, NatSet variables, boolean existential) {
            boolean[] result = table.clone();
            for (int variable = 0; variable < n; variable++) {
                if (variables.contains(variable)) {
                    int bit = 1 << variable;
                    for (int index = 0; index < result.length; index++) {
                        result[index] = existential
                                ? result[index] || result[index ^ bit]
                                : result[index] && result[index ^ bit];
                    }
                }
            }
            return result;
        }

        /** The function with each variable replaced by its table, a null table leaving the variable alone. */
        private boolean[] composed(boolean[] table, boolean[][] mappingTables) {
            boolean[] result = new boolean[1 << n];
            for (int index = 0; index < result.length; index++) {
                int image = 0;
                for (int variable = 0; variable < n; variable++) {
                    boolean[] replacement = mappingTables[variable];
                    boolean value = replacement == null ? ((index >> variable) & 1) != 0 : replacement[index];
                    if (value) {
                        image |= 1 << variable;
                    }
                }
                result[index] = table[image];
            }
            return result;
        }

        private void assertTable(boolean[] expected, int function) {
            assertArrayEquals(expected, table(function));
        }

        private void assertAgreesOn(boolean[] expected, boolean[] domainTable, int function) {
            boolean[] actual = table(function);
            for (int index = 0; index < actual.length; index++) {
                if (domainTable[index]) {
                    assertEquals(expected[index], actual[index], "Disagreement on the domain");
                }
            }
        }
    }
}
