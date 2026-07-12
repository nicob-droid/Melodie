package com.melodie.player;

import android.app.Application;

import androidx.hilt.work.HiltWorkerFactory;
import androidx.work.Configuration;

import com.google.android.gms.ads.MobileAds;

import com.melodie.player.data.repository.MusicRepository;

import javax.inject.Inject;

import dagger.hilt.android.HiltAndroidApp;

@HiltAndroidApp
public class MelodieApp extends Application implements Configuration.Provider {

    @Inject
    HiltWorkerFactory workerFactory;

    @Inject
    MusicRepository musicRepository;

    @Override
    public void onCreate() {
        super.onCreate();
        MobileAds.initialize(this);
        // Re-tente UNE fois les pochettes précédemment marquées "introuvables".
        musicRepository.retryMissingCoversOnStartup();
    }

    @Override
    public Configuration getWorkManagerConfiguration() {
        return new Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .build();
    }
}

