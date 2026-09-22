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
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Every {@code MtBdd}-side {@link RegisteredOperation} implementation, gathered here (rather than one file
 * each) because they share almost all of their structure with each other, and with {@link BddOperations}:
 * {@link MtBdd#registerCompose} canonicalizes its mapping and picks between the identity fast path (a
 * lambda) and the general, cache-owning {@link Compose}; the other four bind a pure function over terminal
 * values ({@link Apply}, {@link Mapper}, {@link MapBoolean}, {@link ApplyBoolean}) and so need no node
 * protection at all - unlike {@link Compose}, whose replacement array is {@code Bdd}-side even though the
 * composed function is an MTBDD.
 */
final class MtBddOperations {
    private MtBddOperations() {}

    private static boolean preserveEntries(MtBddImpl mtbdd, boolean mtbddOrigin, int invalidatedNodes) {
        int tableSize = mtbddOrigin ? mtbdd.tableSize() : mtbdd.bddImpl().tableSize();
        return mtbdd.bddImpl().configuration().useCachePreserve() && invalidatedNodes < tableSize / 2;
    }

    static final class Compose extends ProtectedOperation
            implements RegisteredOperation.Unary, RegisteredOperation.Binary, NodeTableObserver, VariableOrderObserver {
        private final MtBddImpl mtbdd;
        private final int[] bddVariableMapping;
        private int maxReplacedLevel;
        private final MtBddCache.UnaryToIntCache composeCache;
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
            /* Once, not once per table it registers with: the order tells each listener a single time. */
            mtbdd.variableOrder().registerObserver(this);
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
        public void orderChanged(int[] previousVariableToLevel, int[] currentVariableToLevel, BitSet movedVariables) {
            // As BddOperations.Compose: the cut-off it holds is a level, and its caches used the old one.
            maxReplacedLevel = mtbdd.bddImpl().maxReplacedLevel(bddVariableMapping);
            // see BooleanCache#orderChanged
            invalidateCaches();
        }

        @Override
        public void variablesInserted(int level, int count) {
            // See BddOperations.Compose: only the bound moves, every comparison against it is preserved.
            if (maxReplacedLevel >= level) {
                maxReplacedLevel += count;
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

        private void invalidateCaches() {
            composeCache.invalidate();
            if (composeSimplifyCache != null) {
                composeSimplifyCache.invalidate();
            }
        }

        @SuppressWarnings({"PMD.CompareObjectsWithEquals", "ObjectEquality"})
        private void pruneInvalidNodes(DecisionDiagram origin, int invalidatedNodes, BitSet reclaimedValues) {
            if (mtbdd.variableOrder().isReordering()) {
                // See BooleanCache#onBddNodesInvalidated.
                invalidateCaches();
                return;
            }
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

    static final class Mapper implements RegisteredOperation.Unary, RegisteredOperation.Binary, NodeTableObserver {
        private final MtBddImpl mtbdd;
        private final IntUnaryOperator operator;
        private final MtBddCache.UnaryToIntCache mapCache;

        /** Only allocated for {@code registerMapSimplify}; see {@link Compose#composeSimplifyCache}. */
        private final MtBddCache.@Nullable MtbddBddToIntCache mapSimplifyCache;

        Mapper(MtBddImpl mtbdd, IntUnaryOperator operator, boolean withSimplify) {
            this.mtbdd = mtbdd;
            this.operator = operator;
            this.mapCache = new MtBddCache.UnaryToIntCache(mtbdd, mtbdd.bddImpl());
            this.mapSimplifyCache = withSimplify ? new MtBddCache.MtbddBddToIntCache(mtbdd, mtbdd.bddImpl()) : null;
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
            mapCache.grow(floor);
            if (mapSimplifyCache != null) {
                mapSimplifyCache.grow(floor);
            }
        }

        @Override
        public int applyAsInt(int function) {
            return applyAsInt(function, mtbdd.bdd().trueFunction());
        }

        @Override
        public int applyAsInt(int function, int bddDomain) {
            assert bddDomain == mtbdd.bdd().trueFunction()
                            || bddDomain == mtbdd.bdd().falseFunction()
                            || mapSimplifyCache != null
                    : "A domain-carrying map must be registered through registerMapSimplify";
            int result = mtbdd.map(function, bddDomain, operator, mapCache, mapSimplifyCache);
            mapCache.growOnUsage();
            if (mapSimplifyCache != null) {
                mapSimplifyCache.growOnUsage();
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
            if (origin == mtbdd) { // NOPMD
                growToTableFloor();
            }
        }

        @SuppressWarnings({"PMD.CompareObjectsWithEquals", "ObjectEquality"})
        private void pruneInvalidNodes(DecisionDiagram origin, int invalidatedNodes, BitSet reclaimedValues) {
            if (mtbdd.variableOrder().isReordering()) {
                // See BooleanCache#onBddNodesInvalidated.
                mapCache.invalidate();
                if (mapSimplifyCache != null) {
                    mapSimplifyCache.invalidate();
                }
                return;
            }
            if (invalidatedNodes == 0 && reclaimedValues.isEmpty()) {
                return;
            }
            boolean mtbddOrigin = origin == mtbdd;
            boolean preserve = preserveEntries(mtbdd, mtbddOrigin, invalidatedNodes);
            if (mtbddOrigin) {
                mapCache.clearInvalidMtbddNodes(preserve);
                if (mapSimplifyCache != null) {
                    mapSimplifyCache.clearInvalidMtbddNodes(preserve);
                }
            } else {
                assert mapSimplifyCache != null : "Registered with the Bdd only when simplify-capable";
                mapSimplifyCache.clearInvalidBddNodes(preserve);
            }
        }
    }

    /**
     * {@code mapBoolean} and {@code applyBoolean} both fold terminals into a bit, so their results are
     * {@code Bdd} functions and every entry straddles the two tables - which is why, unlike {@link Apply},
     * these two always observe the {@code Bdd} as well, not only in their simplifying form.
     */
    static final class MapBoolean implements RegisteredOperation.Unary, NodeTableObserver {
        private final MtBddImpl mtbdd;
        private final IntPredicate values;
        private final MtBddCache.UnaryToBddCache mapBooleanCache;

        MapBoolean(MtBddImpl mtbdd, IntPredicate values) {
            this.mtbdd = mtbdd;
            this.values = values;
            this.mapBooleanCache = new MtBddCache.UnaryToBddCache(mtbdd, mtbdd.bddImpl());
            mtbdd.registerObserver(this);
            mtbdd.bddImpl().registerObserver(this);
            growToTableFloor();
        }

        private void growToTableFloor() {
            BddConfiguration configuration = mtbdd.bddImpl().configuration();
            mapBooleanCache.grow(mtbdd.tableSize()
                    / (configuration.mtbddCacheEphemeralMultiplier() * configuration.registeredOperationDivider()));
        }

        @Override
        public int applyAsInt(int function) {
            int result = mtbdd.mapBoolean(function, values, mapBooleanCache);
            mapBooleanCache.growOnUsage();
            return result;
        }

        @Override
        public void afterGc(DecisionDiagram origin, int reclaimedNodes, BitSet reclaimedValues) {
            pruneInvalidNodes(origin, reclaimedNodes, reclaimedValues);
        }

        @Override
        public void afterTableGrowth(DecisionDiagram origin, int invalidatedNodes, BitSet reclaimedValues) {
            pruneInvalidNodes(origin, invalidatedNodes, reclaimedValues);
            if (origin == mtbdd) { // NOPMD
                growToTableFloor();
            }
        }

        @SuppressWarnings({"PMD.CompareObjectsWithEquals", "ObjectEquality"})
        private void pruneInvalidNodes(DecisionDiagram origin, int invalidatedNodes, BitSet reclaimedValues) {
            if (mtbdd.variableOrder().isReordering()) {
                // See BooleanCache#onBddNodesInvalidated.
                mapBooleanCache.invalidate();
                return;
            }
            if (invalidatedNodes == 0 && reclaimedValues.isEmpty()) {
                return;
            }
            boolean mtbddOrigin = origin == mtbdd;
            boolean preserve = preserveEntries(mtbdd, mtbddOrigin, invalidatedNodes);
            if (mtbddOrigin) {
                mapBooleanCache.clearInvalidMtbddNodes(preserve);
            } else {
                mapBooleanCache.clearInvalidBddNodes(preserve);
            }
        }
    }

    static final class ApplyBoolean implements RegisteredOperation.Binary, NodeTableObserver {
        private final MtBddImpl mtbdd;
        private final MtBddBinaryPredicate predicate;
        private final MtBddCache.BinaryToBddCache applyBooleanCache;

        ApplyBoolean(MtBddImpl mtbdd, MtBddBinaryPredicate predicate) {
            this.mtbdd = mtbdd;
            this.predicate = predicate;
            this.applyBooleanCache = new MtBddCache.BinaryToBddCache(mtbdd, mtbdd.bddImpl());
            mtbdd.registerObserver(this);
            mtbdd.bddImpl().registerObserver(this);
            growToTableFloor();
        }

        private void growToTableFloor() {
            BddConfiguration configuration = mtbdd.bddImpl().configuration();
            applyBooleanCache.grow(mtbdd.tableSize()
                    / (configuration.mtbddCacheEphemeralMultiplier() * configuration.registeredOperationDivider()));
        }

        @Override
        public int applyAsInt(int function1, int function2) {
            int result = mtbdd.applyBoolean(function1, function2, predicate, applyBooleanCache);
            applyBooleanCache.growOnUsage();
            return result;
        }

        @Override
        public void afterGc(DecisionDiagram origin, int reclaimedNodes, BitSet reclaimedValues) {
            pruneInvalidNodes(origin, reclaimedNodes, reclaimedValues);
        }

        @Override
        public void afterTableGrowth(DecisionDiagram origin, int invalidatedNodes, BitSet reclaimedValues) {
            pruneInvalidNodes(origin, invalidatedNodes, reclaimedValues);
            if (origin == mtbdd) { // NOPMD
                growToTableFloor();
            }
        }

        @SuppressWarnings({"PMD.CompareObjectsWithEquals", "ObjectEquality"})
        private void pruneInvalidNodes(DecisionDiagram origin, int invalidatedNodes, BitSet reclaimedValues) {
            if (mtbdd.variableOrder().isReordering()) {
                // See BooleanCache#onBddNodesInvalidated.
                applyBooleanCache.invalidate();
                return;
            }
            if (invalidatedNodes == 0 && reclaimedValues.isEmpty()) {
                return;
            }
            boolean mtbddOrigin = origin == mtbdd;
            boolean preserve = preserveEntries(mtbdd, mtbddOrigin, invalidatedNodes);
            if (mtbddOrigin) {
                applyBooleanCache.clearInvalidMtbddNodes(preserve);
            } else {
                applyBooleanCache.clearInvalidBddNodes(preserve);
            }
        }
    }

    static final class Apply implements RegisteredOperation.Binary, RegisteredOperation.Ternary, NodeTableObserver {
        private final MtBddImpl mtbdd;
        private final MtBddBinaryOperator operator;
        private final MtBddCache.BinaryToIntCache applyCache;
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
            growToTableFloor();
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
            if (origin == mtbdd) { // NOPMD
                growToTableFloor();
            }
        }

        @SuppressWarnings({"PMD.CompareObjectsWithEquals", "ObjectEquality"})
        private void pruneInvalidNodes(DecisionDiagram origin, int invalidatedNodes, BitSet reclaimedValues) {
            if (mtbdd.variableOrder().isReordering()) {
                // See BooleanCache#onBddNodesInvalidated.
                applyCache.invalidate();
                if (applySimplifyCache != null) {
                    applySimplifyCache.invalidate();
                }
                return;
            }
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
