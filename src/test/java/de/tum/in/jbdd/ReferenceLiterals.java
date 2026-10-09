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

import de.tum.in.jbdd.collections.MutableNatSet;
import de.tum.in.jbdd.collections.NatSet;
import java.util.Optional;
import java.util.PrimitiveIterator;

/** Implied and implying literals and unateness by definition, for the test diagrams that implement the interface over another. */
final class ReferenceLiterals {
    private ReferenceLiterals() {}

    /** f implies v iff f with v false is false, !v iff f with v true is; nothing outside the support but for false. */
    static Optional<Cube> implied(BinaryDd dd, int function) {
        return function == dd.falseFunction() ? Optional.empty() : Optional.of(literals(dd, function, true));
    }

    /** v implies f iff f with v true is true, !v iff f with v false is; nothing outside the support but for true. */
    static Optional<Cube> implying(BinaryDd dd, int function) {
        return function == dd.trueFunction() ? Optional.empty() : Optional.of(literals(dd, function, false));
    }

    /** Per support variable, whether the low cofactor implies the high one (positive) or the converse (negative). */
    static BinaryDecisionDiagram.Unateness unateness(BinaryDd dd, int function) {
        MutableNatSet positive = MutableNatSet.create();
        MutableNatSet negative = MutableNatSet.create();
        PrimitiveIterator.OfInt iterator = dd.support(function).iterator();
        while (iterator.hasNext()) {
            int variable = iterator.nextInt();
            int high = dd.reference(dd.restrict(function, Cube.literal(variable, true)));
            int low = dd.reference(dd.restrict(function, Cube.literal(variable, false)));
            if (dd.implies(low, high)) {
                positive.set(variable);
            }
            if (dd.implies(high, low)) {
                negative.set(variable);
            }
            dd.dereference(low);
            dd.dereference(high);
        }
        return new BinaryDecisionDiagram.Unateness(positive, negative);
    }

    private static Cube literals(BinaryDd dd, int function, boolean implied) {
        int decisive = implied ? dd.falseFunction() : dd.trueFunction();
        MutableNatSet positive = MutableNatSet.create();
        MutableNatSet support = MutableNatSet.create();
        NatSet variables = dd.support(function);
        PrimitiveIterator.OfInt iterator = variables.iterator();
        while (iterator.hasNext()) {
            int variable = iterator.nextInt();
            int high = dd.reference(dd.restrict(function, Cube.literal(variable, true)));
            int low = dd.reference(dd.restrict(function, Cube.literal(variable, false)));
            // implied: the positive literal where the low cofactor is false; implying: where the high one is true
            if ((implied ? low : high) == decisive) {
                positive.set(variable);
                support.set(variable);
            } else if ((implied ? high : low) == decisive) {
                support.set(variable);
            }
            dd.dereference(low);
            dd.dereference(high);
        }
        return Cube.of(positive, support);
    }
}
