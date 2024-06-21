/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2024 Tobias Meggendorfer.
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

import java.util.BitSet;

public final class BddPath {
    final BitSet assignment;
    final BitSet support;

    public BddPath(BitSet assignment, BitSet support) {
        this.assignment = assignment;
        this.support = support;
    }

    public BitSet copyAssignment() {
        return BitSets.copyOf(assignment);
    }

    public BitSet copySupport() {
        return BitSets.copyOf(support);
    }

    public BitSet viewAssignment() {
        return assignment;
    }

    public BitSet viewSupport() {
        return support;
    }
}
