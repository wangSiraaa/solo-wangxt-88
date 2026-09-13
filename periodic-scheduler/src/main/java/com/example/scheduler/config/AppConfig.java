package com.example.scheduler.config;

import com.example.scheduler.calendar.OccurrenceEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;

@Configuration
public class AppConfig {

    /**
     * The single time source of the whole service. Tests replace this bean with a mutable
     * clock to verify leap days, DST transitions and pause/resume deterministically.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public OccurrenceEngine occurrenceEngine() {
        return new OccurrenceEngine();
    }

    @Bean("taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("task-exec-");
        executor.initialize();
        return executor;
    }
}
