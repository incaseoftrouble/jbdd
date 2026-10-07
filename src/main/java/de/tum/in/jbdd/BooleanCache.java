/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2017-2023 Tobias Meggendorfer.
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

import static de.tum.in.jbdd.Util.*;
import static java.util.Map.entry;

import de.tum.in.jbdd.collections.NatSet;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

// One class per cache shape, all held here, is the point of the design.
@SuppressWarnings("PMD.CouplingBetweenObjects")
final class BooleanCache implements VariableOrderObserver, StatisticsReporter {
    private static final Logger logger = Logger.getLogger(BooleanCache.class.getName());

    private static final int[] EMPTY_INT_ARRAY = new int[0];
    private static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];
    private static final double[] EMPTY_DOUBLE_ARRAY = new double[0];
    private static final Statistic EXISTS_REUSE_COUNT = Statistic.counter(
            "exists_reuse_count", "quantifications that reused the exists cache of the previous variable set");
    private static final Statistic AND_EXISTS_REUSE_COUNT = Statistic.counter(
            "and_exists_reuse_count", "relational products that reused the cache of the previous variable set");

    private final BooleanBase<?, ?> bdd;
    private int existsReuseCount = 0;
    private int andExistsReuseCount = 0;

    private final BinaryToIntCache andCache;
    private final TernaryToIntCache andSimplifyCache;
    private final BinaryToIntCache xorCache;
    private final TernaryToIntCache xorSimplifyCache;
    private final BinaryToIntCache simplifyCache;
    private final BinaryToIntCache constrainCache;
    private final BinaryToBooleanCache intersectsCache;
    private final TernaryToIntCache iteCache;
    private final QuaternaryToIntCache iteSimplifyCache;
    private final UnaryToIntCache existsCache;
    private NatSet existsVariables = NatSet.of();
    private final BinaryToIntCache andExistsCache;
    private NatSet andExistsVariables = NatSet.of();
    private final UnaryToObjectCache<BigInteger> satisfactionCache;
    private final BinaryToObjectCache<BigInteger> satisfactionInCache;
    private final Map<String, IntCache> caches;
    // Non-ephemeral, keyed on everything they depend on (see BddImpl#computeComposeJoint).
    private final UnaryToObjectCache<int[]> supportCache;
    private final FractionCache fractionCache;
    private final FractionInCache fractionInCache;
    private final DifferenceCache differenceCache;
    private final ComposeTupleCache composeTupleCache;
    private final OperandTupleCache andAllCache;
    private final RestrictCubeCache restrictCubeCache;

    private int lookupHash;

    BooleanCache(BooleanBase<?, ?> bdd) {
        this.bdd = bdd;
        this.lookupHash = -1;

        andCache = new BinaryToIntCache(bdd);
        andSimplifyCache = new TernaryToIntCache(bdd);
        xorCache = new BinaryToIntCache(bdd);
        xorSimplifyCache = new TernaryToIntCache(bdd);
        simplifyCache = new BinaryToIntCache(bdd);
        constrainCache = new BinaryToIntCache(bdd);
        intersectsCache = new BinaryToBooleanCache(bdd);
        iteCache = new TernaryToIntCache(bdd);
        iteSimplifyCache = new QuaternaryToIntCache(bdd);
        existsCache = new UnaryToIntCache(bdd);
        andExistsCache = new BinaryToIntCache(bdd);
        satisfactionCache = new UnaryToObjectCache<>(bdd);
        satisfactionInCache = new BinaryToObjectCache<>(bdd);

        supportCache = new UnaryToObjectCache<>(bdd);
        // the smallest size; composition grows it on usage (tableSizeChanged leaves it alone)
        supportCache.grow(0);
        fractionCache = new FractionCache(bdd);
        fractionInCache = new FractionInCache(bdd);
        differenceCache = new DifferenceCache(bdd);
        composeTupleCache = new ComposeTupleCache(bdd);
        andAllCache = new OperandTupleCache(bdd);
        restrictCubeCache = new RestrictCubeCache(bdd);

        caches = Map.ofEntries(
                entry("support", supportCache),
                entry("fraction", fractionCache),
                entry("fraction_in", fractionInCache),
                entry("difference", differenceCache),
                entry("and", andCache),
                entry("and_simplify", andSimplifyCache),
                entry("xor", xorCache),
                entry("xor_simplify", xorSimplifyCache),
                entry("ite", iteCache),
                entry("ite_simplify", iteSimplifyCache),
                entry("satisfaction", satisfactionCache),
                entry("satisfaction_in", satisfactionInCache),
                entry("intersects", intersectsCache),
                entry("simplify", simplifyCache),
                entry("constrain", constrainCache),
                entry("exists", existsCache),
                entry("and_exists", andExistsCache));

        tableSizeChanged(0);
    }

    int lookupHash() {
        return lookupHash;
    }

    UnaryToObjectCache<int[]> supportCache() {
        return supportCache;
    }

    FractionCache fractionCache() {
        return fractionCache;
    }

    FractionInCache fractionInCache() {
        return fractionInCache;
    }

    DifferenceCache differenceCache() {
        return differenceCache;
    }

    ComposeTupleCache composeTupleCache() {
        return composeTupleCache;
    }

    OperandTupleCache andAllCache() {
        return andAllCache;
    }

    RestrictCubeCache restrictCubeCache() {
        return restrictCubeCache;
    }

    UnaryToIntCache existsCache() {
        return existsCache;
    }

    BinaryToIntCache andExistsCache() {
        return andExistsCache;
    }

    // Size and invalidation

    void tableSizeChanged(int invalidatedNodes) {
        onBddNodesInvalidated(invalidatedNodes);

        logger.log(Level.FINER, "Growing caches if necessary");
        int size = bdd.tableSize() / bdd.configuration().cacheSizeDivider();

        int unarySize = size / 2;
        satisfactionCache.grow(unarySize);
        satisfactionInCache.grow(unarySize);
        fractionCache.grow(unarySize);

        composeTupleCache.grow(size);
        andAllCache.grow(size);
        restrictCubeCache.grow(size);
        fractionInCache.grow(size);
        differenceCache.grow(size);
        andCache.grow(size);
        xorCache.grow(size);
        simplifyCache.grow(size);
        constrainCache.grow(size);
        intersectsCache.grow(size);
        andSimplifyCache.grow(size);
        xorSimplifyCache.grow(size);
        iteCache.grow(size);
        iteSimplifyCache.grow(size);
        existsCache.grow(size);
        andExistsCache.grow(size);
    }

    void variablesChanged() {
        // Satisfaction counts are counts over [decisionVariable, numberOfVariables) - they are keyed on
        // the node alone, but their value depends on the variable count, so adding a variable makes every
        // stored entry wrong. Growing is not enough: CacheBase#grow only records a desired size and
        // resizes lazily.
        satisfactionCache.invalidate();
        satisfactionInCache.invalidate();

        int size = bdd.tableSize() / bdd.configuration().cacheSizeDivider();
        satisfactionCache.grow(size / 2);
        satisfactionInCache.grow(size / 2);
        existsCache.grow(size);
    }

    @Override
    public void orderChanged(int[] previousVariableToLevel, int[] currentVariableToLevel, NatSet movedVariables) {
        /* Everything goes. Only the counts (ranging over the levels below their node) and constrain (deciding
         * top-down) are wrong now; the rest hold in any order, reordering rewriting in place. But keeping them
         * saves one clear at most: invalidation is lazy, and a reordering that collects has cleared them already. */
        invalidate();
    }

    @Override
    public void variablesInserted(int level, int count) {
        // Nothing moved - an insertion preserves every level comparison - but the count did.
        variablesChanged();
    }

    private Collection<IntCache> caches() {
        return caches.values();
    }

    void invalidate() {
        caches().forEach(IntCache::invalidate);
        composeTupleCache.invalidate();
        andAllCache.invalidate();
        restrictCubeCache.invalidate();
    }

    void onBddNodesInvalidated(int invalidatedNodes) {
        if (bdd.isReordering()) {
            /* A reordering's swaps orphan the old nodes, so its collections reclaim most of what the caches name.
             * One clear beats pruning repeatedly, and clearing on a growth too spares rehashing entries that will
             * not survive either. */
            invalidate();
            return;
        }
        if (invalidatedNodes == 0) {
            return;
        }
        // If we reclaimed a lot of nodes, we won't be able to save much, so don't try
        boolean preserve = invalidatedNodes < bdd.tableSize() / 2;
        for (IntCache cache : caches()) {
            cache.clearInvalidNodes(preserve);
        }
        composeTupleCache.clearInvalidNodes(preserve);
        andAllCache.clearInvalidNodes(preserve);
        restrictCubeCache.clearInvalidNodes(preserve);
    }

    // Lookup

    void initExists(NatSet quantifiedVariables) {
        if (quantifiedVariables.equals(this.existsVariables)) {
            existsReuseCount += 1;
            return;
        }
        this.existsVariables = NatSet.copyOf(quantifiedVariables);
        existsCache.invalidate();
    }

    void initAndExists(NatSet quantifiedVariables) {
        if (quantifiedVariables.equals(this.andExistsVariables)) {
            andExistsReuseCount += 1;
            return;
        }
        this.andExistsVariables = NatSet.copyOf(quantifiedVariables);
        andExistsCache.invalidate();
    }

    int lookupAnd(int function1, int function2) {
        assert bdd.isValidNonConstantFunction(function1) && bdd.isValidNonConstantFunction(function2);
        assert binarySymmetricWellOrdered(function1, function2);
        int result = andCache.lookup(function1, function2);
        lookupHash = andCache.lookupHash();
        return result;
    }

    int lookupAndSimplify(int function1, int function2, int domain) {
        assert bdd.isValidNonConstantFunction(function1)
                && bdd.isValidNonConstantFunction(function2)
                && bdd.isValidNonConstantFunction(domain);
        assert binarySymmetricWellOrdered(function1, function2);
        int result = andSimplifyCache.lookup(function1, function2, domain);
        lookupHash = andSimplifyCache.lookupHash();
        return result;
    }

    int lookupXor(int function1, int function2) {
        assert bdd.isValidNonConstantFunction(function1) && bdd.isValidNonConstantFunction(function2);
        assert binarySymmetricWellOrdered(function1, function2);
        int result = xorCache.lookup(function1, function2);
        lookupHash = xorCache.lookupHash();
        return result;
    }

    int lookupXorSimplify(int function1, int function2, int domain) {
        assert bdd.isValidNonConstantFunction(function1)
                && bdd.isValidNonConstantFunction(function2)
                && bdd.isValidNonConstantFunction(domain);
        assert binarySymmetricWellOrdered(function1, function2);
        int result = xorSimplifyCache.lookup(function1, function2, domain);
        lookupHash = xorSimplifyCache.lookupHash();
        return result;
    }

    int lookupSimplify(int function, int domain) {
        assert bdd.isValidNonConstantFunction(function)
                && bdd.isPositive(function)
                && bdd.isValidNonConstantFunction(domain);
        int result = simplifyCache.lookup(function, domain);
        lookupHash = simplifyCache.lookupHash();
        return result;
    }

    int lookupConstrain(int function, int domain) {
        assert bdd.isValidNonConstantFunction(function)
                && bdd.isPositive(function)
                && bdd.isValidNonConstantFunction(domain);
        int result = constrainCache.lookup(function, domain);
        lookupHash = constrainCache.lookupHash();
        return result;
    }

    int lookupIntersects(int function1, int function2) {
        assert bdd.isValidNonConstantFunction(function1) && bdd.isValidNonConstantFunction(function2);
        assert binarySymmetricWellOrdered(function1, function2);
        int result = intersectsCache.lookup(function1, function2);
        lookupHash = intersectsCache.lookupHash();
        return result;
    }

    int lookupIfThenElse(int function1, int function2, int function3) {
        assert bdd.isValidNonConstantFunction(function1)
                && bdd.isPositive(function1)
                && bdd.isValidNonConstantFunction(function2)
                && bdd.isPositive(function2)
                && bdd.isValidNonConstantFunction(function3);
        int result = iteCache.lookup(function1, function2, function3);
        lookupHash = iteCache.lookupHash();
        return result;
    }

    int lookupIfThenElseSimplify(int function1, int function2, int function3, int domain) {
        assert bdd.isValidNonConstantFunction(function1)
                && bdd.isPositive(function1)
                && bdd.isValidNonConstantFunction(function2)
                && bdd.isPositive(function2)
                && bdd.isValidNonConstantFunction(function3)
                && bdd.isValidNonConstantFunction(domain);
        int result = iteSimplifyCache.lookup(function1, function2, function3, domain);
        lookupHash = iteSimplifyCache.lookupHash();
        return result;
    }

    @Nullable
    BigInteger lookupSatisfaction(int function) {
        assert bdd.isValidNonConstantFunction(function);
        BigInteger result = satisfactionCache.lookup(function);
        lookupHash = satisfactionCache.lookupHash();
        return result;
    }

    @Nullable
    BigInteger lookupSatisfactionIn(int function, int domain) {
        assert bdd.isValidNonConstantFunction(function) && bdd.isValidNonConstantFunction(domain);
        BigInteger result = satisfactionInCache.lookup(function, domain);
        lookupHash = satisfactionInCache.lookupHash();
        return result;
    }

    int lookupExists(int function) {
        assert bdd.isValidNonConstantFunction(function);
        int result = existsCache.lookup(function);
        lookupHash = existsCache.lookupHash();
        return result;
    }

    // Put

    void putAnd(int hash, int function1, int function2, int result) {
        assert bdd.isValidNonConstantFunction(function1)
                && bdd.isValidNonConstantFunction(function2)
                && bdd.isValidFunction(result);
        assert binarySymmetricWellOrdered(function1, function2);
        andCache.put(hash, function1, function2, result);
    }

    void putAndSimplify(int hash, int function1, int function2, int domain, int result) {
        assert bdd.isValidNonConstantFunction(function1)
                && bdd.isValidNonConstantFunction(function2)
                && bdd.isValidNonConstantFunction(domain)
                && bdd.isValidFunction(result);
        assert binarySymmetricWellOrdered(function1, function2);
        andSimplifyCache.put(hash, function1, function2, domain, result);
    }

    void putXor(int hash, int function1, int function2, int result) {
        assert bdd.isValidNonConstantFunction(function1)
                && bdd.isValidNonConstantFunction(function2)
                && bdd.isValidFunction(result);
        assert binarySymmetricWellOrdered(function1, function2);
        xorCache.put(hash, function1, function2, result);
    }

    void putXorSimplify(int hash, int function1, int function2, int domain, int result) {
        assert bdd.isValidNonConstantFunction(function1)
                && bdd.isValidNonConstantFunction(function2)
                && bdd.isValidNonConstantFunction(domain)
                && bdd.isValidFunction(result);
        assert binarySymmetricWellOrdered(function1, function2);
        xorSimplifyCache.put(hash, function1, function2, domain, result);
    }

    void putSimplify(int hash, int function, int domain, int result) {
        assert bdd.isValidNonConstantFunction(function)
                && bdd.isPositive(function)
                && bdd.isValidNonConstantFunction(domain)
                && bdd.isValidFunction(result);
        simplifyCache.put(hash, function, domain, result);
    }

    void putConstrain(int hash, int function, int domain, int result) {
        assert bdd.isValidNonConstantFunction(function)
                && bdd.isPositive(function)
                && bdd.isValidNonConstantFunction(domain)
                && bdd.isValidFunction(result);
        constrainCache.put(hash, function, domain, result);
    }

    void putIntersects(int hash, int function1, int function2, boolean result) {
        assert bdd.isValidNonConstantFunction(function1) && bdd.isValidNonConstantFunction(function2);
        assert binarySymmetricWellOrdered(function1, function2);
        intersectsCache.put(hash, function1, function2, result);
    }

    void putIfThenElse(int hash, int function1, int function2, int function3, int result) {
        assert bdd.isValidNonConstantFunction(function1)
                && bdd.isPositive(function1)
                && bdd.isValidNonConstantFunction(function2)
                && bdd.isPositive(function2)
                && bdd.isValidNonConstantFunction(function3)
                && bdd.isValidFunction(result);
        iteCache.put(hash, function1, function2, function3, result);
    }

    void putIfThenElseSimplify(int hash, int function1, int function2, int function3, int domain, int result) {
        assert bdd.isValidNonConstantFunction(function1)
                && bdd.isPositive(function1)
                && bdd.isValidNonConstantFunction(function2)
                && bdd.isPositive(function2)
                && bdd.isValidNonConstantFunction(function3)
                && bdd.isValidNonConstantFunction(domain)
                && bdd.isValidFunction(result);
        iteSimplifyCache.put(hash, function1, function2, function3, domain, result);
    }

    void putSatisfaction(int hash, int function, BigInteger satisfactionCount) {
        assert bdd.isValidNonConstantFunction(function);
        satisfactionCache.put(hash, function, satisfactionCount);
    }

    void putSatisfactionIn(int hash, int function, int domain, BigInteger satisfactionCount) {
        assert bdd.isValidNonConstantFunction(function);
        satisfactionInCache.put(hash, function, domain, satisfactionCount);
    }

    void putExists(int hash, int inputNode, int result) {
        assert bdd.isValidNonConstantFunction(inputNode) && bdd.isValidFunction(result);
        assert hash == HashUtil.hash(inputNode);
        existsCache.put(hash, inputNode, result);
    }

    // Utility

    @Override
    public void report(StatisticsReport report, StatisticsDetail detail) {
        caches.forEach((name, cache) -> cache.report(report.about("cache_", name), detail));
        composeTupleCache.report(report.about("cache_", "compose_tuple"), detail);
        andAllCache.report(report.about("cache_", "and_all"), detail);
        restrictCubeCache.report(report.about("cache_", "restrict_cube"), detail);
        report.put(EXISTS_REUSE_COUNT, existsReuseCount);
        report.put(AND_EXISTS_REUSE_COUNT, andExistsReuseCount);
    }

    abstract static class IntCache extends CacheBase.IntKeys {
        final BooleanBase<?, ?> bdd;

        IntCache(BooleanBase<?, ?> bdd, int arity, int binSize) {
            super(arity, binSize);
            this.bdd = bdd;
        }

        IntCache(BooleanBase<?, ?> bdd, int arity, int binSize, BooleanSupplier cacheDependenciesValid) {
            super(arity, binSize, cacheDependenciesValid);
            this.bdd = bdd;
        }

        @Override
        protected boolean isValid(int binStart) {
            for (int j = binStart; j < binStart + keyCount; j++) {
                if (!bdd.isValidNonConstantFunction(cache[j])) {
                    return false;
                }
            }
            return isValidResult(binStart);
        }

        void clearInvalidNodes(boolean attemptPruning) {
            prune(attemptPruning, this::isValid);
        }

        protected abstract boolean isValidResult(int binStart);
    }

    /** Function keys to a function, the result in the bin after the keys; the subclasses fix the key count. */
    abstract static class ToIntCache extends IntCache {
        ToIntCache(BooleanBase<?, ?> bdd, int arity) {
            super(bdd, arity, arity + 1);
        }

        ToIntCache(BooleanBase<?, ?> bdd, int arity, BooleanSupplier cacheDependenciesValid) {
            super(bdd, arity, arity + 1, cacheDependenciesValid);
        }

        @Override
        protected boolean isValidResult(int binStart) {
            return bdd.isValidFunction(cache[binStart + keyCount]);
        }
    }

    static class UnaryToIntCache extends ToIntCache {
        UnaryToIntCache(BooleanBase<?, ?> bdd) {
            super(bdd, 1);
        }

        UnaryToIntCache(BooleanBase<?, ?> bdd, BooleanSupplier pruningValid) {
            super(bdd, 1, pruningValid);
        }

        protected int lookup(int function) {
            return resultIn(findBin(function));
        }

        void put(int hash, int function, int result) {
            storeResult(storeKeys(hash, function), result);
        }
    }

    static class BinaryToIntCache extends ToIntCache {
        BinaryToIntCache(BooleanBase<?, ?> bdd) {
            super(bdd, 2);
        }

        BinaryToIntCache(BooleanBase<?, ?> bdd, BooleanSupplier cacheDependenciesValid) {
            super(bdd, 2, cacheDependenciesValid);
        }

        protected int lookup(int function1, int function2) {
            return resultIn(findBin(function1, function2));
        }

        void put(int hash, int function1, int function2, int result) {
            storeResult(storeKeys(hash, function1, function2), result);
        }
    }

    static class TernaryToIntCache extends ToIntCache {
        TernaryToIntCache(BooleanBase<?, ?> bdd) {
            super(bdd, 3);
        }

        protected int lookup(int function1, int function2, int function3) {
            return resultIn(findBin(function1, function2, function3));
        }

        void put(int hash, int function1, int function2, int function3, int result) {
            storeResult(storeKeys(hash, function1, function2, function3), result);
        }
    }

    static class QuaternaryToIntCache extends ToIntCache {
        QuaternaryToIntCache(BooleanBase<?, ?> bdd) {
            super(bdd, 4);
        }

        protected int lookup(int function1, int function2, int function3, int function4) {
            return resultIn(findBin(function1, function2, function3, function4));
        }

        void put(int hash, int function1, int function2, int function3, int function4, int result) {
            storeResult(storeKeys(hash, function1, function2, function3, function4), result);
        }
    }

    /** Two function keys to a bit, kept beside the keys in {@link CacheBase.Bits}. */
    static class BinaryToBooleanCache extends IntCache {
        private Bits values = new Bits(0);

        BinaryToBooleanCache(BooleanBase<?, ?> bdd) {
            super(bdd, 2, 2);
        }

        @Override
        protected boolean isValidResult(int binStart) {
            return true;
        }

        @Override
        protected void growInto(int newSize, int[] newCache, boolean preserve) {
            Bits newValues = new Bits(newSize);
            if (preserve) {
                rehashInto(newSize, newCache, (oldBin, newBin) -> newValues.set(newBin, values.get(oldBin)));
            }
            this.values = newValues;
        }

        protected int lookup(int function1, int function2) {
            int bin = findBin(function1, function2);
            if (bin < 0) {
                return NodeTable.PLACEHOLDER;
            }
            return values.get(bin) ? bdd.trueFunction() : bdd.falseFunction();
        }

        void put(int hash, int function1, int function2, boolean result) {
            values.set(storeKeys(hash, function1, function2), result);
        }
    }

    @SuppressWarnings("unchecked")
    static class UnaryToObjectCache<V> extends IntCache {
        private Object[] values = EMPTY_OBJECT_ARRAY;

        UnaryToObjectCache(BooleanBase<?, ?> bdd) {
            super(bdd, 1, 1);
        }

        @Override
        protected boolean isValidResult(int binStart) {
            return true;
        }

        @Override
        protected void growInto(int newSize, int[] newCache, boolean preserve) {
            if (preserve) {
                Object[] newValues = new Object[newSize];
                rehashInto(newSize, newCache, (oldBin, newBin) -> newValues[newBin] = values[oldBin]);
                this.values = newValues;
            } else {
                this.values = new Object[newSize];
            }
        }

        @Nullable
        protected V lookup(int function) {
            int bin = findBin(function);
            return bin < 0 ? null : (V) values[bin];
        }

        void put(int hash, int function, V result) {
            values[storeKeys(hash, function)] = result;
        }
    }

    static final class FractionCache extends IntCache {
        private double[] fractions = EMPTY_DOUBLE_ARRAY;
        private int lookupBin = -1;

        FractionCache(BooleanBase<?, ?> bdd) {
            super(bdd, 1, 1);
        }

        @Override
        protected boolean isValidResult(int binStart) {
            return true;
        }

        @Override
        protected void growInto(int newSize, int[] newCache, boolean preserve) {
            if (preserve) {
                double[] newFractions = new double[2 * newSize];
                rehashInto(newSize, newCache, (oldBin, newBin) -> {
                    newFractions[2 * newBin] = fractions[2 * oldBin];
                    newFractions[2 * newBin + 1] = fractions[2 * oldBin + 1];
                });
                this.fractions = newFractions;
            } else {
                this.fractions = new double[2 * newSize];
            }
        }

        /** Whether {@code node} is cached; if so, {@link #fraction()} and {@link #complementFraction()} read it. */
        boolean lookup(int node) {
            lookupBin = findBin(node);
            return lookupBin >= 0;
        }

        double fraction() {
            return fractions[2 * lookupBin];
        }

        double complementFraction() {
            return fractions[2 * lookupBin + 1];
        }

        void put(int hash, int node, double fraction, double complementFraction) {
            ensureValid();
            assert bdd.isValidNonConstantFunction(node) && bdd.isPositive(node);
            int binIndex = storeKeys(hash, node);
            fractions[2 * binIndex] = fraction;
            fractions[2 * binIndex + 1] = complementFraction;
        }
    }

    /**
     * {@link FractionCache} relative to a domain: per regular node and domain, the satisfying fractions of the node's
     * and its complement's conjunction with the domain, both scaled by one binary exponent (see
     * {@link BddImpl#satisfyingFractionIn}). Stable for the same reasons.
     */
    static final class FractionInCache extends IntCache {
        private double[] fractions = EMPTY_DOUBLE_ARRAY;
        private int[] exponents = EMPTY_INT_ARRAY;
        private int lookupBin = -1;

        FractionInCache(BooleanBase<?, ?> bdd) {
            super(bdd, 2, 2);
        }

        @Override
        protected boolean isValidResult(int binStart) {
            return true;
        }

        @Override
        protected void growInto(int newSize, int[] newCache, boolean preserve) {
            if (preserve) {
                double[] newFractions = new double[2 * newSize];
                int[] newExponents = new int[newSize];
                rehashInto(newSize, newCache, (oldBin, newBin) -> {
                    newFractions[2 * newBin] = fractions[2 * oldBin];
                    newFractions[2 * newBin + 1] = fractions[2 * oldBin + 1];
                    newExponents[newBin] = exponents[oldBin];
                });
                this.fractions = newFractions;
                this.exponents = newExponents;
            } else {
                this.fractions = new double[2 * newSize];
                this.exponents = new int[newSize];
            }
        }

        /**
         * Whether the pair is cached; if so, {@link #fraction()}, {@link #complementFraction()} and
         * {@link #exponent()} read it.
         */
        boolean lookup(int node, int domain) {
            lookupBin = findBin(node, domain);
            return lookupBin >= 0;
        }

        double fraction() {
            return fractions[2 * lookupBin];
        }

        double complementFraction() {
            return fractions[2 * lookupBin + 1];
        }

        int exponent() {
            return exponents[lookupBin];
        }

        void put(int hash, int node, int domain, double fraction, double complementFraction, int exponent) {
            ensureValid();
            assert bdd.isValidNonConstantFunction(node)
                    && bdd.isPositive(node)
                    && bdd.isValidNonConstantFunction(domain);
            int binIndex = storeKeys(hash, node, domain);
            fractions[2 * binIndex] = fraction;
            fractions[2 * binIndex + 1] = complementFraction;
            exponents[binIndex] = exponent;
        }
    }

    /**
     * Per pair of regular nodes, the smaller first, the fraction of assignments on which they differ and the fraction on
     * which they agree (see {@link BddImpl#influences}): a complement on either side swaps the two, so one entry serves
     * all four combinations. Stable for the same reasons as {@link FractionCache}.
     */
    static final class DifferenceCache extends IntCache {
        private double[] fractions = EMPTY_DOUBLE_ARRAY;
        private int lookupBin = -1;

        DifferenceCache(BooleanBase<?, ?> bdd) {
            super(bdd, 2, 2);
        }

        @Override
        protected boolean isValidResult(int binStart) {
            return true;
        }

        @Override
        protected void growInto(int newSize, int[] newCache, boolean preserve) {
            if (preserve) {
                double[] newFractions = new double[2 * newSize];
                rehashInto(newSize, newCache, (oldBin, newBin) -> {
                    newFractions[2 * newBin] = fractions[2 * oldBin];
                    newFractions[2 * newBin + 1] = fractions[2 * oldBin + 1];
                });
                this.fractions = newFractions;
            } else {
                this.fractions = new double[2 * newSize];
            }
        }

        /** Whether the pair is cached; if so, {@link #difference()} and {@link #agreement()} read it. */
        boolean lookup(int first, int second) {
            lookupBin = findBin(first, second);
            return lookupBin >= 0;
        }

        double difference() {
            return fractions[2 * lookupBin];
        }

        double agreement() {
            return fractions[2 * lookupBin + 1];
        }

        void put(int hash, int first, int second, double difference, double agreement) {
            ensureValid();
            assert bdd.isValidNonConstantFunction(first)
                    && bdd.isPositive(first)
                    && bdd.isValidNonConstantFunction(second)
                    && bdd.isPositive(second)
                    && first < second;
            int binIndex = storeKeys(hash, first, second);
            fractions[2 * binIndex] = difference;
            fractions[2 * binIndex + 1] = agreement;
        }
    }

    /**
     * Compositions keyed on their whole context: {@code [F, D, v_1, ..., v_k, R_1, ..., R_k]} - the function, the
     * domain, and each replaced variable in F's support, then their replacements. Positions 0, 1 and the last k name
     * functions, the rest variables. Stable: nothing outside the key enters the result.
     */
    static final class ComposeTupleCache extends CacheBase.ObjectKeys<int[]> {
        private final BooleanBase<?, ?> bdd;
        private int[] values = EMPTY_INT_ARRAY;
        int lookupHash;

        ComposeTupleCache(BooleanBase<?, ?> bdd) {
            this.bdd = bdd;
        }

        @Override
        protected boolean isValid(int binStart) {
            int[] key = cache[binStart];
            if (key == null || !bdd.isValidFunction(values[binStart])) {
                return false;
            }
            if (!bdd.isValidFunction(key[0]) || !bdd.isValidFunction(key[1])) {
                return false;
            }
            // The node and the domain, then the variables, then their replacements.
            for (int index = 2 + (key.length - 2) / 2; index < key.length; index++) {
                if (!bdd.isValidFunction(key[index])) {
                    return false;
                }
            }
            return true;
        }

        @Override
        protected int[][] newArray(int size) {
            return new int[size][];
        }

        @Override
        protected int hashOf(int[] key) {
            return Arrays.hashCode(key);
        }

        @Override
        protected void growInto(int newSize, int[][] newCache, boolean preserve) {
            if (preserve) {
                int[] newValues = new int[newSize];
                rehashInto(newSize, newCache, (oldBin, newBin) -> newValues[newBin] = values[oldBin]);
                this.values = newValues;
            } else {
                this.values = new int[newSize];
            }
        }

        void clearInvalidNodes(boolean attemptPruning) {
            prune(attemptPruning, this::isValid);
        }

        int lookup(int[] key) {
            ensureValid();
            int hash = Arrays.hashCode(key);
            lookupHash = hash;
            int index = binIndex(hash);
            if (Arrays.equals(key, cache[index])) {
                assert isValid(index);
                statistics.hit();
                return values[index];
            }
            statistics.miss();
            return bdd.placeholder();
        }

        void put(int hash, int[] key, int result) {
            ensureValid();
            assert hash == Arrays.hashCode(key);
            int index = putBin(hash);
            cache[index] = key;
            values[index] = result;
        }
    }

    /** An n-ary conjunction keyed on its canonical operand tuple (every entry a function), the result an int. */
    static final class OperandTupleCache extends CacheBase.ObjectKeys<int[]> {
        private final BooleanBase<?, ?> bdd;
        private int[] values = EMPTY_INT_ARRAY;
        int lookupHash;

        OperandTupleCache(BooleanBase<?, ?> bdd) {
            this.bdd = bdd;
        }

        @Override
        protected boolean isValid(int binStart) {
            int[] key = cache[binStart];
            if (key == null || !bdd.isValidFunction(values[binStart])) {
                return false;
            }
            for (int operand : key) {
                if (!bdd.isValidNonConstantFunction(operand)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        protected int[][] newArray(int size) {
            return new int[size][];
        }

        @Override
        protected int hashOf(int[] key) {
            return Arrays.hashCode(key);
        }

        @Override
        protected void growInto(int newSize, int[][] newCache, boolean preserve) {
            if (preserve) {
                int[] newValues = new int[newSize];
                rehashInto(newSize, newCache, (oldBin, newBin) -> newValues[newBin] = values[oldBin]);
                this.values = newValues;
            } else {
                this.values = new int[newSize];
            }
        }

        void clearInvalidNodes(boolean attemptPruning) {
            prune(attemptPruning, this::isValid);
        }

        int lookup(int[] key) {
            ensureValid();
            int hash = Arrays.hashCode(key);
            lookupHash = hash;
            int index = binIndex(hash);
            if (Arrays.equals(key, cache[index])) {
                assert isValid(index);
                statistics.hit();
                return values[index];
            }
            statistics.miss();
            return bdd.placeholder();
        }

        void put(int hash, int[] key, int result) {
            ensureValid();
            assert hash == Arrays.hashCode(key);
            int index = putBin(hash);
            cache[index] = key;
            values[index] = result;
        }
    }

    /**
     * A node restricted by a cube within a domain ({@code TRUE} for a plain restriction). The cube is never changed
     * once it is a key.
     */
    static final class RestrictKey {
        final int node;
        final int domain;
        final Cube cube;
        final int hash;

        RestrictKey(int node, int domain, Cube cube, int hash) {
            this.node = node;
            this.domain = domain;
            this.cube = cube;
            this.hash = hash;
        }
    }

    /**
     * Restrictions keyed on the node, the domain they are simplified within and the whole cube, so entries of
     * different restrictions coexist.
     */
    static final class RestrictCubeCache extends CacheBase.ObjectKeys<RestrictKey> {
        private final BooleanBase<?, ?> bdd;
        private int[] values = EMPTY_INT_ARRAY;
        int lookupHash;

        RestrictCubeCache(BooleanBase<?, ?> bdd) {
            this.bdd = bdd;
        }

        static int hash(int node, int domain, int cubeHash) {
            return HashUtil.hash(node, domain, cubeHash);
        }

        @Override
        protected boolean isValid(int binStart) {
            RestrictKey key = cache[binStart];
            return key != null
                    && bdd.isValidNonConstantFunction(key.node)
                    && bdd.isValidFunction(key.domain)
                    && bdd.isValidFunction(values[binStart]);
        }

        @Override
        protected RestrictKey[] newArray(int size) {
            return new RestrictKey[size];
        }

        @Override
        protected int hashOf(RestrictKey key) {
            return key.hash;
        }

        @Override
        protected void growInto(int newSize, RestrictKey[] newCache, boolean preserve) {
            if (preserve) {
                int[] newValues = new int[newSize];
                rehashInto(newSize, newCache, (oldBin, newBin) -> newValues[newBin] = values[oldBin]);
                this.values = newValues;
            } else {
                this.values = new int[newSize];
            }
        }

        void clearInvalidNodes(boolean attemptPruning) {
            prune(attemptPruning, this::isValid);
        }

        int lookup(int node, int domain, Cube cube, int cubeHash) {
            ensureValid();
            int hash = hash(node, domain, cubeHash);
            lookupHash = hash;
            int index = binIndex(hash);
            RestrictKey key = cache[index];
            if (key != null
                    && key.node == node
                    && key.domain == domain
                    && (key.cube == cube || key.cube.equals(cube))) { // NOPMD - the same cube is the common case
                assert isValid(index);
                statistics.hit();
                return values[index];
            }
            statistics.miss();
            return bdd.placeholder();
        }

        void put(int hash, int node, int domain, Cube cube, int result) {
            ensureValid();
            int index = putBin(hash);
            cache[index] = new RestrictKey(node, domain, cube, hash);
            values[index] = result;
        }
    }

    @SuppressWarnings("unchecked")
    static class BinaryToObjectCache<V> extends IntCache {
        private Object[] values = EMPTY_OBJECT_ARRAY;

        BinaryToObjectCache(BooleanBase<?, ?> bdd) {
            super(bdd, 2, 2);
        }

        @Override
        protected boolean isValidResult(int binStart) {
            return true;
        }

        @Override
        protected void growInto(int newSize, int[] newCache, boolean preserve) {
            if (preserve) {
                Object[] newValues = new Object[newSize];
                rehashInto(newSize, newCache, (oldBin, newBin) -> newValues[newBin] = values[oldBin]);
                this.values = newValues;
            } else {
                this.values = new Object[newSize];
            }
        }

        @Nullable
        protected V lookup(int function1, int function2) {
            int bin = findBin(function1, function2);
            return bin < 0 ? null : (V) values[bin];
        }

        void put(int hash, int function1, int function2, V result) {
            values[storeKeys(hash, function1, function2)] = result;
        }
    }
}
