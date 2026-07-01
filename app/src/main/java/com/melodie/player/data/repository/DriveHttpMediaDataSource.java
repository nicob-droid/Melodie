package com.melodie.player.data.repository;

import android.media.MediaDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * {@link MediaDataSource} qui alimente {@link android.media.MediaMetadataRetriever} à partir
 * d'un fichier Google Drive, en effectuant des requêtes HTTP {@code Range} à la demande.
 *
 * <p>C'est la clé de la rapidité : le retriever ne lit QUE les octets dont il a besoin
 * (en-tête au début, et pour les conteneurs MP4/M4A l'atome {@code moov} situé en fin de
 * fichier). On évite ainsi de télécharger le fichier entier — contrairement à
 * {@code setDataSource(url, headers)} qui streame progressivement le média.
 *
 * <p>Un petit cache de bloc limite le nombre de requêtes HTTP : chaque lecture rapproche
 * un bloc de {@link #BLOCK_SIZE} octets, réutilisé pour les lectures adjacentes.
 */
class DriveHttpMediaDataSource extends MediaDataSource {

    private static final int BLOCK_SIZE = 128 * 1024; // 128 Ko
    private static final int TIMEOUT_MS = 15000;

    private final String url;
    private final String authHeader;

    private long totalSize = -1;
    private byte[] block;
    private long blockStart = -1;
    private int blockLen = 0;

    DriveHttpMediaDataSource(String url, String authHeader) {
        this.url = url;
        this.authHeader = authHeader;
    }

    @Override
    public int readAt(long position, byte[] bytes, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (position < 0) return -1;
        if (totalSize >= 0 && position >= totalSize) return -1;

        if (!isInBlock(position)) {
            fetchBlock(position);
        }
        if (!isInBlock(position)) {
            return -1;
        }

        int within = (int) (position - blockStart);
        int available = blockLen - within;
        if (available <= 0) return -1;

        int toCopy = Math.min(available, length);
        System.arraycopy(block, within, bytes, offset, toCopy);
        return toCopy;
    }

    @Override
    public long getSize() throws IOException {
        if (totalSize < 0) {
            // Une simple lecture renseigne la taille totale via l'en-tête Content-Range.
            fetchBlock(0);
        }
        return totalSize;
    }

    @Override
    public void close() {
        block = null;
        blockStart = -1;
        blockLen = 0;
    }

    private boolean isInBlock(long position) {
        return blockStart >= 0 && position >= blockStart && position < blockStart + blockLen;
    }

    private void fetchBlock(long position) throws IOException {
        long end = position + BLOCK_SIZE - 1;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", authHeader);
            conn.setRequestProperty("Range", "bytes=" + position + "-" + end);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) {
                throw new IOException("Unexpected HTTP status " + code);
            }

            // Content-Range: "bytes start-end/total" -> on en déduit la taille totale.
            String contentRange = conn.getHeaderField("Content-Range");
            if (contentRange != null) {
                int slash = contentRange.indexOf('/');
                if (slash >= 0) {
                    String totalStr = contentRange.substring(slash + 1).trim();
                    if (!"*".equals(totalStr)) {
                        try {
                            totalSize = Long.parseLong(totalStr);
                        } catch (NumberFormatException ignored) {
                            // taille inconnue : on laisse -1
                        }
                    }
                }
            }

            if (block == null) {
                block = new byte[BLOCK_SIZE];
            }
            int read = 0;
            try (InputStream in = conn.getInputStream()) {
                int r;
                while (read < BLOCK_SIZE && (r = in.read(block, read, BLOCK_SIZE - read)) != -1) {
                    read += r;
                }
            }

            if (read <= 0) {
                blockStart = -1;
                blockLen = 0;
            } else {
                blockStart = position;
                blockLen = read;
            }
        } finally {
            conn.disconnect();
        }
    }
}

