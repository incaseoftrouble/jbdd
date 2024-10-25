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
import java.util.function.IntConsumer;

/**
 * Generic interface for (binary) decision diagrams, i.e. a data structure that represents functions mapping
 * from boolean assignments to some domain through a tree-like structure. Each function is represented by an
 * (opaque) integer. A (reduced) decision diagram ensures that two functions are equal exactly if their
 * identifiers are equal.
 */
public interface DecisionDiagram {
    /**
     * A special reserved placeholder distinct from any possible function or node value, which may be used as a
     * placeholder in some operations. Needs to stay constant throughout the life of the diagram.
     */
    int placeholder();

    /**
     * Determines whether the given {@code function} is a constant, e.g. {@code true} or {@code false}.
     *
     * @param function The function to be checked.
     * @return If the {@code node} represents a constant.
     */
    boolean isConstant(int function);

    /**
     * Returns the number of variables in this decision diagram.
     *
     * @return The number of variables.
     */
    int numberOfVariables();

    /**
     * Gets the topmost decision variable of the given {@code function}.
     */
    int decisionVariable(int function);

    // Reference counting

    /**
     * Increases the reference count of the specified {@code function}.
     *
     * @param function The to be referenced function
     * @return The given function, to be used for chaining.
     */
    int reference(int function);

    /**
     * Decreases the reference count of the specified {@code function}.
     *
     * @param function The to be de-referenced function
     * @return The given function, to be used for chaining.
     */
    int dereference(int function);

    /**
     * Decreases the reference count of the specified {@code functions}.
     *
     * @param functions The to be de-referenced functions
     */
    default void dereference(int... functions) {
        for (int node : functions) {
            dereference(node);
        }
    }

    /**
     * Auxiliary method useful for updating function variables. It dereferences the inputs and
     * references {@code result}. This is useful for assignments like {@code fun = f(in1, in2)} where
     * {@code f} is some operation on this object and both {@code in1} and {@code in2} are temporary
     * functions or not used anymore. In this case, calling {@code fun = bdd.consume(f(in1, in2),
     * in1, in2)} updates the references as needed.
     *
     * @param result The result of some operation involving input1 and input2
     * @param input1 First input of the operation.
     * @param input2 Second input of the operation.
     * @return The given {@code result}.
     */
    default int consume(int result, int input1, int input2) {
        reference(result);
        dereference(input1);
        dereference(input2);
        return result;
    }

    /**
     * Auxiliary method useful for updating node variables. It dereferences {@code input} and
     * references {@code result}. This is useful for assignments like {@code fun = f(fun, ...)}
     * where {@code f} is some operation on this object. In this case, calling {@code fun =
     * bdd.updateWith(f(fun, ...), fun)} updates the references as needed and leaves the other
     * parameters untouched.
     *
     * @param result The result of some operation involving input.
     * @param input The function which gets assigned the value of the result.
     * @return The given {@code result}.
     */
    default int updateWith(int result, int input) {
        reference(result);
        dereference(input);
        return result;
    }

    // Support

    /**
     * Computes the <b>support</b> of the given {@code function}. The support of a function are
     * all variables which have an influence on its value.
     *
     * @param function The function whose support should be computed.
     * @return A bit set with bit {@code i} is set iff the {@code i}-th variable is in the support.
     */
    default BitSet support(int function) {
        return supportTo(function, new BitSet(numberOfVariables()));
    }

    /**
     * Computes the <b>support</b> of the given {@code function} and writes it in the {@code bitSet}.
     * Note that the {@code bitSet} is not cleared, the support variables are added to the set.
     *
     * @param function The function whose support should be computed.
     * @param bitSet The BitSet used to store the result.
     * @return The given bitset, useful for chaining.
     * @see #support(int)
     */
    default BitSet supportTo(int function, BitSet bitSet) {
        forEachSupportVariable(function, bitSet::set);
        return bitSet;
    }

    /**
     * Calls the given {@code action} for each variable in the support of {@code function} <em>at least</em> once.
     *
     * @param function The function whose support should be computed.
     */
    default void forEachSupportVariable(int function, IntConsumer action) {
        BitSet filter = new BitSet(numberOfVariables());
        filter.set(0, numberOfVariables());
        forEachSupportFiltered(function, filter, action);
    }

    default BitSet supportFiltered(int function, BitSet filter) {
        BitSet bitSet = new BitSet(numberOfVariables());
        forEachSupportFiltered(function, filter, bitSet::set);
        return bitSet;
    }

    /**
     * Calls the given {@code action} for each variable in the support of {@code function} <em>at least</em> once.
     * Only considers variables in the given {@code filter}.
     *
     * @param function The function whose support should be computed.
     * @see #forEachSupportVariable(int, IntConsumer)
     */
    void forEachSupportFiltered(int function, BitSet filter, IntConsumer action);

    /**
     * A wrapper class to guard some function in an area where exceptions can occur. It increases
     * the reference count of the given function and decreases it when it's closed.
     */
    final class ReferenceGuard implements AutoCloseable {
        public final DecisionDiagram diagram;
        public final int function;

        public ReferenceGuard(int function, DecisionDiagram diagram) {
            this.function = diagram.reference(function);
            this.diagram = diagram;
        }

        @Override
        public void close() {
            diagram.dereference(function);
        }
    }
}
