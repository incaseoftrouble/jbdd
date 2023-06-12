package de.tum.in.jbdd;

public final class BddBuilder {
    private BddBuilder() {}

    /* N-Queens problem, loosely inspired by RuDD, which took it from BuDDy */
    public static int makeQueens(Bdd bdd, int n) {
        int queen = bdd.trueNode();

        int[][] x = new int[n][n];
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                x[r][c] = bdd.createVariable();
            }
        }

        // Queen in each row
        for (int r = 0; r < n; r++) {
            int cond = bdd.falseNode();
            for (int c = 0; c < n; c++) {
                cond = bdd.updateWith(bdd.or(cond, x[r][c]), cond);
            }
            queen = bdd.updateWith(bdd.and(queen, cond), queen);
        }

        // Constraints
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                int cond = bdd.trueNode();

                // No two in same row
                for (int oc = 0; oc < n; oc++) {
                    if (oc != c) {
                        cond = bdd.updateWith(bdd.and(cond, bdd.implication(x[r][c], bdd.not(x[r][oc]))), cond);
                    }
                }

                // Diagonal
                for (int or = 0; or < n; or++) {
                    int drc = (c + or - r);
                    if (or != r && 0 <= drc && drc < n) {
                        cond = bdd.updateWith(bdd.and(cond, bdd.implication(x[r][c], bdd.not(x[or][drc]))), cond);
                    }
                    int ulc = (c + r - or);
                    if (or != r && 0 <= ulc && ulc < n) {
                        cond = bdd.updateWith(bdd.and(cond, bdd.implication(x[r][c], bdd.not(x[or][ulc]))), cond);
                    }
                }

                // No two in same column
                for (int or = 0; or < n; or++) {
                    if (or != r) {
                        cond = bdd.updateWith(bdd.and(cond, bdd.implication(x[r][c], bdd.not(x[or][c]))), cond);
                    }
                }

                queen = bdd.updateWith(bdd.and(queen, cond), queen);
            }
        }
        return queen;
    }

    /* Object-Oriented version of the N-Queens problem */
    public static BddSet makeQueensSet(BddSetFactory bdd, int n) {
        BddSet queen = bdd.universe();

        BddSet[][] x = new BddSet[n][n];
        int var = 0;
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                x[r][c] = bdd.var(var);
                var += 1;
            }
        }

        // Queen in each row
        for (int r = 0; r < n; r++) {
            BddSet cond = bdd.empty();
            for (int c = 0; c < n; c++) {
                cond = cond.union(x[r][c]);
            }
            queen = queen.intersection(cond);
        }

        // Constraints
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                BddSet cond = bdd.universe();

                // No two in same row
                for (int oc = 0; oc < n; oc++) {
                    if (oc != c) {
                        cond = cond.intersection(x[r][c].complement().union(x[r][oc].complement()));
                    }
                }

                // Diagonal
                for (int or = 0; or < n; or++) {
                    int drc = (c + or - r);
                    if (or != r && 0 <= drc && drc < n) {
                        cond = cond.intersection(x[r][c].complement().union(x[or][drc].complement()));
                    }
                    int ulc = (c + r - or);
                    if (or != r && 0 <= ulc && ulc < n) {
                        cond = cond.intersection(x[r][c].complement().union(x[or][ulc].complement()));
                    }
                }

                // No two in same column
                for (int or = 0; or < n; or++) {
                    if (or != r) {
                        cond = cond.intersection(x[r][c].complement().union(x[or][c].complement()));
                    }
                }

                queen = queen.intersection(cond);
            }
        }
        return queen;
    }

    /* Binary adder, loosely inspired by RuDD, which took it from BuDDy */
    public static int[][] makeAdder(Bdd bdd, int n) {
        int[] ain = new int[n];
        int[] bin = new int[n];

        for (int i = 0; i < n; i++) {
            ain[i] = bdd.createVariable();
            bin[i] = bdd.createVariable();
        }

        int carry = bdd.reference(bdd.and(ain[0], bin[0]));
        int[] out = new int[n];
        out[0] = bdd.reference(bdd.xor(ain[0], bin[0]));

        for (int i = 1; i < n; i++) {
            int xor = bdd.xor(ain[i], bin[i]);
            out[i] = bdd.reference(bdd.xor(xor, carry));
            int newCarry = bdd.reference(bdd.and(ain[i], bin[i]));
            newCarry = bdd.updateWith(bdd.or(newCarry, bdd.and(ain[i], carry)), newCarry);
            newCarry = bdd.updateWith(bdd.or(newCarry, bdd.and(bin[i], carry)), newCarry);
            carry = bdd.updateWith(newCarry, carry);
        }
        return new int[][] {ain, bin, out};
    }
}
