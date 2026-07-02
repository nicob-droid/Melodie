package com.melodie.player.di;

import android.content.Context;

import androidx.room.Room;

import com.melodie.player.data.db.AlbumDao;
import com.melodie.player.data.db.FolderSourceDao;
import com.melodie.player.data.db.DriveAudioDao;
import com.melodie.player.data.db.DriveFolderDao;
import com.melodie.player.data.db.DriveSyncStateDao;
import com.melodie.player.data.db.MelodieDatabase;
import com.melodie.player.data.db.PlaylistDao;
import com.melodie.player.data.db.SongDao;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.components.SingletonComponent;

@Module
@InstallIn(SingletonComponent.class)
public class DatabaseModule {

    @Provides
    @Singleton
    public MelodieDatabase provideDatabase(@ApplicationContext Context ctx) {
        return Room.databaseBuilder(ctx, MelodieDatabase.class, "melodie.db")
                .fallbackToDestructiveMigration()
                .build();
    }

    @Provides
    public SongDao provideSongDao(MelodieDatabase db) {
        return db.songDao();
    }

    @Provides
    public AlbumDao provideAlbumDao(MelodieDatabase db) {
        return db.albumDao();
    }

    @Provides
    public PlaylistDao providePlaylistDao(MelodieDatabase db) {
        return db.playlistDao();
    }

    @Provides
    public DriveFolderDao provideDriveFolderDao(MelodieDatabase db) {
        return db.driveFolderDao();
    }

    @Provides
    public DriveAudioDao provideDriveAudioDao(MelodieDatabase db) {
        return db.driveAudioDao();
    }

    @Provides
    public FolderSourceDao provideFolderSourceDao(MelodieDatabase db) {
        return db.folderSourceDao();
    }

    @Provides
    public DriveSyncStateDao provideDriveSyncStateDao(MelodieDatabase db) {
        return db.driveSyncStateDao();
    }
}
