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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import org.junit.jupiter.api.Test;

class DimacsReaderTest {
    private static int load(Bdd bdd, String dimacs) throws IOException, DimacsReader.InvalidFormatException {
        try (BufferedReader reader = new BufferedReader(new StringReader(dimacs))) {
            return DimacsReader.loadDimacs(bdd, reader);
        }
    }

    @Test
    void testUnitClauses() throws Exception {
        // (x1) AND (x2)
        Bdd bdd = BddFactory.buildBdd();
        int fromDimacs = load(bdd, "p cnf 2 2\n1 0\n2 0\n");

        int manual = bdd.and(bdd.variableFunction(0), bdd.variableFunction(1));

        assertEquals(manual, fromDimacs);
    }

    @Test
    void testMultiLiteralClauses() throws Exception {
        // (x1 OR x2) AND (NOT x1 OR x3) AND (NOT x2 OR NOT x3)
        Bdd bdd = BddFactory.buildBdd();
        String dimacs = "c a comment line, should be ignored\np cnf 3 3\n1 2 0\n-1 3 0\n-2 -3 0\n";
        int fromDimacs = load(bdd, dimacs);

        int x1 = bdd.variableFunction(0);
        int x2 = bdd.variableFunction(1);
        int x3 = bdd.variableFunction(2);
        int clause1 = bdd.or(x1, x2);
        int clause2 = bdd.or(bdd.not(x1), x3);
        int clause3 = bdd.or(bdd.not(x2), bdd.not(x3));
        int manual = bdd.and(bdd.and(clause1, clause2), clause3);

        assertEquals(manual, fromDimacs);
    }

    @Test
    void testUnsatisfiableFormulaIsFalse() throws Exception {
        // (x1) AND (NOT x1) - contradictory unit clauses, no satisfying assignment.
        Bdd bdd = BddFactory.buildBdd();
        int fromDimacs = load(bdd, "p cnf 1 2\n1 0\n-1 0\n");

        assertEquals(bdd.falseFunction(), fromDimacs);
    }
}
