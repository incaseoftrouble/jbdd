/*
 * Copyright (C) 2017 (See AUTHORS)
 *
 * This file is part of JBDD.
 *
 * JBDD is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JBDD is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with JBDD.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.tum.in.jbdd;

import java.util.BitSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * This interface contains various BDD operations.
 *
 * <p>Note that for the sake of performance, most required properties of the arguments are only
 * checked though {@code assert} statements. With disabled assertions, undefined behaviour might
 * occur with invalid arguments. Especially, the BDD may appear to be in a working state for a long
 * time after an invalid call.</p>
 */
public interface Bdd {

  /**
   * Returns the node representing <tt>true</tt>.
   */
  int getTrueNode();

  /**
   * Returns the node representing <tt>false</tt>.
   */
  int getFalseNode();

  /**
   * Returns the number of variables in this BDD.
   *
   * @return The number of variables.
   */
  int numberOfVariables();


  int getHigh(int node);

  int getLow(int node);

  /**
   * Gets the variable of the given {@code node}.
   */
  int getVariable(int node);


  /**
   * Returns the node which represents the variable with given {@code variableNumber}. The variable
   * must already have been created.
   *
   * @param variableNumber
   *     The number of the requested variable.
   *
   * @return The corresponding node.
   */
  int getVariableNode(int variableNumber);

  /**
   * Creates a new variable and returns the node representing it. The implementation guarantees that
   * variables are always allocated sequentially starting from 0, i.e. {@code
   * getVariable(createVariable()) == numberOfVariables() - 1}.
   *
   * @return The node representing the new variable.
   */
  int createVariable();

  /**
   * Creates {@code count} many variables and returns their respective nodes. The first created
   * variable is at first position of the array.
   *
   * @throws IllegalArgumentException if count is not positive.
   */
  default int[] createVariables(int count) {
    if (count <= 0) {
      throw new IllegalArgumentException("Count must be positive");
    }
    int[] array = new int[count];
    for (int i = 0; i < count; i++) {
      array[i] = createVariable();
    }
    return array;
  }


  /**
   * Determines whether the given {@code node} represents a constant, i.e. TRUE or FALSE.
   *
   * @param node
   *     The node to be checked.
   *
   * @return If the {@code node} represents a constant.
   */
  boolean isNodeRoot(int node);

  /**
   * Determines whether the given {@code node} represents a variable.
   *
   * @param node
   *     The node to be checked.
   *
   * @return If the {@code node} represents a variable.
   */
  boolean isVariable(int node);

  /**
   * Determines whether the given {@code node} represents a negated variable.
   *
   * @param node
   *     The node to be checked.
   *
   * @return If the {@code node} represents a negated variable.
   */
  boolean isVariableNegated(int node);

  /**
   * Determines whether the given {@code node} represents a variable or it's negation.
   *
   * @param node
   *     The node to be checked.
   *
   * @return If the {@code node} represents a variable.
   */
  boolean isVariableOrNegated(int node);

  /**
   * Increases the reference count of the specified {@code node}.
   *
   * @param node
   *     The to be referenced node
   *
   * @return The given node, to be used for chaining.
   */
  int reference(int node);

  /**
   * Decreases the reference count of the specified {@code node}.
   *
   * @param node
   *     The to be referenced node
   *
   * @return The given node, to be used for chaining.
   */
  int dereference(int node);

  /**
   * Returns the reference count of the given node or {@literal -1} if this number can't be
   * accurately determined (e.g., when a node is saturated).
   */
  int getReferenceCount(int node);


  /**
   * Checks whether the given {@code node} evaluates to <tt>true</tt> under the given variable
   * assignment specified by {@code assignment}.
   *
   * @param node
   *     The node to evaluate.
   * @param assignment
   *     The variable assignment.
   *
   * @return The truth value of the node under the given assignment.
   */
  boolean evaluate(int node, BitSet assignment);

  /**
   * Returns any satisfying assignment.
   *
   * @throws IllegalArgumentException if the given {@code node} is {@literal false}.
   */
  BitSet getSatisfyingAssignment(int node);

  /**
   * Counts the number of satisfying assignments for the function represented by this node.
   *
   * <p><b>Warning:</b> Floating-point overflow easily possible for complex functions!</p>
   */
  double countSatisfyingAssignments(int node);

  /**
   * Iteratively computes all (minimal) solutions of the function represented by {@code node} and
   * executes the given {@code action} with it. The returned solutions are all bit sets representing
   * a path from node to <tt>true</tt> in the graph induced by the BDD structure. Furthermore, the
   * solutions are generated in lexicographic ascending order.
   *
   * <p><b>Note:</b> The passed bit set is modified in-place. If all solutions should be gathered
   * into a set or similar, they have to be cloned after each call to the consumer.</p>
   *
   * @param node
   *     The node whose solutions should be computed.
   * @param action
   *     The action to be performed on these solutions.
   */
  default void forEachMinimalSolution(int node, Consumer<BitSet> action) {
    forEachMinimalSolution(node, (path, pathSupport) -> action.accept(path));
  }

  /**
   * Iteratively computes all (minimal) solutions of the function represented by {@code node} and
   * executes the given {@code action} with it as in {@link #forEachMinimalSolution(int, Consumer)}.
   * Additionally, the action will be provided the set of relevant variables for each solution.
   *
   * <p><b>Note:</b> The passed bit sets are modified in-place. If all solutions should be gathered
   * into a set or similar, they have to be cloned after each call to the consumer.</p>
   *
   * @param node
   *     The node whose solutions should be computed.
   * @param action
   *     The action to be performed on these solutions.
   */
  default void forEachMinimalSolution(int node, BiConsumer<BitSet, BitSet> action) {
    forEachNonEmptyPath(node, numberOfVariables(), action);
  }

  /**
   * Iteratively computes all partial assignments (up to the {@code highestVariable}) such that the
   * node reached after inserting this partial assignment is not <tt>false</tt>. Additionally, the
   * action will be provided the set of relevant variables for each partial solution.
   *
   * <p><b>Note:</b> The passed bit sets are modified in-place. If all solutions should be gathered
   * into a set or similar, they have to be cloned after each call to the consumer.</p>
   */
  void forEachNonEmptyPath(int node, int highestVariable, BiConsumer<BitSet, BitSet> action);

  /**
   * Computes the <b>support</b> of the function represented by the given {@code node}. The support
   * of a function are all variables which have an influence on its value.
   *
   * @param node
   *     The node whose support should be computed.
   *
   * @return A bit set with bit {@code i} is set iff the {@code i}-th variable is in the support.
   */
  default BitSet support(int node) {
    return support(node, numberOfVariables());
  }

  /**
   * Computes the <b>support</b> of the function represented by the given {@code node}. The support
   * of a function are all variables which have an influence on its value.
   *
   * @param node
   *     The node whose support should be computed.
   *
   * @return A bit set with bit {@code i} is set iff the {@code i}-th variable is in the support.
   */
  default BitSet support(int node, int highestVariable) {
    BitSet bitSet = new BitSet(highestVariable);
    support(node, bitSet, highestVariable);
    return bitSet;
  }

  /**
   * Computes the <b>support</b> of the given {@code node} and writes it in the {@code bitSet}.
   * Note that the {@code bitSet} is not cleared, the support variables are added to the set.
   *
   * @param node
   *     The node whose support should be computed.
   * @param bitSet
   *     The BitSet used to store the result.
   *
   * @see #support(int)
   */
  default void support(int node, BitSet bitSet) {
    support(node, bitSet, numberOfVariables());
  }

  /**
   * Computes the <b>support</b> of the given {@code node} and writes it in the {@code bitSet}.
   * Note that the {@code bitSet} is not cleared, the support variables are added to the set.
   *
   * @param node
   *     The node whose support should be computed.
   * @param bitSet
   *     The BitSet used to store the result.
   *
   * @see #support(int)
   */
  void support(int node, BitSet bitSet, int highestVariable);


  /**
   * Creates the conjunction of all variables specified by {@code cubeVariables}.
   *
   * @param cubeVariables
   *     The variables to build the cube.
   *
   * @return The conjunction of specified variables.
   */
  int cube(BitSet cubeVariables);


  /**
   * Auxiliary function useful for updating node variables. It dereferences the inputs and
   * references {@code result}. This is useful for assignments like {@code node = f(in1, in2)} where
   * <tt>f</tt> is some operation on this BDD and both <tt>in1</tt> and <tt>in2</tt> are temporary
   * nodes or not used anymore. In this case, calling {@code node = consume(bdd, node(in1, in2),
   * in1, in2)} updates the references as needed.
   *
   * <p>This would be more concise when implemented using method references, but these are
   * comparatively heavyweight.</p>
   *
   * @param result
   *     The result of some operation on this BDD involving inputNode1 and inputNode2
   * @param inputNode1
   *     First input of the operation.
   * @param inputNode2
   *     Second input of the operation.
   *
   * @return The given {@code result}.
   */
  default int consume(int result, int inputNode1, int inputNode2) {
    reference(result);
    dereference(inputNode1);
    dereference(inputNode2);
    return result;
  }

  /**
   * Auxiliary function useful for updating node variables. It dereferences {@code inputNode} and
   * references {@code result}. This is useful for assignments like {@code node = f(node, ...)}
   * where <tt>f</tt> is some operation on the BDD. In this case, calling {@code node =
   * updateWith(bdd, f(node, ...), inputNode)} updates the references as needed and leaves the other
   * parameters untouched.
   *
   * <p>This would be more concise when implemented using method references, but these are
   * comparatively heavyweight.</p>
   *
   * @param result
   *     The result of some operation on this BDD.
   * @param inputNode
   *     The node which gets assigned the value of the result.
   *
   * @return The given {@code result}.
   */
  default int updateWith(int result, int inputNode) {
    reference(result);
    dereference(inputNode);
    return result;
  }


  /**
   * Constructs the node representing <tt>{@code node1} AND {@code node2}</tt>.
   */
  int and(int node1, int node2);

  /**
   * Constructs the node representing the <i>composition</i> of the function represented by {@code
   * node} with the functions represented by the entries of {@code variableNodes}. More formally, if
   * <tt>f(x_1, x_2, ..., x_n)</tt> is the function represented by {@code node}, this method returns
   * <tt>f(f_1(x_1, ..., x_n), ..., f_n(x_1, ..., x_n))</tt>, where <tt>f_i = {@code
   * variableNodes[i]}</tt> <p> The {@code variableNodes} array can contain less than <tt>n</tt>
   * entries, then only the first variables are replaced. Furthermore, -1 can be used as an entry to
   * denote "don't replace this variable" (which semantically is the same as saying "replace this
   * variable by itself"). Note that after the call the -1 entries will be replaced by the actual
   * corresponding variable nodes. </p>
   *
   * @param node
   *     The node to be composed.
   * @param variableNodes
   *     The nodes of the functions with which each variable should be replaced.
   *
   * @return The node representing the composed function.
   */
  int compose(int node, int[] variableNodes);

  /**
   * Constructs the node representing <tt>{@code node1} EQUIVALENT {@code node2}</tt>.
   */
  int equivalence(int node1, int node2);

  /**
   * Constructs the node representing the function obtained by existential quantification of {@code
   * node} with all variables specified by {@code quantifiedVariables}. Formally, let <tt>f(x_1,
   * ..., x_m)</tt> be the function specified by {@code node} and <tt>x_1, ..., x_m</tt> all
   * variables for which {@code quantifiedVariables} is set. This method then constructs <tt>E x_1 E
   * x_2 ... E x_n f(x_1, ..., x_m)</tt>.
   *
   * @param node
   *     The node representing the basis of the quantification.
   * @param quantifiedVariables
   *     The variables which should be quantified over.
   *
   * @return The node representing the quantification.
   */
  int exists(int node, BitSet quantifiedVariables);

  /**
   * Constructs the node representing <tt>IF {@code ifNode} THEN {@code thenNode} ELSE {@code
   * elseNode}</tt>.
   */
  int ifThenElse(int ifNode, int thenNode, int elseNode);

  /**
   * Constructs the node representing <tt>{@code node1} IMPLIES {@code node2}</tt>.
   */
  int implication(int node1, int node2);

  /**
   * Checks whether the given {@code node1} implies {@code node2}, i.e. if every valuation under
   * which the function represented by {@code node1} evaluates to true also evaluates to true on
   * {@code node2}. This is equivalent to checking if {@link #implication(int, int)} with {@code
   * node1} and {@code node2} as parameters is equal to {@link #getTrueNode()} and equal to checking
   * whether {@code node1} equals <tt>{@code node1} OR {@code node2}</tt>, but faster.
   *
   * <p><b>Note:</b> As many operations are cached, it may be even faster to use an alternative
   * logical representation of implication depending on how the BDD is used before this invocation.
   * E.g. if <tt>{@code node1} OR {@code 2}</tt> has been computed already, checking if <tt>{@code
   * node1} == {@code node1} OR {@code node2}</tt> is a constant time operation.</p>
   *
   * @param node1
   *     The node representing the assumption.
   * @param node2
   *     The node representing the consequence.
   *
   * @return Whether <tt>{@code node1} IMPLIES {@code node2}</tt> is a tautology.
   */
  boolean implies(int node1, int node2);

  /**
   * Constructs the node representing <tt>NOT {@code node}</tt>.
   *
   * @param node
   *     The node to be negated.
   *
   * @return The negation of the given BDD.
   */
  int not(int node);

  /**
   * Constructs the node representing <tt>{@code node1} NAND {@code node2}</tt>.
   */
  int notAnd(int node1, int node2);

  /**
   * Constructs the node representing <tt>{@code node1} OR {@code node2}</tt>.
   */
  int or(int node1, int node2);

  /**
   * Computes the restriction of the given {@code node}, where all variables specified by {@code
   * restrictedVariables} are replaced by the value given in {@code restrictedVariableValues}.
   * Formally, if {@code node} represents <tt>f(x_1, ..., x_n)</tt>, this method computes the node
   * representing <tt>f(x_1, ..., x_{i_1-1}, c_1, x_{i_1+1}, ..., x_{i_2-1}, c_2, x_{i_2+1}, ...,
   * x_n</tt>, where <tt>i_k</tt> are the elements of the {@code restrictedVariables} set and
   * <tt>c_k := restrictedVariableValues.get(i_k)</tt>. This is semantically equivalent to calling
   * compose where all specified variables are replaced by <tt>true</tt> or <tt>false</tt>, but
   * slightly faster.
   *
   * @param node
   *     The node to be restricted.
   * @param restrictedVariables
   *     The variables used in the restriction.
   * @param restrictedVariableValues
   *     The values of the restricted variables.
   *
   * @return The restricted node.
   *
   * @see #compose(int, int[])
   */
  int restrict(int node, BitSet restrictedVariables, BitSet restrictedVariableValues);

  /**
   * Constructs the node representing <tt>{@code node1} XOR {@code node2}</tt>.
   */
  int xor(int node1, int node2);


  /**
   * A wrapper class to guard some node in an area where exceptions can occur. It increases the
   * reference count of the given node and decreases it when it's closed.
   *
   * <p>Note: This should seldom be used, as the overhead of object construction and deconstruction
   * is noticeable.</p>
   */
  final class ReferenceGuard implements AutoCloseable {
    private final Bdd bdd;
    private final int node;

    public ReferenceGuard(int node, Bdd bdd) {
      this.node = bdd.reference(node);
      this.bdd = bdd;
    }

    @Override
    public void close() {
      bdd.dereference(node);
    }

    public Bdd getBdd() {
      return bdd;
    }

    public int getNode() {
      return node;
    }
  }
}
