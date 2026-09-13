package com.example.scheduler.exec;

import java.util.UUID;

/**
 * Business logic executed for a due instance. Implement this interface to plug in real work.
 * The default is {@link LoggingTaskHandler}.
 *
 * <p>Contract: the instance is LEASED to this node while {@code handle} runs. Throwing any
 * exception marks the attempt as failed and (while retries remain) returns the SAME instance
 * to PLANNED with its retry count incremented.
 */
@FunctionalInterface
public interface TaskHandler {
    void handle(UUID instanceId, UUID definitionId) throws Exception;
}
