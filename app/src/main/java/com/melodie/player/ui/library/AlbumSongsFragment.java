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

        RecyclerView rv = view.findViewById(R.id.recycler);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        LibraryViewModel vm = new ViewModelProvider(this).get(LibraryViewModel.class);

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

    private List<Song> currentSongs(RecyclerView rv) {
        SongAdapter adapter = (SongAdapter) rv.getAdapter();
        if (adapter == null) return new ArrayList<>();
        return new ArrayList<>(adapter.getCurrentList());
    }
}


