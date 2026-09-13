package com.example.scheduler.quartz;

import com.example.scheduler.service.DispatcherService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Quartz tick that dispatches due instances. Runs on every node; the lease protocol in the
 * database guarantees that competing dispatchers produce exactly one valid lease per instance.
 */
@DisallowConcurrentExecution
public class DispatcherJob extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(DispatcherJob.class);

    @Autowired
    private DispatcherService dispatcherService;

    @Override
    protected void executeInternal(JobExecutionContext context) {
        try {
            dispatcherService.dispatchDue();
        } catch (Exception e) {
            log.error("dispatcher tick failed", e);
        }
    }
}
