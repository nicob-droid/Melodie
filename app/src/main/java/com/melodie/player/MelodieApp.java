package com.melodie.player;

import android.app.Application;

import androidx.hilt.work.HiltWorkerFactory;
import androidx.work.Configuration;

import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.RequestConfiguration;

import com.melodie.player.data.repository.MusicRepository;

import java.util.Arrays;

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
        // Enregistre les appareils de test : l'émulateur reçoit toujours des annonces de test,
        // et un appareil physique reçoit des annonces de test si son ID (affiché dans le logcat
        // "Ads" au premier chargement) est ajouté ci-dessous. Cela permet de vérifier
        // l'intégration même avec l'unité de production, sans risquer de clics invalides.
        RequestConfiguration configuration = new RequestConfiguration.Builder()
                .setTestDeviceIds(Arrays.asList(
                        com.google.android.gms.ads.AdRequest.DEVICE_ID_EMULATOR,
                        "8BC25F13CEDC3FDAE7FDEA29585379E6" // appareil de test (log "Ads")
                ))
                .build();
        MobileAds.setRequestConfiguration(configuration);
        MobileAds.initialize(this);
    }

    @Override
    public Configuration getWorkManagerConfiguration() {
        return new Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .build();
    }
}

