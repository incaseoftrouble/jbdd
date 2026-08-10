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

abstract class ProtectedOperation implements RegisteredOperation {
    private final ProtectionTracker.Protection protection;

    ProtectedOperation(ProtectionTracker tracker, Runnable onRelease) {
        this.protection = tracker.track(this, onRelease);
    }

    final boolean isReleased() {
        return protection.isReleased();
    }

    @Override
    public final void release() {
        protection.releaseNow();
    }

    final void checkNotReleased() {
        assert !isReleased() : "This operation has been released and can no longer be used";
    }
}
