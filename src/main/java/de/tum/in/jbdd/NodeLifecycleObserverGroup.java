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

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class NodeLifecycleObserverGroup<O extends NodeLifecycleObserver> {
    private final List<WeakReference<O>> observers = new ArrayList<>();
    private final List<O> ownedObservers = new ArrayList<>();

    void register(O observer) {
        observers.add(new WeakReference<>(observer));
    }

    void registerStrongly(O observer) {
        ownedObservers.add(observer);
    }

    void dispatch(Consumer<O> action) {
        // Indexed rather than for-each: an action may register a further observer, which a for-each would
        // turn into a ConcurrentModificationException.
        //noinspection ForLoopReplaceableByForEach
        for (int i = 0; i < ownedObservers.size(); i++) { // NOPMD - see above
            action.accept(ownedObservers.get(i));
        }
        if (observers.isEmpty()) {
            return;
        }
        observers.removeIf(ref -> {
            O observer = ref.get();
            if (observer == null) {
                return true;
            }
            action.accept(observer);
            return false;
        });
    }
}
