package com.melodie.player.ui.library;

import android.Manifest;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.melodie.player.R;
import com.melodie.player.data.entity.Song;
import com.melodie.player.playback.PlayerController;
import com.melodie.player.ui.adapter.SongAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class AlbumSongsFragment extends Fragment {

    public static final String ARG_ALBUM_ID = "album_id";
    public static final String ARG_ALBUM_NAME = "album_name";

    @Inject
    PlayerController playerController;

    private LibraryViewModel viewModel;

    /** Album en cours de suppression (en attente de la confirmation système / permission). */
    private long pendingDeleteAlbumId = -1L;

    /** Lancement de la demande système de suppression de fichiers (API 30+ / Q recoverable). */
    private ActivityResultLauncher<IntentSenderRequest> deleteRequestLauncher;

    /** Demande de permission WRITE_EXTERNAL_STORAGE pour la suppression directe (API <= 29). */
    private ActivityResultLauncher<String> writePermissionLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        deleteRequestLauncher = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(),
                result -> {
                    long albumId = pendingDeleteAlbumId;
                    pendingDeleteAlbumId = -1L;
                    if (albumId <= 0) return;
                    if (result.getResultCode() == android.app.Activity.RESULT_OK) {
                        finalizeDeletion(albumId, R.string.album_delete_success);
                    } else {
                        Toast.makeText(requireContext(), R.string.album_delete_failed, Toast.LENGTH_SHORT).show();
                    }
                });
        writePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    long albumId = pendingDeleteAlbumId;
                    if (albumId <= 0) return;
                    if (granted) {
                        startAlbumDeletion(albumId);
                    } else {
                        pendingDeleteAlbumId = -1L;
                        Toast.makeText(requireContext(), R.string.album_delete_failed, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_filtered_songs, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        view.findViewById(R.id.btn_back).setOnClickListener(v ->
                NavHostFragment.findNavController(AlbumSongsFragment.this).navigateUp());

        long albumId = requireArguments().getLong(ARG_ALBUM_ID, -1L);
        String albumName = requireArguments().getString(ARG_ALBUM_NAME, "");
        if (albumName == null || albumName.trim().isEmpty()) {
            albumName = getString(R.string.unknown_album);
        }

        TextView title = view.findViewById(R.id.title);
        title.setText(getString(R.string.library_album_tracks_title, albumName));
        View menuButton = view.findViewById(R.id.btn_menu);
        menuButton.setVisibility(View.VISIBLE);
        long finalAlbumId = albumId;
        String finalAlbumName = albumName;
        menuButton.setOnClickListener(v -> showAlbumMenu(v, finalAlbumId, finalAlbumName));

        RecyclerView rv = view.findViewById(R.id.recycler);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        viewModel = new ViewModelProvider(this).get(LibraryViewModel.class);
        LibraryViewModel vm = viewModel;

        vm.album(albumId).observe(getViewLifecycleOwner(), album -> {
            if (album == null) return;
            String displayName = album.name != null && !album.name.trim().isEmpty()
                    ? album.name
                    : getString(R.string.unknown_album);
            title.setText(getString(R.string.library_album_tracks_title, displayName));
        });

        SongAdapter adapter = new SongAdapter((song, position) -> {
            playerController.playQueue(((SongAdapter) rv.getAdapter()).getCurrentList(), position);
            NavHostFragment.findNavController(AlbumSongsFragment.this)
                    .navigate(R.id.playerFragment);
        }, (anchor, song, position) -> PlaylistDialogs.showAddToPlaylistDialog(
                AlbumSongsFragment.this,
                vm,
                song.id,
                () -> requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), R.string.playlist_track_added, Toast.LENGTH_SHORT).show())
        ));
        rv.setAdapter(adapter);

        view.findViewById(R.id.btn_play).setOnClickListener(v -> {
            List<Song> songs = currentSongs(rv);
            if (songs.isEmpty()) return;
            playerController.playQueue(songs, 0);
            NavHostFragment.findNavController(AlbumSongsFragment.this).navigate(R.id.playerFragment);
        });

        view.findViewById(R.id.btn_shuffle).setOnClickListener(v -> {
            List<Song> songs = currentSongs(rv);
            if (songs.isEmpty()) return;
            Collections.shuffle(songs);
            playerController.playQueue(songs, 0);
            NavHostFragment.findNavController(AlbumSongsFragment.this).navigate(R.id.playerFragment);
        });

        vm.songsByAlbum(albumId).observe(getViewLifecycleOwner(), adapter::submitList);
    }

    private void showAlbumMenu(View anchor, long albumId, String albumName) {
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        popup.inflate(R.menu.menu_album_options);
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_edit_album_metadata) {
                Bundle args = new Bundle();
                args.putLong(AlbumEditFragment.ARG_ALBUM_ID, albumId);
                args.putString(AlbumEditFragment.ARG_ALBUM_NAME, albumName);
                NavHostFragment.findNavController(this).navigate(R.id.albumEditFragment, args);
                return true;
            }
            if (item.getItemId() == R.id.action_hide_album) {
                hideAlbum(albumId);
                return true;
            }
            if (item.getItemId() == R.id.action_delete_album) {
                confirmAndDeleteAlbum(albumId, albumName);
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void hideAlbum(long albumId) {
        viewModel.setAlbumHidden(albumId, true, () -> {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), R.string.album_hidden_confirmation, Toast.LENGTH_SHORT).show();
                NavHostFragment.findNavController(AlbumSongsFragment.this).navigateUp();
            });
        });
    }

    private void confirmAndDeleteAlbum(long albumId, String albumName) {
        String safeName = albumName != null && !albumName.trim().isEmpty()
                ? albumName
                : getString(R.string.unknown_album);
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.album_delete_dialog_title)
                .setMessage(getString(R.string.album_delete_dialog_message, safeName))
                .setNegativeButton(R.string.album_delete_cancel, null)
                .setPositiveButton(R.string.album_delete_confirm, (dialog, which) -> requestDeletion(albumId))
                .show();
    }

    /** Verifie la permission (API <= 29) puis lance la recuperation des fichiers a supprimer. */
    private void requestDeletion(long albumId) {
        pendingDeleteAlbumId = albumId;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            boolean granted = ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                return;
            }
        }
        startAlbumDeletion(albumId);
    }

    private void startAlbumDeletion(long albumId) {
        viewModel.getLocalSongUrisByAlbum(albumId, uris -> {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                performDeletion(albumId, uris);
            });
        });
    }

    private void performDeletion(long albumId, List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            // Aucun fichier local : on purge simplement l'entree de la bibliotheque.
            pendingDeleteAlbumId = -1L;
            finalizeDeletion(albumId, R.string.album_delete_no_local_files);
            return;
        }
        ContentResolver resolver = requireContext().getContentResolver();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                pendingDeleteAlbumId = albumId;
                PendingIntent pendingIntent = MediaStore.createDeleteRequest(resolver, uris);
                deleteRequestLauncher.launch(
                        new IntentSenderRequest.Builder(pendingIntent.getIntentSender()).build());
            } catch (Exception e) {
                pendingDeleteAlbumId = -1L;
                Toast.makeText(requireContext(), R.string.album_delete_failed, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        // API 26-29 : suppression directe (permission WRITE_EXTERNAL_STORAGE accordee).
        try {
            for (Uri uri : uris) {
                resolver.delete(uri, null, null);
            }
            pendingDeleteAlbumId = -1L;
            finalizeDeletion(albumId, R.string.album_delete_success);
        } catch (SecurityException e) {
            pendingDeleteAlbumId = -1L;
            Toast.makeText(requireContext(), R.string.album_delete_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void finalizeDeletion(long albumId, int messageRes) {
        viewModel.deleteAlbumFromLibrary(albumId, () -> {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), messageRes, Toast.LENGTH_SHORT).show();
                NavHostFragment.findNavController(AlbumSongsFragment.this).navigateUp();
            });
        });
    }

    private List<Song> currentSongs(RecyclerView rv) {
        SongAdapter adapter = (SongAdapter) rv.getAdapter();
        if (adapter == null) return new ArrayList<>();
        return new ArrayList<>(adapter.getCurrentList());
    }
}


