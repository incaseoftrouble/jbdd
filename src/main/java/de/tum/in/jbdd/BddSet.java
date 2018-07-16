package de.tum.in.jbdd;

import java.math.BigInteger;
import java.util.AbstractSet;
import java.util.BitSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A set abstraction of BDD nodes as a set of {@link BitSet bitsets}.
 *
 * <strong>Note</strong>: Modification is not supported due to the manual memory management
 * required for BDDs. Instead, these sets should be used as a view. By using a
 * {@link CanonicalGcManager garbage manager} and factory patterns, this issue can be overcome.
 */
public class BddSet extends AbstractSet<BitSet> implements CanonicalGcManager.BddWrapper {
  private final Bdd bdd;
  private final int node;

  public BddSet(Bdd bdd, int node) {
    this.bdd = bdd;
    this.node = node;
  }

  @Override
  public int node() {
    return node;
  }

  public Bdd bdd() {
    return bdd;
  }


  @Override
  public boolean isEmpty() {
    return node == bdd.falseNode();
  }

  public boolean isUniverse() {
    return node == bdd.trueNode();
  }


  @Override
  public boolean contains(Object o) {
    return o instanceof BitSet && bdd.evaluate(node, (BitSet) o);
  }

  @Override
  public boolean containsAll(Collection<?> collection) {
    if (collection instanceof BddSet) {
      BddSet other = (BddSet) collection;
      //noinspection ObjectEquality
      if (bdd == other.bdd) {
        return bdd.implies(other.node, node);
      }
    }
    return super.containsAll(collection);
  }


  @Override
  public Iterator<BitSet> iterator() {
    return bdd.solutionIterator(node);
  }

  @Override
  public void forEach(Consumer<? super BitSet> consumer) {
    bdd.forEachSolution(node, consumer);
  }


  @Override
  public int size() {
    return sizeBig().intValueExact();
  }

  public BigInteger sizeBig() {
    return bdd.countSatisfyingAssignments(node);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return false;
    }
    if (!(o instanceof Set)) {
      return false;
    }

    Set<?> other = (Set<?>) o;
    BigInteger size = bdd.countSatisfyingAssignments(node);

    if (o instanceof BddSet) {
      BddSet otherBdd = (BddSet) o;
      BigInteger otherSize = otherBdd.bdd.countSatisfyingAssignments(otherBdd.node);
      if (!size.equals(otherSize)) {
        return false;
      }
      //noinspection ObjectEquality
      if (bdd == otherBdd.bdd) {
        return node == otherBdd.node;
      }
    } else {
      if (size.intValueExact() != other.size()) {
        return false;
      }
    }

    //noinspection ObjectInstantiationInEqualsHashCode
    for (BitSet bitSet : this) {
      if (!other.contains(bitSet)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public int hashCode() {
    return bdd.hashCode() * 31 + HashUtil.hash(node);
  }


  @Override
  public boolean add(BitSet bitSet) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean remove(Object o) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean addAll(Collection<? extends BitSet> collection) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean retainAll(Collection<?> collection) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void clear() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean removeIf(Predicate<? super BitSet> predicate) {
    throw new UnsupportedOperationException();
  }
}
