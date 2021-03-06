/*
 * Copyright (C) 2011 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.common.hash;

import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import com.google.common.testing.EqualsTester;
import com.google.common.testing.NullPointerTester;
import com.google.common.testing.SerializableTester;

import junit.framework.TestCase;

import java.util.Random;

import javax.annotation.Nullable;

/**
 * Tests for SimpleGenericBloomFilter and derived BloomFilter views.
 *
 * @author Dimitris Andreou
 */
public class BloomFilterTest extends TestCase {

  public void testCreateAndCheckBloomFilterWithKnownFalsePositives() {
    int numInsertions = 1000000;
    BloomFilter<CharSequence> bf = BloomFilter.create(Funnels.stringFunnel(), numInsertions);

    // Insert "numInsertions" even numbers into the BF.
    for (int i = 0; i < numInsertions * 2; i += 2) {
      bf.put(Integer.toString(i));
    }

    // Assert that the BF "might" have all of the even numbers.
    for (int i = 0; i < numInsertions * 2; i += 2) {
      assertTrue(bf.mightContain(Integer.toString(i)));
    }

    // Now we check for known false positives using a set of known false positives.
    // (These are all of the false positives under 900.)
    ImmutableSet<Integer> falsePositives = ImmutableSet.of(
        49, 51, 59, 163, 199, 321, 325, 363, 367, 469, 545, 561, 727, 769, 773, 781);
    for (int i = 1; i < 900; i += 2) {
      if (!falsePositives.contains(i)) {
        assertFalse("BF should not contain " + i, bf.mightContain(Integer.toString(i)));
      }
    }

    // Check that there are exactly 29824 false positives for this BF.
    int knownNumberOfFalsePositives = 29824;
    int numFpp = 0;
    for (int i = 1; i < numInsertions * 2; i += 2) {
      if (bf.mightContain(Integer.toString(i))) {
        numFpp++;
      }
    }
    assertEquals(knownNumberOfFalsePositives, numFpp);
    double actualFpp = (double) knownNumberOfFalsePositives / numInsertions;
    double expectedFpp = bf.expectedFpp();
    // The normal order of (expected, actual) is reversed here on purpose.
    assertEquals(actualFpp, expectedFpp, 0.00015);
  }

  /**
   * Sanity checking with many combinations of false positive rates and expected insertions
   */
  public void testBasic() {
    for (double fpr = 0.0000001; fpr < 0.1; fpr *= 10) {
      for (int expectedInsertions = 1; expectedInsertions <= 10000; expectedInsertions *= 10) {
        checkSanity(BloomFilter.create(HashTestUtils.BAD_FUNNEL, expectedInsertions, fpr));
      }
    }
  }

  public void testPreconditions() {
    try {
      BloomFilter.create(Funnels.stringFunnel(), -1);
      fail();
    } catch (IllegalArgumentException expected) {}
    try {
      BloomFilter.create(Funnels.stringFunnel(), -1, 0.03);
      fail();
    } catch (IllegalArgumentException expected) {}
    try {
      BloomFilter.create(Funnels.stringFunnel(), 1, 0.0);
      fail();
    } catch (IllegalArgumentException expected) {}
    try {
      BloomFilter.create(Funnels.stringFunnel(), 1, 1.0);
      fail();
    } catch (IllegalArgumentException expected) {}
  }

  public void testFailureWhenMoreThan255HashFunctionsAreNeeded() {
    try {
      int n = 1000;
      double p = 0.00000000000000000000000000000000000000000000000000000000000000000000000000000001;
      BloomFilter.create(Funnels.stringFunnel(), n, p);
      fail();
    } catch (IllegalArgumentException expected) {}
  }

  public void testNullPointers() {
    NullPointerTester tester = new NullPointerTester();
    tester.testAllPublicInstanceMethods(BloomFilter.create(Funnels.stringFunnel(), 100));
    tester.testAllPublicStaticMethods(BloomFilter.class);
  }

  /**
   * Tests that we never get an optimal hashes number of zero.
   */
  public void testOptimalHashes() {
    for (int n = 1; n < 1000; n++) {
      for (int m = 0; m < 1000; m++) {
        assertTrue(BloomFilter.optimalNumOfHashFunctions(n, m) > 0);
      }
    }
  }

  /**
   * Tests that we always get a non-negative optimal size.
   */
  public void testOptimalSize() {
    for (int n = 1; n < 1000; n++) {
      for (double fpp = Double.MIN_VALUE; fpp < 1.0; fpp += 0.001) {
        assertTrue(BloomFilter.optimalNumOfBits(n, fpp) >= 0);
      }
    }

    // some random values
    Random random = new Random(0);
    for (int repeats = 0; repeats < 10000; repeats++) {
      assertTrue(BloomFilter.optimalNumOfBits(random.nextInt(1 << 16), random.nextDouble()) >= 0);
    }

    // and some crazy values (this used to be capped to Integer.MAX_VALUE, now it can go bigger
    assertEquals(3327428144502L, BloomFilter.optimalNumOfBits(
        Integer.MAX_VALUE, Double.MIN_VALUE));
    try {
      BloomFilter.create(HashTestUtils.BAD_FUNNEL, Integer.MAX_VALUE, Double.MIN_VALUE);
      fail("we can't represent such a large BF!");
    } catch (IllegalArgumentException expected) {
      assertEquals("Could not create BloomFilter of 3327428144502 bits", expected.getMessage());
    }
  }

  private void checkSanity(BloomFilter<Object> bf) {
    assertFalse(bf.mightContain(new Object()));
    assertFalse(bf.apply(new Object()));
    for (int i = 0; i < 100; i++) {
      Object o = new Object();
      bf.put(o);
      assertTrue(bf.mightContain(o));
      assertTrue(bf.apply(o));
    }
  }

  public void testCopy() {
    BloomFilter<CharSequence> original = BloomFilter.create(Funnels.stringFunnel(), 100);
    BloomFilter<CharSequence> copy = original.copy();
    assertNotSame(original, copy);
    assertEquals(original, copy);
  }

  public void testExpectedFpp() {
    BloomFilter<Object> bf = BloomFilter.create(HashTestUtils.BAD_FUNNEL, 10, 0.03);
    double fpp = bf.expectedFpp();
    assertEquals(0.0, fpp);
    // usually completed in less than 200 iterations
    while (fpp != 1.0) {
      boolean changed = bf.put(new Object());
      double newFpp = bf.expectedFpp();
      // if changed, the new fpp is strictly higher, otherwise it is the same
      assertTrue(changed ? newFpp > fpp : newFpp == fpp);
      fpp = newFpp;
    }
  }

  public void testEquals_empty() {
    new EqualsTester()
        .addEqualityGroup(BloomFilter.create(Funnels.byteArrayFunnel(), 100, 0.01))
        .addEqualityGroup(BloomFilter.create(Funnels.byteArrayFunnel(), 100, 0.02))
        .addEqualityGroup(BloomFilter.create(Funnels.byteArrayFunnel(), 200, 0.01))
        .addEqualityGroup(BloomFilter.create(Funnels.byteArrayFunnel(), 200, 0.02))
        .addEqualityGroup(BloomFilter.create(Funnels.stringFunnel(), 100, 0.01))
        .addEqualityGroup(BloomFilter.create(Funnels.stringFunnel(), 100, 0.02))
        .addEqualityGroup(BloomFilter.create(Funnels.stringFunnel(), 200, 0.01))
        .addEqualityGroup(BloomFilter.create(Funnels.stringFunnel(), 200, 0.02))
        .testEquals();
  }

  public void testEquals() {
    BloomFilter<CharSequence> bf1 = BloomFilter.create(Funnels.stringFunnel(), 100);
    bf1.put("1");
    bf1.put("2");

    BloomFilter<CharSequence> bf2 = BloomFilter.create(Funnels.stringFunnel(), 100);
    bf2.put("1");
    bf2.put("2");

    new EqualsTester()
        .addEqualityGroup(bf1, bf2)
        .testEquals();

    bf2.put("3");

    new EqualsTester()
        .addEqualityGroup(bf1)
        .addEqualityGroup(bf2)
        .testEquals();
  }

  public void testEqualsWithCustomFunnel() {
    BloomFilter<Long> bf1 = BloomFilter.create(new CustomFunnel(), 100);
    BloomFilter<Long> bf2 = BloomFilter.create(new CustomFunnel(), 100);
    assertEquals(bf1, bf2);
  }

  public void testSerializationWithCustomFunnel() {
    SerializableTester.reserializeAndAssert(BloomFilter.create(new CustomFunnel(), 100));
  }

  private static final class CustomFunnel implements Funnel<Long> {
    @Override
    public void funnel(Long value, PrimitiveSink into) {
      into.putLong(value);
    }
    @Override
    public boolean equals(@Nullable Object object) {
      return (object instanceof CustomFunnel);
    }
    @Override
    public int hashCode() {
      return 42;
    }
  }

  public void testPutReturnValue() {
    for (int i = 0; i < 10; i++) {
      BloomFilter<CharSequence> bf = BloomFilter.create(Funnels.stringFunnel(), 100);
      for (int j = 0; j < 10; j++) {
        String value = new Object().toString();
        boolean mightContain = bf.mightContain(value);
        boolean put = bf.put(value);
        assertTrue(mightContain != put);
      }
    }
  }

  public void testJavaSerialization() {
    BloomFilter<byte[]> bf = BloomFilter.create(Funnels.byteArrayFunnel(), 100);
    for (int i = 0; i < 10; i++) {
      bf.put(Ints.toByteArray(i));
    }

    BloomFilter<byte[]> copy = SerializableTester.reserialize(bf);
    for (int i = 0; i < 10; i++) {
      assertTrue(copy.mightContain(Ints.toByteArray(i)));
    }
    assertEquals(bf.expectedFpp(), copy.expectedFpp());

    SerializableTester.reserializeAndAssert(bf);
  }

  /**
   * This test will fail whenever someone updates/reorders the BloomFilterStrategies constants.
   * Only appending a new constant is allowed.
   */
  public void testBloomFilterStrategies() {
    assertEquals(1, BloomFilterStrategies.values().length);
    assertEquals(BloomFilterStrategies.MURMUR128_MITZ_32, BloomFilterStrategies.values()[0]);
  }
}
