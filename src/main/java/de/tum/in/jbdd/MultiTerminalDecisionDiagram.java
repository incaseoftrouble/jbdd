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

import de.tum.in.jbdd.collections.Cursor;
import de.tum.in.jbdd.collections.MutableNatSet;
import de.tum.in.jbdd.collections.NatSet;
import java.math.BigInteger;
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
    BinaryDecisionDiagram bdd();

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
    int evaluate(int function, NatSet assignment);

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
     * Creates a function that evaluates to the given child functions, i.e. the one deciding on {@code
     * variable}. Nothing re-orders the result into shape, so the caller owes the order: {@code variable}
     * has to sit above every variable either child decides on.
     */
    int of(int variable, int trueChild, int falseChild);

    /**
     * Returns any assignment of the given {@code function} leading to the given {@code values}, if any.
     */
    Optional<MutableNatSet> anyAssignment(int function, IntPredicate values);

    /**
     * Counts the number of assignments under which the given {@code function} evaluates to the given {@code values}.
     */
    BigInteger countAssignments(int function, IntPredicate values);

    /**
     * Counts the number of assignments under which the given {@code function} evaluates to the given {@code values},
     * only considering variables in the {@code support}.
     */
    BigInteger countAssignments(int function, IntPredicate values, NatSet support);

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
    ValuedCursor<NatSet> assignmentCursor(int function, @Nullable IntPredicate values);

    /**
     * Like {@link #assignmentCursor(int, IntPredicate)}, but only distinguishing assignments to the
     * variables in {@code support}, which must contain the function's own support.
     */
    ValuedCursor<NatSet> assignmentCursor(int function, @Nullable IntPredicate values, NatSet support);

    /**
     * Walks all root-to-leaf paths of the given {@code function} in lexicographic ascending order, with
     * {@link ValuedCursor#value()} yielding the value the path leads to. A path constrains only the
     * variables actually tested along it; every variable outside its {@link Cube#support support} is
     * a "don't care".
     *
     * <p><b>Note:</b> The {@link Cube} it hands out is its own working state - see {@link Cursor}.
     * If all paths should be gathered into a collection, they have to be
     * {@link Cube#copy() copied}.</p>
     *
     * @see #forEachPath(int, PathValueConsumer)
     */
    ValuedCursor<Cube> pathCursor(int function);

    /**
     * Executes the given action for each assignment under which the given {@code function} evaluates to the given
     * {@code values}.
     *
     * <p>The solutions are generated in lexicographic ascending order.</p>
     */
    default void forEachSolution(int function, @Nullable IntPredicate values, Consumer<? super NatSet> action) {
        assignmentCursor(function, values).forEachRemaining(action);
    }

    /**
     * Executes the given {@code action} once for each root-to-leaf path of the given {@code function},
     * together with the value that path leads to. A path constrains only the variables actually tested
     * along it; every variable outside its {@link Cube#support support} is a "don't care".
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
    void forEachPath(int function, PathValueConsumer action);

    /**
     * Computes the co-domain of the given {@code function}.
     */
    default MutableNatSet valuesOf(int function) {
        MutableNatSet values = MutableNatSet.create();
        forEachValue(function, values::set);
        return values;
    }

    /**
     * Determines whether all values in the co-domain of the given {@code function} match the {@code predicate}.
     */
    boolean allValuesMatch(int function, IntPredicate predicate);

    /**
     * Determines whether any value in the co-domain of the given {@code function} matches the {@code predicate}.
     */
    boolean anyValueMatches(int function, IntPredicate predicate);

    /**
     * Calls the given {@code action} for each value in the co-domain of the given {@code function}
     * <em>at least</em> once.
     */
    void forEachValue(int function, IntConsumer action);

    /**
     * Constructs the <i>composition</i> of the given {@code function} with the boolean functions in
     * {@code variableMapping}. Formally, if {@code function} is {@code f(x_1, x_2, ..., x_n)}, this method returns
     * {@code f(f_1(x_1, ..., x_n), ..., f_n(x_1, ..., x_n))}, where {@code f_i = variableMapping[i]}.
     *
     * <p>The {@code variableMapping} array can contain less than {@code n} entries, then only the first variables are
     * replaced. Furthermore, {@link #placeholder()} can be used as an entry to denote "don't replace this variable"
     * (which semantically is the same as saying "replace this variable by itself"). The array is only read.</p>
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
     * Registers a {@code compose} operation bound to a fixed {@code bddVariableMapping} - see
     * {@link RegisteredOperation}.
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
     * {@code function} with every variable of the {@code restriction} fixed to its value there, so the result no
     * longer depends on them.
     *
     * @see #compose(int, int[])
     */
    int restrict(int function, Cube restriction);

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
     * {@code function} of {@code source}, another multi-terminal diagram, rebuilt in this one with each variable
     * {@code v} of it read as {@code variableMapping(v)} here, which must exist, and each value {@code x} as
     * {@code valueMapping(x)}. The source is only read; the result is not referenced.
     *
     * <p>As {@link BinaryDecisionDiagram#adopt}: one memoized pass over the source's nodes, a single node each where
     * the mapped variable lies above both rebuilt children in this diagram's order.
     */
    int adopt(
            MultiTerminalDecisionDiagram source,
            int function,
            IntUnaryOperator variableMapping,
            IntUnaryOperator valueMapping);

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
     * Registers an {@code apply} operation bound to a fixed {@code operator} - see
     * {@link RegisteredOperation}.
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
     * point every n-ary {@code apply}/{@code applyXxx} overload below funnels through. Over no operands it is the
     * constant {@code operator} yields for the empty tuple.
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
     * Registers a {@code map} operation bound to a fixed {@code map} - see {@link RegisteredOperation}.
     *
     * @see #map(int, IntUnaryOperator)
     */
    RegisteredOperation.Unary registerMap(IntUnaryOperator map);

    /**
     * The {@link #mapSimplify} counterpart of {@link #registerMap}: the returned operation takes the
     * function and the domain, in that order.
     */
    RegisteredOperation.Binary registerMapSimplify(IntUnaryOperator map);

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
     * Whether the two functions' values satisfy {@code predicate} at every valuation.
     */
    boolean allMatch(int function1, int function2, MtBddBinaryPredicate predicate);

    /**
     * Registers an {@code applyBoolean} operation bound to a fixed {@code predicate} - see
     * {@link RegisteredOperation}.
     *
     * @see #applyBoolean(int, int, MtBddBinaryPredicate)
     */
    RegisteredOperation.Binary registerApplyBoolean(MtBddBinaryPredicate predicate);

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
     * Registers a {@code mapBoolean} operation bound to a fixed {@code values} predicate - see
     * {@link RegisteredOperation}.
     *
     * @see #mapBoolean(int, IntPredicate)
     */
    RegisteredOperation.Unary registerMapBoolean(IntPredicate values);

    /**
     * The inverse of the given {@code function}: {@link FunctionToFunctionMap#function()} is {@code function} itself,
     * and {@link FunctionToFunctionMap#functionFor(int)} the boolean function of the {@link #bdd() Bdd} describing all
     * assignments under which it evaluates to that value, {@literal false} for a value it never takes.
     *
     * @see #mapBoolean(int, IntPredicate)
     */
    FunctionToFunctionMap invert(int function);

    /**
     * Creates the split of the given {@code function}. Suppose {@code function} is {@code f(x_1, ..., x_n}}
     * and {@code splitVariables} are all even variables. Then, the result's {@link FunctionToFunctionMap#function()}
     * is a function {@code g(x_2, x_4, ..., x_{n-1})} which, for every assignment to these variables, yields
     * an index into {@link FunctionToFunctionMap#codomain()} whose associated function {@code h(x_1, x_3, ..., x_n)}
     * evaluates to {@code f(x_1, ..., x_n)}.
     */
    FunctionToFunctionMap split(int function, NatSet splitVariables);

    /**
     * {@link #split} for a function of the associated BDD: the result's {@link FunctionToFunctionMap#function()}
     * is a function of this diagram over just {@code splitVariables}, yielding for each of their assignments an
     * index into {@link FunctionToFunctionMap#codomain()} whose associated <em>BDD</em> function is what {@code
     * bddFunction} restricts to under that assignment. As with {@link #split}, the pieces are unprotected and
     * must be referenced before any further call.
     */
    FunctionToFunctionMap splitBdd(int bddFunction, NatSet splitVariables);

    /**
     * Like {@link #split}, but instead of handing back a {@link FunctionToFunctionMap} whose pieces the
     * caller must protect (reference) before making any further call, immediately relabels each residual
     * sub-function via {@code relabeler} (called at most once per distinct sub-function) and returns the
     * combined result directly - equivalent to {@code map(split(function, splitVariables).function(), v ->
     * relabeler.applyAsInt(split(function, splitVariables).functionFor(v)))}, except that no intermediate function is
     * ever exposed unprotected. The relabeler runs outside any operation, every residual referenced meanwhile, so it
     * may start operations of its own.
     */
    int splitRelabeled(int function, NatSet splitVariables, IntUnaryOperator relabeler);

    /**
     * One cube per value of {@code function} that {@code values} accepts: the decisions along a path of the function
     * to the value, so every assignment extending the cube is mapped to the value - the first such path, low before
     * high. A value the function does not take has none. One walk over the function, each node once.
     */
    default ValueCubes cubes(int function, IntPredicate values) {
        return PathCubes.cubes(this, function, values, Integer.MAX_VALUE);
    }

    /**
     * The values {@code function} takes, each with a cube of a path to it: {@link #valuesOf} with the paths it walks.
     */
    default ValueCubes cubes(int function) {
        return cubes(function, (int value) -> true);
    }

    /** Like {@link #cubes(int, IntPredicate)}, stopping once every value of {@code values} has its cube. */
    default ValueCubes cubes(int function, NatSet values) {
        return PathCubes.cubes(this, function, values::contains, values.size());
    }

    /**
     * A shortest path of {@code function} to a value {@code values} accepts, as a cube: of the paths fixing the fewest
     * variables, the first, low before high; empty if the function takes no such value.
     */
    default Optional<Cube> shortestCube(int function, IntPredicate values) {
        return PathCubes.shortestCube(this, function, values);
    }

    /** {@link #shortestCube} for each value of {@code values} the function takes. */
    default ValueCubes shortestCubes(int function, NatSet values) {
        return PathCubes.shortestCubes(this, function, values);
    }

    /** {@link #shortestCubes(int, NatSet)} for the values {@code values} accepts. */
    default ValueCubes shortestCubes(int function, IntPredicate values) {
        MutableNatSet accepted = valuesOf(function);
        accepted.removeIf(value -> !values.test(value));
        return PathCubes.shortestCubes(this, function, accepted);
    }

    /**
     * Creates the product of the given {@code functions}. Suppose each function is {@code f_i(x_1, ..., x_n}},
     * then their product is a function {@code f(x)} that yields {@code [f_1(x), ..., f_m(x)]}. The returned
     * function indexes the {@code values} map. The outputs of {@code f} do not need to be dense. The product of no
     * functions is the constant naming the empty tuple.
     */
    FunctionToFunctionsMap cartesianProduct(int[] functions);

    /**
     * The <em>residual product</em>: the boolean {@code operator} evaluated on {@code operands} as far as their values
     * decide it, together with the values it still depends on.
     *
     * <p>Each operand replaces one variable of the operator: {@code operands[v]} replaces the variable {@code v}, as
     * in {@link BinaryDecisionDiagram#compose}, and {@link #placeholder()} (or an index past the array) leaves
     * {@code v} as it is. The {@code valuation} reads each value an operand takes as true, false, or undecided for
     * the variable it replaces. At an assignment {@code x} of this diagram's variables, the result is the pair of
     *
     * <ul>
     *   <li>the <em>residual</em>: the operator with every variable fixed whose operand takes a value the valuation
     *       reads as true or false, and
     *   <li>the <em>essential values</em>: the values of the operands replacing a variable the residual still depends
     *       on.
     * </ul>
     *
     * An operand whose value is undecided is not essential if the residual no longer depends on its variable - the
     * decided operands already decide that part of the operator. A variable no operand replaces stays in every
     * residual. Where the decided operands decide the operator altogether, the residual is a constant and nothing is
     * essential. Computing the product visits an operand only while the residual depends on it, and assignments that
     * differ only in the values of operands that are not essential share one pair.
     *
     * <p><b>Example.</b> The operator {@code F = a & (b | c)} with operands {@code T_a, T_b, T_c}, and the valuation
     * reading {@code 0} as false, {@code 1} as true and every other value as undecided:
     *
     * <ul>
     *   <li>where {@code T_a(x) = 0}, the pair is {@code (false, {})}, and below such a point neither {@code T_b} nor
     *       {@code T_c} is visited;
     *   <li>where {@code T_a(x) = 7}, {@code T_b(x) = 1} and {@code T_c(x) = 9}, it is {@code (a, {a: 7})}:
     *       {@code b} decides the disjunction, so {@code T_c} is not essential although its value is undecided;
     *   <li>where {@code T_a(x) = 7}, {@code T_b(x) = 5} and {@code T_c(x) = 0}, it is {@code (a & b, {a: 7, b: 5})}.
     * </ul>
     *
     * <p><b>Formally</b>, with {@code nu} the valuation and {@code T_v = operands[v]}: at {@code x} the replacing
     * operands induce the partial assignment {@code rho(x) = { v -> nu(v, T_v(x)) }} over the replaced variables whose
     * value is not {@link PartialValuation.Truth#UNDECIDED}, and the result maps {@code x} to
     *
     * <pre>  ( R(x), (T_v(x) | v in E(x)) )    where  R(x) = F|rho(x)  and  E(x) = { v replaced | v in supp(R(x)) },</pre>
     *
     * {@code F|rho} being the restriction ({@link BinaryDecisionDiagram#restrict}). {@code F} agrees with {@code R(x)}
     * on every assignment extending {@code rho(x)}, and {@code R(x)} is free of the replaced variables exactly when
     * the decided ones determine {@code F} whatever the undecided ones are.
     *
     * <p><b>As a chain of operations</b>, it is the {@link #cartesianProduct} of the replacing operands with each
     * tuple {@code t} replaced by its residual and its essential values,
     *
     * <pre>  map(cartesianProduct(T), t -> ( restrict(F, rho(t)), t restricted to E(t) ))</pre>
     *
     * computed in one descent instead, which restricts {@code F} as soon as an operand reaches a value the valuation
     * decides and leaves an operand out as soon as it is not essential. Its boolean part is a vector composition
     * followed by a split: with the replaced variables outside the operands' supports, {@code x -> R(x)} is the
     * {@link #splitBdd} on the operands' variables of
     *
     * <pre>  compose(F, v -> [nu(v, T_v) = TRUE] | ([nu(v, T_v) = UNDECIDED] &amp; v))</pre>
     *
     * with {@code [nu(v, T_v) = c]} the {@link #mapBoolean} of the operand replacing {@code v} to the values the
     * valuation reads as {@code c}: a decided variable is substituted by its truth value, an undecided one by itself.
     *
     * <p><b>The operator</b> combines the operands' values, it never decides on this diagram's variables: its
     * variables only name operands. So it is a function of any binary decision diagram ({@link Operator}), which need
     * not share this diagram's variables or their order. A replaced variable must exist in the operator's diagram.
     *
     * <p><b>Where the parts of the result live.</b>
     *
     * <ul>
     *   <li>{@link ResidualProduct#function()} is a function of this diagram, over this diagram's variables (those the
     *       operands decide on); its values are not operand values but indices of the pairs, {@link
     *       ResidualProduct#codomain()}.
     *   <li>{@link ResidualProduct#residualFor} of an index is a function of the <em>operator's</em> diagram, over the
     *       operator's variables - unprotected, as with {@link #splitBdd}: it must be referenced, in the operator's
     *       diagram, before any further call.
     *   <li>{@link ResidualProduct#valuesFor} of an index are terminal values of this diagram (the operands' values),
     *       indexed by the operator's variables like {@code operands}: {@link ResidualProduct#ABSENT} for a variable
     *       whose operand is not essential or that no operand replaces.
     * </ul>
     */
    default ResidualProduct residualProduct(Operator operator, int[] operands, PartialValuation valuation) {
        return ResidualProducts.ofCartesianProduct(this, operator, operands, valuation);
    }

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

    /** A function whose values name functions - of this diagram or of the {@link #bdd() Bdd}, as the operation says. */
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
         * The co-domain of the meta-function: the values it takes.
         */
        NatSet codomain();
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
         * The co-domain of the meta-function: the values it takes.
         */
        NatSet codomain();
    }

    /**
     * A boolean function as the operator of a {@link #residualProduct}: the function together with the binary decision
     * diagram it belongs to, which may be another one than the residual product's own.
     */
    final class Operator {
        private final BinaryDecisionDiagram diagram;
        private final int function;

        private Operator(BinaryDecisionDiagram diagram, int function) {
            this.diagram = diagram;
            this.function = function;
        }

        /** {@code function} of {@code diagram}. */
        public static Operator of(BinaryDecisionDiagram diagram, int function) {
            assert diagram.isValidFunction(function);
            return new Operator(diagram, function);
        }

        /** The diagram the function belongs to. */
        public BinaryDecisionDiagram diagram() {
            return diagram;
        }

        /** The function. */
        public int function() {
            return function;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            // Diagram identity: a function means something only in its own diagram.
            return o instanceof Operator && diagram == ((Operator) o).diagram && function == ((Operator) o).function;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(diagram) + function;
        }

        @Override
        public String toString() {
            return function + "@" + diagram;
        }
    }

    /** The pairs of a {@link #residualProduct}, indexed by the values of its function. */
    interface ResidualProduct {
        /** The value {@link #valuesFor} holds for an operand that is not essential. */
        int ABSENT = -1;

        /** The function of this diagram, over its variables, whose values index the pairs. */
        int function();

        /** The residual of the pair, a function of the operator's diagram. */
        int residualFor(int value);

        /**
         * The values of the pair, indexed as the operands are: the value of the operand replacing a variable the
         * residual depends on, {@link #ABSENT} for every other index. Not to be modified.
         */
        int[] valuesFor(int value);

        /** The co-domain of the function. */
        NatSet codomain();
    }

    /** One cube per value of a function: the decisions along one of its paths to the value. */
    interface ValueCubes {
        /** The values with a cube. */
        NatSet codomain();

        /**
         * The cube of a path to {@code value}: every assignment extending it is mapped to the value. For a value of
         * {@link #codomain()}.
         */
        Cube cubeFor(int value);

        /** Hands each value with its cube to {@code action}, by ascending value. */
        void forEach(PathValueConsumer action);
    }

    @FunctionalInterface
    interface PathValueConsumer {
        void accept(Cube path, int value);
    }
}
