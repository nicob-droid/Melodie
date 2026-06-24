package com.melodie.player.ui.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.melodie.player.R;
import com.melodie.player.data.repository.MusicRepository;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class SettingsFragment extends Fragment {

    @Inject
    MusicRepository musicRepository;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        TextView audio = view.findViewById(R.id.settings_audio);
        TextView library = view.findViewById(R.id.settings_library);
        TextView drive = view.findViewById(R.id.settings_drive);
        SwitchMaterial onlineCover = view.findViewById(R.id.settings_online_cover);
        TextView refreshCover = view.findViewById(R.id.settings_refresh_cover);
        TextView cache = view.findViewById(R.id.settings_cache);
        TextView theme = view.findViewById(R.id.settings_theme);
        TextView eq = view.findViewById(R.id.settings_eq);

        onlineCover.setChecked(musicRepository.isOnlineCoverEnabled());
        onlineCover.setOnCheckedChangeListener((buttonView, isChecked) ->
                musicRepository.setOnlineCoverEnabled(isChecked));

        refreshCover.setOnClickListener(v -> musicRepository.resolveMissingCoversNow(() -> {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(),
                    R.string.settings_cover_refresh_done, Toast.LENGTH_SHORT).show());
        }));

        library.setOnClickListener(v -> musicRepository.scanLocal(null));
        drive.setOnClickListener(v ->
                NavHostFragment.findNavController(SettingsFragment.this).navigate(R.id.driveFragment));
        eq.setOnClickListener(v ->
                NavHostFragment.findNavController(SettingsFragment.this).navigate(R.id.equalizerFragment));
        // Audio / Cache / Theme : placeholders
        audio.setOnClickListener(v -> { });
        cache.setOnClickListener(v -> { });
        theme.setOnClickListener(v -> { });
    }
}

