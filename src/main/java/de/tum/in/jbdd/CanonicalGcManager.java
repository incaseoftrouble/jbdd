package de.tum.in.jbdd;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

// TODO Maybe instead equip this with a constructor function from int to V, hiding internalization
public class CanonicalGcManager<V extends CanonicalGcManager.BddWrapper> {
  private static final Logger logger = Logger.getLogger(CanonicalGcManager.class.getName());

  protected final Bdd bdd;

  private final Map<Integer, WeakReference<V>> gcObjects = new HashMap<>();
  private final Map<Integer, V> nonGcObjects = new HashMap<>();
  private final ReferenceQueue<V> queue = new ReferenceQueue<>();

  public CanonicalGcManager(Bdd bdd) {
    this.bdd = bdd;
  }

  // This is not thread safe!
  public V canonicalize(V wrapper) {
    int node = wrapper.node();

    // Root nodes and variables are exempt from GC.
    if (bdd.isNodeRoot(node) || bdd.isVariableOrNegated(node)) {
      assert bdd.getReferenceCount(node) == -1
        : reportReferenceCountMismatch(-1, bdd.getReferenceCount(node));

      return nonGcObjects.merge(node, wrapper, (oldWrapper, newWrapper) -> oldWrapper);
    }

    WeakReference<V> canonicalReference = gcObjects.get(node);

    if (canonicalReference == null) {
      // The BDD was created and needs a reference to be protected.
      assert bdd.getReferenceCount(node) == 0
        : reportReferenceCountMismatch(0, bdd.getReferenceCount(node));

      bdd.reference(node);
    } else {
      // The BDD already existed.
      assert bdd.getReferenceCount(node) == 1
        : reportReferenceCountMismatch(1, bdd.getReferenceCount(node));

      V canonicalWrapper = canonicalReference.get();

      if (canonicalWrapper == null) {
        // This object was GC'ed since the last run of clear(), but potentially wasn't added to the
        // ReferenceQueue by the GC yet. Make sure that the reference is queued and cleared to
        // avoid inconsistencies.
        canonicalReference.enqueue();
      } else {
        assert node == canonicalWrapper.node();
        return canonicalWrapper;
      }
    }

    assert bdd.getReferenceCount(node) == 1;
    // Remove queued BDDs from the mapping.
    processReferenceQueue(node);

    // Insert BDD into mapping.
    gcObjects.put(node, new WeakReference<>(wrapper, queue));
    assert bdd.getReferenceCount(node) == 1;
    return wrapper;
  }

  private void processReferenceQueue(int protectedNode) {
    Reference<? extends V> reference = queue.poll();
    if (reference == null) {
      // Queue is empty
      return;
    }

    int count = 0;
    do {
      V object = reference.get();
      assert object != null;

      int node = object.node();
      gcObjects.remove(node);

      if (node != protectedNode) {
        assert bdd.getReferenceCount(node) == 1;
        bdd.dereference(node);
        assert bdd.getReferenceCount(node) == 0;
        count += 1;
      }

      reference = queue.poll();
    } while (reference != null);

    logger.log(Level.FINEST, "Cleared {0} references", count);
  }

  @SuppressWarnings("InterfaceMayBeAnnotatedFunctional")
  public interface BddWrapper {
    int node();
  }

  private static String reportReferenceCountMismatch(int expected, int actual) {
    return String.format(
      "Expected reference count {%d}, but actual count is {%d}.", expected, actual);
  }
}
