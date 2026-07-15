package com.melodie.player.util;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * Gère le téléchargement et la sauvegarde des pochettes d'albums en ligne
 * dans le stockage privé de l'application.
 */
@Singleton
public class CoverImageDownloader {

    private static final String TAG = "CoverImageDownloader";
    private static final String COVERS_DIR_NAME = "covers";
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final int BUFFER_SIZE = 8192;

    private final File coversDir;

    @Inject
    public CoverImageDownloader(@ApplicationContext Context context) {
        this.coversDir = new File(context.getFilesDir(), COVERS_DIR_NAME);
        if (!coversDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            coversDir.mkdirs();
        }
    }

    /**
     * Télécharge une image de pochette depuis une URL et la sauvegarde localement.
     *
     * @param imageUrl l'URL de l'image à télécharger
     * @param callback callback invoqué avec le chemin local (ou null en cas d'erreur)
     */
    public void downloadAndSaveCover(String imageUrl, CoverDownloadCallback callback) {
        if (imageUrl == null || imageUrl.trim().isEmpty()) {
            if (callback != null) callback.onError("Invalid URL");
            return;
        }

        String fileName = generateFileName(imageUrl);
        File targetFile = new File(coversDir, fileName);

        // Si le fichier existe déjà localement, retourner le chemin
        if (targetFile.exists()) {
            Log.d(TAG, "Cover already cached: " + targetFile.getAbsolutePath());
            if (callback != null) callback.onSuccess(targetFile.getAbsolutePath());
            return;
        }

        // Télécharger en arrière-plan
        new Thread(() -> {
            try {
                downloadAndWrite(imageUrl, targetFile);
                Log.d(TAG, "Cover downloaded successfully: " + targetFile.getAbsolutePath());
                if (callback != null) callback.onSuccess(targetFile.getAbsolutePath());
            } catch (Exception e) {
                Log.e(TAG, "Failed to download cover from " + imageUrl, e);
                // Nettoyer le fichier partiel
                if (targetFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    targetFile.delete();
                }
                if (callback != null) callback.onError(e.getMessage());
            }
        }).start();
    }

    /**
     * Génère un nom de fichier déterministe à partir de l'URL.
     * Utilise MD5 pour obtenir un hash court et reproductible.
     */
    private String generateFileName(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.append(".jpg").toString();
        } catch (Exception e) {
            Log.w(TAG, "Unable to generate MD5 hash, using URL hash", e);
            return Math.abs(url.hashCode()) + ".jpg";
        }
    }

    /**
     * Télécharge l'image et l'écrit dans le fichier.
     */
    private void downloadAndWrite(String urlStr, File targetFile) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", "MelodiePlayer/1.0");

        try {
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new Exception("HTTP " + responseCode + " for URL: " + urlStr);
            }

            long contentLength = connection.getContentLength();
            if (contentLength <= 0) {
                throw new Exception("Invalid content length: " + contentLength);
            }
            if (contentLength > 10 * 1024 * 1024) { // Limiter à 10 MB
                throw new Exception("Image trop volumineux: " + contentLength + " bytes");
            }

            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(targetFile)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Nettoie les fichiers de pochettes qui ne sont pas référencés.
     * À appeler périodiquement pour libérer de l'espace.
     */
    public void cleanupUnusedCovers() {
        new Thread(() -> {
            try {
                File[] files = coversDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.isFile() && System.currentTimeMillis() - file.lastModified() > 30 * 24 * 60 * 60 * 1000L) {
                            // Supprimer les fichiers non modifiés depuis 30 jours
                            if (file.delete()) {
                                Log.d(TAG, "Cleaned up old cover: " + file.getName());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error during cover cleanup", e);
            }
        }).start();
    }

    /**
     * Callback pour les opérations de téléchargement.
     */
    public interface CoverDownloadCallback {
        void onSuccess(String localFilePath);
        void onError(String errorMessage);
    }
}




