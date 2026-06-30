package com.melodie.player.ui.player;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.media3.common.Player;
import androidx.navigation.fragment.NavHostFragment;
import androidx.palette.graphics.Palette;

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
    private ProgressBar buffering;
    private ImageView cover;
    private View coverGlow;
    private TextView title;
    private TextView artist;
    private TextView album;
    private ImageButton btnFav;
    private ImageButton btnShuffle;
    private ImageButton btnRepeat;
    private View playerRoot;
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
        playerRoot = view;
        coverGlow = view.findViewById(R.id.cover_glow);
        cover = view.findViewById(R.id.cover);
        title = view.findViewById(R.id.title);
        artist = view.findViewById(R.id.artist);
        album = view.findViewById(R.id.album);
        seek = view.findViewById(R.id.seek);
        elapsed = view.findViewById(R.id.elapsed);
        total = view.findViewById(R.id.total);
        btnPlay = view.findViewById(R.id.btn_play);
        buffering = view.findViewById(R.id.buffering);
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
        playerController.isBuffering.observe(getViewLifecycleOwner(), this::showBuffering);
    }

    /**
     * Affiche un spinner au centre du bouton lecture pendant que le flux n'est pas
     * prêt (ex. chargement initial d'un fichier Drive), en masquant temporairement
     * l'icône play/pause pour éviter la superposition.
     */
    private void showBuffering(Boolean isBuffering) {
        boolean show = Boolean.TRUE.equals(isBuffering);
        if (buffering != null) {
            buffering.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (btnPlay != null) {
            btnPlay.setImageAlpha(show ? 0 : 255);
            // Évite un toggle play/pause tant que le flux n'est pas prêt.
            btnPlay.setEnabled(!show);
        }
    }

    private void bind(Song s) {
        currentSong = s;
        if (s == null) {
            applyDefaultBackground();
            return;
        }
        title.setText(s.title);
        artist.setText(s.artist != null ? s.artist : getString(R.string.unknown_artist));
        album.setText(s.album != null ? s.album : getString(R.string.unknown_album));
        Glide.with(this)
                .load(s.cover != null ? Uri.parse(s.cover) : null)
                .placeholder(R.drawable.ic_album)
                .error(R.drawable.ic_album)
                .listener(new com.bumptech.glide.request.RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable com.bumptech.glide.load.engine.GlideException e,
                                                Object model,
                                                com.bumptech.glide.request.target.Target<Drawable> target,
                                                boolean isFirstResource) {
                        applyDefaultBackground();
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource,
                                                   Object model,
                                                   com.bumptech.glide.request.target.Target<Drawable> target,
                                                   com.bumptech.glide.load.DataSource dataSource,
                                                   boolean isFirstResource) {
                        applyArtworkGradient(resource);
                        return false;
                    }
                })
                .into(cover);
    }

    private void applyArtworkGradient(@Nullable Drawable drawable) {
        Bitmap bitmap = toBitmap(drawable);
        if (bitmap == null) {
            applyDefaultBackground();
            return;
        }

        // Build a richer gradient from multiple artwork swatches to better "extend" the cover.
        Palette.from(bitmap).generate(palette -> {
            if (!isAdded() || playerRoot == null) return;

            final int fallback = ContextCompat.getColor(requireContext(), R.color.bg_dark);
            int dominant = palette != null ? palette.getDominantColor(fallback) : fallback;
            int vibrant = palette != null ? palette.getVibrantColor(dominant) : dominant;
            int muted = palette != null ? palette.getMutedColor(dominant) : dominant;
            int darkVibrant = palette != null ? palette.getDarkVibrantColor(muted) : muted;

            int c1 = blendWithBlack(shiftSaturation(vibrant, 1.18f), 0.15f);
            int c2 = blendWithBlack(blend(vibrant, muted, 0.40f), 0.35f);
            int c3 = blendWithBlack(blend(muted, darkVibrant, 0.55f), 0.55f);
            int c4 = blendWithBlack(darkVibrant, 0.78f);

            GradientDrawable gradient = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{c1, c2, c3, c4}
            );

            int glowCenter = withAlpha(shiftSaturation(vibrant, 1.12f), 130);
            int glowEdge = withAlpha(darkVibrant, 0);
            GradientDrawable radialGlow = new GradientDrawable();
            radialGlow.setShape(GradientDrawable.OVAL);
            radialGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            float radius = cover != null && cover.getWidth() > 0
                    ? (cover.getWidth() * 0.9f)
                    : 420f;
            radialGlow.setGradientRadius(radius);
            radialGlow.setColors(new int[]{glowCenter, glowEdge});

            requireActivity().runOnUiThread(() -> {
                playerRoot.setBackground(gradient);
                if (coverGlow != null) {
                    coverGlow.setBackground(radialGlow);
                    coverGlow.setAlpha(0.95f);
                }
                // Accent dérivé de la même base que le glow (vibrant), mais normalisé
                // pour rester lisible : icône blanche sur btn_play + barre sur fond sombre.
                int accent = buildReadableAccent(shiftSaturation(vibrant, 1.12f));
                applyAccentToControls(accent);
            });
        });
    }

    private void applyDefaultBackground() {
        if (!isAdded() || playerRoot == null) return;
        playerRoot.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.bg_dark));
        if (coverGlow != null) {
            coverGlow.setBackground(null);
        }
        // Restaure les couleurs d'accent par défaut des contrôles.
        int defaultProgress = ContextCompat.getColor(requireContext(), R.color.melodie_purple);
        int defaultThumb = ContextCompat.getColor(requireContext(), R.color.audio_cyan);
        if (btnPlay != null) {
            btnPlay.setBackgroundTintList(ColorStateList.valueOf(defaultProgress));
        }
        if (seek != null) {
            seek.setProgressTintList(ColorStateList.valueOf(defaultProgress));
            seek.setThumbTintList(ColorStateList.valueOf(defaultThumb));
        }
    }

    /**
     * Applique la couleur d'accent (dérivée de la pochette) au bouton lecture et à la
     * barre de progression. Le thumb utilise une variante éclaircie pour ressortir.
     */
    private void applyAccentToControls(int accent) {
        if (btnPlay != null) {
            btnPlay.setBackgroundTintList(ColorStateList.valueOf(accent));
        }
        if (seek != null) {
            seek.setProgressTintList(ColorStateList.valueOf(accent));
            seek.setThumbTintList(ColorStateList.valueOf(lighten(accent, 0.18f)));
        }
    }

    /**
     * Normalise une couleur en un accent vif mais lisible : saturation minimale garantie
     * et luminosité bornée pour conserver le contraste avec l'icône blanche du bouton
     * lecture et la visibilité de la barre sur le fond sombre.
     */
    private int buildReadableAccent(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.max(0.45f, Math.min(1f, hsv[1]));
        hsv[2] = Math.max(0.55f, Math.min(0.82f, hsv[2]));
        return Color.HSVToColor(hsv);
    }

    private int lighten(int color, float amount) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.max(0f, Math.min(1f, hsv[2] + amount));
        return Color.HSVToColor(hsv);
    }

    @Nullable
    private Bitmap toBitmap(@Nullable Drawable drawable) {
        if (drawable == null) return null;
        int width = Math.max(1, drawable.getIntrinsicWidth());
        int height = Math.max(1, drawable.getIntrinsicHeight());
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    private int blendWithBlack(int color, float amount) {
        amount = Math.max(0f, Math.min(1f, amount));
        int r = (int) (Color.red(color) * (1f - amount));
        int g = (int) (Color.green(color) * (1f - amount));
        int b = (int) (Color.blue(color) * (1f - amount));
        return Color.rgb(r, g, b);
    }

    private int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.rgb(r, g, bl);
    }

    private int shiftSaturation(int color, float factor) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.max(0f, Math.min(1f, hsv[1] * factor));
        return Color.HSVToColor(hsv);
    }

    private int withAlpha(int color, int alpha) {
        alpha = Math.max(0, Math.min(255, alpha));
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
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

