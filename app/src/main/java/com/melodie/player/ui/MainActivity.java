package com.melodie.player.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.melodie.player.R;
import com.melodie.player.data.repository.MusicRepository;
import com.melodie.player.playback.PlayerController;

import java.util.HashSet;
import java.util.Set;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class MainActivity extends AppCompatActivity {

    @Inject
    PlayerController playerController;

    @Inject
    MusicRepository musicRepository;

    private ActivityResultLauncher<String[]> permissionLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        NavHostFragment host = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host);
        NavController nav = host.getNavController();

        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        NavigationUI.setupWithNavController(bottomNav, nav);

        // Hide bottom nav + mini player on player full screen
        View miniPlayer = findViewById(R.id.mini_player_container);
        nav.addOnDestinationChangedListener((c, dest, args) -> {
            boolean onPlayer = dest.getId() == R.id.playerFragment;
            bottomNav.setVisibility(onPlayer ? View.GONE : View.VISIBLE);
            miniPlayer.setVisibility(onPlayer ? View.GONE : View.VISIBLE);
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                toAsk.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        if (!toAsk.isEmpty()) {
            permissionLauncher.launch(toAsk.toArray(new String[0]));
        }
    }

    @Override
    protected void onDestroy() {
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

