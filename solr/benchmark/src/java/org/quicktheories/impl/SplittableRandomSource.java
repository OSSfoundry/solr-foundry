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
package org.quicktheories.impl;

import org.quicktheories.core.DetatchedRandomnessSource;

import java.util.SplittableRandom;

public class SplittableRandomSource implements ExtendedRandomnessSource {
  SplittableRandom random;

  public SplittableRandomSource(SplittableRandom random) {
    this.random = random;
  }

  @Override
  public long next(Constraint constraints) {
    if (constraints.min() == constraints.max()) {
      throw new RuntimeException(constraints.min() + " " + constraints.max());
    }

    return random.nextLong(constraints.min(), constraints.max() + 1);
  }

  @Override
  public DetatchedRandomnessSource detach() {
    return new ConcreteDetachedSource(this);
  }

  @Override
  public void registerFailedAssumption() {}

  @Override
  public long tryNext(Constraint constraints) {
    return next(constraints);
  }

  @Override
  public void add(Precursor other) {}
}
