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
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.melodie.player.R;
import com.melodie.player.data.cover.CoverArtFetcher;
import com.melodie.player.util.CoverImageDownloader;

import dagger.hilt.android.AndroidEntryPoint;

import javax.inject.Inject;

import java.util.List;
import java.util.function.Consumer;

@AndroidEntryPoint
public class AlbumEditFragment extends Fragment {

    public static final String ARG_ALBUM_ID = "album_id";
    public static final String ARG_ALBUM_NAME = "album_name";
    private static final String NO_REMOTE_COVER = "__NO_REMOTE_COVER__";

    private long albumId;
    private LibraryViewModel viewModel;
    private ImageView coverPreview;
    private EditText nameInput;
    private EditText artistInput;
    private EditText releaseDateInput;
    private TextView coverValue;
    private String selectedCover;
    private String currentArtist = "";
    private ActivityResultLauncher<String[]> pickCoverLauncher;
    private MaterialButton chooseCoverButton;
    private MaterialButton saveButton;
    private boolean onlineCoverSearchInFlight;
    private boolean savingCover;
    @Inject
    CoverImageDownloader coverImageDownloader;

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
        artistInput = view.findViewById(R.id.input_album_artist);
        releaseDateInput = view.findViewById(R.id.input_album_release_date);
        coverValue = view.findViewById(R.id.album_cover_value);
        chooseCoverButton = view.findViewById(R.id.btn_pick_cover);
        MaterialButton clearCover = view.findViewById(R.id.btn_clear_cover);
        saveButton = view.findViewById(R.id.btn_save_album_metadata);

        String fallbackName = requireArguments().getString(ARG_ALBUM_NAME, "");
        if (fallbackName == null || fallbackName.trim().isEmpty()) {
            fallbackName = getString(R.string.unknown_album);
        }
        title.setText(getString(R.string.album_edit_title_with_name, fallbackName));

        String finalFallbackName = fallbackName;
        viewModel.album(albumId).observe(getViewLifecycleOwner(), album -> {
            if (album == null) return;
            currentArtist = album.artist != null ? album.artist.trim() : "";
            if (!nameInput.hasFocus()) {
                String currentName = album.name != null ? album.name : "";
                nameInput.setText(currentName);
                nameInput.setSelection(currentName.length());
            }
            if (artistInput != null && !artistInput.hasFocus()) {
                String currentArtistText = album.artist != null ? album.artist : "";
                artistInput.setText(currentArtistText);
                artistInput.setSelection(currentArtistText.length());
            }
            if (!releaseDateInput.hasFocus()) {
                releaseDateInput.setText(album.releaseDate != null ? album.releaseDate : "");
            }
            selectedCover = normalizeCover(album.cover);
            updateCoverUi(selectedCover);
            title.setText(getString(R.string.album_edit_title_with_name,
                    (album.name != null && !album.name.trim().isEmpty()) ? album.name : finalFallbackName));
        });

        chooseCoverButton.setOnClickListener(v -> showCoverSourceDialog());
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
            String newArtist = artistInput != null && artistInput.getText() != null
                    ? artistInput.getText().toString().trim()
                    : "";
            String newDate = releaseDateInput.getText() != null ? releaseDateInput.getText().toString().trim() : "";
            if (!newDate.isEmpty() && !isValidYear(newDate)) {
                Toast.makeText(requireContext(), R.string.album_edit_release_date_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
            handleSaveWithCoverDownload(albumId, newName, newArtist, newDate, selectedCover);
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        coverPreview = null;
        nameInput = null;
        artistInput = null;
        releaseDateInput = null;
        coverValue = null;
        chooseCoverButton = null;
        saveButton = null;
        onlineCoverSearchInFlight = false;
        savingCover = false;
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
        coverValue.setText(describeCoverValue(normalized));
        Object glideSource = normalized.startsWith("http") ? normalized : Uri.parse(normalized);
        Glide.with(coverPreview)
                .load(glideSource)
                .placeholder(R.drawable.ic_album)
                .error(R.drawable.ic_album)
                .into(coverPreview);
    }

    private CharSequence describeCoverValue(String cover) {
        if (cover.startsWith("content://") || cover.startsWith("file://")) {
            return getString(R.string.album_edit_cover_value_local);
        }
        if (cover.startsWith("http")) {
            return getString(R.string.album_edit_cover_value_online);
        }
        return cover;
    }

    private void showCoverSourceDialog() {
        if (!isAdded()) return;
        String[] options = new String[]{
                getString(R.string.album_edit_pick_cover_local),
                getString(R.string.album_edit_pick_cover_online)
        };
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.album_edit_cover_source_title)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        pickCoverLauncher.launch(new String[]{"image/*"});
                    } else {
                        searchOnlineCoverSuggestions();
                    }
                })
                .show();
    }

    private void searchOnlineCoverSuggestions() {
        if (!isAdded() || onlineCoverSearchInFlight) return;

        String albumQuery = resolveAlbumSearchQuery();
        String artistQuery = resolveArtistSearchQuery();
        if (albumQuery.isEmpty() && artistQuery.isEmpty()) {
            Toast.makeText(requireContext(), R.string.album_edit_online_cover_missing_query, Toast.LENGTH_SHORT).show();
            return;
        }

        onlineCoverSearchInFlight = true;
        if (chooseCoverButton != null) {
            chooseCoverButton.setEnabled(false);
        }
        Toast.makeText(requireContext(), R.string.album_edit_online_cover_search_started, Toast.LENGTH_SHORT).show();

        viewModel.searchAlbumCoverCandidates(artistQuery, albumQuery, 10, candidates -> {
            FragmentActivity activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                onlineCoverSearchInFlight = false;
                if (chooseCoverButton != null) {
                    chooseCoverButton.setEnabled(true);
                }
                if (!isAdded()) return;
                if (candidates == null || candidates.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.album_edit_online_cover_no_results, Toast.LENGTH_SHORT).show();
                    return;
                }
                showOnlineCoverResultsDialog(candidates, albumQuery, artistQuery);
            });
        });
    }

    private String resolveAlbumSearchQuery() {
        String fromInput = nameInput != null && nameInput.getText() != null
                ? nameInput.getText().toString().trim()
                : "";
        if (!fromInput.isEmpty()) {
            return fromInput;
        }
        String fromArgs = requireArguments().getString(ARG_ALBUM_NAME, "");
        return fromArgs != null ? fromArgs.trim() : "";
    }

    private String resolveArtistSearchQuery() {
        String fromInput = artistInput != null && artistInput.getText() != null
                ? artistInput.getText().toString().trim()
                : "";
        if (!fromInput.isEmpty()) {
            return fromInput;
        }
        return currentArtist != null ? currentArtist.trim() : "";
    }

    private void showOnlineCoverResultsDialog(List<CoverArtFetcher.CoverCandidate> candidates,
                                              String albumQuery,
                                              String artistQuery) {
        if (!isAdded()) return;

        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_online_cover_picker, null, false);
        TextView subtitle = dialogView.findViewById(R.id.online_cover_subtitle);
        RecyclerView recyclerView = dialogView.findViewById(R.id.online_cover_recycler);

        subtitle.setText(getString(
                R.string.album_edit_online_cover_results_subtitle,
                buildCoverSearchLabel(albumQuery, artistQuery)
        ));
        recyclerView.setLayoutManager(new GridLayoutManager(requireContext(), 2));

        final AlertDialog[] dialogRef = new AlertDialog[1];
        recyclerView.setAdapter(new OnlineCoverOptionAdapter(candidates, candidate -> {
            onOnlineCoverSelected(candidate, dialogRef);
        }));

        dialogRef[0] = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.album_edit_online_cover_results_title)
                .setView(dialogView)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialogRef[0].show();
    }

    private void onOnlineCoverSelected(CoverArtFetcher.CoverCandidate candidate, AlertDialog[] dialogRef) {
        if (!isAdded()) return;

        if (candidate.imageUrl == null || candidate.imageUrl.trim().isEmpty()) {
            Toast.makeText(requireContext(), R.string.album_edit_online_cover_invalid_url, Toast.LENGTH_SHORT).show();
            return;
        }

        // Seulement sélectionner l'URL distante, pas télécharger
        selectedCover = candidate.imageUrl;
        updateCoverUi(selectedCover);
        Toast.makeText(requireContext(), R.string.album_edit_online_cover_selected, Toast.LENGTH_SHORT).show();

        if (dialogRef[0] != null && dialogRef[0].isShowing()) {
            dialogRef[0].dismiss();
        }
    }

    private void handleSaveWithCoverDownload(long albumId, String newName, String newArtist, String newDate, String coverUrl) {
        // Vérifier si la pochette est une URL en ligne et doit être téléchargée
        if (coverUrl != null && !coverUrl.trim().isEmpty() && coverUrl.startsWith("http")) {
            if (savingCover) return; // Éviter les doubles clics

            savingCover = true;
            saveButton.setEnabled(false);
            Toast.makeText(requireContext(), R.string.album_edit_online_cover_downloading, Toast.LENGTH_SHORT).show();

            coverImageDownloader.downloadAndSaveCover(coverUrl, new CoverImageDownloader.CoverDownloadCallback() {
                @Override
                public void onSuccess(String localFilePath) {
                    if (getActivity() == null || !isAdded()) {
                        savingCover = false;
                        return;
                    }
                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded()) {
                            savingCover = false;
                            return;
                        }
                        savingCover = false;
                        if (saveButton != null) {
                            saveButton.setEnabled(true);
                        }

                        String finalCoverPath = "file://" + localFilePath;
                        savingCoverThenNavigate(albumId, newName, newArtist, newDate, finalCoverPath);
                    });
                }

                @Override
                public void onError(String errorMessage) {
                    if (getActivity() == null) {
                        savingCover = false;
                        return;
                    }
                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded()) {
                            savingCover = false;
                            return;
                        }
                        savingCover = false;
                        if (saveButton != null) {
                            saveButton.setEnabled(true);
                        }

                        Toast.makeText(requireContext(), R.string.album_edit_online_cover_download_failed, Toast.LENGTH_SHORT).show();
                    });
                }
            });
        } else {
            // Pas de pochette en ligne, sauvegarder directement
            savingCoverThenNavigate(albumId, newName, newArtist, newDate, coverUrl);
        }
    }

    private void savingCoverThenNavigate(long albumId, String newName, String newArtist, String newDate, String coverUrl) {
        viewModel.updateAlbumMetadataWithCallback(albumId, newName, newArtist, newDate, coverUrl, () -> {
            if (getActivity() == null || !isAdded()) {
                savingCover = false;
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) {
                    savingCover = false;
                    return;
                }
                savingCover = false;
                if (saveButton != null) {
                    saveButton.setEnabled(true);
                }
                Toast.makeText(requireContext(), R.string.album_edit_saved, Toast.LENGTH_SHORT).show();
                // Retour à la liste des albums plutôt que navigateUp(), car l'album peut avoir
                // fusionné et son id logique changé. Cela évite d'afficher un album vide.
                NavHostFragment.findNavController(AlbumEditFragment.this).navigate(R.id.libraryFragment);
            });
        });
    }

    private String buildCoverSearchLabel(String albumQuery, String artistQuery) {
        boolean hasAlbum = albumQuery != null && !albumQuery.trim().isEmpty();
        boolean hasArtist = artistQuery != null && !artistQuery.trim().isEmpty();
        if (hasAlbum && hasArtist) {
            return artistQuery.trim() + " — " + albumQuery.trim();
        }
        if (hasAlbum) {
            return albumQuery.trim();
        }
        if (hasArtist) {
            return artistQuery.trim();
        }
        return getString(R.string.unknown_album);
    }

    private String normalizeCover(String cover) {
        if (cover == null) return "";
        String trimmed = cover.trim();
        if (trimmed.isEmpty() || NO_REMOTE_COVER.equals(trimmed)) return "";
        return trimmed;
    }

    private static class OnlineCoverOptionAdapter extends RecyclerView.Adapter<OnlineCoverOptionAdapter.VH> {

        private final List<CoverArtFetcher.CoverCandidate> items;
        private final Consumer<CoverArtFetcher.CoverCandidate> onClick;

        OnlineCoverOptionAdapter(List<CoverArtFetcher.CoverCandidate> items,
                                 Consumer<CoverArtFetcher.CoverCandidate> onClick) {
            this.items = items;
            this.onClick = onClick;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_online_cover_option, parent, false);
            return new VH(view);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            CoverArtFetcher.CoverCandidate candidate = items.get(position);
            holder.title.setText(candidate.albumName != null && !candidate.albumName.trim().isEmpty()
                    ? candidate.albumName
                    : holder.itemView.getContext().getString(R.string.unknown_album));

            String artistText = candidate.artistName != null && !candidate.artistName.trim().isEmpty()
                    ? candidate.artistName.trim()
                    : holder.itemView.getContext().getString(R.string.unknown_artist);
            holder.subtitle.setText(artistText);
            holder.provider.setText(candidate.provider != null ? candidate.provider : "");

            Glide.with(holder.cover)
                    .load(candidate.imageUrl)
                    .placeholder(R.drawable.ic_album)
                    .error(R.drawable.ic_album)
                    .into(holder.cover);

            holder.itemView.setOnClickListener(v -> {
                if (onClick != null) {
                    onClick.accept(candidate);
                }
            });
        }

        @Override
        public int getItemCount() {
            return items != null ? items.size() : 0;
        }

        static class VH extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView title;
            final TextView subtitle;
            final TextView provider;

            VH(@NonNull View itemView) {
                super(itemView);
                cover = itemView.findViewById(R.id.online_cover_image);
                title = itemView.findViewById(R.id.online_cover_title);
                subtitle = itemView.findViewById(R.id.online_cover_subtitle_text);
                provider = itemView.findViewById(R.id.online_cover_provider);
            }
        }
    }
}

