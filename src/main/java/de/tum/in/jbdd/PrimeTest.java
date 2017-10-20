package de.tum.in.jbdd;

// Taken and adapted from Guava
@SuppressWarnings({"StandardVariableNames", "MagicNumber", "PMD.AvoidReassigningParameters",
                      "PMD.PrematureDeclaration", "PMD.AssignmentInOperand"})
final class PrimeTest {
  static final long FLOOR_SQRT_MAX_LONG = 3037000499L;
  private static final int SIEVE_30 = ~((1 << 1) | (1 << 7) | (1 << 11) | (1 << 13)
      | (1 << 17) | (1 << 19) | (1 << 23) | (1 << 29));
  // CSOFF: Indentation
  private static final long[][] millerRabinBaseSets = {
      {291830L, 126401071349994536L},
      {885594168L, 725270293939359937L, 3569819667048198375L},
      {273919523040L, 15L, 7363882082L, 992620450144556L},
      {47636622961200L, 2L, 2570940L, 211991001L, 3749873356L},
      {
          7999252175582850L,
          2L,
          4130806001517L,
          149795463772692060L,
          186635894390467037L,
          3967304179347715805L
      },
      {
          585226005592931976L,
          2L,
          123635709730000L,
          9233062284813009L,
          43835965440333360L,
          761179012939631437L,
          1263739024124850375L
      },
      {Long.MAX_VALUE, 2L, 325L, 9375L, 28178L, 450775L, 9780504L, 1795265022L}
  };
  // CSON: Indentation

  private PrimeTest() {}

  private static int compare(long a, long b) {
    return Long.compare(flip(a), flip(b));
  }

  private static long flip(long a) {
    return a ^ Long.MIN_VALUE;
  }

  static boolean isPrime(long n) {
    assert n >= 0L;
    if (n < 2L) {
      return false;
    }
    if (n == 2L || n == 3L || n == 5L || n == 7L || n == 11L || n == 13L) {
      return true;
    }

    if ((SIEVE_30 & (1 << (n % 30L))) != 0) {
      return false;
    }
    if (n % 7L == 0L || n % 11L == 0L || n % 13L == 0L) {
      return false;
    }
    if (n < (long) (17 * 17)) {
      return true;
    }

    for (long[] baseSet : millerRabinBaseSets) {
      if (n <= baseSet[0]) {
        for (int i = 1; i < baseSet.length; i++) {
          if (!MillerRabinTester.test(baseSet[i], n)) {
            return false;
          }
        }
        return true;
      }
    }
    throw new AssertionError();
  }

  private static long remainder(long dividend, long divisor) {
    if (divisor < 0L) { // i.e., divisor >= 2^63:
      if (compare(dividend, divisor) < 0) {
        return dividend; // dividend < divisor
      } else {
        return dividend - divisor; // dividend >= divisor
      }
    }
    if (dividend >= 0L) {
      return dividend % divisor;
    }
    long quotient = ((dividend >>> 1) / divisor) << 1;
    long rem = dividend - quotient * divisor;
    return rem - (compare(rem, divisor) >= 0 ? divisor : 0L);
  }

  @SuppressWarnings({"MagicNumber", "AssignmentToMethodParameter"})
  private enum MillerRabinTester {
    SMALL {
      @Override
      long mulMod(long a, long b, long m) {
        return (a * b) % m;
      }

      @Override
      long squareMod(long a, long m) {
        return (a * a) % m;
      }
    },
    LARGE {
      private long plusMod(long a, long b, long m) {
        return (a >= m - b) ? (a + b - m) : (a + b);
      }

      private long times2ToThe32Mod(long a, long m) {
        int remainingPowersOf2 = 32;
        do {
          int shift = Math.min(remainingPowersOf2, Long.numberOfLeadingZeros(a));
          a = remainder(a << shift, m);
          remainingPowersOf2 -= shift;
        } while (remainingPowersOf2 > 0);
        return a;
      }

      @Override
      long mulMod(long a, long b, long m) {
        long aHi = a >>> 32; // < 2^31
        long bHi = b >>> 32; // < 2^31
        long aLo = a & 0xFFFFFFFFL; // < 2^32
        long bLo = b & 0xFFFFFFFFL; // < 2^32

        long result = times2ToThe32Mod(aHi * bHi /* < 2^62 */, m); // < m < 2^63
        result += aHi * bLo; // aHi * bLo < 2^63, result < 2^64
        if (result < 0L) {
          result = remainder(result, m);
        }
        // result < 2^63 again
        result += aLo * bHi; // aLo * bHi < 2^63, result < 2^64
        result = times2ToThe32Mod(result, m); // result < m < 2^63
        return plusMod(
            result,
            remainder(aLo * bLo /* < 2^64 */, m),
            m);
      }

      @Override
      long squareMod(long a, long m) {
        long aHi = a >>> 32; // < 2^31
        long aLo = a & 0xFFFFFFFFL; // < 2^32

        long result = times2ToThe32Mod(aHi * aHi /* < 2^62 */, m); // < m < 2^63
        long hiLo = aHi * aLo * 2L;
        if (hiLo < 0L) {
          hiLo = remainder(hiLo, m);
        }
        // hiLo < 2^63
        result += hiLo; // result < 2^64
        result = times2ToThe32Mod(result, m); // result < m < 2^63
        return plusMod(
            result,
            remainder(aLo * aLo /* < 2^64 */, m),
            m);
      }
    };

    static boolean test(long base, long n) {
      // Since base will be considered % n, it's okay if base > FLOOR_SQRT_MAX_LONG,
      // so long as n <= FLOOR_SQRT_MAX_LONG.
      return ((n <= FLOOR_SQRT_MAX_LONG) ? SMALL : LARGE).testWitness(base, n);
    }

    abstract long mulMod(long a, long b, long m);

    private long powMod(long a, long p, long m) {
      long res = 1L;
      for (; p != 0L; p >>= 1L) {
        if ((p & 1L) != 0L) {
          res = mulMod(res, a, m);
        }
        a = squareMod(a, m);
      }
      return res;
    }

    abstract long squareMod(long a, long m);

    private boolean testWitness(long base, long n) {
      int r = Long.numberOfTrailingZeros(n - 1L);
      long d = (n - 1L) >> r;
      base %= n;
      if (base == 0L) {
        return true;
      }
      long a = powMod(base, d, n);
      if (a == 1L) {
        return true;
      }
      int j = 0;
      while (a != n - 1L) {
        //noinspection ValueOfIncrementOrDecrementUsed
        if (++j == r) {
          return false;
        }
        a = squareMod(a, n);
      }
      return true;
    }
  }
}
