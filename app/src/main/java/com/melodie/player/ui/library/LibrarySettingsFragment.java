package com.melodie.player.ui.library;

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
public class LibrarySettingsFragment extends Fragment {

    @Inject
    MusicRepository musicRepository;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_library_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        TextView eq = view.findViewById(R.id.settings_eq);
        TextView folders = view.findViewById(R.id.settings_folders);
        SwitchMaterial onlineCover = view.findViewById(R.id.settings_online_cover);

        onlineCover.setChecked(musicRepository.isOnlineCoverEnabled());
        onlineCover.setOnCheckedChangeListener((buttonView, isChecked) ->
                musicRepository.setOnlineCoverEnabled(isChecked));


        folders.setOnClickListener(v ->
                NavHostFragment.findNavController(LibrarySettingsFragment.this).navigate(R.id.foldersFragment));
        eq.setOnClickListener(v ->
                NavHostFragment.findNavController(LibrarySettingsFragment.this).navigate(R.id.equalizerFragment));
    }
}

