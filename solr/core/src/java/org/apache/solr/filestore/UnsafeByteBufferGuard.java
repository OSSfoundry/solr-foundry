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
package org.apache.solr.filestore;

import org.agrona.MutableDirectBuffer;
import org.apache.lucene.store.ByteBufferIndexInput;
import org.apache.solr.common.AlreadyClosedException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A guard that is created for every {@link ByteBufferIndexInput} that tries on best effort to
 * reject any access to the {@link ByteBuffer} behind, once it is unmapped. A single instance of
 * this is used for the original and all clones, so once the original is closed and unmapped all
 * clones also throw {@link AlreadyClosedException}, triggered by a {@link NullPointerException}.
 *
 * <p>This code tries to hopefully flush any CPU caches using a store-store barrier. It also yields
 * the current thread to give other threads a chance to finish in-flight requests...
 */
final class UnsafeByteBufferGuard {

  /**
   * Pass in an implementation of this interface to cleanup ByteBuffers. MMapDirectory implements
   * this to allow unmapping of bytebuffers with private Java APIs.
   */
  @FunctionalInterface
  static interface BufferCleaner {
    void freeBuffer(String resourceDescription, BulkUnsafeBuffer b) throws IOException;
  }

  private final String resourceDescription;
  private final BufferCleaner cleaner;

  /** Not volatile; see comments on visibility below! */
  private boolean invalidated = false;

  /** Used as a store-store barrier; see comments below! */
  private final AtomicInteger barrier = new AtomicInteger();

  /**
   * Creates an instance to be used for a single {@link ByteBufferIndexInput} which must be shared
   * by all of its clones.
   */
  public UnsafeByteBufferGuard(String resourceDescription, BufferCleaner cleaner) {
    this.resourceDescription = resourceDescription;
    this.cleaner = cleaner;
  }

  /** Invalidates this guard and unmaps (if supported). */
  public void invalidateAndUnmap(BulkUnsafeBuffer... bufs) throws IOException {
    if (cleaner != null) {
      invalidated = true;
      // This call should hopefully flush any CPU caches and as a result make
      // the "invalidated" field update visible to other threads. We specifically
      // don't make "invalidated" field volatile for performance reasons, hoping the
      // JVM won't optimize away reads of that field and hardware should ensure
      // caches are in sync after this call. This isn't entirely "fool-proof"
      // (see LUCENE-7409 discussion), but it has been shown to work in practice
      // and we count on this behavior.
      barrier.lazySet(0);
      // we give other threads a bit of time to finish reads on their ByteBuffer...:
      Thread.yield();
      // finally unmap the ByteBuffers:
      for (BulkUnsafeBuffer b : bufs) {
        cleaner.freeBuffer(resourceDescription, b);
      }
    }
  }

  private void ensureValid() {
    if (invalidated) {
      // this triggers an AlreadyClosedException in ByteBufferIndexInput:
      throw new NullPointerException();
    }
  }

  public void getBytes(BulkUnsafeBuffer receiver, long index, byte[] dst, int offset, int length) {
    ensureValid();
    receiver.getBytes(index, dst, offset, length);
  }

//  public void getBytes(BulkUnsafeBuffer receiver, long index, byte[] dst, int offset, int length) {
//    ensureValid();
//    receiver.getBytes(index, dst, offset, length);
//  }
//
  public byte getByte(BulkUnsafeBuffer receiver, long pos) {
    ensureValid();
    return receiver.getByte(pos);
  }

  public short getShort(BulkUnsafeBuffer receiver, long pos) {
    ensureValid();
    return receiver.getShort(pos);
  }

  public int getInt(BulkUnsafeBuffer receiver, long pos) {
    ensureValid();
    return receiver.getInt(pos);
  }

  public long getLong(BulkUnsafeBuffer receiver, long pos) {
    ensureValid();
    return receiver.getLong(pos);
  }

  public void getLongs(BulkUnsafeBuffer receiver, long index, long[] dst, int offset, int length) {
    ensureValid();
    receiver.getLongs(index, dst, offset, length);
  }

  public void getFloats(BulkUnsafeBuffer receiver, long index, float[] dst, int offset, int length) {
    ensureValid();
    receiver.getFloats(index, dst, offset, length);
  }
}
