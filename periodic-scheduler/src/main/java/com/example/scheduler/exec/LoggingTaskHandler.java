package com.example.scheduler.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Default handler: logs the execution and succeeds. Replace with a real {@link TaskHandler}. */
@Component
public class LoggingTaskHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(LoggingTaskHandler.class);

    @Override
    public void handle(UUID instanceId, UUID definitionId) {
        log.info("executing instance {} of schedule {}", instanceId, definitionId);
    }
}
