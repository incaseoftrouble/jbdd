package de.tum.in.jbdd;

import java.util.concurrent.atomic.AtomicBoolean;

public final class CheckedBdd extends DelegatingBdd {
  private final AtomicBoolean access;

  public CheckedBdd(Bdd delegate) {
    super(delegate);
    access = new AtomicBoolean(false);
  }

  @Override
  protected void onEnter(String name) {
    if (!access.compareAndSet(false, true)) {
      throw new IllegalStateException("Concurrent access to " + name);
    }
  }

  @Override
  protected void onExit() {
    if (!access.getAndSet(false)) {
      throw new IllegalStateException("Concurrently accessed");
    }
  }
}
