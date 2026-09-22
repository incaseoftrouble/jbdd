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

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * A conjunction of literals: {@link #support()} names the variables it fixes, {@link #assignment()} the ones
 * fixed to true among them. Equivalently a partial assignment, standing for all its completions - a minterm
 * being the cube over every variable, the empty cube the constant true.
 *
 * <p>Operations return new cubes and never modify their operands. A cube handed out by a path walk or cursor
 * is that walk's working state, though, and changes under the caller - see {@link Cursor}; {@link #copy()}
 * it to keep it.
 */
public final class Cube {
    private static final Cube EMPTY = new Cube(new BitSet(0), new BitSet(0));

    final BitSet assignment;
    final BitSet support;

    // Takes ownership of both sets; the walks mutate them in place.
    Cube(BitSet assignment, BitSet support) {
        assert assignment.stream().allMatch(support::get);
        this.assignment = assignment;
        this.support = support;
    }

    /** The cube fixing the variables of {@code support} as {@code valuation} assigns them. */
    public static Cube of(BitSet valuation, BitSet support) {
        BitSet assignment = BitSets.copyOf(valuation);
        assignment.and(support);
        return new Cube(assignment, BitSets.copyOf(support));
    }

    /** The cube fixing nothing - the constant true. */
    public static Cube empty() {
        return EMPTY;
    }

    /** The single literal {@code variable} (if {@code value}) or its negation. */
    public static Cube literal(int variable, boolean value) {
        BitSet support = BitSets.of(variable);
        return new Cube(value ? BitSets.copyOf(support) : new BitSet(0), support);
    }

    /** The conjunction of all {@code variables}. */
    public static Cube positive(BitSet variables) {
        BitSet copy = BitSets.copyOf(variables);
        // One set for both is safe: nothing modifies a cube's sets but the walk owning it.
        return new Cube(copy, copy);
    }

    /** The conjunction of the negations of all {@code variables}. */
    public static Cube negative(BitSet variables) {
        return new Cube(new BitSet(0), BitSets.copyOf(variables));
    }

    /** The variables fixed to true. Handed out as-is, so callers must not modify it. */
    public BitSet assignment() {
        return assignment;
    }

    /** The variables fixed. Handed out as-is, so callers must not modify it. */
    public BitSet support() {
        return support;
    }

    public BitSet copyAssignment() {
        return BitSets.copyOf(assignment);
    }

    public BitSet copySupport() {
        return BitSets.copyOf(support);
    }

    /** A cube equal to this one that owns its sets - see the class comment. */
    public Cube copy() {
        return new Cube(BitSets.copyOf(assignment), BitSets.copyOf(support));
    }

    /** The number of literals. */
    public int size() {
        return support.cardinality();
    }

    /** Whether this cube fixes nothing, i.e. is the constant true. */
    public boolean isEmpty() {
        return support.isEmpty();
    }

    public boolean fixes(int variable) {
        return support.get(variable);
    }

    /** The value {@code variable} is fixed to, which it must be. */
    public boolean value(int variable) {
        if (!support.get(variable)) {
            throw new IllegalArgumentException("Variable " + variable + " is not fixed by " + this);
        }
        return assignment.get(variable);
    }

    /** The variables fixed to true, as a set the caller owns. */
    public BitSet positives() {
        return copyAssignment();
    }

    /** The variables fixed to false, as a set the caller owns. */
    public BitSet negatives() {
        BitSet negatives = BitSets.copyOf(support);
        negatives.andNot(assignment);
        return negatives;
    }

    /** Whether {@code valuation} (a full assignment) satisfies this cube. */
    public boolean contains(BitSet valuation) {
        for (int variable = support.nextSetBit(0); variable >= 0; variable = support.nextSetBit(variable + 1)) {
            if (valuation.get(variable) != assignment.get(variable)) {
                return false;
            }
        }
        return true;
    }

    /** Whether every valuation of this cube is one of {@code other}'s: every literal of {@code other} is one of these. */
    public boolean implies(Cube other) {
        BitSet otherSupport = other.support;
        for (int variable = otherSupport.nextSetBit(0);
                variable >= 0;
                variable = otherSupport.nextSetBit(variable + 1)) {
            if (!support.get(variable) || assignment.get(variable) != other.assignment.get(variable)) {
                return false;
            }
        }
        return true;
    }

    /** Whether some valuation satisfies both cubes: they agree wherever both fix a variable. */
    public boolean intersects(Cube other) {
        // Iterate the smaller support.
        Cube smaller = support.cardinality() <= other.support.cardinality() ? this : other;
        Cube larger = smaller == this ? other : this; // NOPMD - identity is the point
        for (int variable = smaller.support.nextSetBit(0);
                variable >= 0;
                variable = smaller.support.nextSetBit(variable + 1)) {
            if (larger.support.get(variable) && smaller.assignment.get(variable) != larger.assignment.get(variable)) {
                return false;
            }
        }
        return true;
    }

    /** The conjunction of both cubes, or empty if they contradict each other. */
    public Optional<Cube> intersection(Cube other) {
        if (!intersects(other)) {
            return Optional.empty();
        }
        BitSet assignment = BitSets.copyOf(this.assignment);
        assignment.or(other.assignment);
        BitSet support = BitSets.copyOf(this.support);
        support.or(other.support);
        return Optional.of(new Cube(assignment, support));
    }

    /** This cube with {@code variable} fixed to {@code value}, replacing whatever it was fixed to. */
    public Cube with(int variable, boolean value) {
        BitSet assignment = BitSets.copyOf(this.assignment);
        BitSet support = BitSets.copyOf(this.support);
        assignment.set(variable, value);
        support.set(variable);
        return new Cube(assignment, support);
    }

    /** This cube without the literal on {@code variable}. */
    public Cube without(int variable) {
        if (!support.get(variable)) {
            return this;
        }
        BitSet assignment = BitSets.copyOf(this.assignment);
        BitSet support = BitSets.copyOf(this.support);
        assignment.clear(variable);
        support.clear(variable);
        return new Cube(assignment, support);
    }

    /** This cube's literals on {@code variables} only - existential quantification of all others. */
    public Cube restrictedTo(BitSet variables) {
        BitSet assignment = BitSets.copyOf(this.assignment);
        BitSet support = BitSets.copyOf(this.support);
        assignment.and(variables);
        support.and(variables);
        return new Cube(assignment, support);
    }

    /**
     * The cubes of {@code cubes} that imply no other one of them, duplicates removed: the same set of
     * valuations, described without redundant, longer cubes. Quadratic in the number of cubes.
     */
    public static List<Cube> antichain(Collection<Cube> cubes) {
        List<Cube> distinct = new ArrayList<>(new LinkedHashSet<>(cubes));
        List<Cube> antichain = new ArrayList<>(distinct.size());
        for (Cube candidate : distinct) {
            boolean subsumed = false;
            for (Cube other : distinct) {
                // Mutual implication means equality, which the deduplication above already removed.
                if (other != candidate && candidate.implies(other)) { // NOPMD - identity is the point
                    subsumed = true;
                    break;
                }
            }
            if (!subsumed) {
                antichain.add(candidate);
            }
        }
        return List.copyOf(antichain);
    }

    /** Calls {@code action} once per literal, in ascending variable order. */
    public void forEachLiteral(LiteralConsumer action) {
        BitSets.forEach(support, variable -> action.accept(variable, assignment.get(variable)));
    }

    @FunctionalInterface
    public interface LiteralConsumer {
        void accept(int variable, boolean value);
    }

    /** The literals over {@code [0, support().length())}: {@code 1}, {@code 0}, or {@code ?} for free. */
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
        return (obj instanceof Cube)
                && this.assignment.equals(((Cube) obj).assignment)
                && this.support.equals(((Cube) obj).support);
    }

    @Override
    public int hashCode() {
        return 31 * assignment.hashCode() + support.hashCode();
    }
}
