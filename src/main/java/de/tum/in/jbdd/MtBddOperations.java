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

import java.util.Arrays;
import java.util.BitSet;
import org.jspecify.annotations.Nullable;

/**
 * Every {@code MtBdd}-side {@link RegisteredOperation} implementation, gathered here (rather than one file
 * each) because they share almost all of their structure with each other, and with {@link BddOperations}:
 * {@link MtBdd#registerCompose} canonicalizes its mapping and picks between the identity fast path (a
 * lambda) and the general, cache-owning {@link Compose}; {@link MtBdd#registerApply}'s operator is a pure
 * function over terminal values, so its {@link Apply} needs no node protection at all - unlike
 * {@link Compose}, whose replacement array is {@code Bdd}-side even though the composed function is an
 * MTBDD.
 */
final class MtBddOperations {
    private MtBddOperations() {}

    private static boolean preserveEntries(MtBddImpl mtbdd, boolean mtbddOrigin, int invalidatedNodes) {
        int tableSize = mtbddOrigin ? mtbdd.tableSize() : mtbdd.bddImpl().tableSize();
        return mtbdd.bddImpl().configuration().useCachePreserve() && invalidatedNodes < tableSize / 2;
    }

    static final class Compose extends ProtectedOperation
            implements RegisteredOperation.Unary, RegisteredOperation.Binary, NodeTableObserver {
        private final MtBddImpl mtbdd;
        private final int[] bddVariableMapping;
        private int maxReplacedLevel;
        private final MtBddCache.UnaryToIntCache composeCache;

        /**
         * Only allocated for {@code registerComposeSimplify} - see {@link BddOperations.Compose}'s field of
         * the same name for why a plain registered compose never needs one.
         */
        private final MtBddCache.@Nullable MtbddBddToIntCache composeSimplifyCache;

        Compose(
                MtBddImpl mtbdd,
                int[] resolvedMapping,
                int maxReplacedLevel,
                int[] protectedNodes,
                boolean withSimplify) {
            /* The replacements are the *companion BDD's* functions, so that is where they were referenced
             * (MtBddImpl#registerCompose) and where they have to be released. The tracker is shared - see
             * MtBddImpl's constructor - so either diagram's GC drains it. */
            super(mtbdd.protectionTracker(), () -> mtbdd.bddImpl().dereference(protectedNodes));
            assert Arrays.stream(protectedNodes).allMatch(mtbdd.bddImpl()::nodeIsReferenced);
            this.mtbdd = mtbdd;
            this.bddVariableMapping = resolvedMapping;
            this.maxReplacedLevel = maxReplacedLevel;
            this.composeCache = new MtBddCache.UnaryToIntCache(mtbdd, mtbdd.bddImpl());
            this.composeSimplifyCache = withSimplify ? new MtBddCache.MtbddBddToIntCache(mtbdd, mtbdd.bddImpl()) : null;
            mtbdd.registerObserver(this);
            if (withSimplify) {
                mtbdd.bddImpl().registerObserver(this);
            }
            growToTableFloor();
        }

        private void growToTableFloor() {
            BddConfiguration configuration = mtbdd.bddImpl().configuration();
            int floor = mtbdd.tableSize()
                    / (configuration.mtbddCacheEphemeralMultiplier() * configuration.registeredOperationDivider());
            composeCache.grow(floor);
            if (composeSimplifyCache != null) {
                composeSimplifyCache.grow(floor);
            }
        }

        @Override
        public void levelsSwapped(DecisionDiagram origin, int level) {
            /* A simplifying compose is registered on both diagrams, and they share one order, so it would
             * hear this twice - once is enough, and the shift below is not idempotent. */
            if (origin != mtbdd) {
                return;
            }
            // As BddOperations.Compose: the cut-off it holds is a level, and its caches used the old one.
            maxReplacedLevel = mtbdd.bddImpl().maxReplacedLevel(bddVariableMapping);
            composeCache.invalidate();
            if (composeSimplifyCache != null) {
                composeSimplifyCache.invalidate();
            }
        }

        @Override
        public void variableInserted(DecisionDiagram origin, int level) {
            if (origin != mtbdd) {
                return;
            }
            // See BddOperations.Compose: only the bound moves, every comparison against it is preserved.
            if (maxReplacedLevel >= level) {
                maxReplacedLevel += 1;
            }
            assert maxReplacedLevel == mtbdd.bddImpl().maxReplacedLevel(bddVariableMapping);
        }

        @Override
        public int applyAsInt(int mtbddFunction) {
            return applyAsInt(mtbddFunction, mtbdd.bdd().trueFunction());
        }

        @Override
        public int applyAsInt(int mtbddFunction, int bddDomain) {
            checkNotReleased();
            assert mtbdd.isValidFunction(mtbddFunction);

            if (mtbdd.isConstant(mtbddFunction)) {
                return mtbddFunction;
            }
            if (bddDomain == mtbdd.bdd().falseFunction()) {
                return mtbdd.simplify(mtbddFunction, bddDomain);
            }
            assert bddDomain == mtbdd.bdd().trueFunction() || composeSimplifyCache != null
                    : "A domain-carrying compose must be registered through registerComposeSimplify";
            int result = mtbdd.composeGeneral(
                    mtbddFunction, bddDomain, bddVariableMapping, maxReplacedLevel, composeCache, composeSimplifyCache);
            composeCache.growOnUsage();
            if (composeSimplifyCache != null) {
                composeSimplifyCache.growOnUsage();
            }
            return result;
        }

        @Override
        public void afterGc(DecisionDiagram origin, int reclaimedNodes, BitSet reclaimedValues) {
            if (isReleased()) {
                return;
            }
            pruneInvalidNodes(origin, reclaimedNodes, reclaimedValues);
        }

        @Override
        public void afterTableGrowth(DecisionDiagram origin, int invalidatedNodes, BitSet reclaimedValues) {
            if (isReleased()) {
                return;
            }
            pruneInvalidNodes(origin, invalidatedNodes, reclaimedValues);
            //noinspection ObjectEquality
            if (origin == mtbdd) { // NOPMD
                growToTableFloor();
            }
        }

        @SuppressWarnings({"PMD.CompareObjectsWithEquals", "ObjectEquality"})
        private void pruneInvalidNodes(DecisionDiagram origin, int invalidatedNodes, BitSet reclaimedValues) {
            if (invalidatedNodes == 0 && reclaimedValues.isEmpty()) {
                return;
            }
            boolean mtbddOrigin = origin == mtbdd;
            boolean preserve = preserveEntries(mtbdd, mtbddOrigin, invalidatedNodes);
            if (mtbddOrigin) {
                composeCache.clearInvalidMtbddNodes(preserve);
                if (composeSimplifyCache != null) {
                    composeSimplifyCache.clearInvalidMtbddNodes(preserve);
                }
            } else {
                assert composeSimplifyCache != null : "Registered with the Bdd only when simplify-capable";
                composeSimplifyCache.clearInvalidBddNodes(preserve);
            }
        }
    }

    static final class Apply implements RegisteredOperation.Binary, RegisteredOperation.Ternary, NodeTableObserver {
        private final MtBddImpl mtbdd;
        private final MtBddBinaryOperator operator;
        private final MtBddCache.BinaryToIntCache applyCache;

        /** Only allocated for {@code registerApplySimplify}; see {@link Compose#composeSimplifyCache}. */
        private final MtBddCache.@Nullable ApplySimplifyCache applySimplifyCache;

        Apply(MtBddImpl mtbdd, MtBddBinaryOperator operator, boolean withSimplify) {
            this.mtbdd = mtbdd;
            this.operator = operator;
            this.applyCache = new MtBddCache.BinaryToIntCache(mtbdd, mtbdd.bddImpl());
            this.applySimplifyCache = withSimplify ? new MtBddCache.ApplySimplifyCache(mtbdd, mtbdd.bddImpl()) : null;
            mtbdd.registerObserver(this);
            if (withSimplify) {
                mtbdd.bddImpl().registerObserver(this);
            }
            growToTableFloor(); // size the cache for the table as it stands now, not just future grows
        }

        private void growToTableFloor() {
            BddConfiguration configuration = mtbdd.bddImpl().configuration();
            int floor = mtbdd.tableSize()
                    / (configuration.mtbddCacheEphemeralMultiplier() * configuration.registeredOperationDivider());
            applyCache.grow(floor);
            if (applySimplifyCache != null) {
                applySimplifyCache.grow(floor);
            }
        }

        @Override
        public int applyAsInt(int function1, int function2) {
            return applyAsInt(function1, function2, mtbdd.bdd().trueFunction());
        }

        @Override
        public int applyAsInt(int function1, int function2, int bddDomain) {
            assert bddDomain == mtbdd.bdd().trueFunction()
                            || bddDomain == mtbdd.bdd().falseFunction()
                            || applySimplifyCache != null
                    : "A domain-carrying apply must be registered through registerApplySimplify";
            int result = mtbdd.apply(function1, function2, bddDomain, operator, applyCache, applySimplifyCache);
            applyCache.growOnUsage();
            if (applySimplifyCache != null) {
                applySimplifyCache.growOnUsage();
            }
            return result;
        }

        @Override
        public void afterGc(DecisionDiagram origin, int reclaimedNodes, BitSet reclaimedValues) {
            pruneInvalidNodes(origin, reclaimedNodes, reclaimedValues);
        }

        @Override
        public void afterTableGrowth(DecisionDiagram origin, int invalidatedNodes, BitSet reclaimedValues) {
            pruneInvalidNodes(origin, invalidatedNodes, reclaimedValues);
            if (origin == mtbdd) {
                growToTableFloor();
            }
        }

        @SuppressWarnings({"PMD.CompareObjectsWithEquals", "ObjectEquality"})
        private void pruneInvalidNodes(DecisionDiagram origin, int invalidatedNodes, BitSet reclaimedValues) {
            if (invalidatedNodes == 0 && reclaimedValues.isEmpty()) {
                return;
            }
            boolean mtbddOrigin = origin == mtbdd;
            boolean preserve = preserveEntries(mtbdd, mtbddOrigin, invalidatedNodes);
            if (mtbddOrigin) {
                applyCache.clearInvalidMtbddNodes(preserve);
                if (applySimplifyCache != null) {
                    applySimplifyCache.clearInvalidMtbddNodes(preserve);
                }
            } else {
                assert applySimplifyCache != null;
                applySimplifyCache.clearInvalidBddNodes(preserve);
            }
        }
    }
}
