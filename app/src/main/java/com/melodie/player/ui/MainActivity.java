package com.melodie.player.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;
import com.melodie.player.BuildConfig;
import com.melodie.player.R;
import com.melodie.player.data.repository.DriveRepository;
import com.melodie.player.data.repository.MusicRepository;
import com.melodie.player.playback.PlayerController;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class MainActivity extends AppCompatActivity {

    @Inject
    PlayerController playerController;

    @Inject
    MusicRepository musicRepository;

    @Inject
    DriveRepository driveRepository;

    private ActivityResultLauncher<String[]> permissionLauncher;
    private AdView bannerAdView;
    private android.widget.FrameLayout adContainer;
    private boolean adLoaded = false;
    // Relance automatique de la bannière en cas d'échec réseau (backoff exponentiel plafonné).
    private int adRetryAttempt = 0;
    private static final int AD_MAX_RETRIES = 5;
    private final android.os.Handler adRetryHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    // Bloc bannière de PRODUCTION (release). En debug on utilise l'unité de test Google
    // pour toujours voir une annonce et vérifier l'intégration (les nouvelles unités prod
    // mettent du temps à diffuser et renvoient souvent "no fill" au début).
    private static final String BANNER_AD_UNIT_PROD = "ca-app-pub-7013455903622493/5643616017";
    private static final String BANNER_AD_UNIT_TEST = "ca-app-pub-3940256099942544/6300978111";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        // Edge-to-edge : on dessine sous les barres système et on gère les insets manuellement
        // pour protéger le header (haut) et la zone du bas (pub / mini-player).
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        applyWindowInsets();

        NavHostFragment host = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host);
        NavController nav = host.getNavController();

        // Hide mini player on player full screen
        View miniPlayer = findViewById(R.id.mini_player_container);
        adContainer = findViewById(R.id.ad_container);
        loadBannerAd();
        nav.addOnDestinationChangedListener((c, dest, args) -> {
            boolean onPlayer = dest.getId() == R.id.playerFragment;
            miniPlayer.setVisibility(onPlayer ? View.GONE : View.VISIBLE);
            if (adContainer != null) {
                // Masquée sur le lecteur plein écran. Ailleurs, visible seulement si une
                // annonce a réellement été chargée (évite un espace vide).
                adContainer.setVisibility((!onPlayer && adLoaded) ? View.VISIBLE : View.GONE);
            }
        });

        // Bandeau global de synchronisation Drive : visible tant que la synchro tourne,
        // même après avoir quitté l'écran Drive (la synchro s'exécute en arrière-plan).
        View syncBanner = findViewById(R.id.sync_banner);
        ProgressBar syncProgress = findViewById(R.id.sync_banner_progress);
        TextView syncDetail = findViewById(R.id.sync_banner_detail);
        driveRepository.getIsSyncing().observe(this, syncing ->
                syncBanner.setVisibility(Boolean.TRUE.equals(syncing) ? View.VISIBLE : View.GONE));

        driveRepository.getSyncProgress().observe(this, state -> {
            if (state == null) {
                syncProgress.setIndeterminate(true);
                syncProgress.setMax(100);
                syncProgress.setProgress(0);
                syncDetail.setText("");
                return;
            }

            syncProgress.setIndeterminate(false);
            syncProgress.setMax(100);

            if ("duration".equals(state.phase) || "duration_fast".equals(state.phase) || "tags".equals(state.phase)) {
                int phaseWeight = "tags".equals(state.phase) ? 30 : 20;
                int base = "tags".equals(state.phase) ? 70 : 80;
                int durationPct = state.total > 0
                        ? Math.min(phaseWeight, (state.current * phaseWeight) / Math.max(state.total, 1))
                        : 0;
                int globalPct = Math.min(100, base + durationPct);
                syncProgress.setProgress(globalPct);
                String detail = state.total > 0
                        ? String.format(Locale.getDefault(), "Progression globale: %d%% • Métadonnées %d/%d", globalPct, state.current, state.total)
                        : String.format(Locale.getDefault(), "Progression globale: %d%%", globalPct);
                syncDetail.setText(detail);
                return;
            }

            int basePct = state.total > 0
                    ? Math.min(80, (state.current * 80) / Math.max(state.total, 1))
                    : 5;
            syncProgress.setProgress(basePct);

            String tracksPart = state.tracksTotal > 0
                    ? String.format(Locale.getDefault(), "%d/%d pistes", state.tracksDone, state.tracksTotal)
                    : String.format(Locale.getDefault(), "%d pistes", state.tracksDone);
            syncDetail.setText(String.format(Locale.getDefault(), "Progression globale: %d%% • %s", basePct, tracksPart));
        });

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    if (Boolean.TRUE.equals(result.get(audioPerm()))) {
                        musicRepository.ensureLocalIndexed(null);
                    }
                });

        playerController.init();

        requestPermissionsIfNeeded();
    }

    private String audioPerm() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private void requestPermissionsIfNeeded() {
        Set<String> toAsk = new HashSet<>();
        String audio = audioPerm();
        if (ContextCompat.checkSelfPermission(this, audio) != PackageManager.PERMISSION_GRANTED) {
            toAsk.add(audio);
        } else {
            musicRepository.ensureLocalIndexed(null);
        }
        if (!toAsk.isEmpty()) {
            permissionLauncher.launch(toAsk.toArray(new String[0]));
        }
    }

    private void loadBannerAd() {
        if (adContainer == null) return;
        // AdView créée en code : adSize + adUnitId définis avant loadAd, ce qui évite
        // l'erreur d'inflation "Required XML attribute adSize was missing" (cadre rouge).
        bannerAdView = new AdView(this);
        bannerAdView.setAdSize(AdSize.BANNER);
        // Empêche MIUI/Android de forcer le mode sombre sur le WebView de l'annonce (sinon invisible).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            bannerAdView.setForceDarkAllowed(false);
        }
        // En debug: unité de test (toujours remplie). En release: unité de production.
        String unit = BuildConfig.DEBUG ? BANNER_AD_UNIT_TEST : BANNER_AD_UNIT_PROD;
        bannerAdView.setAdUnitId(unit);
        adContainer.removeAllViews();
        adContainer.addView(bannerAdView);
        bannerAdView.setAdListener(new AdListener() {
            @Override
            public void onAdLoaded() {
                // On n'affiche la bannière que si une annonce a bien été chargée,
                // pour ne pas réserver un espace vide.
                adRetryAttempt = 0;
                adLoaded = true;
                if (adContainer != null) adContainer.setVisibility(View.VISIBLE);
            }

            @Override
            public void onAdFailedToLoad(@NonNull LoadAdError error) {
                android.util.Log.w("AdMob", "Banner failed to load: code=" + error.getCode()
                        + " msg=" + error.getMessage());
                adLoaded = false;
                if (adContainer != null) adContainer.setVisibility(View.GONE);
                // Erreur souvent transitoire (réseau au démarrage) : on retente avec backoff.
                scheduleAdRetry();
            }
        });
        requestBannerAd();
    }

    /** Envoie une requête d'annonce sur la bannière existante. */
    private void requestBannerAd() {
        if (bannerAdView == null) return;
        bannerAdView.loadAd(new AdRequest.Builder().build());
    }

    /** Reprogramme un chargement de bannière avec un backoff exponentiel plafonné à 30s. */
    private void scheduleAdRetry() {
        if (adRetryAttempt >= AD_MAX_RETRIES) return;
        long delaySeconds = (long) Math.min(30, Math.pow(2, adRetryAttempt));
        adRetryAttempt++;
        adRetryHandler.removeCallbacksAndMessages(null);
        adRetryHandler.postDelayed(this::requestBannerAd, delaySeconds * 1000L);
    }

    /**
     * Applique les insets système sur le conteneur racine : padding haut pour la status bar
     * (header protégé) et padding bas pour la navigation/gesture bar (pub, bandeau de synchro
     * et mini-player protégés). Le fond edge-to-edge reste dessiné jusqu'aux bords.
     */
    private void applyWindowInsets() {
        final View root = findViewById(R.id.root_container);
        if (root == null) return;
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    @Override
    protected void onPause() {
        if (bannerAdView != null) {
            bannerAdView.pause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (bannerAdView != null) {
            bannerAdView.resume();
        }
    }

    @Override
    protected void onDestroy() {
        adRetryHandler.removeCallbacksAndMessages(null);
        if (bannerAdView != null) {
            bannerAdView.destroy();
            bannerAdView = null;
        }
        super.onDestroy();
        if (isFinishing()) {
            playerController.release();
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        NavHostFragment host = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host);
        return host.getNavController().navigateUp() || super.onSupportNavigateUp();
    }
}

