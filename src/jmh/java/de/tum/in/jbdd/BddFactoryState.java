package de.tum.in.jbdd;

import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@State(Scope.Benchmark)
public class BddFactoryState {
    @SuppressWarnings("NotNullFieldNotInitialized")
    private BddSetFactory factory;

    @Setup(Level.Iteration)
    public void setUpBdd() {
        factory = BddSetFactory.create();
    }

    public BddSetFactory factory() {
        return factory;
    }
}
