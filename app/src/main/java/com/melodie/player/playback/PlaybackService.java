package com.melodie.player.playback;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.melodie.player.data.repository.DriveRepository;

import java.io.IOException;
import java.util.Collections;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
@UnstableApi
public class PlaybackService extends MediaSessionService {

    @Inject
    DriveRepository driveRepository;

    private MediaSession mediaSession;

    @Override
    public void onCreate() {
        super.onCreate();

        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent("Melodie/DriveStreaming")
                .setConnectTimeoutMs(4_000)
                .setReadTimeoutMs(8_000)
                .setKeepPostFor302Redirects(true);

        DefaultDataSource.Factory defaultDataSourceFactory = new DefaultDataSource.Factory(this, httpFactory);
        ResolvingDataSource.Factory resolvingFactory = new ResolvingDataSource.Factory(
                defaultDataSourceFactory,
                this::resolveDriveDataSpec
        );

        // Démarre la lecture dès qu'un petit buffer est prêt (500 ms) au lieu des 2,5 s
        // par défaut : réduit nettement le délai au lancement d'un flux Drive.
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        /* minBufferMs= */ 15_000,
                        /* maxBufferMs= */ 50_000,
                        /* bufferForPlaybackMs= */ 500,
                        /* bufferForPlaybackAfterRebufferMs= */ 1_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        ExoPlayer player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(resolvingFactory))
                .setLoadControl(loadControl)
                .setAudioAttributes(
                        new AudioAttributes.Builder()
                                .setUsage(C.USAGE_MEDIA)
                                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                                .build(),
                        /* handleAudioFocus = */ true)
                .setHandleAudioBecomingNoisy(true)
                .build();

        mediaSession = new MediaSession.Builder(this, player).build();
    }

    private DataSpec resolveDriveDataSpec(DataSpec dataSpec) throws IOException {
        if (dataSpec == null || dataSpec.uri == null) {
            return dataSpec;
        }

        Uri original = dataSpec.uri;
        if (!"drive".equalsIgnoreCase(original.getScheme())) {
            return dataSpec;
        }

        String fileId = extractDriveFileId(original);
        if (fileId == null || fileId.trim().isEmpty()) {
            throw new IOException("Invalid drive URI: " + original);
        }

        String accessToken = getDriveAccessToken();
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new IOException("Google Drive access token unavailable for playback");
        }

        Uri streamUri = new Uri.Builder()
                .scheme("https")
                .authority("www.googleapis.com")
                .appendPath("drive")
                .appendPath("v3")
                .appendPath("files")
                .appendPath(fileId)
                .appendQueryParameter("alt", "media")
                .build();

        return dataSpec.buildUpon()
                .setUri(streamUri)
                .setHttpRequestHeaders(Collections.singletonMap("Authorization", "Bearer " + accessToken))
                .build();
    }

    private String extractDriveFileId(Uri uri) {
        String host = uri.getHost();
        if (host != null && !host.trim().isEmpty() && !"file".equalsIgnoreCase(host)) {
            return host.trim();
        }

        String path = uri.getPath();
        if (path == null || path.trim().isEmpty() || "/".equals(path.trim())) {
            return null;
        }
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private String getDriveAccessToken() throws IOException {
        String token = driveRepository != null ? driveRepository.getDriveAccessToken() : null;
        if (token == null || token.trim().isEmpty()) {
            throw new IOException("Drive token missing. Reconnect Google Drive and retry.");
        }
        return token;
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.getPlayer().release();
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }
}

