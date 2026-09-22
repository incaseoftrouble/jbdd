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

    // TODO Registerable restrict?

    static final class Exists implements RegisteredOperation.Unary, NodeTableObserver, VariableOrderObserver {
        private final BddImpl bdd;
        private final BitSet quantifiedVariables;
        private BitSet quantifiedLevels;
        private final BooleanCache.UnaryToIntCache existsCache;

        Exists(BddImpl bdd, BitSet quantifiedVariables) {
            this.bdd = bdd;
            this.quantifiedVariables = quantifiedVariables;
            this.quantifiedLevels = bdd.variablesToLevels(quantifiedVariables);
            this.existsCache = new BooleanCache.UnaryToIntCache(bdd);
            bdd.registerObserver(this);
            bdd.variableOrder().registerObserver(this);
            growToTableFloor();
        }

        private void growToTableFloor() {
            BddConfiguration configuration = bdd.configuration();
            existsCache.grow(bdd.tableSize()
                    / (configuration.cacheEphemeralMultiplier() * configuration.registeredOperationDivider()));
        }

        @Override
        public void orderChanged(int[] previousVariableToLevel, int[] currentVariableToLevel, BitSet movedVariables) {
            // quantifiedVariables is by variable, so the by-level set has to be rebuilt - unless none of
            // them is among the ones that moved, in which case every one of their levels is what it was.
            if (movedVariables.intersects(quantifiedVariables)) {
                quantifiedLevels = bdd.variablesToLevels(quantifiedVariables);
            }
            assert quantifiedLevels.equals(bdd.variablesToLevels(quantifiedVariables));

            // see BooleanCache#orderChanged
            existsCache.invalidate();
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
            if (quantifiedVariables.cardinality() == bdd.numberOfVariables()) {
                return bdd.trueFunction();
            }
            int result = bdd.existsGeneral(function, quantifiedLevels, existsCache);
            existsCache.growOnUsage();
            return result;
        }

        @Override
        public void afterGc(DecisionDiagram origin, int reclaimedNodes, BitSet reclaimedValues) {
            pruneInvalidNodes(reclaimedNodes);
        }

        @Override
        public void afterTableGrowth(DecisionDiagram origin, int invalidatedNodes, BitSet reclaimedValues) {
            pruneInvalidNodes(invalidatedNodes);
            growToTableFloor();
        }

        private void pruneInvalidNodes(int invalidatedNodes) {
            if (bdd.isReordering()) {
                // See BooleanCache#onBddNodesInvalidated.
                existsCache.invalidate();
                return;
            }
            if (invalidatedNodes == 0) {
                return;
            }
            boolean preserve = bdd.configuration().useCachePreserve() && invalidatedNodes < bdd.tableSize() / 2;
            existsCache.clearInvalidNodes(preserve);
        }
    }

    static final class Compose extends ProtectedOperation
            implements RegisteredOperation.Unary, RegisteredOperation.Binary, NodeTableObserver, VariableOrderObserver {
        private final BddImpl bdd;
        private final int[] variableMapping;
        private int maxReplacedLevel;
        private final BooleanCache.UnaryToIntCache composeCache;
        // Only allocated for registerComposeSimplify
        private final BooleanCache.@Nullable BinaryToIntCache composeSimplifyCache;

        @SuppressWarnings("AssignmentOrReturnOfFieldWithMutableType")
        Compose(BddImpl bdd, int[] resolvedMapping, int maxReplacedLevel, int[] protectedNodes, boolean withSimplify) {
            super(bdd.protectionTracker(), () -> bdd.dereference(protectedNodes));
            assert Arrays.stream(protectedNodes).allMatch(bdd::nodeIsReferenced);
            this.bdd = bdd;
            this.variableMapping = resolvedMapping;
            this.maxReplacedLevel = maxReplacedLevel;
            this.composeCache = new BooleanCache.UnaryToIntCache(bdd);
            this.composeSimplifyCache = withSimplify ? new BooleanCache.BinaryToIntCache(bdd) : null;
            bdd.registerObserver(this);
            bdd.variableOrder().registerObserver(this);
            growToTableFloor();
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

        private boolean isReplaced(int variable) {
            return variable < variableMapping.length && variableMapping[variable] != bdd.variableFunction(variable);
        }

        @Override
        public void orderChanged(int[] previousVariableToLevel, int[] currentVariableToLevel, BitSet movedVariables) {
            /* The cut-off it holds is a level, so it has to be taken again - unless no replaced variable
             * moved, since the maximum is over exactly those and each of them is where it was. */
            if (BitSets.anyMatch(movedVariables, this::isReplaced)) {
                maxReplacedLevel = bdd.maxReplacedLevel(variableMapping);
            }
            assert maxReplacedLevel == bdd.maxReplacedLevel(variableMapping);
            // see BooleanCache#orderChanged
            invalidateCaches();
        }

        @Override
        public void variablesInserted(int level, int count) {
            if (maxReplacedLevel >= level) {
                maxReplacedLevel += count;
            }
            assert maxReplacedLevel == bdd.maxReplacedLevel(variableMapping);
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
                    function, domain, variableMapping, maxReplacedLevel, composeCache, composeSimplifyCache);
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

        private void invalidateCaches() {
            composeCache.invalidate();
            if (composeSimplifyCache != null) {
                composeSimplifyCache.invalidate();
            }
        }

        private void pruneInvalidNodes(int invalidatedNodes) {
            if (bdd.isReordering()) {
                // See BooleanCache#onBddNodesInvalidated.
                invalidateCaches();
                return;
            }
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
