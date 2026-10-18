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
            implements RegisteredOperation.Unary,
                    RegisteredOperation.Binary,
                    RegisteredOperation.Ternary,
                    NodeTableObserver,
                    VariableOrderObserver {
        private final BddImpl bdd;
        private final NatSet quantifiedVariables;
        private NatSet quantifiedLevels;
        private final BooleanCache.UnaryToIntCache existsCache;
        // Allocated on first use (CacheBase resizes lazily), so a handle used only one way pays for one cache.
        private final BooleanCache.BinaryToIntCache andExistsCache;
        private final BooleanCache.TernaryToIntCache andExistsSimplifyCache;

        Exists(BddImpl bdd, NatSet quantifiedVariables) {
            this.bdd = bdd;
            this.quantifiedVariables = quantifiedVariables;
            this.quantifiedLevels = bdd.variablesToLevels(quantifiedVariables);
            this.existsCache = new BooleanCache.UnaryToIntCache(bdd);
            this.andExistsCache = new BooleanCache.BinaryToIntCache(bdd);
            this.andExistsSimplifyCache = new BooleanCache.TernaryToIntCache(bdd);
            existsCache.countInto(bdd.cache().registeredTotal("exists"));
            andExistsCache.countInto(bdd.cache().registeredTotal("and_exists"));
            andExistsSimplifyCache.countInto(bdd.cache().registeredTotal("and_exists_simplify"));
            bdd.registerObserver(this);
            bdd.variableOrder().registerObserver(this);
            growToTableFloor();
        }

        /** An ephemeral cache's size: the private caches grow on usage up to it. */
        private int usageLimit() {
            return bdd.tableSize() / bdd.configuration().cacheSizeDivider();
        }

        private void growToTableFloor() {
            int size =
                    bdd.tableSize() / (bdd.configuration().cacheSizeDivider() * CacheBase.REGISTERED_OPERATION_DIVIDER);
            existsCache.grow(size);
            existsCache.growWithUsageUpTo(usageLimit());
            andExistsCache.grow(size);
            andExistsCache.growWithUsageUpTo(usageLimit());
            andExistsSimplifyCache.grow(size);
            andExistsSimplifyCache.growWithUsageUpTo(usageLimit());
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
            andExistsSimplifyCache.invalidate();
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
            return bdd.existsGeneral(function, quantifiedLevels, existsCache);
        }

        @Override
        public int applyAsInt(int function1, int function2) {
            assert bdd.isValidFunction(function1) && bdd.isValidFunction(function2);

            if (quantifiedVariables.size() == bdd.numberOfVariables()) {
                return bdd.intersects(function1, function2) ? bdd.trueFunction() : bdd.falseFunction();
            }
            return bdd.andExistsGeneral(function1, function2, quantifiedLevels, andExistsCache, existsCache);
        }

        @Override
        public int applyAsInt(int function1, int function2, int domain) {
            assert bdd.isValidFunction(function1) && bdd.isValidFunction(function2) && bdd.isValidFunction(domain);

            if (domain == bdd.falseFunction()) {
                return domain;
            }
            if (quantifiedVariables.size() == bdd.numberOfVariables()) {
                return bdd.intersects(function1, function2) ? bdd.trueFunction() : bdd.falseFunction();
            }
            return bdd.andExistsSimplifyGeneral(
                    function1,
                    function2,
                    domain,
                    quantifiedLevels,
                    andExistsCache,
                    existsCache,
                    andExistsSimplifyCache);
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
                andExistsSimplifyCache.invalidate();
                return;
            }
            if (invalidatedNodes == 0) {
                return;
            }
            boolean preserve = invalidatedNodes < bdd.tableSize() / 2;
            existsCache.clearInvalidNodes(preserve);
            andExistsCache.clearInvalidNodes(preserve);
            andExistsSimplifyCache.clearInvalidNodes(preserve);
        }
    }

    /**
     * A composition no path literal restricts, composed node by node (BddImpl#composeNodewise), bound to its mapping:
     * it references the replacements and keeps a private cache keyed by the node. With a domain, the composition is
     * simplified to it.
     */
    static final class NodewiseCompose extends ProtectedOperation
            implements RegisteredOperation.Unary, RegisteredOperation.Binary, NodeTableObserver, VariableOrderObserver {
        private final BddImpl bdd;
        private final int[] variableMapping;
        private int maxReplacedLevel;
        private final BooleanCache.UnaryToIntCache nodewiseCache;

        @SuppressWarnings("AssignmentOrReturnOfFieldWithMutableType")
        NodewiseCompose(BddImpl bdd, int[] resolvedMapping, int[] protectedNodes) {
            super(bdd.protectionTracker(), () -> bdd.dereference(protectedNodes));
            assert Arrays.stream(protectedNodes).allMatch(bdd::nodeIsReferenced);
            this.bdd = bdd;
            this.variableMapping = resolvedMapping;
            this.maxReplacedLevel = bdd.maxReplacedLevel(resolvedMapping);
            this.nodewiseCache = new BooleanCache.UnaryToIntCache(bdd);
            nodewiseCache.countInto(bdd.cache().registeredTotal("compose_nodewise"));
            bdd.registerObserver(this);
            bdd.variableOrder().registerObserver(this);
            growToTableFloor();
        }

        /** An ephemeral cache's size: the private caches grow on usage up to it. */
        private int usageLimit() {
            return bdd.tableSize() / bdd.configuration().cacheSizeDivider();
        }

        private void growToTableFloor() {
            nodewiseCache.grow(bdd.tableSize()
                    / (bdd.configuration().cacheSizeDivider() * CacheBase.REGISTERED_OPERATION_DIVIDER));
            nodewiseCache.growWithUsageUpTo(usageLimit());
        }

        @Override
        public int applyAsInt(int function) {
            checkNotReleased();
            assert bdd.isValidFunction(function);
            return bdd.composeNodewise(function, variableMapping, maxReplacedLevel, nodewiseCache);
        }

        @Override
        public int applyAsInt(int function, int domain) {
            checkNotReleased();
            assert bdd.isValidFunction(function) && bdd.isValidFunction(domain);
            if (domain == bdd.falseFunction()) {
                return domain;
            }
            return bdd.simplify(applyAsInt(function), domain);
        }

        @Override
        public void orderChanged(int[] previousVariableToLevel, int[] currentVariableToLevel, NatSet movedVariables) {
            maxReplacedLevel = bdd.maxReplacedLevel(variableMapping);
            // see BooleanCache#orderChanged
            nodewiseCache.invalidate();
        }

        @Override
        public void variablesInserted(int level, int count) {
            // The functions did not change, so every entry holds; only the levels moved.
            maxReplacedLevel = bdd.maxReplacedLevel(variableMapping);
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
                nodewiseCache.invalidate();
            } else if (invalidatedNodes > 0) {
                nodewiseCache.clearInvalidNodes(invalidatedNodes < bdd.tableSize() / 2);
            }
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
