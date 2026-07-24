package com.melodie.player.ui.library;

import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.melodie.player.BuildConfig;
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
        super.onViewCreated(view, savedInstanceState);

        TextView eq = view.findViewById(R.id.settings_eq);
        TextView folders = view.findViewById(R.id.settings_folders);
        TextView hiddenAlbums = view.findViewById(R.id.settings_hidden_albums);
        TextView legalTerms = view.findViewById(R.id.settings_legal_terms);
        TextView legalPrivacy = view.findViewById(R.id.settings_legal_privacy);
        TextView appVersion = view.findViewById(R.id.settings_app_version);
        TextView resetApp = view.findViewById(R.id.settings_reset_app);

        appVersion.setText(getString(R.string.settings_about_version_value, BuildConfig.VERSION_NAME));

        folders.setOnClickListener(v ->
                NavHostFragment.findNavController(LibrarySettingsFragment.this).navigate(R.id.foldersFragment));
        hiddenAlbums.setOnClickListener(v ->
                NavHostFragment.findNavController(LibrarySettingsFragment.this).navigate(R.id.hiddenAlbumsFragment));
        eq.setOnClickListener(v ->
                NavHostFragment.findNavController(LibrarySettingsFragment.this).navigate(R.id.equalizerFragment));

        legalTerms.setOnClickListener(v -> new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_legal_terms)
                .setMessage(R.string.settings_legal_terms_content)
                .setPositiveButton(android.R.string.ok, null)
                .show());

        legalPrivacy.setOnClickListener(v -> new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_legal_privacy)
                .setMessage(R.string.settings_legal_privacy_content)
                .setPositiveButton(android.R.string.ok, null)
                .show());

        resetApp.setOnClickListener(v -> new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_reset_app_title)
                .setMessage(R.string.settings_reset_app_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.settings_reset_app, (dialog, which) ->
                        showResetTypingConfirmation())
                .show());
    }

    private void showResetTypingConfirmation() {
        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.settings_reset_app_type_reset_hint);
        input.setSingleLine(true);
        int horizontalPadding = (int) (20 * getResources().getDisplayMetrics().density);
        int verticalPadding = (int) (8 * getResources().getDisplayMetrics().density);
        input.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_reset_app_confirm_title)
                .setMessage(R.string.settings_reset_app_confirm_message)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.settings_reset_app, (dialog, which) -> {
                    String typed = input.getText() != null ? input.getText().toString().trim() : "";
                    if (!"RESET".equals(typed)) {
                        Toast.makeText(requireContext(), R.string.settings_reset_app_type_reset_error, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    musicRepository.resetApplication(() -> {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() -> Toast.makeText(
                                requireContext(),
                                R.string.settings_reset_app_done,
                                Toast.LENGTH_SHORT
                        ).show());
                    });
                })
                .show();
    }
}

