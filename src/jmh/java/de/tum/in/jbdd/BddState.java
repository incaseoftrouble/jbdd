package de.tum.in.jbdd;

import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@State(Scope.Benchmark)
public class BddState {
    @Param({"1"})
    private float cacheSizeFactor;

    @Param({"true", "false"})
    private boolean iterative;

    private Bdd bdd;

    @SuppressWarnings("NumericCastThatLosesPrecision")
    @Setup(Level.Iteration)
    public void setUpBdd() {
        bdd = BddFactory.buildBdd(
                iterative,
                ImmutableBddConfiguration.builder()
                        .cacheNegationDivider((int) (BddConfiguration.DEFAULT_CACHE_NEGATION_DIVIDER / cacheSizeFactor))
                        .cacheBinaryDivider((int) (BddConfiguration.DEFAULT_CACHE_BINARY_DIVIDER / cacheSizeFactor))
                        .cacheTernaryDivider((int) (BddConfiguration.DEFAULT_CACHE_TERNARY_DIVIDER / cacheSizeFactor))
                        .cacheSatisfactionDivider(
                                (int) (BddConfiguration.DEFAULT_CACHE_SATISFACTION_DIVIDER / cacheSizeFactor))
                        .cacheComposeDivider((int) (BddConfiguration.DEFAULT_CACHE_COMPOSE_DIVIDER / cacheSizeFactor))
                        .build());
    }

    public Bdd bdd() {
        return bdd;
    }
}
