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

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class HiddenAlbumsFragment extends Fragment {

    private LibraryViewModel viewModel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_hidden_albums, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        view.findViewById(R.id.btn_back).setOnClickListener(v ->
                NavHostFragment.findNavController(HiddenAlbumsFragment.this).navigateUp());

        viewModel = new ViewModelProvider(this).get(LibraryViewModel.class);

        RecyclerView rv = view.findViewById(R.id.recycler);
        TextView emptyView = view.findViewById(R.id.empty_view);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));

        HiddenAlbumsAdapter adapter = new HiddenAlbumsAdapter(album -> {
            if (album == null) return;
            viewModel.setAlbumHidden(album.id, false, () -> {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(), R.string.album_unhidden_confirmation, Toast.LENGTH_SHORT).show();
                });
            });
        });
        rv.setAdapter(adapter);

        viewModel.hiddenAlbums().observe(getViewLifecycleOwner(), albums -> {
            adapter.submitList(albums);
            boolean empty = albums == null || albums.isEmpty();
            emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
            rv.setVisibility(empty ? View.GONE : View.VISIBLE);
        });
    }
}

