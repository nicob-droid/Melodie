package com.melodie.player.data.db;

import androidx.room.Database;
import androidx.room.RoomDatabase;

import com.melodie.player.data.entity.Album;
import com.melodie.player.data.entity.FolderSource;
import com.melodie.player.data.entity.DriveAudio;
import com.melodie.player.data.entity.DriveFolder;
import com.melodie.player.data.entity.Playlist;
import com.melodie.player.data.entity.PlaylistSong;
import com.melodie.player.data.entity.Song;
import com.melodie.player.data.entity.SongOverride;
import com.melodie.player.data.entity.DriveSyncState;
import com.melodie.player.data.entity.DriveSyncSession;
import com.melodie.player.data.entity.DriveEnrichmentJob;

@Database(
        entities = {Song.class, Album.class, Playlist.class, PlaylistSong.class, DriveFolder.class, DriveAudio.class, FolderSource.class, DriveSyncState.class, DriveSyncSession.class, DriveEnrichmentJob.class, SongOverride.class},
        version = 17,
        exportSchema = false
)
public abstract class MelodieDatabase extends RoomDatabase {
    public abstract SongDao songDao();

    public abstract AlbumDao albumDao();

    public abstract SongOverrideDao songOverrideDao();

    public abstract PlaylistDao playlistDao();

    public abstract DriveFolderDao driveFolderDao();

    public abstract DriveAudioDao driveAudioDao();

    public abstract FolderSourceDao folderSourceDao();

    public abstract DriveSyncStateDao driveSyncStateDao();

    public abstract DriveSyncSessionDao driveSyncSessionDao();

    public abstract DriveEnrichmentJobDao driveEnrichmentJobDao();
}
