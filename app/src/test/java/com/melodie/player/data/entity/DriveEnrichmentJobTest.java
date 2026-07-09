package com.melodie.player.data.entity;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests unitaires pour DriveEnrichmentJob
 */
public class DriveEnrichmentJobTest {

    @Test
    public void testJobCreationWithDefaults() {
        DriveEnrichmentJob job = new DriveEnrichmentJob();
        assertEquals("", job.fileId);
        assertFalse(job.needDuration);
        assertFalse(job.needTags);
        assertEquals(0, job.priority);
        assertEquals(DriveEnrichmentJob.STATE_PENDING, job.state);
        assertEquals(0, job.attemptCount);
        assertEquals(0, job.lastAttemptAt);
        assertEquals("", job.lastError);
    }

    @Test
    public void testJobStates() {
        assertEquals("PENDING", DriveEnrichmentJob.STATE_PENDING);
        assertEquals("RUNNING", DriveEnrichmentJob.STATE_RUNNING);
        assertEquals("DONE", DriveEnrichmentJob.STATE_DONE);
        assertEquals("FAILED", DriveEnrichmentJob.STATE_FAILED);
    }

    @Test
    public void testJobConfiguration() {
        DriveEnrichmentJob job = new DriveEnrichmentJob();
        job.fileId = "drive123";
        job.needDuration = true;
        job.needTags = true;
        job.priority = 100;
        job.state = DriveEnrichmentJob.STATE_RUNNING;

        assertEquals("drive123", job.fileId);
        assertTrue(job.needDuration);
        assertTrue(job.needTags);
        assertEquals(100, job.priority);
        assertEquals(DriveEnrichmentJob.STATE_RUNNING, job.state);
    }

    @Test
    public void testJobErrorTracking() {
        DriveEnrichmentJob job = new DriveEnrichmentJob();
        job.fileId = "file001";
        job.lastError = "Connection timeout";
        job.attemptCount = 3;
        job.lastAttemptAt = System.currentTimeMillis();

        assertEquals("Connection timeout", job.lastError);
        assertEquals(3, job.attemptCount);
        assertTrue(job.lastAttemptAt > 0);
    }

    @Test
    public void testJobGeneration() {
        DriveEnrichmentJob job = new DriveEnrichmentJob();
        long generation = 12345L;
        job.generation = generation;
        job.updatedAt = System.currentTimeMillis();

        assertEquals(generation, job.generation);
        assertTrue(job.updatedAt > 0);
    }

    @Test
    public void testDurationOnlyJob() {
        DriveEnrichmentJob job = new DriveEnrichmentJob();
        job.fileId = "file_duration";
        job.needDuration = true;
        job.needTags = false;
        job.priority = 100;

        assertTrue(job.needDuration);
        assertFalse(job.needTags);
    }

    @Test
    public void testTagsOnlyJob() {
        DriveEnrichmentJob job = new DriveEnrichmentJob();
        job.fileId = "file_tags";
        job.needDuration = false;
        job.needTags = true;
        job.priority = 60;

        assertFalse(job.needDuration);
        assertTrue(job.needTags);
    }
}

