package com.melodie.player.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "drive_audio_files")
public class DriveAudio {
    public static final String METADATA_STATUS_DISCOVERED = "DISCOVERED";
    public static final String METADATA_STATUS_DURATION_DONE = "DURATION_DONE";
    public static final String METADATA_STATUS_TAGS_DONE = "TAGS_DONE";
    public static final String METADATA_STATUS_FAILED = "FAILED";

    public static final String ARTIST_SOURCE_UNKNOWN = "UNKNOWN";
    public static final String ARTIST_SOURCE_EMBEDDED_ALBUM_ARTIST = "EMBEDDED_ALBUM_ARTIST";
    public static final String ARTIST_SOURCE_EMBEDDED_ARTIST = "EMBEDDED_ARTIST";
    public static final String ARTIST_SOURCE_SIBLING_PROPAGATION = "SIBLING_PROPAGATION";
    public static final String ARTIST_SOURCE_FOLDER_INFERENCE = "FOLDER_INFERENCE";
    public static final String ARTIST_SOURCE_FILENAME_INFERENCE = "FILENAME_INFERENCE";

    @PrimaryKey
    @NonNull
    public String fileId = "";

    @NonNull
    public String fileName = "";

    @NonNull
    public String folderId = "";

    /** ID FolderSource associe a ce fichier Drive (racine selectionnee). */
    public long folderSourceId;

    /** ID de la racine Drive (My Drive/Shared root) si connu. */
    @NonNull
    public String rootDriveId = "";

    @NonNull
    public String mimeType = "";

    @NonNull
    public String md5Checksum = "";

    public long fileSize;

    public long lastModified;

    /** Timestamp local de decouverte/indexation. */
    public long discoveredAt;

    /** Generation de sync a laquelle ce fichier a ete vu pour la derniere fois. */
    public long lastSeenSyncGeneration;

    /** Marqueur logique (fichier supprime/non vu dans la derniere generation). */
    public boolean removed;

    /** Duration from Google Drive audio metadata (ms). 0 if unavailable. */
    public long durationMs;

    /** Track number from Google Drive audio metadata. 0 if unavailable. */
    public int trackNumber;

    @NonNull
    public String metadataStatus = METADATA_STATUS_DISCOVERED;

    public int metadataAttempts;

    public long lastMetadataAttemptAt;

    @NonNull
    public String lastMetadataError = "";

    @NonNull
    public String embeddedTitle = "";

    @NonNull
    public String embeddedArtist = "";

    @NonNull
    public String embeddedAlbumArtist = "";

    @NonNull
    public String embeddedAlbum = "";

    @NonNull
    public String embeddedYear = "";

    public int embeddedTrackNumber;

    @NonNull
    public String artistSource = ARTIST_SOURCE_UNKNOWN;

    public int artistConfidence;

    @NonNull
    public String coverState = "UNKNOWN";

    @NonNull
    public String webContentLink = "";

    public boolean downloaded;

    @NonNull
    public String localPath = "";
}

