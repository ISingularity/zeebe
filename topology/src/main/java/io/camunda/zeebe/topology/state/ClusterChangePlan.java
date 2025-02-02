/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.topology.state;

import io.atomix.cluster.MemberId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents the ongoing cluster topology changes. The pendingOperations are executed sequentially.
 * Only after completing one operation, the next operation is started. Once an operation is
 * completed, it should be removed from the plan, so that the next operation can be picked up.
 *
 * <p>version starts at 1 and increments every time an operation is completed and removed from the
 * pending operations. This helps to choose the latest state of the topology change when receiving
 * gossip update out of order.
 */
public record ClusterChangePlan(
    long id,
    int version,
    Status status,
    Instant startedAt,
    List<CompletedOperation> completedOperations,
    List<TopologyChangeOperation> pendingOperations) {

  public static ClusterChangePlan init(
      final long id, final List<TopologyChangeOperation> operations) {
    return new ClusterChangePlan(
        id, 1, Status.IN_PROGRESS, Instant.now(), List.of(), List.copyOf(operations));
  }

  /** To be called when the first operation is completed. */
  ClusterChangePlan advance() {
    // List#subList hold on to the original list. Make a copy to prevent a potential memory leak.
    final var nextPendingOperations =
        List.copyOf(pendingOperations.subList(1, pendingOperations.size()));
    final var newCompletedOperations = new ArrayList<>(completedOperations);
    newCompletedOperations.add(new CompletedOperation(pendingOperations.get(0), Instant.now()));
    return new ClusterChangePlan(
        id, version + 1, status, startedAt(), newCompletedOperations, nextPendingOperations);
  }

  CompletedChange completed() {
    return new CompletedChange(id, Status.COMPLETED, startedAt(), Instant.now());
  }

  public ClusterChangePlan merge(final ClusterChangePlan other) {
    // Pick the highest version
    if (other == null) {
      return this;
    }
    if (other.version > version) {
      return other;
    }
    return this;
  }

  public boolean hasPendingChangesFor(final MemberId memberId) {
    return !pendingOperations.isEmpty() && pendingOperations.get(0).memberId().equals(memberId);
  }

  public TopologyChangeOperation nextPendingOperation() {
    return pendingOperations().get(0);
  }

  public boolean hasPendingChanges() {
    return !pendingOperations().isEmpty();
  }

  public record CompletedOperation(TopologyChangeOperation operation, Instant completedAt) {}

  public enum Status {
    IN_PROGRESS,
    COMPLETED,
    FAILED;
  }
}
