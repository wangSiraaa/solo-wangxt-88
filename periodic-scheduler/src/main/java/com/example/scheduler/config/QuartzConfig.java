package com.example.scheduler.config;

import com.example.scheduler.quartz.DispatcherJob;
import com.example.scheduler.quartz.PlannerJob;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Quartz is used as the in-process tick source (planner + dispatcher). Cross-process
 * correctness does NOT depend on Quartz: it is enforced by PostgreSQL constraints
 * (unique occurrence keys, one lease row per instance, guarded status transitions).
 * To also make the ticks cluster-aware, switch {@code spring.quartz.job-store-type}
 * to {@code jdbc} — the guarantees above remain unchanged.
 */
@Configuration
public class QuartzConfig {

    @Bean
    public JobDetail plannerJobDetail() {
        return JobBuilder.newJob(PlannerJob.class).withIdentity("plannerJob").storeDurably().build();
    }

    @Bean
    public Trigger plannerTrigger(JobDetail plannerJobDetail,
                                  @Value("${app.planner.tick:PT30S}") Duration tick) {
        return TriggerBuilder.newTrigger().forJob(plannerJobDetail).withIdentity("plannerTrigger")
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInMilliseconds(Math.max(100, tick.toMillis()))
                        .repeatForever()
                        .withMisfireHandlingInstructionNextWithRemainingCount())
                .build();
    }

    @Bean
    public JobDetail dispatcherJobDetail() {
        return JobBuilder.newJob(DispatcherJob.class).withIdentity("dispatcherJob").storeDurably().build();
    }

    @Bean
    public Trigger dispatcherTrigger(JobDetail dispatcherJobDetail,
                                     @Value("${app.dispatcher.tick:PT2S}") Duration tick) {
        return TriggerBuilder.newTrigger().forJob(dispatcherJobDetail).withIdentity("dispatcherTrigger")
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInMilliseconds(Math.max(100, tick.toMillis()))
                        .repeatForever()
                        .withMisfireHandlingInstructionNextWithRemainingCount())
                .build();
    }
}
