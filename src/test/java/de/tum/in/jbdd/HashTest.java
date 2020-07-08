package de.tum.in.jbdd;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.util.Random;
import org.junit.jupiter.api.Test;

public class HashTest {
  @SuppressWarnings("NumericCastThatLosesPrecision")
  private static byte[] toArray(int key) {
    // CSOFF: Indentation
    return new byte[] {
        (byte) (key >>> 24), (byte) (key >>> 16), (byte) (key >>> 8), (byte) key
    };
    // CSON: Indentation
  }

  @SuppressWarnings("NumericCastThatLosesPrecision")
  private static byte[] toArray(long key) {
    // CSOFF: Indentation
    return new byte[] {
        (byte) (key >>> 56), (byte) (key >>> 48), (byte) (key >>> 40), (byte) (key >>> 32),
        (byte) (key >>> 24), (byte) (key >>> 16), (byte) (key >>> 8), (byte) key
    };
    // CSON: Indentation
  }

  private static byte[] concat(byte[]... arrays) {
    int length = 0;
    for (byte[] array : arrays) {
      length += array.length;
    }
    byte[] result = new byte[length];
    int offset = 0;
    for (byte[] array : arrays) {
      System.arraycopy(array, 0, result, offset, array.length);
      offset += array.length;
    }
    return result;
  }

  @Test
  public void testFnv1aHash() {
    Random random = new Random(0);

    for (int i = 0; i < 1000; i++) {
      int int1 = random.nextInt();
      int int2 = random.nextInt();
      long long1 = random.nextLong();
      long long2 = random.nextLong();

      byte[] int1data = toArray(int1);
      byte[] int2data = toArray(int2);
      byte[] long1data = toArray(long1);
      byte[] long2data = toArray(long2);

      assertThat(HashUtil.fnv1aHash(int1), is(fnv1aReference(int1data)));
      assertThat(HashUtil.fnv1aHash(long1), is(fnv1aReference(long1data)));
      assertThat(HashUtil.fnv1aHash(long1, int1),
          is(fnv1aReference(concat(long1data, int1data))));
      assertThat(HashUtil.fnv1aHash(long1, long2),
          is(fnv1aReference(concat(long1data, long2data))));
      assertThat(HashUtil.fnv1aHash(int1, new int[] {int2}),
          is(fnv1aReference(concat(int1data, int2data))));
    }
  }

  @Test
  public void testMurmur3Hash() {
    Random random = new Random(0);

    for (int i = 0; i < 1000; i++) {
      int int1 = random.nextInt();
      int int2 = random.nextInt();
      long long1 = random.nextLong();
      long long2 = random.nextLong();

      byte[] int1data = toArray(int1);
      byte[] int2data = toArray(int2);
      byte[] long1data = toArray(long1);
      byte[] long2data = toArray(long2);

      assertThat(HashUtil.murmur3Hash(int1), is(murmur3reference(int1data)));
      assertThat(HashUtil.murmur3Hash(long1), is(murmur3reference(long1data)));
      assertThat(HashUtil.murmur3Hash(long1, int1),
          is(murmur3reference(concat(long1data, int1data))));
      assertThat(HashUtil.murmur3Hash(long1, long2),
          is(murmur3reference(concat(long1data, long2data))));
      assertThat(HashUtil.murmur3Hash(int1, new int[] {int2}),
          is(murmur3reference(concat(int1data, int2data))));
    }
  }

  private static int fnv1aReference(byte[] data) {
    int hash = 0x811c9dc5;
    for (byte i : data) {
      hash ^= (i & 0xff);
      hash *= 0x1000193;
    }
    return hash;
  }

  private static int murmur3reference(byte[] data) {
    int c1 = 0xcc9e2d51;
    int c2 = 0x1b873593;

    int h1 = HashUtil.MURMUR3_SEED;
    int roundedEnd = (data.length & 0xfffffffc);  // round down to 4 byte block

    for (int i = 0; i < roundedEnd; i += 4) {
      int k1 = (data[i + 3] & 0xff) | ((data[i + 2] & 0xff) << 8) | ((data[i + 1] & 0xff) << 16)
          | (data[i] << 24);
      k1 *= c1;
      k1 = (k1 << 15) | (k1 >>> 17);  // ROTL32(k1,15);
      k1 *= c2;

      h1 ^= k1;
      h1 = (h1 << 13) | (h1 >>> 19);  // ROTL32(h1,13);
      h1 = h1 * 5 + 0xe6546b64;
    }

    // NOTE: Tail not relevant here, since our data.length will always be multiple of 4
    assert (data.length & 0x03) == 0;

    // finalization
    h1 ^= data.length;

    // fmix(h1);
    h1 ^= h1 >>> 16;
    h1 *= 0x85ebca6b;
    h1 ^= h1 >>> 13;
    h1 *= 0xc2b2ae35;
    h1 ^= h1 >>> 16;

    return h1;
  }
}
