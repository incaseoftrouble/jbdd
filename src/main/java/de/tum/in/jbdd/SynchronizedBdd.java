package de.tum.in.jbdd;

import java.util.BitSet;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * Synchronizes a given Bdd using a {@link java.util.concurrent.locks.ReadWriteLock}.
 */
public class SynchronizedBdd implements Bdd {
  private final Bdd delegate;
  private final ReadWriteLock lock;
  private final Lock readLock;
  private final Lock writeLock;

  private SynchronizedBdd(Bdd delegate, ReadWriteLock lock) {
    this.delegate = delegate;
    this.lock = lock;
    writeLock = lock.writeLock();
    readLock = lock.readLock();
  }

  public static SynchronizedBdd create(Bdd bdd) {
    if (bdd instanceof SynchronizedBdd) {
      return (SynchronizedBdd) bdd;
    }
    ReadWriteLock lock = new ReentrantReadWriteLock();
    return new SynchronizedBdd(bdd, lock);
  }

  @Override
  public int and(int node1, int node2) {
    writeLock.lock();
    int result = delegate.and(node1, node2);
    writeLock.unlock();
    return result;
  }

  @Override
  public int compose(int node, int[] variableNodes) {
    writeLock.lock();
    int result = delegate.compose(node, variableNodes);
    writeLock.unlock();
    return result;
  }

  @Override
  public int consume(int result, int inputNode1, int inputNode2) {
    writeLock.lock();
    int consumed = delegate.consume(result, inputNode1, inputNode2);
    writeLock.unlock();
    return consumed;
  }

  @Override
  public double countSatisfyingAssignments(int node) {
    readLock.lock();
    double result = delegate.countSatisfyingAssignments(node);
    readLock.unlock();
    return result;
  }

  @Override
  public int createVariable() {
    writeLock.lock();
    int result = delegate.createVariable();
    writeLock.unlock();
    return result;
  }

  @Override
  public int cube(BitSet cubeVariables) {
    writeLock.lock();
    int result = delegate.cube(cubeVariables);
    writeLock.unlock();
    return result;
  }

  @Override
  public int dereference(int node) {
    writeLock.lock();
    int result = delegate.dereference(node);
    writeLock.unlock();
    return result;
  }

  @Override
  public int equivalence(int node1, int node2) {
    writeLock.lock();
    int result = delegate.equivalence(node1, node2);
    writeLock.unlock();
    return result;
  }

  @Override
  public boolean evaluate(int node, BitSet assignment) {
    readLock.lock();
    boolean result = delegate.evaluate(node, assignment);
    readLock.unlock();
    return result;
  }

  @Override
  public int exists(int node, BitSet quantifiedVariables) {
    writeLock.lock();
    int result = delegate.exists(node, quantifiedVariables);
    writeLock.unlock();
    return result;
  }

  @Override
  public void forEachMinimalSolution(int node, Consumer<BitSet> action) {
    readLock.lock();
    delegate.forEachMinimalSolution(node, action);
    readLock.unlock();
  }

  @Override
  public int getFalseNode() {
    return delegate.getFalseNode();
  }

  @Override
  public int getHigh(int node) {
    readLock.lock();
    int result = delegate.getHigh(node);
    readLock.unlock();
    return result;
  }

  @Override
  public int getLow(int node) {
    readLock.lock();
    int result = delegate.getLow(node);
    readLock.unlock();
    return result;
  }

  @Override
  public int getTrueNode() {
    return delegate.getTrueNode();
  }

  @Override
  public int getVariable(int node) {
    readLock.lock();
    int result = delegate.getVariable(node);
    readLock.unlock();
    return result;
  }

  @Override
  public int getVariableNode(int variableNumber) {
    readLock.lock();
    int result = delegate.getVariableNode(variableNumber);
    readLock.unlock();
    return result;
  }

  @Override
  public int ifThenElse(int ifNode, int thenNode, int elseNode) {
    writeLock.lock();
    int result = delegate.ifThenElse(ifNode, thenNode, elseNode);
    writeLock.unlock();
    return result;
  }

  @Override
  public int implication(int node1, int node2) {
    writeLock.lock();
    int result = delegate.implication(node1, node2);
    writeLock.unlock();
    return result;
  }

  @Override
  public boolean implies(int node1, int node2) {
    readLock.lock();
    boolean result = delegate.implies(node1, node2);
    readLock.unlock();
    return result;
  }

  @Override
  public boolean isNodeRoot(int node) {
    return delegate.isNodeRoot(node);
  }

  @Override
  public boolean isVariable(int node) {
    readLock.lock();
    boolean result = delegate.isVariable(node);
    readLock.unlock();
    return result;
  }

  @Override
  public boolean isVariableNegated(int node) {
    readLock.lock();
    boolean result = delegate.isVariableNegated(node);
    readLock.unlock();
    return result;
  }

  @Override
  public boolean isVariableOrNegated(int node) {
    readLock.lock();
    boolean result = delegate.isVariableOrNegated(node);
    readLock.unlock();
    return result;
  }

  @Override
  public int not(int node) {
    writeLock.lock();
    int result = delegate.not(node);
    writeLock.unlock();
    return result;
  }

  @Override
  public int notAnd(int node1, int node2) {
    writeLock.lock();
    int result = delegate.notAnd(node1, node2);
    writeLock.unlock();
    return result;
  }

  @Override
  public int numberOfVariables() {
    readLock.lock();
    int result = delegate.numberOfVariables();
    readLock.unlock();
    return result;
  }

  @Override
  public int or(int node1, int node2) {
    writeLock.lock();
    int result = delegate.or(node1, node2);
    writeLock.unlock();
    return result;
  }

  public void readLock() {
    readLock.lock();
  }

  public void readUnlock() {
    readLock.unlock();
  }

  @Override
  public int reference(int node) {
    writeLock.lock();
    int result = delegate.reference(node);
    writeLock.unlock();
    return result;
  }

  @Override
  public int restrict(int node, BitSet restrictedVariables,
      BitSet restrictedVariableValues) {
    writeLock.lock();
    int result = delegate.restrict(node, restrictedVariables, restrictedVariableValues);
    writeLock.unlock();
    return result;
  }

  @Override
  public BitSet support(int node) {
    readLock.lock();
    BitSet result = delegate.support(node);
    readLock.unlock();
    return result;
  }

  @Override
  public BitSet support(int node, int highestVariable) {
    readLock.lock();
    BitSet result = delegate.support(node, highestVariable);
    readLock.unlock();
    return result;
  }

  @Override
  public void support(int node, BitSet bitSet) {
    readLock.lock();
    delegate.support(node, bitSet);
    readLock.unlock();
  }

  @Override
  public void support(int node, BitSet bitSet, int highestVariable) {
    readLock.lock();
    delegate.support(node, bitSet, highestVariable);
    readLock.unlock();
  }

  @Override
  public int updateWith(int result, int inputNode) {
    writeLock.lock();
    int updated = delegate.updateWith(result, inputNode);
    writeLock.unlock();
    return updated;
  }

  public void writeLock() {
    writeLock.lock();
  }

  public void writeUnlock() {
    writeLock.unlock();
  }

  @Override
  public int xor(int node1, int node2) {
    writeLock.lock();
    int result = delegate.xor(node1, node2);
    writeLock.unlock();
    return result;
  }
}
