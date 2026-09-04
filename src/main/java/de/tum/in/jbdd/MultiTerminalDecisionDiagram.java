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

import java.math.BigInteger;
import java.util.BitSet;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntBinaryOperator;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
import java.util.function.ToIntFunction;
import org.jspecify.annotations.Nullable;

public interface MultiTerminalDecisionDiagram extends BooleanDecisionDiagram {
    /**
     * The underlying BDD with which this structure shares its variables.
     */
    Bdd bdd();

    /**
     * Computes the value the given {@code function} takes under the given {@code assignment}.
     *
     * @param function
     *     The function to evaluate.
     * @param assignment
     *     The variable assignment.
     *
     * @return The value of the function under the given assignment.
     */
    int evaluate(int function, boolean[] assignment);

    /**
     * Computes the value the given {@code function} takes under the given {@code assignment}.
     *
     * @param function
     *     The function to evaluate.
     * @param assignment
     *     The variable assignment.
     *
     * @return The value of the function under the given assignment.
     */
    int evaluate(int function, BitSet assignment);

    /**
     * Creates the constant function with given {@code value}.
     *
     * <p>Terminal values are non-negative ids chosen by the caller, not handed out by this diagram (see
     * the class comment of the implementation). They are assumed to be reasonably <em>dense</em>: the
     * diagram keeps per-value bookkeeping in structures indexed by the raw value, so a caller using
     * sparse ids (hash codes, identity values, ...) pays memory proportional to the largest id it ever
     * passes in, not to the number of distinct values.</p>
     */
    int of(int value);

    /**
     * Creates a function that evaluates to the given child functions.
     */
    int of(int variable, int trueChild, int falseChild);

    /**
     * Returns any assignment of the given {@code function} leading to the given {@code values}, if any.
     */
    Optional<BitSet> anyAssignment(int function, IntPredicate values);

    /**
     * Counts the number of assignments under which the given {@code function} evaluates to the given {@code values}.
     */
    BigInteger countAssignments(int function, IntPredicate values);

    /**
     * Counts the number of assignments under which the given {@code function} evaluates to the given {@code values},
     * only considering variables in the {@code support}.
     */
    BigInteger countAssignments(int function, IntPredicate values, BitSet support);

    /**
     * Walks all assignments under which the given {@code function} evaluates to one of the given
     * {@code values}, in lexicographic ascending order. {@code values} may be {@code null}, meaning "any
     * value" - then this walks every assignment. {@link ValuedCursor#value()} yields the value the
     * function takes under the assignment the cursor stands on.
     *
     * <p><b>Note:</b> The returned cursor is lazy and consults diagram-internal state that the next query
     * overwrites. It must be fully drained before any other operation is invoked on this diagram;
     * interleaving is checked by an assertion, not supported.</p>
     *
     * <p><b>Note:</b> The bit set it hands out is its own working state - see {@link Cursor}. If all
     * assignments should be gathered into a set or similar, they have to be cloned.</p>
     */
    ValuedCursor<BitSet> assignmentCursor(int function, @Nullable IntPredicate values);

    /**
     * Like {@link #assignmentCursor(int, IntPredicate)}, but only distinguishing assignments to the
     * variables in {@code support}, which must contain the function's own support.
     */
    ValuedCursor<BitSet> assignmentCursor(int function, @Nullable IntPredicate values, BitSet support);

    /**
     * Walks all root-to-leaf paths of the given {@code function} in lexicographic ascending order, with
     * {@link ValuedCursor#value()} yielding the value the path leads to. A path constrains only the
     * variables actually tested along it; every variable outside its {@link BinaryPath#support support} is
     * a "don't care".
     *
     * <p><b>Note:</b> The {@link BinaryPath} it hands out is its own working state - see {@link Cursor}.
     * If all paths should be gathered into a collection, they have to be
     * {@link BinaryPath#copy() copied}.</p>
     *
     * @see #forEachPath(int, PathConsumer)
     */
    ValuedCursor<BinaryPath> pathCursor(int function);

    /**
     * Executes the given action for each assignment under which the given {@code function} evaluates to the given
     * {@code values}.
     *
     * <p>The solutions are generated in lexicographic ascending order.</p>
     */
    default void forEachSolution(int function, @Nullable IntPredicate values, Consumer<? super BitSet> action) {
        assignmentCursor(function, values).forEachRemaining(action);
    }

    /**
     * Executes the given {@code action} once for each root-to-leaf path of the given {@code function},
     * together with the value that path leads to. A path constrains only the variables actually tested
     * along it; every variable outside its {@link BinaryPath#support support} is a "don't care".
     *
     * <p>The paths are generated in lexicographic ascending order.</p>
     *
     * <p><b>Note:</b> The passed path is modified in-place. If all paths should be gathered into a set
     * or similar, they have to be cloned after each call to the consumer.</p>
     *
     * @param function
     *     The function whose paths should be enumerated.
     * @param action
     *     The action to be performed on each path and its value.
     */
    void forEachPath(int function, PathConsumer action);

    /**
     * Computes the co-domain of the given {@code function}.
     */
    default BitSet valuesOf(int function) {
        BitSet values = new BitSet();
        forEachValue(function, values::set);
        return values;
    }

    /**
     * Determines whether all values in the co-domain of the given {@code function} match the {@code predicate}.
     */
    default boolean allValuesMatch(int function, IntPredicate predicate) {
        return valuesOf(function).stream().allMatch(predicate);
    }

    /**
     * Determines whether any value in the co-domain of the given {@code function} matches the {@code predicate}.
     */
    default boolean anyValueMatches(int function, IntPredicate predicate) {
        return !allValuesMatch(function, predicate.negate());
    }

    /**
     * Calls the given {@code action} for each value in the co-domain of the given {@code function}
     * <em>at least</em> once.
     */
    void forEachValue(int function, IntConsumer action);

    /**
     * Constructs the <i>composition</i> of the given {@code function} with the boolean functions in {@code variableNodes}.
     * Formally, if {@code function} is {@code f(x_1, x_2, ..., x_n)}, this method returns
     * {@code f(f_1(x_1, ..., x_n), ..., f_n(x_1, ..., x_n))}, where {@code f_i = variableNodes[i]}.
     *
     * <p>The {@code variableNodes} array can contain less than {@code n} entries, then only the first variables are replaced.
     * Furthermore, {@code placeholder} can be used as an entry to denote "don't replace this variable" (which semantically
     * is the same as saying "replace this variable by itself"). After the call, the {@code placeholder} entries will be
     * replaced by the actual corresponding variable nodes. </p>
     *
     * @param function
     *     The MTBDD function to be composed.
     * @param variableMapping
     *     The boolean functions (in the underlying BDD) with which each variable should be replaced.
     *
     * @return The composed MTBDD function.
     */
    int compose(int function, int[] variableMapping);

    /**
     * {@link #compose} and {@link #simplify} in one traversal: the result agrees with
     * {@code compose(function, variableMapping)} on every assignment satisfying {@code domain} and is
     * unspecified elsewhere. Equivalent to {@code simplify(compose(function, variableMapping), domain)},
     * except that the domain is narrowed <em>during</em> the composition, so intermediate results are
     * simplified too rather than only the final one.
     *
     * <p>Note that {@code domain} constrains the <em>composed</em> function, i.e. it speaks about the
     * variables the replacements are written in.</p>
     *
     * @see #compose(int, int[])
     * @see #simplify(int, int)
     */
    int composeSimplify(int function, int[] variableMapping, int domain);

    /**
     * Registers a {@code compose} operation bound to a fixed {@code bddVariableMapping}. Unlike
     * {@link #compose}, which shares a single cache slot invalidated wholesale every time a caller
     * alternates between different replacement arrays, the returned {@link RegisteredOperation.Unary} owns a private
     * cache that survives for as long as it (or several other registered operations, alternated between)
     * are kept around.
     *
     * @param bddVariableMapping
     *     The boolean functions (in the underlying BDD) with which each variable should be replaced; see
     *     {@link #compose}.
     *
     * @return A {@link RegisteredOperation.Unary} bound to {@code bddVariableMapping}.
     */
    RegisteredOperation.Unary registerCompose(int[] bddVariableMapping);

    /**
     * The {@link #composeSimplify} counterpart of {@link #registerCompose}: the returned operation takes the
     * function and the domain, in that order.
     */
    RegisteredOperation.Binary registerComposeSimplify(int[] bddVariableMapping);

    /**
     * Computes the restriction of the given {@code function}, where all variables specified by {@code
     * restrictedVariables} are replaced by the value given in {@code restrictedVariableValues}.
     * Formally, if {@code function} is {@code f(x_1, ..., x_n)}, this method computes the function
     * {@code f(x_1, ..., x_{i_1-1}, c_1, x_{i_1+1}, ..., x_{i_2-1}, c_2, x_{i_2+1}, ...,
     * x_n}, where {@code i_k} are the elements of the {@code restrictedVariables} set and
     * {@code c_k := restrictedVariableValues.get(i_k)}.
     *
     * @param function
     *     The function to be restricted.
     * @param restrictedVariables
     *     The variables used in the restriction.
     * @param restrictedVariableValues
     *     The values of the restricted variables.
     *
     * @return The restricted function.
     *
     * @see #compose(int, int[])
     */
    int restrict(int function, BitSet restrictedVariables, BitSet restrictedVariableValues);

    /**
     * Compute the function where all given {@code assignments} (represented as function in the underlying BDD)
     * evaluates to the given {@code value}.
     */
    default int update(int function, int assignments, int value) {
        return ifThenElse(assignments, of(value), function);
    }

    /**
     * Compute the function which yields {@code thenFunction} when {@code ifFunction} (represented as function
     * in the underlying BDD) evaluates to {@code true}, and {@code elseFunction} otherwise.
     */
    int ifThenElse(int ifFunction, int thenFunction, int elseFunction);

    /**
     * The single canonical entry point every other {@code apply}/{@code applyXxx} overload below funnels
     * through, by wrapping its {@link IntBinaryOperator} into an {@link MtBddBinaryOperator} declaring
     * whatever properties that particular overload's name promises. {@code operator}'s properties are
     * unchecked preconditions - see {@link MtBddBinaryOperator}'s javadoc.
     */
    int apply(int function1, int function2, MtBddBinaryOperator operator);

    /**
     * {@link #apply(int, int, MtBddBinaryOperator)} and {@link #simplify} in one traversal: the result
     * agrees with {@code apply(function1, function2, operator)} on every assignment satisfying
     * {@code domain} (a function of the underlying {@link #bdd() Bdd}) and is unspecified elsewhere.
     * Equivalent to {@code simplify(apply(function1, function2, operator), domain)}, except that the domain
     * is narrowed <em>during</em> the recursion, so the operator is never evaluated for a branch the domain
     * excludes and intermediate results are simplified as well.
     *
     * <p>There is deliberately no n-ary counterpart.</p>
     *
     * @see #apply(int, int, MtBddBinaryOperator)
     * @see #simplify(int, int)
     */
    int applySimplify(int function1, int function2, MtBddBinaryOperator operator, int domain);

    /**
     * Registers an {@code apply} operation bound to a fixed {@code operator}. Unlike {@link #apply}, which
     * shares a single cache slot invalidated wholesale every time a caller alternates between different
     * operators, the returned {@link RegisteredOperation.Binary} owns a private cache that survives for as long as it
     * (or several other registered operations, alternated between) are kept around.
     *
     * @param operator
     *     The operator to apply; see {@link #apply(int, int, MtBddBinaryOperator)}.
     *
     * @return A {@link RegisteredOperation.Binary} bound to {@code operator}.
     */
    RegisteredOperation.Binary registerApply(MtBddBinaryOperator operator);

    /**
     * The {@link #applySimplify} counterpart of {@link #registerApply}: the returned operation takes the two
     * functions and the domain, in that order.
     */
    RegisteredOperation.Ternary registerApplySimplify(MtBddBinaryOperator operator);

    /**
     * Compute the function that for each assignment {@code a} evaluates to {@code map(function1(a), function2(a))}.
     */
    default int apply(int function1, int function2, IntBinaryOperator map) {
        return apply(function1, function2, MtBddBinaryOperator.of(map));
    }

    /**
     * Like {@link #apply(int, int, IntBinaryOperator)}, but for a commutative operator: requires
     * {@code map(a, b) == map(b, a)} for all {@code a, b}. Lets the implementation canonicalize argument
     * order before consulting its operation cache, since {@code apply(f, g)} and {@code apply(g, f)} then
     * always share one cache entry.
     */
    default int applyCommutative(int function1, int function2, IntBinaryOperator map) {
        return apply(function1, function2, MtBddBinaryOperator.commutative(map));
    }

    /**
     * Like {@link #applyCommutative}, additionally requiring {@code map(x, neutral) == map(neutral, x) ==
     * x} for all {@code x}. Lets the implementation return the other operand unchanged - without recursing
     * into it, even if it's a large subtree - the moment either side is the constant {@code neutral}.
     */
    default int applyMonoid(int function1, int function2, IntBinaryOperator map, int neutral) {
        return apply(function1, function2, MtBddBinaryOperator.monoid(map, neutral));
    }

    /**
     * Like {@link #applyCommutative}, additionally requiring {@code map(x, absorbing) == map(absorbing, x)
     * == absorbing} for all {@code x}. Lets the implementation return the constant {@code absorbing}
     * immediately - pruning both subtrees entirely - the moment either side is it. Usually the more
     * impactful of the two shortcuts (e.g. boolean {@code and}'s {@code FALSE}), since it prunes work
     * rather than merely avoiding a rebuild.
     */
    default int applyAbsorbing(int function1, int function2, IntBinaryOperator map, int absorbing) {
        return apply(function1, function2, MtBddBinaryOperator.absorbing(map, absorbing));
    }

    /**
     * {@link #applyMonoid} and {@link #applyAbsorbing} combined, for operators that have both (e.g.
     * arithmetic {@code *}: {@code neutral=1}, {@code absorbing=0}; boolean {@code and}: {@code
     * neutral=TRUE}, {@code absorbing=FALSE}).
     */
    default int applyMonoid(int function1, int function2, IntBinaryOperator map, int neutral, int absorbing) {
        return apply(function1, function2, MtBddBinaryOperator.monoid(map, neutral, absorbing));
    }

    /**
     * The n-ary counterpart of {@link #apply(int, int, MtBddBinaryOperator)} - the single canonical entry
     * point every n-ary {@code apply}/{@code applyXxx} overload below funnels through.
     */
    int apply(int[] functions, MtBddNaryOperator operator);

    /**
     * Compute the function that for each assignment {@code a} evaluates to {@code map(f_1(a), ..., f_m(a))}.
     */
    default int apply(int[] functions, ToIntFunction<int[]> map) {
        return apply(functions, MtBddNaryOperator.of(functions.length, map));
    }

    /**
     * Like {@link #applyCommutative(int, int, IntBinaryOperator)}, generalized to n operands: requires
     * {@code map} to be invariant under any permutation of {@code functions}.
     */
    default int applyCommutative(int[] functions, ToIntFunction<int[]> map) {
        return apply(functions, MtBddNaryOperator.commutative(functions.length, map));
    }

    /**
     * Like {@link #applyMonoid(int, int, IntBinaryOperator, int)}, generalized to n operands: requires
     * {@code map(v_1, ..., v_n) == v_i} whenever every {@code v_j} with {@code j != i} equals {@code
     * neutral}. Lets the implementation return the one non-neutral operand unchanged - without recursing
     * into it - the moment all the others are the constant {@code neutral}.
     */
    default int applyMonoid(int[] functions, ToIntFunction<int[]> map, int neutral) {
        return apply(functions, MtBddNaryOperator.monoid(functions.length, map, neutral));
    }

    /**
     * Like {@link #applyAbsorbing(int, int, IntBinaryOperator, int)}, generalized to n operands: requires
     * {@code map(v_1, ..., v_n) == absorbing} whenever any {@code v_i} equals {@code absorbing}. Lets the
     * implementation return the constant {@code absorbing} immediately, pruning every subtree, the moment
     * any operand is it.
     */
    default int applyAbsorbing(int[] functions, ToIntFunction<int[]> map, int absorbing) {
        return apply(functions, MtBddNaryOperator.absorbing(functions.length, map, absorbing));
    }

    /** {@link #applyMonoid(int[], ToIntFunction, int)} and {@link #applyAbsorbing(int[], ToIntFunction, int)}
     * combined. */
    default int applyMonoid(int[] functions, ToIntFunction<int[]> map, int neutral, int absorbing) {
        return apply(functions, MtBddNaryOperator.monoid(functions.length, map, neutral, absorbing));
    }

    /**
     * Constructs the function obtained by composing the given {@code function} with {@code map}, i.e.
     * {@code map(function(input))}.
     */
    default int map(int function, IntUnaryOperator map) {
        return apply(new int[] {function}, a -> map.applyAsInt(a[0]));
    }

    /**
     * The unary counterpart of {@link #applySimplify}: the result agrees with {@code map(function, map)} on
     * every assignment satisfying {@code domain} and is unspecified elsewhere.
     *
     * @see #map(int, IntUnaryOperator)
     * @see #simplify(int, int)
     */
    int mapSimplify(int function, IntUnaryOperator map, int domain);

    /**
     * Constructs the boolean function in the associated BDD which evaluates to true exactly for those
     * valuations on which the given functions yield the same value.
     *
     * <p>{@link #applyBoolean} with raw terminal equality, on its own cache.
     */
    int agreement(int function1, int function2);

    /**
     * Constructs the boolean function in the associated BDD which evaluates to true exactly for those
     * valuations on which the two functions' values satisfy {@code predicate} - the boolean-valued
     * counterpart of {@link #apply(int, int, MtBddBinaryOperator)}, folding two terminal values into one
     * bit instead of into another terminal.
     *
     * <p>The predicate is only ever handed raw terminal values, so a caller whose two operands number
     * their terminals differently can resolve each side through its own numbering here - which is what
     * makes this, and not {@link #agreement}, the general form. See {@link MtBddBinaryPredicate} for the
     * properties worth claiming and what each one buys.
     */
    int applyBoolean(int function1, int function2, MtBddBinaryPredicate predicate);

    /**
     * Creates the boolean function representing all assignments under which the given {@code function}
     * evaluates to the given {@code value} in the underlying {@link #bdd() Bdd}.
     *
     * @see #agreement(int, int)
     */
    default int where(int function, int value) {
        return agreement(function, of(value));
    }

    /**
     * Creates the boolean function representing all assignments under which the given {@code function}
     * evaluates to the given {@code values} in the underlying {@link #bdd() Bdd}.
     *
     * @see #agreement(int, int)
     */
    int mapBoolean(int function, IntPredicate values);

    /**
     * Creates the inverse of the given {@code function}, i.e. a mapping from each value to the boolean
     * function describing all assignments under which the function evaluates to that value.
     *
     * @see #mapBoolean(int, IntPredicate)
     */
    Inverse invert(int function);

    /**
     * Creates the split of the given {@code function}. Suppose {@code function} is {@code f(x_1, ..., x_n}}
     * and {@code splitVariables} are all even variables. Then, the result's {@link FunctionToFunctionMap#function()}
     * is a function {@code g(x_2, x_4, ..., x_{n-1})} which, for every assignment to these variables, yields
     * an index into {@link FunctionToFunctionMap#codomain()} whose associated function {@code h(x_1, x_3, ..., x_n)}
     * evaluates to {@code f(x_1, ..., x_n)}.
     */
    FunctionToFunctionMap split(int function, BitSet splitVariables);

    /**
     * Like {@link #split}, but instead of handing back a {@link FunctionToFunctionMap} whose pieces the
     * caller must protect (reference) before making any further call, immediately relabels each residual
     * sub-function via {@code relabeler} (called at most once per distinct sub-function) and returns the
     * combined result directly - equivalent to {@code map(split(function, splitVariables).function(), v ->
     * relabeler.applyAsInt(split(function, splitVariables).functionFor(v)))}, except the whole computation
     * happens as a single traversal, so no intermediate function is ever exposed unprotected.
     */
    int splitRelabeled(int function, BitSet splitVariables, IntUnaryOperator relabeler);

    /**
     * Creates the product of the given {@code functions}. Suppose each function is {@code f_i(x_1, ..., x_n}},
     * then their product is a function {@code f(x)} that yields {@code [f_1(x), ..., f_m(x)]}. The returned
     * function indexes the {@code values} map. The outputs of {@code f} do not need to be dense.
     */
    FunctionToFunctionsMap cartesianProduct(int[] functions);

    /**
     * Constructs the generalized cofactor of {@code function} w.r.t. {@code domain} (Coudert &amp; Madre),
     * also written {@code f @ g}: agrees with {@code function} wherever {@code domain} holds, and
     * elsewhere takes the value of {@code function} at the nearest {@code domain}-satisfying assignment
     * (variables decided top-down, flipped only when {@code domain} forces it) - a specific canonical
     * choice, unlike {@link #simplify}'s arbitrary one, so the result may depend on variables {@code
     * function} did not and is not guaranteed to stay bounded in size.
     *
     * <p>Equivalently, and more usefully: {@code f @ domain} is {@code f} precomposed with the projection
     * that maps each assignment to the {@code domain}-satisfying one just described. That makes it the
     * <em>canonical</em> representative of "agrees with {@code f} on {@code domain}" - two functions have
     * the same cofactor exactly when they agree there - and makes it commute with pointwise operations
     * ({@code apply(f, g, op) @ d} equals {@code apply(f @ d, g @ d, op)}). {@link #simplify} offers
     * neither; pick {@code constrain} when you need one of those properties, {@code simplify} when you
     * only want a smaller representative.</p>
     *
     * <p><b>Precondition:</b> {@code domain} must not be {@link Bdd#falseFunction()}. The projection
     * above does not exist for an empty domain, so there is nothing to return; this is checked by an
     * assertion. (BDD/ADD implementations conventionally answer {@code 0} there, which is available to
     * them only because their co-domain has a distinguished zero.)</p>
     */
    int constrain(int function, int domain);

    /**
     * Constructs a simplified version of the given {@code function} which is equivalent to it for all assignments
     * where {@code domain} is true. Unlike {@link #constrain}, the value outside {@code domain} is
     * unspecified rather than canonical, which is what keeps the result's variable dependencies within
     * {@code function}'s own.
     *
     * <p>A heuristic, not a minimization: the result is usually much smaller, but it is <em>not</em>
     * guaranteed to be. Each node is simplified against the domain cofactor it is reached under, so a
     * subgraph shared by two paths with different domain contexts can simplify two different ways and be
     * duplicated - every path gets no longer, but the diagram loses a merge. Unlike {@link #constrain},
     * an empty {@code domain} is fine here: everything is don't-care, and some constant is returned.</p>
     */
    int simplify(int function, int domain);

    interface Inverse {
        /**
         * The inverted MTBDD function.
         */
        int function();

        /**
         * Return the BDD function describing all valuations that yield the given value in the MTBDD function.
         * In particular, for values outside the support, this function returns {@link Bdd#falseFunction() FALSE}.
         */
        int functionFor(int value);

        /**
         * The co-domain of the inverted function.
         */
        BitSet codomain();
    }

    interface FunctionToFunctionMap {
        /**
         * The meta-function whose values index functions.
         */
        int function();

        /**
         * The function corresponding to the given value (leaves of the meta-function).
         */
        int functionFor(int value);

        /**
         * The co-domain of the meta-function.
         */
        BitSet codomain();
    }

    interface FunctionToFunctionsMap {
        /**
         * The meta-function whose values index functions.
         */
        int function();

        /**
         * The functions corresponding to the given value (leaves of the meta-function).
         */
        int[] functionFor(int value);

        /**
         * The co-domain of the meta-function.
         */
        BitSet codomain();
    }

    @FunctionalInterface
    interface PathConsumer {
        void accept(BinaryPath path, int value);
    }

    /** A {@link Cursor} that also reports the terminal the element it stands on leads to. */
    interface ValuedCursor<E> extends Cursor<E> {
        /**
         * The value the underlying function takes on the element the cursor stands on. Defined exactly
         * while {@link #valid()} holds.
         */
        int value();
    }
}
