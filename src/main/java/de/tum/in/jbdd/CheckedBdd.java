/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2018-2023 Tobias Meggendorfer.
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

import java.util.concurrent.atomic.AtomicBoolean;

public final class CheckedBdd extends DelegatingBdd {
    private final AtomicBoolean access;

    public CheckedBdd(Bdd delegate) {
        super(delegate);
        access = new AtomicBoolean(false);
    }

    @Override
    protected void onEnter(String name) {
        if (!access.compareAndSet(false, true)) {
            throw new IllegalStateException("Concurrent access to " + name);
        }
    }

    @Override
    protected void onExit() {
        if (!access.getAndSet(false)) {
            throw new IllegalStateException("Concurrently accessed");
        }
    }
}
