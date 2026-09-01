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

final class BddOperations {
    private BddOperations() {}

    static final class Compose extends ProtectedOperation
            implements RegisteredOperation.Unary, RegisteredOperation.Binary, NodeLifecycleObserver {
        private final BddImpl bdd;
        private final int[] variableMapping;
        private final int highestReplacedVariable;
        private final BooleanCache.UnaryToIntCache composeCache;

        /**
         * Only allocated for {@code registerComposeSimplify}. A plain registered compose is always invoked
         * with a {@code TRUE} domain, and {@code computeComposeSimplify} keeps it {@code TRUE} all the way
         * down when there is no cache to key domain-carrying entries on - it gives up narrowing the domain
         * to the current branch condition, which is the only thing that would introduce one.
         */
        private final BooleanCache.@Nullable BinaryToIntCache composeSimplifyCache;

        @SuppressWarnings("AssignmentOrReturnOfFieldWithMutableType")
        Compose(
                BddImpl bdd,
                int[] resolvedMapping,
                int highestReplacedVariable,
                int[] protectedNodes,
                boolean withSimplify) {
            super(bdd.protectionTracker(), () -> bdd.dereference(protectedNodes));
            assert Arrays.stream(protectedNodes).allMatch(bdd::nodeIsReferenced);
            this.bdd = bdd;
            this.variableMapping = resolvedMapping;
            this.highestReplacedVariable = highestReplacedVariable;
            this.composeCache = new BooleanCache.UnaryToIntCache(bdd);
            this.composeSimplifyCache = withSimplify ? new BooleanCache.BinaryToIntCache(bdd) : null;
            bdd.registerObserver(this);
            growToTableFloor(); // size caches for the table as it stands now, not just future grows
        }

        private void growToTableFloor() {
            BddConfiguration configuration = bdd.configuration();
            int floor = bdd.tableSize()
                    / (configuration.cacheEphemeralMultiplier() * configuration.registeredOperationDivider());
            composeCache.grow(floor);
            if (composeSimplifyCache != null) {
                composeSimplifyCache.grow(floor);
            }
        }

        @Override
        public int applyAsInt(int function) {
            return applyAsInt(function, bdd.trueFunction());
        }

        @Override
        public int applyAsInt(int function, int domain) {
            checkNotReleased();
            assert bdd.isValidFunction(function) && bdd.isValidFunction(domain);

            if (bdd.isConstant(function)) {
                return function;
            }
            if (domain == bdd.falseFunction()) {
                return bdd.falseFunction();
            }
            assert domain == bdd.trueFunction() || composeSimplifyCache != null
                    : "A domain-carrying compose must be registered through registerComposeSimplify";
            int result = bdd.composeGeneral(
                    function, domain, variableMapping, highestReplacedVariable, composeCache, composeSimplifyCache);
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
            pruneInvalidNodes(reclaimedNodes);
        }

        @Override
        public void afterTableGrowth(DecisionDiagram origin, int invalidatedNodes, BitSet reclaimedValues) {
            if (isReleased()) {
                return;
            }
            pruneInvalidNodes(invalidatedNodes);
            growToTableFloor();
        }

        private void pruneInvalidNodes(int invalidatedNodes) {
            if (invalidatedNodes == 0) {
                return;
            }
            boolean preserve = bdd.configuration().useCachePreserve() && invalidatedNodes < bdd.tableSize() / 2;
            composeCache.clearInvalidNodes(preserve);
            if (composeSimplifyCache != null) {
                composeSimplifyCache.clearInvalidNodes(preserve);
            }
        }
    }
}
