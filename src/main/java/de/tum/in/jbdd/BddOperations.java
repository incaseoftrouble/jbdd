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

import de.tum.in.jbdd.collections.NatSet;
import java.util.Arrays;

final class BddOperations {
    private BddOperations() {}

    // TODO [RESTRICT-REGISTER] A registered restrict?

    /** A quantification bound to its variables: unary, the exists; binary, the and-exists. */
    static final class Exists
            implements RegisteredOperation.Unary, RegisteredOperation.Binary, NodeTableObserver, VariableOrderObserver {
        private final BddImpl bdd;
        private final NatSet quantifiedVariables;
        private NatSet quantifiedLevels;
        private final BooleanCache.UnaryToIntCache existsCache;
        // Allocated on first use (CacheBase resizes lazily), so a handle used only one way pays for one cache.
        private final BooleanCache.BinaryToIntCache andExistsCache;

        Exists(BddImpl bdd, NatSet quantifiedVariables) {
            this.bdd = bdd;
            this.quantifiedVariables = quantifiedVariables;
            this.quantifiedLevels = bdd.variablesToLevels(quantifiedVariables);
            this.existsCache = new BooleanCache.UnaryToIntCache(bdd);
            this.andExistsCache = new BooleanCache.BinaryToIntCache(bdd);
            bdd.registerObserver(this);
            bdd.variableOrder().registerObserver(this);
            growToTableFloor();
        }

        private void growToTableFloor() {
            int size =
                    bdd.tableSize() / (bdd.configuration().cacheSizeDivider() * CacheBase.REGISTERED_OPERATION_DIVIDER);
            existsCache.grow(size);
            andExistsCache.grow(size);
        }

        @Override
        public void orderChanged(int[] previousVariableToLevel, int[] currentVariableToLevel, NatSet movedVariables) {
            // quantifiedVariables is by variable, so the by-level set has to be rebuilt - unless none of
            // them is among the ones that moved, in which case every one of their levels is what it was.
            if (movedVariables.intersects(quantifiedVariables)) {
                quantifiedLevels = bdd.variablesToLevels(quantifiedVariables);
            }
            assert quantifiedLevels.equals(bdd.variablesToLevels(quantifiedVariables));

            // see BooleanCache#orderChanged
            existsCache.invalidate();
            andExistsCache.invalidate();
        }

        @Override
        public void variablesInserted(int level, int count) {
            quantifiedLevels = bdd.variablesToLevels(quantifiedVariables);
            // Every entry in the cache does not involve the new variable, so we can keep it
        }

        @Override
        public int applyAsInt(int function) {
            assert bdd.isValidFunction(function);

            if (bdd.isConstant(function)) {
                return function;
            }
            if (quantifiedVariables.size() == bdd.numberOfVariables()) {
                return bdd.trueFunction();
            }
            int result = bdd.existsGeneral(function, quantifiedLevels, existsCache);
            existsCache.growOnUsage();
            return result;
        }

        @Override
        public int applyAsInt(int function1, int function2) {
            assert bdd.isValidFunction(function1) && bdd.isValidFunction(function2);

            if (quantifiedVariables.size() == bdd.numberOfVariables()) {
                return bdd.intersects(function1, function2) ? bdd.trueFunction() : bdd.falseFunction();
            }
            int result = bdd.andExistsGeneral(function1, function2, quantifiedLevels, andExistsCache, existsCache);
            existsCache.growOnUsage();
            andExistsCache.growOnUsage();
            return result;
        }

        @Override
        public void afterGc(DecisionDiagram origin, int reclaimedNodes, NatSet reclaimedValues) {
            pruneInvalidNodes(reclaimedNodes);
        }

        @Override
        public void afterTableGrowth(DecisionDiagram origin, int invalidatedNodes, NatSet reclaimedValues) {
            pruneInvalidNodes(invalidatedNodes);
            growToTableFloor();
        }

        private void pruneInvalidNodes(int invalidatedNodes) {
            if (bdd.isReordering()) {
                // See BooleanCache#onBddNodesInvalidated.
                existsCache.invalidate();
                andExistsCache.invalidate();
                return;
            }
            if (invalidatedNodes == 0) {
                return;
            }
            boolean preserve = invalidatedNodes < bdd.tableSize() / 2;
            existsCache.clearInvalidNodes(preserve);
            andExistsCache.clearInvalidNodes(preserve);
        }
    }

    /**
     * A composition bound to its mapping. It holds no cache of its own: the composition's cache is keyed on its
     * whole context (BddImpl#composeJoint), so the handle only resolves and protects the mapping once.
     */
    static final class Compose extends ProtectedOperation
            implements RegisteredOperation.Unary, RegisteredOperation.Binary {
        private final BddImpl bdd;
        private final int[] variableMapping;

        @SuppressWarnings("AssignmentOrReturnOfFieldWithMutableType")
        Compose(BddImpl bdd, int[] resolvedMapping, int[] protectedNodes) {
            super(bdd.protectionTracker(), () -> bdd.dereference(protectedNodes));
            assert Arrays.stream(protectedNodes).allMatch(bdd::nodeIsReferenced);
            this.bdd = bdd;
            this.variableMapping = resolvedMapping;
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
            return bdd.computeCompose(function, domain, variableMapping);
        }
    }
}
