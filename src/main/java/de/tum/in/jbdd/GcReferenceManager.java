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
 *
 * <p>The wrappers sit in an open-addressing table over primitive {@code long} keys (linear probing, backward-shift
 * deletion), so neither a lookup nor an entry allocates beyond the weak reference itself. The table is also what
 * keeps those references reachable: a reference that is itself unreachable is never enqueued, and its function
 * would stay referenced for good. One table per manager, therefore, never one per key space.</p>
 */
public class GcReferenceManager<V extends GcReferenceManager.DdContainer, DD extends DecisionDiagram> {
    private static final Logger logger = Logger.getLogger(GcReferenceManager.class.getName());

    // TODO Read this in depth

    // Function 0 is PLACEHOLDER, never a function, so no key space ever produces key 0.
    private static final long EMPTY = 0L;
    private static final int INITIAL_CAPACITY_BITS = 6;

    protected final DD dd;
    private final ReferenceQueue<V> queue = new ReferenceQueue<>();

    private long[] keys = new long[1 << INITIAL_CAPACITY_BITS];
    private @Nullable DdReference<V>[] references = newReferences(1 << INITIAL_CAPACITY_BITS);
    private int shift = Long.SIZE - INITIAL_CAPACITY_BITS;
    private int size;

    public GcReferenceManager(DD dd) {
        this.dd = dd;
    }

    int protectedObjectCount() {
        return size;
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

        drainCollected();
        slot = find(key);
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

    private void drainCollected() {
        int count = 0;
        for (Reference<? extends V> polled = queue.poll(); polled != null; polled = queue.poll()) {
            DdReference<?> dead = (DdReference<?>) polled;
            if (!dead.inherited) {
                remove(dead);
                dd.dereference(dead.function);
                count += 1;
            }
        }
        if (count > 0) {
            logger.log(Level.FINEST, "Cleared {0} references", count);
        }
    }

    // Table

    private int home(long key) {
        return (int) ((key * 0x9E3779B97F4A7C15L) >>> shift);
    }

    private int find(long key) {
        int mask = keys.length - 1;
        for (int slot = home(key); ; slot = (slot + 1) & mask) {
            long present = keys[slot];
            if (present == key) {
                return slot;
            }
            if (present == EMPTY) {
                return -1;
            }
        }
    }

    private DdReference<V> referenceAt(int slot) {
        DdReference<V> reference = references[slot];
        assert reference != null;
        return reference;
    }

    private void insert(long key, DdReference<V> reference) {
        if (3 * (size + 1) > 2 * keys.length) {
            grow();
        }
        place(key, reference);
        size += 1;
    }

    private void place(long key, DdReference<V> reference) {
        int mask = keys.length - 1;
        int slot = home(key);
        while (keys[slot] != EMPTY) {
            slot = (slot + 1) & mask;
        }
        keys[slot] = key;
        references[slot] = reference;
    }

    private void grow() {
        long[] oldKeys = keys;
        @Nullable DdReference<V>[] oldReferences = references;
        keys = new long[2 * oldKeys.length];
        references = newReferences(keys.length);
        shift -= 1;
        for (int slot = 0; slot < oldKeys.length; slot++) {
            DdReference<V> reference = oldReferences[slot];
            if (reference != null) {
                place(oldKeys[slot], reference);
            }
        }
    }

    private void remove(DdReference<?> dead) {
        int hole = find(dead.key);
        assert hole >= 0 && references[hole] == dead;
        int mask = keys.length - 1;
        for (int next = (hole + 1) & mask; keys[next] != EMPTY; next = (next + 1) & mask) {
            // The entry at next may move into the hole only if the hole lies on its probe path.
            if (((next - home(keys[next])) & mask) >= ((next - hole) & mask)) {
                keys[hole] = keys[next];
                references[hole] = references[next];
                hole = next;
            }
        }
        keys[hole] = EMPTY;
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
        private boolean inherited;

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
