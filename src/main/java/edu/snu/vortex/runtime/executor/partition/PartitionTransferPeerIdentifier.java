/*
 * Copyright (C) 2017 Seoul National University
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.snu.vortex.runtime.executor.partition;

import org.apache.reef.wake.Identifier;

/**
 * Identifier for {@link PartitionTransferPeer}.
 */
public final class PartitionTransferPeerIdentifier implements Identifier {
  private final String executorId;

  /**
   * Return the identifier of the specified {@link PartitionTransferPeer}.
   * @param executorId id of the {@link edu.snu.vortex.runtime.executor.Executor}
   *                   to which the specified {@link PartitionTransferPeer} belongs
   */
  public PartitionTransferPeerIdentifier(final String executorId) {
    this.executorId = executorId;
  }

  @Override
  public String toString() {
    return "partitionpeer://" + executorId;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    final PartitionTransferPeerIdentifier that = (PartitionTransferPeerIdentifier) o;
    return executorId.equals(that.executorId);
  }

  @Override
  public int hashCode() {
    return executorId.hashCode();
  }
}