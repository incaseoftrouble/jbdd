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

import de.tum.in.jbdd.MultiTerminalDecisionDiagram.FunctionToFunctionsMap;
import de.tum.in.jbdd.MultiTerminalDecisionDiagram.Operator;
import de.tum.in.jbdd.MultiTerminalDecisionDiagram.ResidualProduct;
import de.tum.in.jbdd.collections.MutableNatSet;
import de.tum.in.jbdd.collections.NatSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The {@link MultiTerminalDecisionDiagram#residualProduct residual product} as the chain of operations it denotes. */
final class ResidualProducts {
    private ResidualProducts() {}

    /** The cartesian product, each tuple restricted and projected; the public operations alone. */
    static ResidualProduct ofCartesianProduct(
            MultiTerminalDecisionDiagram diagram, Operator operator, int[] operands, PartialValuation valuation) {
        BinaryDecisionDiagram bdd = operator.diagram();
        int function = operator.function();
        assert checkArguments(bdd, function, operands, diagram.placeholder());

        int[] replaced = replacedVariables(operands, diagram.placeholder());
        int[] replacing = new int[replaced.length];
        for (int i = 0; i < replaced.length; i++) {
            replacing[i] = operands[replaced[i]];
        }
        FunctionToFunctionsMap product = diagram.cartesianProduct(replacing);
        int productFunction = diagram.reference(product.function());
        Pairs pairs = new Pairs(operands.length);
        int[] relabeling = new int[product.codomain().size()];
        for (int index = 0; index < relabeling.length; index++) {
            int[] tuple = product.functionFor(index);
            MutableNatSet assignment = MutableNatSet.create();
            MutableNatSet restricted = MutableNatSet.create();
            for (int i = 0; i < replaced.length; i++) {
                PartialValuation.Truth truth = valuation.valueOf(replaced[i], tuple[i]);
                if (truth != PartialValuation.Truth.UNDECIDED) {
                    restricted.set(replaced[i]);
                    assignment.set(replaced[i], truth == PartialValuation.Truth.TRUE);
                }
            }
            int residual = bdd.reference(bdd.restrict(function, Cube.of(assignment, restricted)));
            NatSet support = bdd.support(residual);
            int[] values = new int[operands.length];
            Arrays.fill(values, ResidualProduct.ABSENT);
            for (int i = 0; i < replaced.length; i++) {
                if (support.contains(replaced[i])) {
                    values[replaced[i]] = tuple[i];
                }
            }
            int known = pairs.size();
            relabeling[index] = pairs.intern(residual, values);
            if (relabeling[index] < known) {
                // Each pair holds one reference to its residual until the end, so no restriction collects it.
                bdd.dereference(residual);
            }
        }
        int result = diagram.map(productFunction, index -> relabeling[index]);
        diagram.dereference(productFunction);
        for (int index = 0; index < pairs.size(); index++) {
            bdd.dereference(pairs.residuals.get(index));
        }
        return pairs.over(result);
    }

    /** The variables {@code operands} replaces, ascending. */
    static int[] replacedVariables(int[] operands, int placeholder) {
        int count = 0;
        for (int operand : operands) {
            if (operand != placeholder) {
                count += 1;
            }
        }
        int[] replaced = new int[count];
        int next = 0;
        for (int variable = 0; variable < operands.length; variable++) {
            if (operands[variable] != placeholder) {
                replaced[next] = variable;
                next += 1;
            }
        }
        return replaced;
    }

    static boolean checkArguments(BinaryDecisionDiagram bdd, int function, int[] operands, int placeholder) {
        assert bdd.isValidFunction(function);
        for (int variable = 0; variable < operands.length; variable++) {
            assert operands[variable] == placeholder || variable < bdd.numberOfVariables()
                    : "Replaced variable " + variable + " does not exist";
        }
        return true;
    }

    /** The pairs (residual, values) seen so far, numbered in order of appearance. */
    static final class Pairs {
        private final Map<IntArrayTuple, Integer> index = new HashMap<>();
        final IntArrayList residuals = new IntArrayList();
        final List<int[]> values = new ArrayList<>();
        // The values of every pair without essential ones: one array, as nobody may modify what valuesFor hands out.
        private final int[] absent;

        Pairs(int width) {
            absent = new int[width];
            Arrays.fill(absent, ResidualProduct.ABSENT);
        }

        /** The pair without essential values. */
        int intern(int residual) {
            return intern(new int[] {residual}, residual, absent);
        }

        // A pair is its residual and the essential values by ascending variable: the residual's support decides
        // which variables those are, so the key leaves out the absent ones.
        int intern(int residual, int[] pairValues) {
            int essential = 0;
            for (int value : pairValues) {
                if (value != ResidualProduct.ABSENT) {
                    essential += 1;
                }
            }
            int[] key = new int[essential + 1];
            key[0] = residual;
            int next = 1;
            for (int value : pairValues) {
                if (value != ResidualProduct.ABSENT) {
                    key[next] = value;
                    next += 1;
                }
            }
            return intern(key, residual, pairValues);
        }

        private int intern(int[] key, int residual, int[] pairValues) {
            IntArrayTuple tuple = new IntArrayTuple(key);
            Integer known = index.get(tuple);
            if (known != null) {
                return known;
            }
            int created = residuals.size();
            index.put(tuple, created);
            residuals.add(residual);
            values.add(pairValues);
            return created;
        }

        int size() {
            return residuals.size();
        }

        ResidualProduct over(int function) {
            NatSet codomain = NatSet.range(0, size());
            return new ResidualProduct() {
                @Override
                public int function() {
                    return function;
                }

                @Override
                public int residualFor(int value) {
                    return residuals.get(value);
                }

                @Override
                public int[] valuesFor(int value) {
                    return values.get(value);
                }

                @Override
                public NatSet codomain() {
                    return codomain;
                }
            };
        }
    }
}
