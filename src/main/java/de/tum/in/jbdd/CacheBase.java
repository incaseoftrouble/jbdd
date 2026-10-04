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
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

public abstract class CacheBase {
    private static final double USAGE_GROWTH_LOAD_FACTOR = 0.75;
    private static final int USAGE_GROWTH_FACTOR = 2;
    private static final int MINIMUM_SIZE = 16;
    // The written bins are recorded while at most size / WRITTEN_FRACTION: resetting that many scattered bins is still
    // cheaper than filling the whole array.
    private static final int WRITTEN_FRACTION = 32;
    private static final int[] NO_BINS = new int[0];
    /** A registered operation's cache starts at this fraction of an ephemeral one and grows on usage. */
    static final int REGISTERED_OPERATION_DIVIDER = 8;

    int size = 0;
    CacheStatistics statistics = new CacheStatistics();
    private final BooleanSupplier cacheDependenciesValid;
    private int desiredSize = 0;
    private boolean cacheInvalid = true;

    /*
     * The bins written since the last clear, as long as there are few (writtenCount >= 0), so that a clear resets just
     * those: most clears come from a cache that is invalidated per call or per parameter and has since seen a small
     * call, leaving a large array almost empty. -1 once there were more writes than the record holds, or entries were
     * moved by a growth - the next clear is then a full one.
     */
    private int[] written = NO_BINS;
    private int writtenCount = 0;

    CacheBase() {
        this(() -> true);
    }

    CacheBase(BooleanSupplier cacheDependenciesValid) {
        this.cacheDependenciesValid = cacheDependenciesValid;
    }

    int size() {
        return size;
    }

    abstract double loadFactor();

    int binIndex(int hash) {
        return Util.mod(hash, size());
    }

    /** The bin a put of {@code hash} writes: counts the put and records the bin for the next clear. */
    final int putBin(int hash) {
        statistics.put();
        int bin = binIndex(hash);
        int count = writtenCount;
        if (count >= 0) {
            if (count < written.length) {
                written[count] = bin;
                writtenCount = count + 1;
            } else {
                writtenCount = -1;
            }
        }
        return bin;
    }

    protected abstract boolean isValid(int binStart);

    /**
     * Removes entries failing {@code validityCheck} or invalidates the whole cache if pruning isn't
     * worthwhile.
     */
    protected void prune(boolean attemptPruning, IntPredicate validityCheck) {
        if (cacheInvalid) {
            return;
        }
        if (statistics.putCountSinceClear() == 0) {
            assert isEmpty();
            return;
        }
        if (!attemptPruning || !cacheDependenciesValid.getAsBoolean() || statistics.putCountSinceClear() < size() / 4) {
            cacheInvalid = true;
            return;
        }
        if (2 * size < desiredSize) {
            // No point in pruning if we need to grow and check anyway
            growToSize();
            return;
        }
        assert !cacheInvalid;

        int pruned = doPrune(validityCheck);
        statistics.prune(pruned);
        assert allEntriesValid();
    }

    abstract int doPrune(IntPredicate validityCheck);

    void grow(int size) {
        this.desiredSize = Math.max(Math.max(size, MINIMUM_SIZE), this.size);
    }

    void growOnUsage() {
        if (statistics.putCountSinceClear() > size() * USAGE_GROWTH_LOAD_FACTOR) {
            grow(size() * USAGE_GROWTH_FACTOR);
        }
    }

    void ensureValid() {
        if (2 * size < desiredSize) {
            // Also takes care of emptying the cache
            growToSize();
        } else if (cacheInvalid) {
            if (writtenCount >= 0) {
                doClear(written, writtenCount);
                statistics.sparseClear();
            } else {
                doClear();
            }
            writtenCount = 0;
            statistics.clear();
            cacheInvalid = false;
            assert isEmpty();
        }
        assert !Assertions.COSTLY_ASSERTIONS || allEntriesValid();
        assert !cacheInvalid;
    }

    abstract boolean allEntriesValid();

    abstract boolean isEmpty();

    private void growToSize() {
        int newSize = Primes.nextPrime(desiredSize);
        boolean preserve = !cacheInvalid && statistics.putCountSinceClear() > size / 8;
        doGrowToSize(newSize, preserve);
        size = newSize;
        written = newSize / WRITTEN_FRACTION == 0 ? NO_BINS : new int[newSize / WRITTEN_FRACTION];
        // A preserving growth rehashed the entries to bins nobody recorded.
        writtenCount = preserve ? -1 : 0;
        if (!preserve) {
            statistics.clear();
            assert isEmpty();
        }
        // If cache was invalid before grow, make sure the cache has been emptied
        assert !cacheInvalid || isEmpty();
        cacheInvalid = false;
    }

    protected abstract void doClear();

    /** Empties the first {@code count} bins of {@code bins}, which hold every bin written since the last clear. */
    protected abstract void doClear(int[] bins, int count);

    protected abstract void doGrowToSize(int newSize, boolean preserve);

    public void invalidate() {
        if (statistics.putCountSinceClear() > 0) {
            cacheInvalid = true;
        }
    }

    public Map<String, Object> statistics(String name) {
        Map<String, Object> data = Map.of("size", size, "load_factor", loadFactor());
        return Stream.concat(data.entrySet().stream(), statistics.data().entrySet().stream())
                .collect(Collectors.toUnmodifiableMap(
                        e -> String.format("%s_%s", name, e.getKey()), Map.Entry::getValue));
    }

    /**
     * Reports one entry surviving a resize, so that a cache holding a parallel array indexed by <em>bin</em>
     * (rather than by slot) can follow the move. See {@link IntKeys#rehashInto}.
     */
    @FunctionalInterface
    interface BinRelocation {
        BinRelocation NONE = (oldBinIndex, newBinIndex) -> {};

        void relocate(int oldBinIndex, int newBinIndex);
    }

    abstract static class IntKeys extends CacheBase {
        private static final int[] EMPTY_INT_ARRAY = new int[0];

        /**
         * How many leading slots of a bin form its key; the remaining {@code binSize - keyCount} hold the
         * result. Equal to {@code binSize} for a cache whose result lives in a parallel array instead.
         */
        final int keyCount;

        final int binSize;
        int[] cache = EMPTY_INT_ARRAY;
        // The hash of the last lookup, for the put that follows a miss.
        int lookupHash = 0;

        IntKeys(int keyCount, int binSize) {
            this(keyCount, binSize, () -> true);
        }

        IntKeys(int keyCount, int binSize, BooleanSupplier cacheDependenciesValid) {
            super(cacheDependenciesValid);
            assert 0 < keyCount && keyCount <= binSize;
            this.keyCount = keyCount;
            this.binSize = binSize;
        }

        int lookupHash() {
            return lookupHash;
        }

        /*
         * The lookups and puts every int-keyed cache shares, by key count: a lookup finds the bin holding the keys
         * (counting the hit) or -1 (counting the miss) and keeps the hash for the put; a put writes the keys and
         * returns the bin, for the cache to store its result - in the bin after the keys, or in an array of its own.
         */

        final int findBin(int key) {
            ensureValid();
            int hash = HashUtil.hash(key);
            lookupHash = hash;
            int bin = binIndex(hash);
            int binStart = binSize * bin;
            if (key == cache[binStart]) {
                return hit(bin, binStart);
            }
            statistics.miss();
            return -1;
        }

        final int findBin(int key1, int key2) {
            ensureValid();
            int hash = HashUtil.hash(key1, key2);
            lookupHash = hash;
            int bin = binIndex(hash);
            int binStart = binSize * bin;
            if (key1 == cache[binStart] && key2 == cache[binStart + 1]) {
                return hit(bin, binStart);
            }
            statistics.miss();
            return -1;
        }

        final int findBin(int key1, int key2, int key3) {
            ensureValid();
            int hash = HashUtil.hash(key1, key2, key3);
            lookupHash = hash;
            int bin = binIndex(hash);
            int binStart = binSize * bin;
            if (key1 == cache[binStart] && key2 == cache[binStart + 1] && key3 == cache[binStart + 2]) {
                return hit(bin, binStart);
            }
            statistics.miss();
            return -1;
        }

        final int findBin(int key1, int key2, int key3, int key4) {
            ensureValid();
            int hash = HashUtil.hash(key1, key2, key3, key4);
            lookupHash = hash;
            int bin = binIndex(hash);
            int binStart = binSize * bin;
            if (key1 == cache[binStart]
                    && key2 == cache[binStart + 1]
                    && key3 == cache[binStart + 2]
                    && key4 == cache[binStart + 3]) {
                return hit(bin, binStart);
            }
            statistics.miss();
            return -1;
        }

        private int hit(int bin, int binStart) {
            assert isValid(binStart);
            statistics.hit();
            return bin;
        }

        final int storeKeys(int hash, int key) {
            ensureValid();
            assert hash == HashUtil.hash(key);
            int bin = putBin(hash);
            cache[binSize * bin] = key;
            return bin;
        }

        final int storeKeys(int hash, int key1, int key2) {
            ensureValid();
            assert hash == HashUtil.hash(key1, key2);
            int bin = putBin(hash);
            int binStart = binSize * bin;
            cache[binStart] = key1;
            cache[binStart + 1] = key2;
            return bin;
        }

        final int storeKeys(int hash, int key1, int key2, int key3) {
            ensureValid();
            assert hash == HashUtil.hash(key1, key2, key3);
            int bin = putBin(hash);
            int binStart = binSize * bin;
            cache[binStart] = key1;
            cache[binStart + 1] = key2;
            cache[binStart + 2] = key3;
            return bin;
        }

        final int storeKeys(int hash, int key1, int key2, int key3, int key4) {
            ensureValid();
            assert hash == HashUtil.hash(key1, key2, key3, key4);
            int bin = putBin(hash);
            int binStart = binSize * bin;
            cache[binStart] = key1;
            cache[binStart + 1] = key2;
            cache[binStart + 2] = key3;
            cache[binStart + 3] = key4;
            return bin;
        }

        /** For a cache keeping an int result after the keys: the result in {@code bin}, a placeholder for -1. */
        final int resultIn(int bin) {
            return bin < 0 ? NodeTable.PLACEHOLDER : cache[binSize * bin + keyCount];
        }

        final void storeResult(int bin, int result) {
            assert binSize == keyCount + 1;
            cache[binSize * bin + keyCount] = result;
        }

        @Override
        double loadFactor() {
            if (size() == 0) {
                return 0.0;
            }
            int loadedBins = 0;
            for (int i = 0; i < cache.length; i += binSize) {
                if (cache[i] != NodeTable.PLACEHOLDER) {
                    loadedBins++;
                }
            }
            return (double) loadedBins / size();
        }

        @Override
        boolean allEntriesValid() {
            for (int binStart = 0; binStart < cache.length; binStart += binSize) {
                if (cache[binStart] != NodeTable.PLACEHOLDER && !isValid(binStart)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        boolean isEmpty() {
            for (int binStart = 0; binStart < cache.length; binStart += binSize) {
                if (cache[binStart] != NodeTable.PLACEHOLDER) {
                    return false;
                }
            }
            return true;
        }

        @Override
        protected void doClear() {
            if (binSize == 1) {
                Arrays.fill(cache, NodeTable.PLACEHOLDER);
            } else {
                for (int i = 0; i < cache.length; i += binSize) {
                    cache[i] = NodeTable.PLACEHOLDER;
                }
            }
        }

        @Override
        protected void doClear(int[] bins, int count) {
            for (int i = 0; i < count; i++) {
                cache[bins[i] * binSize] = NodeTable.PLACEHOLDER;
            }
        }

        @Override
        protected void doGrowToSize(int newSize, boolean preserve) {
            int[] newCache = new int[newSize * binSize];
            // NodeTable.PLACEHOLDER == 0, so a fresh int[] is already correctly "empty" - no explicit fill.
            assert newCache[0] == NodeTable.PLACEHOLDER;

            growInto(newSize, newCache, preserve);
            cache = newCache;
        }

        /**
         * Fills the freshly allocated {@code newCache}. The default carries over every still-valid entry;
         * a cache with a parallel value array overrides this to size that array too, and to hand
         * {@link #rehashInto} a {@link BinRelocation} moving the values along.
         */
        protected void growInto(int newSize, int[] newCache, boolean preserve) {
            if (preserve) {
                rehashInto(newSize, newCache, BinRelocation.NONE);
            }
        }

        /**
         * Copies every occupied, still-valid bin into the position its key hashes to in {@code newCache},
         * reporting each move to {@code relocation}. Entries whose keys collide in the new table are
         * dropped, exactly as during normal operation - this is a cache, not a map.
         */
        protected final void rehashInto(int newSize, int[] newCache, BinRelocation relocation) {
            for (int binIndex = 0; binIndex < size; binIndex++) {
                int binStart = binIndex * binSize;
                if (cache[binStart] == NodeTable.PLACEHOLDER || !isValid(binStart)) {
                    continue;
                }
                int newBinIndex = Util.mod(hashOfKeys(binStart), newSize);
                System.arraycopy(cache, binStart, newCache, newBinIndex * binSize, binSize);
                relocation.relocate(binIndex, newBinIndex);
            }
        }

        /**
         * The hash of the key held in the bin starting at {@code binStart} - by construction the very value
         * the corresponding {@code lookup} computed from its arguments, which is what makes a rehash land
         * entries where a later lookup will find them.
         */
        protected int hashOfKeys(int binStart) {
            switch (keyCount) {
                case 1:
                    return HashUtil.hash(cache[binStart]);
                case 2:
                    return HashUtil.hash(cache[binStart], cache[binStart + 1]);
                case 3:
                    return HashUtil.hash(cache[binStart], cache[binStart + 1], cache[binStart + 2]);
                case 4:
                    return HashUtil.hash(
                            cache[binStart], cache[binStart + 1], cache[binStart + 2], cache[binStart + 3]);
                default:
                    throw new AssertionError("No hash for key count " + keyCount);
            }
        }

        @Override
        int doPrune(IntPredicate validityCheck) {
            int pruned = 0;
            for (int binStart = 0; binStart < cache.length; binStart += binSize) {
                if (cache[binStart] != NodeTable.PLACEHOLDER && !validityCheck.test(binStart)) {
                    pruned += 1;
                    cache[binStart] = NodeTable.PLACEHOLDER;
                }
            }
            return pruned;
        }
    }

    @SuppressWarnings({"SuspiciousArrayCast", "unchecked", "VariableNotUsedInsideIf"})
    abstract static class ObjectKeys<V> extends CacheBase {
        private static final Object[] EMPTY_ARRAY = new Object[0];

        // The placeholder is an Object[], not a V[] - safe only because it is never handed out or indexed:
        // size() is 0 until the first grow replaces it via newArray, and every access goes through
        // ensureValid (and would divide by zero in binIndex otherwise).
        @Nullable
        V[] cache = (V[]) EMPTY_ARRAY;

        @Override
        double loadFactor() {
            if (size() == 0) {
                return 0.0;
            }
            int loadedBins = 0;
            for (Object o : cache) {
                if (o != null) {
                    loadedBins++;
                }
            }
            return (double) loadedBins / size();
        }

        @Override
        boolean allEntriesValid() {
            for (int i = 0; i < cache.length; i += 1) {
                if (cache[i] != null && !isValid(i)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        boolean isEmpty() {
            for (Object o : cache) {
                if (o != null) {
                    return false;
                }
            }
            return true;
        }

        @Override
        protected void doClear() {
            Arrays.fill(cache, null);
        }

        @Override
        protected void doClear(int[] bins, int count) {
            for (int i = 0; i < count; i++) {
                cache[bins[i]] = null;
            }
        }

        @Override
        protected void doGrowToSize(int newSize, boolean preserve) {
            V[] newCache = newArray(newSize);
            growInto(newSize, newCache, preserve);
            cache = newCache;
        }

        protected abstract V[] newArray(int size);

        protected abstract int hashOf(V key);

        protected void growInto(int newSize, V[] newCache, boolean preserve) {
            if (preserve) {
                rehashInto(newSize, newCache, BinRelocation.NONE);
            }
        }

        protected final void rehashInto(int newSize, V[] newCache, BinRelocation relocation) {
            for (int binIndex = 0; binIndex < size; binIndex++) {
                V key = cache[binIndex];
                if (key == null || !isValid(binIndex)) {
                    continue;
                }
                int newBinIndex = Util.mod(hashOf(key), newSize);
                newCache[newBinIndex] = key;
                relocation.relocate(binIndex, newBinIndex);
            }
        }

        @Override
        int doPrune(IntPredicate validityCheck) {
            int pruned = 0;
            for (int binStart = 0; binStart < cache.length; binStart += 1) {
                if (cache[binStart] != null && !validityCheck.test(binStart)) {
                    pruned += 1;
                    cache[binStart] = null;
                }
            }
            return pruned;
        }
    }

    /** A mini NatSet implementation */
    static final class Bits {
        // Bin b is bit b (modulo 64) of word b >>> WORD_SHIFT.
        private static final int WORD_SHIFT = Integer.numberOfTrailingZeros(Long.SIZE);

        private final long[] words;

        Bits(int bins) {
            words = new long[(bins + Long.SIZE - 1) >>> WORD_SHIFT];
        }

        boolean get(int bin) {
            return (words[bin >>> WORD_SHIFT] & (1L << bin)) != 0;
        }

        void set(int bin, boolean value) {
            if (value) {
                words[bin >>> WORD_SHIFT] |= 1L << bin;
            } else {
                words[bin >>> WORD_SHIFT] &= ~(1L << bin);
            }
        }
    }
}
