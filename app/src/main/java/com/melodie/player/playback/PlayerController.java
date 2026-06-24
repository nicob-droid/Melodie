package com.melodie.player.playback;

import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.lifecycle.MutableLiveData;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;
import com.melodie.player.data.entity.Song;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * Wraps a {@link MediaController} bound to {@link PlaybackService}. Exposes
 * LiveData of the current song / playing state for the UI.
 */
@Singleton
public class PlayerController {

    private final Context context;
    @Nullable
    private MediaController controller;

    public final MutableLiveData<Song> currentSong = new MutableLiveData<>(null);
    public final MutableLiveData<Boolean> isPlaying = new MutableLiveData<>(false);
    public final MutableLiveData<Long> position = new MutableLiveData<>(0L);
    public final MutableLiveData<Long> duration = new MutableLiveData<>(0L);

    private List<Song> queue = new ArrayList<>();

    @Inject
    public PlayerController(@ApplicationContext Context context) {
        this.context = context;
    }

    public void init() {
        if (controller != null) return;
        SessionToken token = new SessionToken(context,
                new ComponentName(context, PlaybackService.class));
        ListenableFuture<MediaController> future =
                new MediaController.Builder(context, token).buildAsync();
        future.addListener(() -> {
            try {
                controller = future.get();
                controller.addListener(new Player.Listener() {
                    @Override
                    public void onIsPlayingChanged(boolean playing) {
                        isPlaying.postValue(playing);
                    }

                    @Override
                    public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                        updateCurrent();
                    }

                    @Override
                    public void onPlaybackStateChanged(int state) {
                        if (controller != null) {
                            duration.postValue(Math.max(0, controller.getDuration()));
                        }
                    }
                });
                updateCurrent();
            } catch (ExecutionException | InterruptedException ignored) {
            }
        }, command -> new Handler(Looper.getMainLooper()).post(command));
    }

    private void updateCurrent() {
        if (controller == null) return;
        int idx = controller.getCurrentMediaItemIndex();
        if (idx >= 0 && idx < queue.size()) {
            currentSong.postValue(queue.get(idx));
        } else {
            currentSong.postValue(null);
        }
        duration.postValue(Math.max(0, controller.getDuration()));
        isPlaying.postValue(controller.isPlaying());
    }

    public void playQueue(List<Song> songs, int startIndex) {
        if (controller == null || songs.isEmpty()) return;
        this.queue = new ArrayList<>(songs);
        List<MediaItem> items = new ArrayList<>(songs.size());
        for (Song s : songs) {
            items.add(toMediaItem(s));
        }
        controller.setMediaItems(items, startIndex, 0L);
        controller.prepare();
        controller.play();
    }

    public void togglePlay() {
        if (controller == null) return;
        if (controller.isPlaying()) controller.pause();
        else controller.play();
    }

    public void next() {
        if (controller != null) controller.seekToNext();
    }

    public void previous() {
        if (controller != null) controller.seekToPrevious();
    }

    public void seekTo(long ms) {
        if (controller != null) controller.seekTo(ms);
    }

    public void setShuffle(boolean shuffle) {
        if (controller != null) controller.setShuffleModeEnabled(shuffle);
    }

    public void setRepeatMode(int mode) {
        if (controller != null) controller.setRepeatMode(mode);
    }

    public long getPosition() {
        return controller != null ? Math.max(0, controller.getCurrentPosition()) : 0L;
    }

    public long getDuration() {
        return controller != null ? Math.max(0, controller.getDuration()) : 0L;
    }

    public boolean isReady() {
        return controller != null;
    }

    private MediaItem toMediaItem(Song s) {
        MediaMetadata md = new MediaMetadata.Builder()
                .setTitle(s.title)
                .setArtist(s.artist)
                .setAlbumTitle(s.album)
                .setArtworkUri(s.cover != null ? Uri.parse(s.cover) : null)
                .build();

        return new MediaItem.Builder()
                .setMediaId(s.id)
                .setUri(s.path)
                .setMediaMetadata(md)
                .build();
    }

    public void release() {
        if (controller != null) {
            controller.release();
            controller = null;
        }
    }
}

