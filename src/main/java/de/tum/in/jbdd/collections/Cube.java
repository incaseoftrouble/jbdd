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
package de.tum.in.jbdd.collections;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * A conjunction of literals: {@link #support()} names the variables it fixes, {@link #assignment()} the ones
 * fixed to true among them. Equivalently a partial assignment, standing for all its completions - a minterm
 * being the cube over every variable, the empty cube the constant true.
 *
 * <p>Operations return new cubes and never modify their operands. The accessors hand out the cube's own sets, as
 * {@link NatSet}s: read, never modified. {@link #of} copies what it is given; {@link #ofUnsafe} takes the sets as
 * they are, the caller giving them up.
 *
 * <p>A cube handed out by a path walk or cursor is that walk's working state and changes under the caller - see
 * {@link Cursor}; {@link #copy()} it to keep it.
 */
public final class Cube {
    private static final Cube EMPTY = new Cube(NatSet.of(), NatSet.of());

    private final NatSet assignment;
    private final NatSet support;

    // Takes the sets as they are; the walks mutate them in place.
    private Cube(NatSet assignment, NatSet support) {
        assert support.containsAll(assignment);
        this.assignment = assignment;
        this.support = support;
    }

    /** The cube fixing the variables of {@code support} as {@code valuation} assigns them. Copies both. */
    public static Cube of(NatSet valuation, NatSet support) {
        return new Cube(valuation.intersection(support), NatSet.copyOf(support));
    }

    /**
     * The cube over {@code support} with {@code assignment} (a subset of it, checked by assertion only) true, taking
     * both sets as they are: the caller gives them up, or - a walk's working state - keeps modifying them.
     */
    public static Cube ofUnsafe(NatSet assignment, NatSet support) {
        return new Cube(assignment, support);
    }

    /** The cube fixing nothing - the constant true. */
    public static Cube empty() {
        return EMPTY;
    }

    /** The single literal {@code variable} (if {@code value}) or its negation. */
    public static Cube literal(int variable, boolean value) {
        NatSet support = NatSet.of(variable);
        return new Cube(value ? support : NatSet.of(), support);
    }

    /** The conjunction of all {@code variables}. */
    public static Cube positive(NatSet variables) {
        NatSet copy = NatSet.copyOf(variables);
        return new Cube(copy, copy);
    }

    /** The conjunction of the negations of all {@code variables}. */
    public static Cube negative(NatSet variables) {
        return new Cube(NatSet.of(), NatSet.copyOf(variables));
    }

    /** The variables fixed to true. */
    public NatSet assignment() {
        return assignment;
    }

    /** The variables fixed. */
    public NatSet support() {
        return support;
    }

    /** A cube equal to this one whose sets never change - see the class comment. */
    public Cube copy() {
        NatSet assignmentCopy = NatSet.copyOf(assignment);
        NatSet supportCopy = NatSet.copyOf(support);
        // Nothing to copy when both sets never change already.
        boolean unchanged = assignmentCopy == assignment && supportCopy == support; // NOPMD - identity is the point
        return unchanged ? this : new Cube(assignmentCopy, supportCopy);
    }

    /** The number of literals. */
    public int size() {
        return support.size();
    }

    /** Whether this cube fixes nothing, i.e. is the constant true. */
    public boolean isEmpty() {
        return support.isEmpty();
    }

    public boolean fixes(int variable) {
        return support.contains(variable);
    }

    /** The value {@code variable} is fixed to, which it must be. */
    public boolean value(int variable) {
        if (!support.contains(variable)) {
            throw new IllegalArgumentException("Variable " + variable + " is not fixed by " + this);
        }
        return assignment.contains(variable);
    }

    /** The variables fixed to false. */
    public NatSet negatives() {
        return support.difference(assignment);
    }

    /** Whether {@code valuation} (a full assignment) satisfies this cube. */
    public boolean contains(NatSet valuation) {
        long[] supportWords = NatSetUtil.wordsOrNone(support);
        long[] assignmentWords = NatSetUtil.wordsOrNone(assignment);
        long[] valuationWords = NatSetUtil.wordsOrNone(valuation);
        if (supportWords != null && assignmentWords != null && valuationWords != null) {
            for (int index = 0; index < supportWords.length; index++) {
                long fixed = supportWords[index];
                if (fixed != 0
                        && ((NatSetUtil.word(valuationWords, index) ^ NatSetUtil.word(assignmentWords, index)) & fixed)
                                != 0) {
                    return false;
                }
            }
            return true;
        }
        return support.allMatch(variable -> valuation.contains(variable) == assignment.contains(variable));
    }

    /** Whether every valuation of this cube is one of {@code other}'s: every literal of {@code other} is one of these. */
    public boolean implies(Cube other) {
        long[] supportWords = NatSetUtil.wordsOrNone(support);
        long[] assignmentWords = NatSetUtil.wordsOrNone(assignment);
        long[] otherSupportWords = NatSetUtil.wordsOrNone(other.support);
        long[] otherAssignmentWords = NatSetUtil.wordsOrNone(other.assignment);
        if (supportWords != null
                && assignmentWords != null
                && otherSupportWords != null
                && otherAssignmentWords != null) {
            for (int index = 0; index < otherSupportWords.length; index++) {
                long fixed = otherSupportWords[index];
                if (fixed != 0
                        && ((fixed & ~NatSetUtil.word(supportWords, index)) != 0
                                || ((NatSetUtil.word(assignmentWords, index)
                                                        ^ NatSetUtil.word(otherAssignmentWords, index))
                                                & fixed)
                                        != 0)) {
                    return false;
                }
            }
            return true;
        }
        return other.support.allMatch(variable ->
                support.contains(variable) && assignment.contains(variable) == other.assignment.contains(variable));
    }

    /** Whether some valuation satisfies both cubes: they agree wherever both fix a variable. */
    public boolean intersects(Cube other) {
        long[] supportWords = NatSetUtil.wordsOrNone(support);
        long[] assignmentWords = NatSetUtil.wordsOrNone(assignment);
        long[] otherSupportWords = NatSetUtil.wordsOrNone(other.support);
        long[] otherAssignmentWords = NatSetUtil.wordsOrNone(other.assignment);
        if (supportWords != null
                && assignmentWords != null
                && otherSupportWords != null
                && otherAssignmentWords != null) {
            int common = Math.min(supportWords.length, otherSupportWords.length);
            for (int index = 0; index < common; index++) {
                long bothFix = supportWords[index] & otherSupportWords[index];
                if (bothFix != 0
                        && ((NatSetUtil.word(assignmentWords, index) ^ NatSetUtil.word(otherAssignmentWords, index))
                                        & bothFix)
                                != 0) {
                    return false;
                }
            }
            return true;
        }
        // Iterate the smaller support.
        Cube smaller = support.size() <= other.support.size() ? this : other;
        Cube larger = smaller == this ? other : this; // NOPMD - identity is the point
        return smaller.support.noneMatch(variable -> larger.support.contains(variable)
                && smaller.assignment.contains(variable) != larger.assignment.contains(variable));
    }

    /** The conjunction of both cubes, or empty if they contradict each other. */
    public Optional<Cube> intersection(Cube other) {
        if (!intersects(other)) {
            return Optional.empty();
        }
        return Optional.of(new Cube(assignment.union(other.assignment), support.union(other.support)));
    }

    /** This cube with {@code variable} fixed to {@code value}, replacing whatever it was fixed to. */
    public Cube with(int variable, boolean value) {
        NatSet literal = NatSet.of(variable);
        return new Cube(value ? assignment.union(literal) : assignment.difference(literal), support.union(literal));
    }

    /** This cube without the literal on {@code variable}. */
    public Cube without(int variable) {
        if (!support.contains(variable)) {
            return this;
        }
        NatSet literal = NatSet.of(variable);
        return new Cube(assignment.difference(literal), support.difference(literal));
    }

    /** This cube's literals on {@code variables} only - existential quantification of all others. */
    public Cube restrictedTo(NatSet variables) {
        return new Cube(assignment.intersection(variables), support.intersection(variables));
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
        support.forEach((int variable) -> action.accept(variable, assignment.contains(variable)));
    }

    @FunctionalInterface
    public interface LiteralConsumer {
        void accept(int variable, boolean value);
    }

    /** The literals over {@code [0, support().length())}: {@code 1}, {@code 0}, or {@code ?} for free. */
    @Override
    public String toString() {
        int length = support.length();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            if (support.contains(i)) {
                sb.append(assignment.contains(i) ? '1' : '0');
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
