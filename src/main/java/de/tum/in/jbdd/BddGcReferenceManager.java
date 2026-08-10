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

public class BddGcReferenceManager<V extends BddGcReferenceManager.BddContainer> {
    private static final Logger logger = Logger.getLogger(BddGcReferenceManager.class.getName());

    protected final Bdd bdd;

    private final Map<Integer, BddReference<V>> gcObjects = new HashMap<>();
    private final Map<Integer, V> nonGcObjects = new HashMap<>();
    private final ReferenceQueue<V> queue = new ReferenceQueue<>();

    public BddGcReferenceManager(Bdd bdd) {
        this.bdd = bdd;
    }

    @Nullable
    protected V get(int function) {
        V wrapper = nonGcObjects.get(function);

        if (wrapper != null) {
            return wrapper;
        }

        BddReference<V> reference = gcObjects.get(function);
        return reference == null ? null : reference.get();
    }

    // This is not thread safe!
    protected V protect(V container) {
        int function = container.function();

        // Constants and variables are exempt from GC but still canonical
        if (bdd.isConstant(function) || bdd.isVariableOrNegated(function)) {
            return nonGcObjects.merge(function, container, (oldW, newW) -> oldW);
        }

        BddReference<V> canonicalReference = gcObjects.get(function);
        if (canonicalReference == null) {
            // The BDD was created and needs a reference to be protected.
            bdd.reference(function);
        } else {
            // The BDD already existed -- Can have a reference for the BDD and its negation
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

        // Remove queued BDDs from the mapping.
        processReferenceQueue(function);

        // Insert BDD into mapping.
        gcObjects.put(function, new BddReference<>(container, queue));
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
            int node = ((BddReference<?>) reference).node;
            gcObjects.remove(node);

            if (node != protectedNode) {
                // assert bdd.nodeReferenceCount(node) == 1;
                bdd.dereference(node);
                // assert bdd.nodeReferenceCount(node) == 0;
                count += 1;
            }

            reference = queue.poll();
        } while (reference != null);

        logger.log(Level.FINEST, "Cleared {0} references", count);
    }

    // TODO Candidate for record in Java 17
    private static final class BddReference<V extends BddContainer> extends WeakReference<V> {
        private final int node;

        private BddReference(V node, ReferenceQueue<? super V> queue) {
            super(node, queue);
            this.node = node.function();
        }
    }

    @SuppressWarnings({"InterfaceMayBeAnnotatedFunctional", "PMD.ImplicitFunctionalInterface"})
    public interface BddContainer {
        int function();
    }
}
