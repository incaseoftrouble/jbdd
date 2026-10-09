/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2017-2023 Tobias Meggendorfer.
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

import de.tum.in.jbdd.collections.Cursor;
import de.tum.in.jbdd.collections.MutableNatSet;

/**
 * A walk's cube over the sets the walk mutates in place: what it hands out is its working state (see
 * {@link Cursor}).
 */
final class WalkCube {
    final MutableNatSet assignment;
    final MutableNatSet support;
    final Cube cube;

    WalkCube(int capacity) {
        this.assignment = MutableNatSet.dense(capacity);
        this.support = MutableNatSet.dense(capacity);
        this.cube = Cube.ofUnsafe(assignment, support);
    }
}
