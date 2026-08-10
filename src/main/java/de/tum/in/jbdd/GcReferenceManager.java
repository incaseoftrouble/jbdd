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
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

public class GcReferenceManager<V extends GcReferenceManager.DdContainer, DD extends DecisionDiagram> {
    private static final Logger logger = Logger.getLogger(GcReferenceManager.class.getName());

    protected final DD dd;

    private final Map<Integer, DdReference<V>> gcObjects = new HashMap<>();
    private final Map<Integer, V> nonGcObjects = new HashMap<>();
    private final ReferenceQueue<V> queue = new ReferenceQueue<>();

    public GcReferenceManager(DD dd) {
        this.dd = dd;
    }

    @Nullable
    protected V get(int function) {
        V wrapper = nonGcObjects.get(function);

        if (wrapper != null) {
            return wrapper;
        }

        DdReference<V> reference = gcObjects.get(function);
        return reference == null ? null : reference.get();
    }

    // This is not thread safe!
    protected V protect(V container) {
        int function = container.function();

        // Constants and variables are exempt from GC but still canonical
        if (dd.isUnmanaged(function)) {
            return nonGcObjects.merge(function, container, (oldW, newW) -> oldW);
        }

        DdReference<V> canonicalReference = gcObjects.get(function);
        if (canonicalReference == null) {
            // The object was created and needs a reference to be protected.
            dd.reference(function);
        } else {
            // The object already existed
            V canonicalNode = canonicalReference.get();
            if (canonicalNode == null) {
                // This object was GC'ed since the last run of clear(), but potentially wasn't added to the
                // ReferenceQueue by the GC yet. Make sure that the reference is queued and cleared to
                // avoid inconsistencies.
                canonicalReference.enqueue();
            } else {
                assert function == canonicalNode.function();
                return canonicalNode;
            }
        }

        // Remove queued functions from the mapping.
        processReferenceQueue(function);

        // Insert function into mapping.
        gcObjects.put(function, new DdReference<>(container, queue));
        return container;
    }

    private void processReferenceQueue(int protectedNode) {
        Reference<? extends V> reference = queue.poll();
        if (reference == null) {
            // Queue is empty
            return;
        }

        int count = 0;
        do {
            int node = ((DdReference<?>) reference).node;
            gcObjects.remove(node);

            if (node != protectedNode) {
                // assert bdd.nodeReferenceCount(node) == 1;
                dd.dereference(node);
                // assert bdd.nodeReferenceCount(node) == 0;
                count += 1;
            }

            reference = queue.poll();
        } while (reference != null);

        logger.log(Level.FINEST, "Cleared {0} references", count);
    }

    private static final class DdReference<V extends DdContainer> extends WeakReference<V> {
        private final int node;

        private DdReference(V node, ReferenceQueue<? super V> queue) {
            super(node, queue);
            this.node = node.function();
        }
    }

    @SuppressWarnings({"InterfaceMayBeAnnotatedFunctional", "PMD.ImplicitFunctionalInterface"})
    public interface DdContainer {
        int function();
    }
}
