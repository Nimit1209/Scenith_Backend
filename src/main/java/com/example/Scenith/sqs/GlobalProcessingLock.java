package com.example.Scenith.sqs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Global semaphore to ensure only ONE heavy processing task runs at a time.
 * Prevents memory exhaustion on small EC2 instances (t3.medium with 4GB RAM).
 * 
 * This is a critical stability component that prevents OOM conditions by:
 * - Allowing only 1 heavy task (video export, subtitle rendering, etc.) at once
 * - Queueing additional tasks until the current one completes
 * - Using fair ordering (FIFO) to prevent starvation
 * 
 * Trade-off: Sequential processing is slower than parallel, but prevents crashes.
 */
@Component
public class GlobalProcessingLock {
    private static final Logger logger = LoggerFactory.getLogger(GlobalProcessingLock.class);
    
    // Only 1 permit = only 1 heavy task can run at once
    // Fair = true ensures FIFO ordering (first come, first served)
    private final Semaphore processingLock = new Semaphore(1, true);
    
    /**
     * Acquire lock before starting heavy processing.
     * Blocks until lock is available or timeout occurs.
     * 
     * @param taskType Description of the task (for logging)
     * @param taskId Unique identifier for the task (for logging)
     * @return true if lock acquired successfully, false if timeout
     */
    public boolean acquireLock(String taskType, String taskId) {
        try {
            int waiting = processingLock.getQueueLength();
            if (waiting > 0) {
                logger.info("⏳ Task '{}' [{}] waiting for processing lock... ({} tasks ahead)", 
                    taskType, taskId, waiting);
            }
            
            // Wait up to 30 minutes for lock (adjust based on your typical job duration)
            boolean acquired = processingLock.tryAcquire(30, TimeUnit.MINUTES);
            
            if (acquired) {
                logger.info("🔒 Task '{}' [{}] acquired processing lock - starting execution", 
                    taskType, taskId);
            } else {
                logger.error("❌ Task '{}' [{}] timed out waiting for lock after 30 minutes", 
                    taskType, taskId);
            }
            return acquired;
        } catch (InterruptedException e) {
            logger.error("Task '{}' [{}] interrupted while waiting for lock", taskType, taskId);
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    /**
     * Release lock after processing completes (success or failure).
     * MUST be called in a finally block to prevent deadlocks.
     * 
     * @param taskType Description of the task (for logging)
     * @param taskId Unique identifier for the task (for logging)
     */
    public void releaseLock(String taskType, String taskId) {
        processingLock.release();
        int waiting = processingLock.getQueueLength();
        logger.info("🔓 Task '{}' [{}] released processing lock ({} tasks waiting)", 
            taskType, taskId, waiting);
    }
    
    /**
     * Get number of tasks currently waiting for the lock.
     * Useful for monitoring and metrics.
     */
    public int getQueueLength() {
        return processingLock.getQueueLength();
    }
    
    /**
     * Check if lock is currently available (not in use).
     */
    public boolean isAvailable() {
        return processingLock.availablePermits() > 0;
    }
}