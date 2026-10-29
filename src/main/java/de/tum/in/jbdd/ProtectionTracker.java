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

import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.util.IdentityHashMap;

final class ProtectionTracker implements NodeTableObserver {
    private final ReferenceQueue<ProtectedOperation> queue = new ReferenceQueue<>();
    // Strongly anchors every tracked Protection so it can't be collected before it is drained
    // IdentityHashMap is required here (Protection has no equals/hashCode).
    @SuppressWarnings({"MismatchedQueryAndUpdateOfCollection", "PMD.LooseCoupling", "CollectionDeclaredAsConcreteClass"
    })
    private final IdentityHashMap<Protection, Object> pending = new IdentityHashMap<>();

    void drain() {
        Reference<? extends ProtectedOperation> ref = queue.poll();
        while (ref != null) {
            ((Protection) ref).releaseNow();
            ref = queue.poll();
        }
    }

    @Override
    public void beforeGc(DecisionDiagram origin) {
        drain();
    }

    Protection track(ProtectedOperation operation, Runnable onRelease) {
        Protection protection = new Protection(operation, this, onRelease);
        pending.put(protection, protection);
        return protection;
    }

    static final class Protection extends PhantomReference<ProtectedOperation> {
        private final ProtectionTracker tracker;
        private final Runnable onRelease;
        private boolean released = false;

        Protection(ProtectedOperation referent, ProtectionTracker tracker, Runnable onRelease) {
            super(referent, tracker.queue);
            this.tracker = tracker;
            this.onRelease = onRelease;
        }

        boolean isReleased() {
            return released;
        }

        void releaseNow() {
            if (released) {
                return;
            }
            released = true;
            tracker.pending.remove(this);
            onRelease.run();
        }
    }
}
