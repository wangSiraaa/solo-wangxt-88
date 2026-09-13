package com.example.scheduler.quartz;

import com.example.scheduler.service.PlannerService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Quartz tick that materializes upcoming trigger instances. Runs on every node; correctness
 * under concurrency comes from the database (unique occurrence keys), not from Quartz.
 */
@DisallowConcurrentExecution
public class PlannerJob extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(PlannerJob.class);

    @Autowired
    private PlannerService plannerService;

    @Override
    protected void executeInternal(JobExecutionContext context) {
        try {
            plannerService.materializeAll();
        } catch (Exception e) {
            log.error("planner tick failed", e);
        }
    }
}
