package com.melodie.player.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.melodie.player.data.entity.DriveEnrichmentJob;

import java.util.List;

@Dao
public interface DriveEnrichmentJobDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(DriveEnrichmentJob job);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<DriveEnrichmentJob> jobs);

    @Query("SELECT fileId FROM drive_enrichment_jobs WHERE needDuration = 1 AND state = :pendingState ORDER BY priority DESC, updatedAt ASC LIMIT :limit")
    List<String> getPendingDurationFileIds(String pendingState, int limit);

    @Query("SELECT fileId FROM drive_enrichment_jobs WHERE needTags = 1 AND state = :pendingState ORDER BY priority DESC, updatedAt ASC LIMIT :limit")
    List<String> getPendingTagFileIds(String pendingState, int limit);

    @Query("UPDATE drive_enrichment_jobs SET needDuration = 0, state = CASE WHEN needTags = 0 THEN 'DONE' ELSE 'PENDING' END, lastError = '', updatedAt = :now WHERE fileId = :fileId")
    void markDurationDone(String fileId, long now);

    @Query("UPDATE drive_enrichment_jobs SET needTags = 0, state = CASE WHEN needDuration = 0 THEN 'DONE' ELSE 'PENDING' END, lastError = '', updatedAt = :now WHERE fileId = :fileId")
    void markTagsDone(String fileId, long now);

    @Query("UPDATE drive_enrichment_jobs SET state = :state, attemptCount = attemptCount + 1, lastAttemptAt = :now, lastError = :error, updatedAt = :now WHERE fileId = :fileId")
    void markAttempt(String fileId, String state, String error, long now);

    @Query("DELETE FROM drive_enrichment_jobs WHERE fileId = :fileId")
    void deleteByFileId(String fileId);

    @Query("DELETE FROM drive_enrichment_jobs WHERE state = 'DONE' AND updatedAt < :olderThanMs")
    void deleteDoneOlderThan(long olderThanMs);

    @Query("SELECT COUNT(*) FROM drive_enrichment_jobs WHERE needDuration = 1")
    int countPendingDuration();

    @Query("SELECT COUNT(*) FROM drive_enrichment_jobs WHERE needTags = 1")
    int countPendingTags();

    @Query("DELETE FROM drive_enrichment_jobs")
    void clear();
}

