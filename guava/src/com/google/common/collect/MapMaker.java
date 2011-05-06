/*
 * Copyright (C) 2009 The Guava Authors
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

package com.google.common.collect;

import static com.google.common.base.Objects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.Beta;
import com.google.common.annotations.GwtCompatible;
import com.google.common.annotations.GwtIncompatible;
import com.google.common.base.Ascii;
import com.google.common.base.Equivalence;
import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Ticker;
import com.google.common.collect.CacheStatsCounter;
import com.google.common.collect.CacheStatsCounterImpl;
import com.google.common.collect.ComputingConcurrentHashMap.ComputingMapAdapter;
import com.google.common.collect.CustomConcurrentHashMap.Strength;
import com.google.common.collect.MapMaker.RemovalListener.RemovalCause;

import java.io.Serializable;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

/**
 * A {@link ConcurrentMap} builder, providing any combination of these features: {@linkplain
 * SoftReference soft} or {@linkplain WeakReference weak} keys, soft or weak values, size-based
 * eviction, timed expiration, and on-demand computation of values. Usage example: <pre>   {@code
 *
 *   ConcurrentMap<Key, Graph> graphs = new MapMaker()
 *       .concurrencyLevel(4)
 *       .softKeys()
 *       .weakValues()
 *       .maximumSize(10000)
 *       .expireAfterWrite(10, TimeUnit.MINUTES)
 *       .makeComputingMap(
 *           new Function<Key, Graph>() {
 *             public Graph apply(Key key) {
 *               return createExpensiveGraph(key);
 *             }
 *           });}</pre>
 *
 * These features are all optional; {@code new MapMaker().makeMap()} returns a valid concurrent map
 * that behaves exactly like a {@link ConcurrentHashMap}.
 *
 * <p>The returned map is implemented as a hash table with similar performance characteristics to
 * {@link ConcurrentHashMap}. It supports all optional operations of the {@code ConcurrentMap}
 * interface. It does not permit null keys or values.
 *
 * <p><b>Note:</b> by default, the returned map uses equality comparisons (the {@link
 * Object#equals(Object) equals} method) to determine equality for keys or values. However, if
 * {@link #weakKeys()} or {@link #softKeys()} was specified, the map uses identity ({@code ==})
 * comparisons instead for keys. Likewise, if {@link #weakValues()} or {@link #softValues()} was
 * specified, the map uses identity comparisons for values.
 *
 * <p>The returned map has <i>weakly consistent iterators</i> which may reflect some, all or none of
 * the changes made to the map after the iterator was created. They do not throw {@link
 * ConcurrentModificationException}, and may proceed concurrently with other operations.
 *
 * <p>An entry whose key or value is reclaimed by the garbage collector immediately disappears from
 * the map. (If the default settings of strong keys and strong values are used, this will never
 * happen.) The client can never observe a partially-reclaimed entry. Any {@link
 * java.util.Map.Entry} instance retrieved from the map's {@linkplain Map#entrySet() entry set} is a
 * snapshot of that entry's state at the time of retrieval; such entries do, however, support {@link
 * java.util.Map.Entry#setValue}, which simply calls {@link java.util.Map#put} on the entry's key.
 *
 * <p>The maps produced by {@code MapMaker} are serializable, and the deserialized maps retain all
 * the configuration properties of the original map. If the map uses soft or weak references, the
 * entries will be reconstructed as they were, but there is no guarantee that the entries won't be
 * immediately reclaimed.
 *
 * <p>{@code new MapMaker().weakKeys().makeMap()} can almost always be used as a drop-in replacement
 * for {@link java.util.WeakHashMap}, adding concurrency, asynchronous cleanup, identity-based
 * equality for keys, and great flexibility.
 *
 * @author Bob Lee
 * @author Charles Fry
 * @author Kevin Bourrillion
 * @since Guava release 02 (imported from Google Collections Library)
 */
@GwtCompatible(emulated = true)
public final class MapMaker extends GenericMapMaker<Object, Object> {
  private static final int DEFAULT_INITIAL_CAPACITY = 16;
  private static final int DEFAULT_CONCURRENCY_LEVEL = 4;
  private static final int DEFAULT_EXPIRATION_NANOS = 0;

  static final Supplier<? extends CacheStatsCounter> DEFAULT_STATS_COUNTER = Suppliers.ofInstance(
      new CacheStatsCounter() {
        @Override
        public void recordHit() {}

        @Override
        public void recordMiss() {}

        @Override
        public void recordEviction() {}

        @Override
        public void recordCreate(long createTime) {}

        @Override
        public CacheStats snapshot() {
          return EMPTY_STATS;
        }
      });
  private static final CacheStats EMPTY_STATS = new CacheStats(0, 0, 0, 0, 0);

  static final Supplier<CacheStatsCounterImpl> CACHE_STATS_COUNTER =
      new Supplier<CacheStatsCounterImpl>() {
    @Override
    public CacheStatsCounterImpl get() {
      return new CacheStatsCounterImpl();
    }
  };

  static final Ticker DEFAULT_TICKER = new Ticker() {
    @Override
    public long read() {
      return System.nanoTime();
    }
  };

  static final int UNSET_INT = -1;

  // TODO(kevinb): dispense with this after benchmarking
  boolean useCustomMap;

  int initialCapacity = UNSET_INT;
  int concurrencyLevel = UNSET_INT;
  int maximumSize = UNSET_INT;

  Strength keyStrength;
  Strength valueStrength;

  long expireAfterWriteNanos = UNSET_INT;
  long expireAfterAccessNanos = UNSET_INT;

  RemovalCause nullRemovalCause;

  Equivalence<Object> keyEquivalence;
  Equivalence<Object> valueEquivalence;

  Executor cleanupExecutor;
  Ticker ticker;

  /**
   * Constructs a new {@code MapMaker} instance with default settings, including strong keys, strong
   * values, and no automatic expiration.
   */
  public MapMaker() {}

  private boolean useNullCache() {
    return (nullRemovalCause == null);
  }

  // TODO(kevinb): undo this indirection if keyEquiv gets released
  MapMaker privateKeyEquivalence(Equivalence<Object> equivalence) {
    checkState(keyEquivalence == null, "key equivalence was already set to %s", keyEquivalence);
    keyEquivalence = checkNotNull(equivalence);
    this.useCustomMap = true;
    return this;
  }

  Equivalence<Object> getKeyEquivalence() {
    return firstNonNull(keyEquivalence, getKeyStrength().defaultEquivalence());
  }

  // TODO(kevinb): undo this indirection if valueEquiv gets released
  MapMaker privateValueEquivalence(Equivalence<Object> equivalence) {
    checkState(valueEquivalence == null,
        "value equivalence was already set to %s", valueEquivalence);
    this.valueEquivalence = checkNotNull(equivalence);
    this.useCustomMap = true;
    return this;
  }

  Equivalence<Object> getValueEquivalence() {
    return firstNonNull(valueEquivalence, getValueStrength().defaultEquivalence());
  }

  /**
   * Sets a custom initial capacity (defaults to 16). Resizing this or any other kind of hash table
   * is a relatively slow operation, so, when possible, it is a good idea to provide estimates of
   * expected table sizes.
   *
   * @throws IllegalArgumentException if {@code initialCapacity} is negative
   * @throws IllegalStateException if an initial capacity was already set
   */
  @Override
  public MapMaker initialCapacity(int initialCapacity) {
    checkState(this.initialCapacity == UNSET_INT, "initial capacity was already set to %s",
        this.initialCapacity);
    checkArgument(initialCapacity >= 0);
    this.initialCapacity = initialCapacity;
    return this;
  }

  int getInitialCapacity() {
    return (initialCapacity == UNSET_INT) ? DEFAULT_INITIAL_CAPACITY : initialCapacity;
  }

  /**
   * Specifies the maximum number of entries the map may contain. While the number of entries in the
   * map is not guaranteed to grow to the maximum, the map will attempt to make the best use of
   * memory without exceeding the maximum number of entries. As the map size grows close to the
   * maximum, the map will evict entries that are less likely to be used again. For example, the map
   * may evict an entry because it hasn't been used recently or very often.
   *
   * <p>When {@code size} is zero, elements can be successfully added to the map, but are evicted
   * immediately.
   *
   * @param size the maximum size of the map
   *
   * @throws IllegalArgumentException if {@code size} is negative
   * @throws IllegalStateException if a maximum size was already set
   * @since Guava release 08
   */
  @Beta
  @Override
  public MapMaker maximumSize(int size) {
    checkState(this.maximumSize == UNSET_INT, "maximum size was already set to %s",
        this.maximumSize);
    checkArgument(size >= 0, "maximum size must not be negative");
    this.maximumSize = size;
    this.useCustomMap = true;
    if (maximumSize == 0) {
      // SIZE trumps EXPIRED
      this.nullRemovalCause = RemovalCause.SIZE;
    }
    return this;
  }

  /**
   * Guides the allowed concurrency among update operations. Used as a hint for internal sizing. The
   * table is internally partitioned to try to permit the indicated number of concurrent updates
   * without contention. Because placement in hash tables is essentially random, the actual
   * concurrency will vary. Ideally, you should choose a value to accommodate as many threads as
   * will ever concurrently modify the table. Using a significantly higher value than you need can
   * waste space and time, and a significantly lower value can lead to thread contention. But
   * overestimates and underestimates within an order of magnitude do not usually have much
   * noticeable impact. A value of one is appropriate when it is known that only one thread will
   * modify and all others will only read. Defaults to 4.
   *
   * <p><b>Note:</b> Prior to Guava release 09, the default was 16. It is possible the default will
   * change again in the future. If you care about this value, you should always choose it
   * explicitly.
   *
   * @throws IllegalArgumentException if {@code concurrencyLevel} is nonpositive
   * @throws IllegalStateException if a concurrency level was already set
   */
  @Override
  public MapMaker concurrencyLevel(int concurrencyLevel) {
    checkState(this.concurrencyLevel == UNSET_INT, "concurrency level was already set to %s",
        this.concurrencyLevel);
    checkArgument(concurrencyLevel > 0);
    this.concurrencyLevel = concurrencyLevel;
    return this;
  }

  int getConcurrencyLevel() {
    return (concurrencyLevel == UNSET_INT) ? DEFAULT_CONCURRENCY_LEVEL : concurrencyLevel;
  }

  /**
   * Specifies that each key (not value) stored in the map should be wrapped in a
   * {@link WeakReference} (by default, strong references are used).
   *
   * <p><b>Note:</b> the map will use identity ({@code ==}) comparison to determine equality of weak
   * keys, which may not behave as you expect. For example, storing a key in the map and then
   * attempting a lookup using a different but {@link Object#equals(Object) equals}-equivalent key
   * will always fail.
   *
   * @throws IllegalStateException if the key strength was already set
   * @see WeakReference
   */
  @GwtIncompatible("java.lang.ref.WeakReference")
  @Override
  public MapMaker weakKeys() {
    return setKeyStrength(Strength.WEAK);
  }

  /**
   * Specifies that each key (not value) stored in the map should be wrapped in a
   * {@link SoftReference} (by default, strong references are used).
   *
   * <p><b>Note:</b> the map will use identity ({@code ==}) comparison to determine equality of soft
   * keys, which may not behave as you expect. For example, storing a key in the map and then
   * attempting a lookup using a different but {@link Object#equals(Object) equals}-equivalent key
   * will always fail.
   *
   * @throws IllegalStateException if the key strength was already set
   * @see SoftReference
   */
  @GwtIncompatible("java.lang.ref.SoftReference")
  @Override
  public MapMaker softKeys() {
    return setKeyStrength(Strength.SOFT);
  }

  MapMaker setKeyStrength(Strength strength) {
    checkState(keyStrength == null, "Key strength was already set to %s", keyStrength);
    keyStrength = checkNotNull(strength);
    if (strength != Strength.STRONG) {
      // STRONG could be used during deserialization.
      useCustomMap = true;
    }
    return this;
  }

  Strength getKeyStrength() {
    return firstNonNull(keyStrength, Strength.STRONG);
  }

  /**
   * Specifies that each value (not key) stored in the map should be wrapped in a
   * {@link WeakReference} (by default, strong references are used).
   *
   * <p>Weak values will be garbage collected once they are weakly reachable. This makes them a poor
   * candidate for caching; consider {@link #softValues()} instead.
   *
   * <p><b>Note:</b> the map will use identity ({@code ==}) comparison to determine equality of weak
   * values. This will notably impact the behavior of {@link Map#containsValue(Object)
   * containsValue}, {@link ConcurrentMap#remove(Object, Object) remove(Object, Object)}, and
   * {@link ConcurrentMap#replace(Object, Object, Object) replace(K, V, V)}.
   *
   * @throws IllegalStateException if the value strength was already set
   * @see WeakReference
   */
  @GwtIncompatible("java.lang.ref.WeakReference")
  @Override
  public MapMaker weakValues() {
    return setValueStrength(Strength.WEAK);
  }

  /**
   * Specifies that each value (not key) stored in the map should be wrapped in a
   * {@link SoftReference} (by default, strong references are used).
   *
   * <p>Soft values will be garbage collected in response to memory demand, and in a
   * least-recently-used manner. This makes them a good candidate for caching.
   *
   * <p><b>Note:</b> the map will use identity ({@code ==}) comparison to determine equality of soft
   * values. This will notably impact the behavior of {@link Map#containsValue(Object)
   * containsValue}, {@link ConcurrentMap#remove(Object, Object) remove(Object, Object)}, and
   * {@link ConcurrentMap#replace(Object, Object, Object) replace(K, V, V)}.
   *
   * @throws IllegalStateException if the value strength was already set
   * @see SoftReference
   */
  @GwtIncompatible("java.lang.ref.SoftReference")
  @Override
  public MapMaker softValues() {
    return setValueStrength(Strength.SOFT);
  }

  MapMaker setValueStrength(Strength strength) {
    checkState(valueStrength == null, "Value strength was already set to %s", valueStrength);
    valueStrength = checkNotNull(strength);
    if (strength != Strength.STRONG) {
      // STRONG could be used during deserialization.
      useCustomMap = true;
    }
    return this;
  }

  Strength getValueStrength() {
    return firstNonNull(valueStrength, Strength.STRONG);
  }

  /**
   * Old name of {@link #expireAfterWrite}.
   *
   * @deprecated use {@link #expireAfterWrite}, which behaves exactly the same. <b>This method is
   *     scheduled for deletion in July 2012.</b>
   */
  @Deprecated
  @Override
  public MapMaker expiration(long duration, TimeUnit unit) {
    return expireAfterWrite(duration, unit);
  }

  /**
   * Specifies that each entry should be automatically removed from the map once a fixed duration
   * has passed since the entry's creation or replacement. Note that changing the value of an entry
   * will reset its expiration time.
   *
   * <p>When {@code duration} is zero, elements can be successfully added to the map, but are
   * evicted immediately.
   *
   * @param duration the length of time after an entry is created that it should be automatically
   *     removed
   * @param unit the unit that {@code duration} is expressed in
   * @throws IllegalArgumentException if {@code duration} is negative
   * @throws IllegalStateException if the time to live or time to idle was already set
   * @since Guava release 08
   */
  @Override
  public MapMaker expireAfterWrite(long duration, TimeUnit unit) {
    checkExpiration(duration, unit);
    this.expireAfterWriteNanos = unit.toNanos(duration);
    if (duration == 0 && this.nullRemovalCause == null) {
      // SIZE trumps EXPIRED
      this.nullRemovalCause = RemovalCause.EXPIRED;
    }
    useCustomMap = true;
    return this;
  }

  private void checkExpiration(long duration, TimeUnit unit) {
    checkState(expireAfterWriteNanos == UNSET_INT, "expireAfterWrite was already set to %s ns",
        expireAfterWriteNanos);
    checkState(expireAfterAccessNanos == UNSET_INT, "expireAfterAccess was already set to %s ns",
        expireAfterAccessNanos);
    checkArgument(duration >= 0, "duration cannot be negative: %s %s", duration, unit);
  }

  long getExpireAfterWriteNanos() {
    return (expireAfterWriteNanos == UNSET_INT) ? DEFAULT_EXPIRATION_NANOS : expireAfterWriteNanos;
  }

  /**
   * Specifies that each entry should be automatically removed from the map once a fixed duration
   * has passed since the entry's last read or write access.
   *
   * <p>When {@code duration} is zero, elements can be successfully added to the map, but are
   * evicted immediately.
   *
   * @param duration the length of time after an entry is last accessed that it should be
   *     automatically removed
   * @param unit the unit that {@code duration} is expressed in
   * @throws IllegalArgumentException if {@code duration} is negative
   * @throws IllegalStateException if the time to idle or time to live was already set
   * @since Guava release 08
   */
  @GwtIncompatible("To be supported")
  @Override
  public MapMaker expireAfterAccess(long duration, TimeUnit unit) {
    checkExpiration(duration, unit);
    this.expireAfterAccessNanos = unit.toNanos(duration);
    if (duration == 0 && this.nullRemovalCause == null) {
      // SIZE trumps EXPIRED
      this.nullRemovalCause = RemovalCause.EXPIRED;
    }
    useCustomMap = true;
    return this;
  }

  long getExpireAfterAccessNanos() {
    return (expireAfterAccessNanos == UNSET_INT)
        ? DEFAULT_EXPIRATION_NANOS : expireAfterAccessNanos;
  }

  Executor getCleanupExecutor() {
    return cleanupExecutor;
  }

  Ticker getTicker() {
    return firstNonNull(ticker, DEFAULT_TICKER);
  }

  /**
   * Specifies a listener instance, which all maps built using this {@code MapMaker} will notify
   * each time an entry is removed from the map by any means.
   *
   * <p>A map built by this map maker will invoke the supplied listener after removing an element
   * for any reason (see removal causes in {@link RemovalListener.RemovalCause}). It will invoke
   * the listener during invocations of any of that map's public methods (even read-only methods).
   *
   * <p><b>Important note:</b> Instead of returning <em>this</em> as a {@code MapMaker} instance,
   * this method returns {@code GenericMapMaker<K, V>}. From this point on, either the original
   * reference or the returned reference may be used to complete configuration and build the map,
   * but only the "generic" one is type-safe. That is, it will properly prevent you from building
   * maps whose key or value types are incompatible with the types accepted by the listener already
   * provided; the {@code MapMaker} type cannot do this. For best results, simply use the standard
   * method-chaining idiom, as illustrated in the documentation at top, configuring a {@code
   * MapMaker} and building your {@link Map} all in a single statement.
   *
   * <p><b>Warning:</b> if you ignore the above advice, and use this {@code MapMaker} to build maps
   * whose key or value types are incompatible with the listener, you will likely experience a
   * {@link ClassCastException} at an undefined point in the future.
   *
   * @throws IllegalStateException if a removal listener was already set
   */
  @GwtIncompatible("To be supported")
  // TODO(user): make public when fully tested
  <K, V> GenericMapMaker<K, V> removalListener(RemovalListener<K, V> listener) {
    checkState(this.removalListener == null);

    // safely limiting the kinds of maps this can produce
    @SuppressWarnings("unchecked")
    GenericMapMaker<K, V> me = (GenericMapMaker<K, V>) this;
    me.removalListener = checkNotNull(listener);
    useCustomMap = true;
    return me;
  }

  /**
   * Specifies a listener instance, which all maps built using this {@code MapMaker} will notify
   * each time an entry is evicted.
   *
   * <p>A map built by this map maker will invoke the supplied listener after it evicts an entry,
   * whether it does so due to timed expiration, exceeding the maximum size, or discovering that the
   * key or value has been reclaimed by the garbage collector. It will invoke the listener
   * during invocations of any of that map's public methods (even read-only methods). The listener
   * will <i>not</i> be invoked on manual removal.
   *
   * <p><b>Important note:</b> Instead of returning <em>this</em> as a {@code MapMaker} instance,
   * this method returns {@code GenericMapMaker<K, V>}. From this point on, either the original
   * reference or the returned reference may be used to complete configuration and build the map,
   * but only the "generic" one is type-safe. That is, it will properly prevent you from building
   * maps whose key or value types are incompatible with the types accepted by the listener already
   * provided; the {@code MapMaker} type cannot do this. For best results, simply use the standard
   * method-chaining idiom, as illustrated in the documentation at top, configuring a {@code
   * MapMaker} and building your {@link Map} all in a single statement.
   *
   * <p><b>Warning:</b> if you ignore the above advice, and use this {@code MapMaker} to build maps
   * whose key or value types are incompatible with the listener, you will likely experience a
   * {@link ClassCastException} at an undefined point in the future.
   *
   * @throws IllegalStateException if an eviction listener was already set
   * @since Guava release 07
   */
  @Beta
  @GwtIncompatible("To be supported")
  public <K, V> GenericMapMaker<K, V> evictionListener(MapEvictionListener<K, V> listener) {
    checkState(this.removalListener == null);

    // safely limiting the kinds of maps this can produce
    @SuppressWarnings("unchecked")
    GenericMapMaker<K, V> me = (GenericMapMaker<K, V>) this;
    me.removalListener = new EvictionToRemovalListener<K, V>(listener);
    useCustomMap = true;
    return me;
  }

  /**
   * Builds a map, without on-demand computation of values. This method does not alter the state of
   * this {@code MapMaker} instance, so it can be invoked again to create multiple independent maps.
   *
   * <p>Insertion, removal, update, and access operations on the returned map safely execute
   * concurrently by multiple threads. Iterators on the returned map are weakly consistent,
   * returning elements reflecting the state of the map at some point at or since the creation of
   * the iterator. They do not throw {@link ConcurrentModificationException}, and may proceed
   * concurrently with other operations.
   *
   * <p>The bulk operations {@code putAll}, {@code equals}, and {@code clear} are not guaranteed to
   * be performed atomically on the returned map. Additionally, {@code size} and {@code
   * containsValue} are implemented as bulk read operations, and thus may fail to observe concurrent
   * writes.
   *
   * @return a serializable concurrent map having the requested features
   */
  @Override
  public <K, V> ConcurrentMap<K, V> makeMap() {
    if (!useCustomMap) {
      return new ConcurrentHashMap<K, V>(getInitialCapacity(), 0.75f, getConcurrencyLevel());
    }
    return (nullRemovalCause == null)
        ? new CustomConcurrentHashMap<K, V>(this, DEFAULT_STATS_COUNTER)
        : new NullConcurrentMap<K, V>(this);
  }

  /**
   * Builds a map that supports atomic, on-demand computation of values. {@link Map#get} either
   * returns an already-computed value for the given key, atomically computes it using the supplied
   * function, or, if another thread is currently computing the value for this key, simply waits for
   * that thread to finish and returns its computed value. Note that the function may be executed
   * concurrently by multiple threads, but only for distinct keys.
   *
   * <p>If an entry's value has not finished computing yet, query methods besides {@code get} return
   * immediately as if an entry doesn't exist. In other words, an entry isn't externally visible
   * until the value's computation completes.
   *
   * <p>{@link Map#get} on the returned map will never return {@code null}. It may throw:
   *
   * <ul>
   * <li>{@link NullPointerException} if the key is null or the computing function returns null
   * <li>{@link ComputationException} if an exception was thrown by the computing function. If that
   * exception is already of type {@link ComputationException} it is propagated directly; otherwise
   * it is wrapped.
   * </ul>
   *
   * <p><b>Note:</b> Callers of {@code get} <i>must</i> ensure that the key argument is of type
   * {@code K}. The {@code get} method accepts {@code Object}, so the key type is not checked at
   * compile time. Passing an object of a type other than {@code K} can result in that object being
   * unsafely passed to the computing function as type {@code K}, and unsafely stored in the map.
   *
   * <p>If {@link Map#put} is called before a computation completes, other threads waiting on the
   * computation will wake up and return the stored value.
   *
   * <p>This method does not alter the state of this {@code MapMaker} instance, so it can be invoked
   * again to create multiple independent maps.
   *
   * <p>Insertion, removal, update, and access operations on the returned map safely execute
   * concurrently by multiple threads. Iterators on the returned map are weakly consistent,
   * returning elements reflecting the state of the map at some point at or since the creation of
   * the iterator. They do not throw {@link ConcurrentModificationException}, and may proceed
   * concurrently with other operations.
   *
   * <p>The bulk operations {@code putAll}, {@code equals}, and {@code clear} are not guaranteed to
   * be performed atomically on the returned map. Additionally, {@code size} and {@code
   * containsValue} are implemented as bulk read operations, and thus may fail to observe concurrent
   * writes.
   *
   * @param computingFunction the function used to compute new values
   * @return a serializable concurrent map having the requested features
   */
  @Override
  public <K, V> ConcurrentMap<K, V> makeComputingMap(
      Function<? super K, ? extends V> computingFunction) {
    return useNullCache()
        ? new ComputingMapAdapter<K, V>(this, CACHE_STATS_COUNTER, computingFunction)
        : new NullComputingConcurrentMap<K, V>(this, computingFunction);
  }

  /**
   * Returns a string representation for this MapMaker instance. The form of this representation is
   * not guaranteed.
   */
  @Override
  public String toString() {
    Objects.ToStringHelper s = Objects.toStringHelper(this);
    if (initialCapacity != UNSET_INT) {
      s.add("initialCapacity", initialCapacity);
    }
    if (concurrencyLevel != UNSET_INT) {
      s.add("concurrencyLevel", concurrencyLevel);
    }
    if (maximumSize != UNSET_INT) {
      s.add("maximumSize", maximumSize);
    }
    if (expireAfterWriteNanos != UNSET_INT) {
      s.add("expireAfterWrite", expireAfterWriteNanos + "ns");
    }
    if (expireAfterAccessNanos != UNSET_INT) {
      s.add("expireAfterAccess", expireAfterAccessNanos + "ns");
    }
    if (keyStrength != null) {
      s.add("keyStrength", Ascii.toLowerCase(keyStrength.toString()));
    }
    if (valueStrength != null) {
      s.add("valueStrength", Ascii.toLowerCase(valueStrength.toString()));
    }
    if (keyEquivalence != null) {
      s.addValue("keyEquivalence");
    }
    if (valueEquivalence != null) {
      s.addValue("valueEquivalence");
    }
    if (removalListener != null) {
      s.addValue("removalListener");
    }
    if (cleanupExecutor != null) {
      s.addValue("cleanupExecutor");
    }
    return s.toString();
  }

  /**
   * An object that can receive a notification when an entry is removed from a map. The removal
   * resulting in notification could have occured to an entry being manually removed or replaced, or
   * due to eviction resulting from timed expiration, exceeding a maximum size, or garbage
   * collection.
   *
   * <p>An instance may be called concurrently by multiple threads to process different entries.
   * Implementations of this interface should avoid performing blocking calls or synchronizing on
   * shared resources.
   *
   * @param <K> the type of keys being evicted
   * @param <V> the type of values being evicted
   */
  // TODO(user): make public when fully tested
  interface RemovalListener<K, V> {

    public enum RemovalCause {
      /**
       * The entry was manully removed from the map. This could result from a call to
       * {@code Map.remove}, {@code ConcurrentMap.remove}, or {@code Iterator.remove}.
       */
      EXPLICIT {
        @Override
        boolean wasEvicted() {
          return false;
        }
      },

      /**
       * The entry's value was replaced due to a manual operation on the map. This could result from
       * a call to {@code Map.put}, {@code Map.putAll}, or {@code ConcurrentMap.replace}.
       */
      REPLACED {
        @Override
        boolean wasEvicted() {
          return false;
        }
      },

      /**
       * The entry was removed automatically because its key or value was garbage-collected. This
       * could occur on maps created with {@code softKeys}, {@code softValues}, {@code weakKeys}, or
       * {@code weakValues}.
       */
      COLLECTED {
        @Override
        boolean wasEvicted() {
          return true;
        }
      },

      /**
       * The entry expired. This could occur on maps created with {@code expireAfterWrite} or
       * {@code expireAfterAccess}.
       */
      EXPIRED {
        @Override
        boolean wasEvicted() {
          return true;
        }
      },

      /**
       * The entry was evicted from the map due to its size. This could occur on maps created with
       * {@code maximumSize}.
       */
      SIZE {
        @Override
        boolean wasEvicted() {
          return true;
        }
      };

      /**
       * Returns true if the removal was due to eviction, false if the removal was caused by a user
       * operation.
       */
      abstract boolean wasEvicted();
    }

    /**
     * Notifies the listener that a removal has occurred.
     *
     * @param key the key of the entry that has already been evicted, or {@code
     *     null} if its reference was collected
     * @param value the value of the entry that has already been evicted, or
     *     {@code null} if its reference was collected
     * @param cause the cause of the removal
     */
    // TODO(user): pass in a RemovalNotification or something, containing the key, value, cause, a
    // wasEvicted method, possibly a timestamp, and leaving room for adding weights
    void onRemoval(@Nullable K key, @Nullable V value, RemovalCause cause);
  }

  private static class EvictionToRemovalListener<K, V>
      implements RemovalListener<K, V>, Serializable {
    private static final long serialVersionUID = 0;
    private final MapEvictionListener<K, V> evictionListener;

    public EvictionToRemovalListener(MapEvictionListener<K, V> evictionListener) {
      this.evictionListener = checkNotNull(evictionListener);
    }

    @Override
    public void onRemoval(K key, V value, RemovalCause cause) {
      if (cause.wasEvicted()) {
        evictionListener.onEviction(key, value);
      }
    }
  }

  /** A map that is always empty and evicts on insertion. */
  static class NullConcurrentMap<K, V> extends AbstractMap<K, V>
      implements ConcurrentMap<K, V>, Serializable {
    private static final long serialVersionUID = 0;

    final RemovalListener<K, V> removalListener;
    final RemovalCause removalCause;

    @SuppressWarnings("unchecked")
    NullConcurrentMap(MapMaker mapMaker) {
      removalListener = (RemovalListener<K, V>) mapMaker.getRemovalListener();
      removalCause = mapMaker.nullRemovalCause;
    }

    // implements ConcurrentMap

    @Override
    public boolean containsKey(Object key) {
      checkNotNull(key);
      return false;
    }

    @Override
    public boolean containsValue(Object value) {
      checkNotNull(value);
      return false;
    }

    @Override
    public V get(Object key) {
      checkNotNull(key);
      return null;
    }

    @Override
    public V put(K key, V value) {
      checkNotNull(key);
      checkNotNull(value);
      removalListener.onRemoval(key, value, removalCause);
      return null;
    }

    @Override
    public V putIfAbsent(K key, V value) {
      return put(key, value);
    }

    @Override
    public V remove(Object key) {
      checkNotNull(key);
      return null;
    }

    @Override
    public boolean remove(Object key, Object value) {
      checkNotNull(key);
      checkNotNull(value);
      return false;
    }

    @Override
    public V replace(K key, V value) {
      checkNotNull(key);
      checkNotNull(value);
      return null;
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
      checkNotNull(key);
      checkNotNull(oldValue);
      checkNotNull(newValue);
      return false;
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
      return Collections.emptySet();
    }
  }

  /** Computes on retrieval and evicts the result. */
  static final class NullComputingConcurrentMap<K, V> extends NullConcurrentMap<K, V> {
    private static final long serialVersionUID = 0;

    final Function<? super K, ? extends V> computingFunction;

    NullComputingConcurrentMap(MapMaker mapMaker,
        Function<? super K, ? extends V> computingFunction) {
      super(mapMaker);
      this.computingFunction = checkNotNull(computingFunction);
    }

    @SuppressWarnings("unchecked") // unsafe, which is why Cache is preferred
    @Override
    public V get(Object k) {
      K key = (K) k;
      V value = compute(key);
      checkNotNull(value, computingFunction + " returned null for key " + key + ".");
      removalListener.onRemoval(key, value, removalCause);
      return value;
    }

    private V compute(K key) {
      checkNotNull(key);
      try {
        return computingFunction.apply(key);
      } catch (ComputationException e) {
        throw e;
      } catch (Throwable t) {
        throw new ComputationException(t);
      }
    }
  }
}