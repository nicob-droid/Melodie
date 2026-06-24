package com.melodie.player.ui.player;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.media3.common.Player;
import androidx.navigation.fragment.NavHostFragment;

import com.bumptech.glide.Glide;
import com.melodie.player.R;
import com.melodie.player.data.entity.Song;
import com.melodie.player.playback.PlayerController;
import com.melodie.player.util.DurationFormatter;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class PlayerFragment extends Fragment {

    @Inject
    PlayerController playerController;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SeekBar seek;
    private TextView elapsed;
    private TextView total;
    private ImageButton btnPlay;
    private ImageView cover;
    private TextView title;
    private TextView artist;
    private TextView album;
    private ImageButton btnFav;
    private ImageButton btnShuffle;
    private ImageButton btnRepeat;
    private boolean shuffle = false;
    private int repeatMode = Player.REPEAT_MODE_OFF;
    private Song currentSong;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long pos = playerController.getPosition();
            long dur = playerController.getDuration();
            if (dur > 0) {
                seek.setMax((int) dur);
                seek.setProgress((int) pos);
                elapsed.setText(DurationFormatter.format(pos));
                total.setText(DurationFormatter.format(dur));
            }
            handler.postDelayed(this, 500);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_player, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        cover = view.findViewById(R.id.cover);
        title = view.findViewById(R.id.title);
        artist = view.findViewById(R.id.artist);
        album = view.findViewById(R.id.album);
        seek = view.findViewById(R.id.seek);
        elapsed = view.findViewById(R.id.elapsed);
        total = view.findViewById(R.id.total);
        btnPlay = view.findViewById(R.id.btn_play);
        btnFav = view.findViewById(R.id.btn_fav);
        btnShuffle = view.findViewById(R.id.btn_shuffle);
        btnRepeat = view.findViewById(R.id.btn_repeat);
        ImageButton btnNext = view.findViewById(R.id.btn_next);
        ImageButton btnPrev = view.findViewById(R.id.btn_prev);
        ImageButton btnBack = view.findViewById(R.id.btn_back);

        btnBack.setOnClickListener(v ->
                NavHostFragment.findNavController(PlayerFragment.this).navigateUp());
        btnPlay.setOnClickListener(v -> playerController.togglePlay());
        btnNext.setOnClickListener(v -> playerController.next());
        btnPrev.setOnClickListener(v -> playerController.previous());
        btnShuffle.setOnClickListener(v -> {
            shuffle = !shuffle;
            playerController.setShuffle(shuffle);
            btnShuffle.setAlpha(shuffle ? 1f : 0.5f);
        });
        btnRepeat.setOnClickListener(v -> {
            repeatMode = (repeatMode + 1) % 3;
            playerController.setRepeatMode(repeatMode);
            btnRepeat.setAlpha(repeatMode == Player.REPEAT_MODE_OFF ? 0.5f : 1f);
        });
        btnFav.setOnClickListener(v -> {
            // Toggle handled by adapter elsewhere; placeholder
        });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                playerController.seekTo(seekBar.getProgress());
            }
        });

        playerController.currentSong.observe(getViewLifecycleOwner(), this::bind);
        playerController.isPlaying.observe(getViewLifecycleOwner(), playing -> btnPlay
                .setImageResource(Boolean.TRUE.equals(playing) ? R.drawable.ic_pause : R.drawable.ic_play));
    }

    private void bind(Song s) {
        currentSong = s;
        if (s == null) return;
        title.setText(s.title);
        artist.setText(s.artist != null ? s.artist : getString(R.string.unknown_artist));
        album.setText(s.album != null ? s.album : getString(R.string.unknown_album));
        Glide.with(this)
                .load(s.cover != null ? Uri.parse(s.cover) : null)
                .placeholder(R.drawable.ic_album)
                .into(cover);
    }

    @Override
    public void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }
}

