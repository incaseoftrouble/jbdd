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

public final class BinaryPath {
    final BitSet assignment;
    final BitSet support;

    public BinaryPath(BitSet assignment, BitSet support) {
        assert assignment.stream().allMatch(support::get);
        this.assignment = assignment;
        this.support = support;
    }

    public BitSet copyAssignment() {
        return BitSets.copyOf(assignment);
    }

    public BitSet copySupport() {
        return BitSets.copyOf(support);
    }

    public BitSet assignment() {
        return assignment;
    }

    public BitSet support() {
        return support;
    }

    public BinaryPath copy() {
        return new BinaryPath(BitSets.copyOf(assignment), BitSets.copyOf(support));
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(support.length());
        for (int i = 0; i < support.length(); i++) {
            if (support.get(i)) {
                sb.append(assignment.get(i) ? '1' : '0');
            } else {
                sb.append('?');
            }
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        return (obj instanceof BinaryPath)
                && this.assignment.equals(((BinaryPath) obj).assignment)
                && this.support.equals(((BinaryPath) obj).support);
    }

    @Override
    public int hashCode() {
        return 31 * assignment.hashCode() + support.hashCode();
    }
}
