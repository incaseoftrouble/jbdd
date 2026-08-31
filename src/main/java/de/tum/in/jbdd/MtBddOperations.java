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

    /**
     * A simplify-capable registered operation additionally keys its cache on a {@code Bdd} domain, so it has
     * to be pruned when <em>either</em> table collects - hence the registration with both diagrams, and
     * hence this shared heuristic: the {@code afterGc} callbacks are indistinguishable once both are
     * registered, so the (purely advisory) "is it worth preserving entries" decision is taken against
     * whichever table is larger.
     */
    private static boolean preserveOnGc(MtBddImpl mtbdd, int reclaimedNodes) {
        return mtbdd.bddImpl().configuration().useCachePreserve()
                && reclaimedNodes < Math.max(mtbdd.tableSize(), mtbdd.bddImpl().tableSize()) / 2;
    }

    static final class Compose extends ProtectedOperation
            implements RegisteredOperation.Unary, RegisteredOperation.Binary, NodeLifecycleObserver {
        private final MtBddImpl mtbdd;
        private final int[] bddVariableMapping;
        private final int highestReplacedVariable;
        private final MtBddCache.UnaryToIntCache composeCache;

        /**
         * Only allocated for {@code registerComposeSimplify} - see {@link BddOperations.Compose}'s field of
         * the same name for why a plain registered compose never needs one.
         */
        private final MtBddCache.@Nullable MtbddBddToIntCache composeSimplifyCache;

        Compose(
                MtBddImpl mtbdd,
                int[] resolvedMapping,
                int highestReplacedVariable,
                int[] protectedNodes,
                boolean withSimplify) {
            super(mtbdd.protectionTracker(), () -> mtbdd.dereference(protectedNodes));
            assert Arrays.stream(protectedNodes).allMatch(mtbdd::nodeIsReferenced);
            this.mtbdd = mtbdd;
            this.bddVariableMapping = resolvedMapping;
            this.highestReplacedVariable = highestReplacedVariable;
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
                    mtbddFunction,
                    bddDomain,
                    bddVariableMapping,
                    highestReplacedVariable,
                    composeCache,
                    composeSimplifyCache);
            composeCache.growOnUsage();
            if (composeSimplifyCache != null) {
                composeSimplifyCache.growOnUsage();
            }
            return result;
        }

        @Override
        public void afterGc(int reclaimedNodes, BitSet reclaimedValues) {
            if (isReleased()) {
                return;
            }
            boolean preserve = preserveOnGc(mtbdd, reclaimedNodes);
            composeCache.clearInvalidMtbddNodes(preserve);
            if (composeSimplifyCache != null) {
                composeSimplifyCache.clearInvalidMtbddNodes(preserve);
                composeSimplifyCache.clearInvalidBddNodes(preserve);
            }
        }

        @Override
        public void afterTableGrowth(int invalidatedNodes, BitSet reclaimedValues) {
            if (isReleased()) {
                return;
            }
            growToTableFloor();
        }
    }

    static final class Apply implements RegisteredOperation.Binary, RegisteredOperation.Ternary, NodeLifecycleObserver {
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
        public void afterGc(int reclaimedNodes, BitSet reclaimedValues) {
            boolean preserve = preserveOnGc(mtbdd, reclaimedNodes);
            applyCache.clearInvalidMtbddNodes(preserve);
            if (applySimplifyCache != null) {
                applySimplifyCache.clearInvalidMtbddNodes(preserve);
                applySimplifyCache.clearInvalidBddNodes(preserve);
            }
        }

        @Override
        public void afterTableGrowth(int invalidatedNodes, BitSet reclaimedValues) {
            growToTableFloor();
        }
    }
}
