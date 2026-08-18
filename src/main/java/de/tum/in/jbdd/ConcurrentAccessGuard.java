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

import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

final class ConcurrentAccessGuard {
    private static final Logger logger = Logger.getLogger(ConcurrentAccessGuard.class.getName());

    private final AtomicReference<@Nullable Thread> owner = new AtomicReference<>();
    private int depth = 0;

    @SuppressWarnings("ObjectEquality")
    boolean acquire() {
        Thread current = Thread.currentThread();
        Thread previous = owner.compareAndExchange(null, current);
        if (previous != null && previous != current) {
            Throwable otherStack = new Throwable("Currently accessed by " + previous);
            otherStack.setStackTrace(previous.getStackTrace());
            logger.log(Level.SEVERE, "Concurrent access by " + current, otherStack);
            return false;
        }
        depth += 1;
        return true;
    }

    boolean release() {
        depth -= 1;
        if (depth == 0) {
            owner.set(null);
        }
        return true;
    }
}
