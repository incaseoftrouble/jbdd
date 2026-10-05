/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2023 Tobias Meggendorfer.
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

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * One wrapper per {@link DdContainer#canonicalKey() canonical key}, each holding one reference to its function for
 * as long as it lives: wrappers are held weakly, and the {@link ReferenceQueue} hands a collected one's reference
 * back, which the next miss drains and dereferences.
 */
public class GcReferenceManager<V extends GcReferenceManager.DdContainer, DD extends DecisionDiagram>
        implements StatisticsReporter {
    private static final Logger logger = Logger.getLogger(GcReferenceManager.class.getName());

    // Function 0 is PLACEHOLDER, never a function, so no key space ever produces key 0.
    private static final long EMPTY = 0L;
    private static final int INITIAL_CAPACITY_BITS = 6;
    private static final Statistic WRAPPER_COUNT = Statistic.gauge("wrapper_count", "live {name} wrappers");
    private static final Statistic WRAPPER_DRAINED_COUNT =
            Statistic.counter("wrapper_drained_count", "collected {name} wrappers whose reference was released");
    private static final Statistic WRAPPER_DRAINED_BEFORE_GC_COUNT = Statistic.counter(
            "wrapper_drained_before_gc_count", "collected {name} wrappers released right before a table collection");

    protected final DD dd;
    private final ReferenceQueue<V> queue = new ReferenceQueue<>();

    /* An open-addressing table over the primitive keys (linear probing, backward-shift deletion). It is also what keeps
     * the weak references reachable: an unreachable reference is never enqueued, and its function would stay
     * referenced for good. Hence one table per manager, never one per key space. It grows at a load of 2/3 and
     * shrinks, when draining, below 1/8. */
    private long[] keys = new long[1 << INITIAL_CAPACITY_BITS];
    private @Nullable DdReference<V>[] references = newReferences(1 << INITIAL_CAPACITY_BITS);
    private int shift = Long.SIZE - INITIAL_CAPACITY_BITS;
    private int size = 0;
    private long drainedCount = 0;
    private long drainedBeforeGcCount = 0;

    /* Drains the queue before the owning diagram collects its table: a wrapper the JVM collected and queued would
     * otherwise keep its function referenced until the next miss, and the collection would count its nodes live.
     * The owner registers it with its diagram; held here, since the diagram holds observers weakly. */
    final NodeTableObserver drainBeforeGc = new NodeTableObserver() {
        @Override
        public void beforeGc(DecisionDiagram origin) {
            drainedBeforeGcCount += drain();
        }
    };

    public GcReferenceManager(DD dd) {
        this.dd = dd;
    }

    int protectedObjectCount() {
        return size;
    }

    /** The live wrappers and the collected ones drained, in total and right before a table collection. */
    @Override
    public void report(StatisticsReport report, StatisticsDetail detail) {
        report.put(WRAPPER_COUNT, size);
        report.put(WRAPPER_DRAINED_COUNT, drainedCount);
        report.put(WRAPPER_DRAINED_BEFORE_GC_COUNT, drainedBeforeGcCount);
    }

    protected V protect(V container) {
        long key = container.canonicalKey();
        assert key != EMPTY;

        int slot = find(key);
        if (slot >= 0) {
            V canonical = referenceAt(slot).get();
            if (canonical != null) {
                assert container.function() == canonical.function();
                return canonical;
            }
        }

        // Removing an entry shifts others back and may shrink the table; with nothing removed, slot still holds.
        if (drain() > 0) {
            slot = find(key);
        }
        DdReference<V> reference = new DdReference<>(container, key, queue);
        if (slot >= 0) {
            // Collected but not queued yet: the new wrapper inherits the reference its predecessor holds.
            referenceAt(slot).inherited = true;
            references[slot] = reference;
        } else {
            dd.reference(container.function());
            insert(key, reference);
        }
        return container;
    }

    private int drain() {
        int count = 0;
        for (Reference<? extends V> polled = queue.poll(); polled != null; polled = queue.poll()) {
            DdReference<?> dead = (DdReference<?>) polled;
            if (!dead.inherited) {
                remove(dead);
                dd.dereference(dead.function);
                count += 1;
            }
        }
        drainedCount += count;
        if (count > 0) {
            logger.log(Level.FINEST, "Cleared {0} references", count);
            if (keys.length > 1 << INITIAL_CAPACITY_BITS && 8 * size < keys.length) {
                int capacity = keys.length;
                while (capacity > 1 << INITIAL_CAPACITY_BITS && 4 * size < capacity) {
                    capacity /= 2;
                }
                resize(capacity);
            }
        }
        return count;
    }

    // Table

    private int slot(long key) {
        return (int) ((key * 0x9E3779B97F4A7C15L) >>> shift);
    }

    private int find(long key) {
        int mask = keys.length - 1;
        int slot = slot(key);
        while (true) {
            long present = keys[slot];
            if (present == key) {
                return slot;
            }
            if (present == EMPTY) {
                return -1;
            }
            slot = (slot + 1) & mask;
        }
    }

    private DdReference<V> referenceAt(int slot) {
        DdReference<V> reference = references[slot];
        assert reference != null;
        return reference;
    }

    private void insert(long key, DdReference<V> reference) {
        if (3 * (size + 1) > 2 * keys.length) {
            resize(2 * keys.length);
        }
        place(key, reference);
        size += 1;
    }

    private void place(long key, DdReference<V> reference) {
        int mask = keys.length - 1;
        int slot = slot(key);
        while (keys[slot] != EMPTY) {
            slot = (slot + 1) & mask;
        }
        keys[slot] = key;
        references[slot] = reference;
    }

    private void resize(int capacity) {
        assert Integer.bitCount(capacity) == 1 && 3 * size <= 2 * capacity;
        long[] oldKeys = keys;
        @Nullable DdReference<V>[] oldReferences = references;
        keys = new long[capacity];
        references = newReferences(capacity);
        shift = Long.SIZE - Integer.numberOfTrailingZeros(capacity);
        for (int slot = 0; slot < oldKeys.length; slot++) {
            DdReference<V> reference = oldReferences[slot];
            if (reference != null) {
                place(oldKeys[slot], reference);
            }
        }
    }

    private void remove(DdReference<?> dead) {
        int hole = find(dead.key);
        assert hole >= 0 && references[hole] == dead; // NOPMD - identity is the point of the check
        int mask = keys.length - 1;
        for (int index = (hole + 1) & mask; keys[index] != EMPTY; index = (index + 1) & mask) {
            // The entry at index may move into the hole only if the hole lies on its probe path, that is if it is
            // at least as far from its home slot as from the hole.
            if (((index - slot(keys[index])) & mask) >= ((index - hole) & mask)) {
                keys[hole] = keys[index];
                references[hole] = references[index];
                hole = index;
            }
        }
        keys[hole] = EMPTY;
        //noinspection AssignmentToNull
        references[hole] = null;
        size -= 1;
    }

    @SuppressWarnings("unchecked")
    private static <V extends DdContainer> @Nullable DdReference<V>[] newReferences(int capacity) {
        return (DdReference<V>[]) new DdReference<?>[capacity];
    }

    private static final class DdReference<V extends DdContainer> extends WeakReference<V> {
        private final long key;
        private final int function;
        // Set once a successor took over this reference's count; draining it then does nothing.
        private boolean inherited = false;

        private DdReference(V container, long key, ReferenceQueue<? super V> queue) {
            super(container, queue);
            this.key = key;
            this.function = container.function();
        }
    }

    @SuppressWarnings({"InterfaceMayBeAnnotatedFunctional", "PMD.ImplicitFunctionalInterface"})
    public interface DdContainer {
        int function();

        /**
         * What a container is canonical per: the function by default. Where one function means different things
         * (a map's under different value numberings), the high half names the space it lives in. Never {@code 0}.
         */
        default long canonicalKey() {
            return Integer.toUnsignedLong(function());
        }
    }
}
