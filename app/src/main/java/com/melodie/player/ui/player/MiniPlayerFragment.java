package com.melodie.player.ui.player;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.bumptech.glide.Glide;
import com.melodie.player.R;
import com.melodie.player.playback.PlayerController;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class MiniPlayerFragment extends Fragment {

    @Inject
    PlayerController playerController;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mini_player, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        ImageView cover = view.findViewById(R.id.cover);
        TextView title = view.findViewById(R.id.title);
        TextView artist = view.findViewById(R.id.artist);
        ImageButton btnPlay = view.findViewById(R.id.btn_play);
        View root = view.findViewById(R.id.root);

        view.setVisibility(View.GONE);

        playerController.currentSong.observe(getViewLifecycleOwner(), s -> {
            if (s == null) {
                view.setVisibility(View.GONE);
                return;
            }
            view.setVisibility(View.VISIBLE);
            title.setText(s.title);
            artist.setText(s.artist != null ? s.artist : "");
            Glide.with(MiniPlayerFragment.this)
                    .load(s.cover != null ? Uri.parse(s.cover) : null)
                    .placeholder(R.drawable.ic_album)
                    .into(cover);
        });

        playerController.isPlaying.observe(getViewLifecycleOwner(), playing -> btnPlay
                .setImageResource(Boolean.TRUE.equals(playing) ? R.drawable.ic_pause : R.drawable.ic_play));

        btnPlay.setOnClickListener(v -> playerController.togglePlay());
        root.setOnClickListener(v -> Navigation.findNavController(requireActivity(), R.id.nav_host)
                .navigate(R.id.playerFragment));
    }
}

