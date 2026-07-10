package com.melodie.player.ui.library;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.melodie.player.R;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class AlbumEditFragment extends Fragment {

    public static final String ARG_ALBUM_ID = "album_id";
    public static final String ARG_ALBUM_NAME = "album_name";
    private static final String NO_REMOTE_COVER = "__NO_REMOTE_COVER__";

    private long albumId;
    private LibraryViewModel viewModel;
    private ImageView coverPreview;
    private EditText nameInput;
    private EditText releaseDateInput;
    private TextView coverValue;
    private String selectedCover;
    private ActivityResultLauncher<String[]> pickCoverLauncher;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_album_edit, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        albumId = requireArguments().getLong(ARG_ALBUM_ID, -1L);
        if (albumId <= 0L) {
            NavHostFragment.findNavController(this).navigateUp();
            return;
        }

        viewModel = new ViewModelProvider(this).get(LibraryViewModel.class);

        view.findViewById(R.id.btn_back).setOnClickListener(v ->
                NavHostFragment.findNavController(this).navigateUp());

        pickCoverLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null || !isAdded()) return;
            try {
                requireContext().getContentResolver().takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            } catch (Exception ignored) {
                // Certains providers ne supportent pas la persistance; on garde l'URI si possible.
            }
            selectedCover = uri.toString();
            updateCoverUi(selectedCover);
        });

        TextView title = view.findViewById(R.id.title);
        coverPreview = view.findViewById(R.id.album_cover_preview);
        nameInput = view.findViewById(R.id.input_album_name);
        releaseDateInput = view.findViewById(R.id.input_album_release_date);
        coverValue = view.findViewById(R.id.album_cover_value);
        MaterialButton chooseCover = view.findViewById(R.id.btn_pick_cover);
        MaterialButton clearCover = view.findViewById(R.id.btn_clear_cover);
        MaterialButton saveButton = view.findViewById(R.id.btn_save_album_metadata);

        String fallbackName = requireArguments().getString(ARG_ALBUM_NAME, "");
        if (fallbackName == null || fallbackName.trim().isEmpty()) {
            fallbackName = getString(R.string.unknown_album);
        }
        title.setText(getString(R.string.album_edit_title_with_name, fallbackName));

        String finalFallbackName = fallbackName;
        viewModel.album(albumId).observe(getViewLifecycleOwner(), album -> {
            if (album == null) return;
            if (!nameInput.hasFocus()) {
                String currentName = album.name != null ? album.name : "";
                nameInput.setText(currentName);
                nameInput.setSelection(currentName.length());
            }
            if (!releaseDateInput.hasFocus()) {
                releaseDateInput.setText(album.releaseDate != null ? album.releaseDate : "");
            }
            selectedCover = normalizeCover(album.cover);
            updateCoverUi(selectedCover);
            title.setText(getString(R.string.album_edit_title_with_name,
                    (album.name != null && !album.name.trim().isEmpty()) ? album.name : finalFallbackName));
        });

        chooseCover.setOnClickListener(v -> pickCoverLauncher.launch(new String[]{"image/*"}));
        clearCover.setOnClickListener(v -> {
            selectedCover = "";
            updateCoverUi(selectedCover);
        });

        saveButton.setOnClickListener(v -> {
            String newName = nameInput.getText() != null ? nameInput.getText().toString().trim() : "";
            if (newName.isEmpty()) {
                Toast.makeText(requireContext(), R.string.album_edit_name_required, Toast.LENGTH_SHORT).show();
                return;
            }
            String newDate = releaseDateInput.getText() != null ? releaseDateInput.getText().toString().trim() : "";
            if (!newDate.isEmpty() && !isValidYear(newDate)) {
                Toast.makeText(requireContext(), R.string.album_edit_release_date_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
            viewModel.updateAlbumMetadata(albumId, newName, newDate, selectedCover);
            Toast.makeText(requireContext(), R.string.album_edit_saved, Toast.LENGTH_SHORT).show();
            NavHostFragment.findNavController(this).navigateUp();
        });
    }

    private boolean isValidYear(String year) {
        if (year == null || year.length() != 4) return false;
        try {
            int yearInt = Integer.parseInt(year);
            return yearInt > 0 && yearInt <= 9999;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void updateCoverUi(String cover) {
        String normalized = normalizeCover(cover);
        if (normalized.isEmpty()) {
            coverValue.setText(R.string.album_edit_cover_not_set);
            coverPreview.setImageResource(R.drawable.ic_album);
            return;
        }
        coverValue.setText(normalized);
        Glide.with(coverPreview)
                .load(Uri.parse(normalized))
                .placeholder(R.drawable.ic_album)
                .error(R.drawable.ic_album)
                .into(coverPreview);
    }

    private String normalizeCover(String cover) {
        if (cover == null) return "";
        String trimmed = cover.trim();
        if (trimmed.isEmpty() || NO_REMOTE_COVER.equals(trimmed)) return "";
        return trimmed;
    }
}

