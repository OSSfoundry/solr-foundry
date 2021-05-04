/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.update;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.lucene.util.BytesRef;
import org.apache.solr.common.ParWork;
import org.apache.solr.common.SolrException;
import org.jctools.maps.NonBlockingHashMap;

// TODO: make inner?
// TODO: store the highest possible in the index on a commit (but how to not block adds?)
// TODO: could also store highest possible in the transaction log after a commit.
// Or on a new index, just scan "version" for the max?
/** @lucene.internal */
/**
 * The default implementation which uses the intrinsic object monitor.
 * It uses less memory but ignores the <code>lockTimeoutMs</code>.
 */
public class VersionBucket {
  public AtomicLong highest = new AtomicLong();

  public ReentrantLock getLock() {
    return lock;
  }

  private final ReentrantLock lock = new ReentrantLock(true);
  private final Condition lockCondition = lock.newCondition();

  private Map<BytesRef,LongAdder> blockedIds = new NonBlockingHashMap<>();

  public void updateHighest(long val) {
    highest.updateAndGet(operand -> Math.max(operand, Math.abs(val)));
  }

  @FunctionalInterface
  public interface CheckedFunction<T, R> {
     R apply() throws IOException;
  }

  public <T, R> R runWithLock(int lockTimeoutMs, CheckedFunction<T,R> function, BytesRef idBytes) throws IOException {
    lock.lock();
    try {
      if (!blockedIds.containsKey(idBytes)) {
        LongAdder adder = new LongAdder();
        adder.increment();
        blockedIds.put(idBytes, adder);
      } else {
        LongAdder adder = blockedIds.get(idBytes);

        while (true) {
          final long longValue = adder.longValue();
          if (!(longValue > 0)) break;
          try {
            lockCondition.awaitNanos(250);
          } catch (InterruptedException e) {
            throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
          }
        }
        adder = blockedIds.get(idBytes);
        if (adder == null) {
          adder = new LongAdder();
          adder.increment();
          blockedIds.put(idBytes, adder);
        }
      }
      return function.apply();
    } finally {
      try {
        LongAdder adder = blockedIds.get(idBytes);
        if (adder != null) {
          adder.decrement();
          if (adder.longValue() == 0L) {
            blockedIds.remove(idBytes);
          }
        }
      } finally {
        lock.unlock();
      }
    }
  }

  public void signalAll() {
    lock.lock();
    try {
      lockCondition.signalAll();
    } finally {
      lock.unlock();
    }
  }

  public void awaitNanos(long nanosTimeout) {
    try {
      if (nanosTimeout > 0) {
        lockCondition.awaitNanos(nanosTimeout);
      }
    } catch (InterruptedException e) {
      ParWork.propagateInterrupt(e);
      throw new RuntimeException(e);
    }
  }

}
