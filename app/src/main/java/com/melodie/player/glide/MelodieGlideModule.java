package com.melodie.player.glide;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.module.AppGlideModule;

/**
 * Configuration globale de Glide pour l'application.
 *
 * <p>On élève le niveau de log à {@link Log#ERROR} afin de masquer les avertissements
 * attendus lorsqu'un album local n'a pas de pochette embarquée
 * (FileNotFoundException: "No album art found"). Dans ce cas, l'app retombe
 * automatiquement sur une recherche de pochette en ligne ; ces warnings ne sont
 * donc que du bruit dans Logcat. Les vraies erreurs (niveau ERROR) restent visibles.</p>
 */
@GlideModule
public class MelodieGlideModule extends AppGlideModule {

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        builder.setLogLevel(Log.ERROR);
    }

    @Override
    public boolean isManifestParsingEnabled() {
        // On déclare nos modules par annotation : pas besoin de parser le manifeste.
        return false;
    }
}

