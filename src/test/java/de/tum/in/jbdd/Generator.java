package de.tum.in.jbdd;

import static de.tum.in.jbdd.HashUtil.fnv1aHash;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

@SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
public final class Generator {
  private static final int MAX_FAILED_UPDATE = 10;
  private static final Logger logger = Logger.getLogger(Generator.class.getName());

  private Generator() {
    // empty
  }

  public static <T extends Bdd> Info<T> fill(T bdd, int seed, int variableCount,
      int treeDepth, int treeWidth, int unaryCount, int binaryCount, int ternaryCount) {
    // It is important that generation of data is ordered for the tests to be reproducible.

    logger.log(Level.INFO, "Filling BDD: {0}/{1}, {2} unary, {3} binary, {4} ternary",
        new Object[] {treeDepth, treeWidth, unaryCount, binaryCount, ternaryCount});

    Random filter = new Random(seed);
    List<Integer> variableList = new ArrayList<>(variableCount);
    for (int i = 0; i < variableCount; i++) {
      variableList.add(bdd.createVariable());
    }

    Map<Integer, SyntaxTree> syntaxTreeMap = new LinkedHashMap<>();
    syntaxTreeMap.put(bdd.falseNode(), SyntaxTree.constant(false));
    syntaxTreeMap.put(bdd.trueNode(), SyntaxTree.constant(true));
    for (int i = 0; i < variableList.size(); i++) {
      SyntaxTree literal = SyntaxTree.literal(i);
      syntaxTreeMap.put(variableList.get(i), literal);
      syntaxTreeMap.put(bdd.reference(bdd.not(variableList.get(i))), SyntaxTree.not(literal));
    }

    Map<SyntaxTree, Integer> treeToNodeMap = new HashMap<>();
    Set<SyntaxTree> previousDepthNodes = new LinkedHashSet<>();

    for (Map.Entry<Integer, SyntaxTree> treeEntry : syntaxTreeMap.entrySet()) {
      treeToNodeMap.put(treeEntry.getValue(), treeEntry.getKey());
      previousDepthNodes.add(treeEntry.getValue());
    }
    logger.log(Level.FINER, "Generating syntax trees from {0} base expressions",
        previousDepthNodes.size());
    List<SyntaxTree> candidates = new ArrayList<>();

    for (int depth = 1; depth < treeDepth; depth++) {
      logger.log(Level.FINEST, "Building tree depth {0}", depth);

      candidates.addAll(previousDepthNodes);
      previousDepthNodes.clear();
      Collections.shuffle(candidates, filter);
      List<SyntaxTree> leftCandidates = ImmutableList.copyOf(candidates);
      Collections.shuffle(candidates, filter);
      List<SyntaxTree> rightCandidates = ImmutableList.copyOf(candidates);
      candidates.clear();

      for (SyntaxTree left : leftCandidates) {
        for (SyntaxTree right : rightCandidates) {
          Map<Integer, SyntaxTree> created = new HashMap<>();
          if (filter.nextBoolean()) {
            created.put(bdd.reference(bdd.and(treeToNodeMap.get(left),
                treeToNodeMap.get(right))), SyntaxTree.and(left, right));
          }
          if (filter.nextBoolean()) {
            created.put(bdd.reference(bdd.or(treeToNodeMap.get(left),
                treeToNodeMap.get(right))), SyntaxTree.or(left, right));
          }
          if (filter.nextBoolean()) {
            created.put(bdd.reference(bdd.xor(treeToNodeMap.get(left),
                treeToNodeMap.get(right))), SyntaxTree.xor(left, right));
          }
          if (filter.nextBoolean()) {
            created.put(bdd.reference(bdd.implication(treeToNodeMap.get(left),
                treeToNodeMap.get(right))), SyntaxTree.implication(left, right));
          }
          if (filter.nextBoolean()) {
            created.put(bdd.reference(bdd.equivalence(treeToNodeMap.get(left),
                treeToNodeMap.get(right))), SyntaxTree.equivalence(left, right));
          }
          if (filter.nextBoolean()) {
            created.put(bdd.reference(bdd.not(treeToNodeMap.get(left))),
                SyntaxTree.not(left));
          }
          if (filter.nextBoolean()) {
            created.put(bdd.reference(bdd.not(treeToNodeMap.get(right))),
                SyntaxTree.not(right));
          }
          created.forEach((node, tree) -> {
            if (syntaxTreeMap.containsKey(node)) {
              bdd.dereference(node);
              return;
            }
            treeToNodeMap.put(tree, node);
            syntaxTreeMap.put(node, tree);
            previousDepthNodes.add(tree);
          });
          if (treeToNodeMap.size() >= treeWidth * depth) {
            break;
          }
        }
        if (treeToNodeMap.size() >= treeWidth * depth) {
          break;
        }
      }

      int failedUpdates = 0;
      Iterator<SyntaxTree> cycle = Iterators.cycle(treeToNodeMap.keySet());
      while (previousDepthNodes.size() < treeWidth && failedUpdates < MAX_FAILED_UPDATE) {
        SyntaxTree next = cycle.next();
        if (filter.nextBoolean()) {
          continue;
        }
        if (previousDepthNodes.add(next)) {
          failedUpdates = 0;
        } else {
          failedUpdates += 1;
        }
      }
    }
    assertThat(bdd.numberOfVariables(), is(variableCount));

    logger.log(Level.FINE, "Building data points");
    List<Integer> availableNodes = new ArrayList<>(syntaxTreeMap.keySet());

    int unaryFailedUpdates = 0;
    Collection<UnaryDataPoint<T>> unaryDataPointSet = new LinkedHashSet<>();
    while (unaryDataPointSet.size() < unaryCount && unaryFailedUpdates < MAX_FAILED_UPDATE) {
      int node = availableNodes.get(filter.nextInt(availableNodes.size()));
      UnaryDataPoint<T> dataPoint = new UnaryDataPoint<>(bdd, node, syntaxTreeMap.get(node));
      if (unaryDataPointSet.add(dataPoint)) {
        unaryFailedUpdates = 0;
      } else {
        unaryFailedUpdates += 1;
      }
    }

    int binaryFailedUpdates = 0;
    Collection<BinaryDataPoint<T>> binaryDataPointSet = new LinkedHashSet<>();
    while (binaryDataPointSet.size() < binaryCount && binaryFailedUpdates < MAX_FAILED_UPDATE) {
      int left = availableNodes.get(filter.nextInt(availableNodes.size()));
      int right = availableNodes.get(filter.nextInt(availableNodes.size()));
      BinaryDataPoint<T> dataPoint = new BinaryDataPoint<>(bdd, left, right,
          syntaxTreeMap.get(left), syntaxTreeMap.get(right));
      if (binaryDataPointSet.add(dataPoint)) {
        binaryFailedUpdates = 0;
      } else {
        binaryFailedUpdates += 1;
      }
    }

    int ternaryFailedUpdates = 0;
    Collection<TernaryDataPoint<T>> ternaryDataPointSet = new LinkedHashSet<>();
    while (ternaryDataPointSet.size() < ternaryCount && ternaryFailedUpdates < MAX_FAILED_UPDATE) {
      int first = availableNodes.get(filter.nextInt(availableNodes.size()));
      int second = availableNodes.get(filter.nextInt(availableNodes.size()));
      int third = availableNodes.get(filter.nextInt(availableNodes.size()));
      TernaryDataPoint<T> dataPoint = new TernaryDataPoint<>(bdd, first, second, third,
          syntaxTreeMap.get(first), syntaxTreeMap.get(second), syntaxTreeMap.get(third));
      if (ternaryDataPointSet.add(dataPoint)) {
        ternaryFailedUpdates = 0;
      } else {
        ternaryFailedUpdates += 1;
      }
    }

    logger.log(Level.FINE, "Filled Bdd");
    return new Info<>(bdd, unaryDataPointSet, binaryDataPointSet, ternaryDataPointSet,
        syntaxTreeMap, variableList);
  }

  public static final class Info<T extends Bdd> {
    public final T bdd;
    public final ImmutableSet<UnaryDataPoint<T>> unaryDataPoints;
    public final ImmutableSet<BinaryDataPoint<T>> binaryDataPoints;
    public final ImmutableSet<TernaryDataPoint<T>> ternaryDataPoints;
    public final Map<Integer, SyntaxTree> syntaxTreeMap;
    public final List<Integer> variableList;

    Info(T bdd, Collection<UnaryDataPoint<T>> unaryDataPoints,
        Collection<BinaryDataPoint<T>> binaryDataPoints,
        Collection<TernaryDataPoint<T>> ternaryDataPoints,
        Map<Integer, SyntaxTree> syntaxTreeMap, List<Integer> variableList) {
      this.bdd = bdd;
      this.unaryDataPoints = ImmutableSet.copyOf(unaryDataPoints);
      this.binaryDataPoints = ImmutableSet.copyOf(binaryDataPoints);
      this.ternaryDataPoints = ImmutableSet.copyOf(ternaryDataPoints);
      this.syntaxTreeMap = ImmutableMap.copyOf(syntaxTreeMap);
      this.variableList = ImmutableList.copyOf(variableList);
    }
  }

  static final class UnaryDataPoint<T extends Bdd> {
    final T bdd;
    final int node;
    final SyntaxTree tree;

    UnaryDataPoint(T bdd, int node, SyntaxTree tree) {
      this.bdd = bdd;
      this.node = node;
      this.tree = tree;
    }

    @Override
    public boolean equals(Object object) {
      if (this == object) {
        return true;
      }
      if (!(object instanceof UnaryDataPoint)) {
        return false;
      }
      UnaryDataPoint<?> other = (UnaryDataPoint<?>) object;
      return Objects.equals(bdd, other.bdd) && node == other.node;
    }

    @Override
    public int hashCode() {
      return fnv1aHash(node) * bdd.hashCode();
    }

    @Override
    public String toString() {
      return String.format("%s: %s", bdd.getClass().getSimpleName(), tree);
    }
  }

  static final class BinaryDataPoint<T extends Bdd> {
    final T bdd;
    final int left;
    final SyntaxTree leftTree;
    final int right;
    final SyntaxTree rightTree;

    BinaryDataPoint(T bdd, int left, int right, SyntaxTree leftTree, SyntaxTree rightTree) {
      this.bdd = bdd;
      this.left = left;
      this.right = right;
      this.leftTree = leftTree;
      this.rightTree = rightTree;
    }

    @Override
    public boolean equals(Object object) {
      if (this == object) {
        return true;
      }
      if (!(object instanceof BinaryDataPoint)) {
        return false;
      }
      BinaryDataPoint<?> other = (BinaryDataPoint<?>) object;
      return Objects.equals(bdd, other.bdd) && left == other.left && right == other.right;
    }

    @Override
    public int hashCode() {
      return fnv1aHash(left) * fnv1aHash(right) * bdd.hashCode();
    }

    @Override
    public String toString() {
      return String.format("%s: %s ### %s", bdd.getClass().getSimpleName(), leftTree, rightTree);
    }
  }

  static final class TernaryDataPoint<T extends Bdd> {
    final T bdd;
    final int first;
    final SyntaxTree firstTree;
    final int second;
    final SyntaxTree secondTree;
    final int third;
    final SyntaxTree thirdTree;

    TernaryDataPoint(T bdd, int first, int second, int third,
        SyntaxTree firstTree, SyntaxTree secondTree, SyntaxTree thirdTree) {
      this.bdd = bdd;
      this.first = first;
      this.second = second;
      this.third = third;
      this.firstTree = firstTree;
      this.secondTree = secondTree;
      this.thirdTree = thirdTree;
    }

    @Override
    public boolean equals(Object object) {
      if (this == object) {
        return true;
      }
      if (!(object instanceof TernaryDataPoint)) {
        return false;
      }
      TernaryDataPoint<?> other = (TernaryDataPoint<?>) object;
      return Objects.equals(bdd, other.bdd) && first == other.first
          && second == other.second && third == other.third;
    }

    @Override
    public int hashCode() {
      return fnv1aHash(first) * fnv1aHash(second) * fnv1aHash(third) * bdd.hashCode();
    }

    @Override
    public String toString() {
      return String.format("%s: %s ### %s ### %s", bdd.getClass().getSimpleName(),
          firstTree, secondTree, thirdTree);
    }
  }
}
