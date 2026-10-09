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

import de.tum.in.jbdd.collections.NatSet;
import java.math.BigDecimal;
import java.util.PrimitiveIterator;

/** The influences of a function by definition, for the test diagrams that implement the interface over another. */
final class ReferenceInfluences {
    private ReferenceInfluences() {}

    /** Per variable of the support, the satisfying fraction of its two cofactors' XOR, exactly up to the conversion. */
    static double[] of(BinaryDd dd, int function) {
        double[] influences = new double[dd.numberOfVariables()];
        NatSet support = dd.support(function);
        PrimitiveIterator.OfInt variables = support.iterator();
        while (variables.hasNext()) {
            int variable = variables.nextInt();
            int high = dd.reference(dd.restrict(function, Cube.literal(variable, true)));
            int low = dd.reference(dd.restrict(function, Cube.literal(variable, false)));
            int difference = dd.reference(dd.xor(high, low));
            influences[variable] = new BigDecimal(dd.countSatisfyingAssignments(difference))
                    .multiply(BigDecimal.valueOf(5, 1).pow(dd.numberOfVariables()))
                    .doubleValue();
            dd.dereference(difference);
            dd.dereference(low);
            dd.dereference(high);
        }
        return influences;
    }
}
