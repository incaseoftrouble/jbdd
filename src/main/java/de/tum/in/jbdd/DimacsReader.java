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

import java.io.BufferedReader;
import java.io.IOException;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

public final class DimacsReader {
    private static final Pattern WHITESPACE = Pattern.compile("[ \t]+");

    private DimacsReader() {}

    @Nullable
    private static String nextLine(BufferedReader reader) throws IOException {
        while (true) {
            String line = reader.readLine();
            if (line == null) {
                return null;
            }
            if (line.isBlank()) {
                continue;
            }
            if (line.charAt(0) != 'c' && line.charAt(0) != '%') {
                return line;
            }
        }
    }

    public static int loadDimacs(Bdd bdd, BufferedReader reader) throws IOException, InvalidFormatException {
        String header = nextLine(reader);
        if (header == null) {
            throw new InvalidFormatException("Stream is empty");
        }
        String[] array = WHITESPACE.split(header);
        if (!"p".equals(array[0]) || !"cnf".equals(array[1]) || array.length != 4) {
            throw new InvalidFormatException("Invalid header " + header);
        }
        int variables;
        try {
            variables = Integer.parseInt(array[2]);
            // Ignore the actual number of clauses, try to leniently parse files
            // int clauses = Integer.parseInt(array[3]);
        } catch (NumberFormatException e) {
            throw new InvalidFormatException("Invalid header " + header, e);
        }
        if (bdd.numberOfVariables() < variables) {
            bdd.createVariables(variables - bdd.numberOfVariables());
        }
        int expression = bdd.trueNode();
        while (true) {
            String clauseLine = nextLine(reader);
            if (clauseLine == null) {
                break;
            }
            String[] clause = WHITESPACE.split(clauseLine.strip());
            int length = "0".equals(clause[clause.length - 1]) ? clause.length - 1 : clause.length;
            int[] clauseLiterals = new int[length];
            for (int j = 0; j < length; j++) {
                int clauseInt;
                try {
                    clauseInt = Integer.parseInt(clause[j]);
                } catch (NumberFormatException e) {
                    throw new InvalidFormatException("Invalid clause " + clauseLine, e);
                }
                if (clauseInt == 0 || clauseInt < -variables || clauseInt > variables) {
                    throw new InvalidFormatException("Invalid clause " + clauseLine);
                }
                clauseLiterals[j] = clauseInt;
            }
            int clauseNode = bdd.falseNode();
            for (int variable : clauseLiterals) {
                assert variable != 0;
                int node = variable < 0 ? bdd.not(bdd.variableNode(-variable - 1)) : bdd.variableNode(variable - 1);
                clauseNode = bdd.updateWith(bdd.or(node, clauseNode), clauseNode);
            }
            expression = bdd.updateWith(clauseNode, expression);
        }
        return expression;
    }

    public static class InvalidFormatException extends Exception {
        public InvalidFormatException(String message) {
            super(message);
        }

        public InvalidFormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
