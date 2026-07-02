package com.melodie.player.data.repository;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Extraction ultra-rapide de la durée d'un fichier audio Google Drive.
 *
 * <p>Contrairement à {@link android.media.MediaMetadataRetriever} (lent : il streame le média
 * et multiplie les I/O), on récupère uniquement quelques Ko d'en-tête via une (ou deux)
 * requête(s) HTTP {@code Range}, puis on décode la durée directement depuis le conteneur :
 * <ul>
 *     <li>MP3  : en-tête de trame + tag Xing/Info/VBRI (VBR) ou bitrate x taille (CBR) ;</li>
 *     <li>FLAC : bloc STREAMINFO (totalSamples / sampleRate) ;</li>
 *     <li>WAV  : byteRate + taille du chunk {@code data} ;</li>
 *     <li>M4A/MP4 : atome {@code mvhd} (duration / timescale).</li>
 * </ul>
 * Aucune donnée audio complète n'est téléchargée.
 */
final class TrackDurationProbe {

    private static final String TAG = "TrackDurationProbe";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    // Petit bloc d'en-tête : suffit pour l'en-tête de trame + Xing dans la majorité des cas.
    // La TAILLE TOTALE du fichier est lue depuis l'en-tête HTTP Content-Range (fiable),
    // ce qui permet le calcul de durée des MP3 CBR sans dépendre d'une taille en base.
    private static final int HEAD_SIZE = 16 * 1024;
    private static final int MAX_ATTEMPTS = 3;

    static {
        // Autorise un grand pool de connexions persistantes par hôte : on enchaîne beaucoup
        // de requêtes Range vers googleapis.com et on veut réutiliser les sockets (keep-alive)
        // plutôt que de refaire un handshake TLS à chaque fichier.
        System.setProperty("http.keepAlive", "true");
        System.setProperty("http.maxConnections", "64");
    }

    private TrackDurationProbe() {
    }

    /**
     * @param url        endpoint {@code files/{id}?alt=media}
     * @param authHeader valeur complète de l'en-tête Authorization ("Bearer xxx")
     * @param fileName   nom du fichier (sert à déduire le format)
     * @param fileSize   taille totale en octets (0 si inconnue) — nécessaire au calcul CBR MP3
     * @return durée en millisecondes, ou 0 si indéterminable
     */
    static long probeDurationMs(String url, String authHeader, String fileName, long fileSize) {
        HttpURLConnection baseConn = null;
        try {
            // Crée UNE SEULE connexion HTTP réutilisable pour ce fichier.
            // Cela économise les handshakes TLS/TCP et réduit drastiquement la latence.
            baseConn = (HttpURLConnection) new URL(url).openConnection();
            baseConn.setRequestMethod("GET");
            baseConn.setRequestProperty("Authorization", authHeader);
            baseConn.setRequestProperty("Accept-Encoding", "identity");
            baseConn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            baseConn.setReadTimeout(READ_TIMEOUT_MS);

            Range headRange = rangeGetRWithConn(url, authHeader, 0, HEAD_SIZE, baseConn);
            byte[] head = headRange != null ? headRange.data : null;
            if (head == null || head.length < 12) return 0L;

            // Taille totale FIABLE issue de Content-Range ; repli sur la taille passée si absente.
            long realSize = headRange.total > 0 ? headRange.total : fileSize;

            String lower = fileName != null ? fileName.toLowerCase() : "";

            long duration;
            String format;

            // Détection par signature de conteneur (plus fiable que l'extension).
            if (head[0] == 'f' && head[1] == 'L' && head[2] == 'a' && head[3] == 'C') {
                format = "flac";
                duration = parseFlac(head);
            } else if (head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'A' && head[10] == 'V' && head[11] == 'E') {
                format = "wav";
                duration = parseWav(head);
            } else if (head.length >= 8 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
                format = "mp4";
                duration = parseMp4(url, authHeader, head, realSize, baseConn);
            } else if (head[0] == 'O' && head[1] == 'g' && head[2] == 'g' && head[3] == 'S') {
                format = "ogg";
                duration = parseOgg(url, authHeader, head, realSize, baseConn);
            } else {
                format = "mp3";
                duration = parseMp3(url, authHeader, head, realSize, baseConn);
                if (duration <= 0) {
                    // Repli sur l'extension pour les conteneurs non détectés par signature.
                    if (lower.endsWith(".flac")) { format = "flac?"; duration = parseFlac(head); }
                    else if (lower.endsWith(".wav")) { format = "wav?"; duration = parseWav(head); }
                    else if (lower.endsWith(".m4a") || lower.endsWith(".mp4") || lower.endsWith(".aac")) {
                        format = "mp4?"; duration = parseMp4(url, authHeader, head, realSize, baseConn);
                    } else if (lower.endsWith(".ogg") || lower.endsWith(".opus")) {
                        format = "ogg?"; duration = parseOgg(url, authHeader, head, realSize, baseConn);
                    }
                }
            }

            if (duration <= 0) {
                Log.d(TAG, "duration=0 for '" + fileName + "' (format=" + format + ", size=" + realSize + ")");
            }
            return duration;
        } catch (Exception e) {
            Log.w(TAG, "probe failed for " + fileName + ": " + e.getMessage());
            return 0L;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // HTTP
    // ---------------------------------------------------------------------------------------------

    /** Résultat d'une requête Range : octets lus + taille totale du fichier (via Content-Range). */
    private static final class Range {
        final byte[] data;
        final long total;

        Range(byte[] data, long total) {
            this.data = data;
            this.total = total;
        }
    }

    private static byte[] rangeGet(String url, String authHeader, long start, int length) throws IOException {
        Range r = rangeGetR(url, authHeader, start, length);
        return r != null ? r.data : null;
    }

    private static byte[] rangeGetWithConn(String url, String authHeader, long start, int length, HttpURLConnection baseConn) throws IOException {
        Range r = rangeGetRWithConn(url, authHeader, start, length, baseConn);
        return r != null ? r.data : null;
    }

    private static Range rangeGetR(String url, String authHeader, long start, int length) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return doRangeGet(url, authHeader, start, length);
            } catch (IOException e) {
                last = e;
                // Backoff léger : laisse retomber un éventuel throttling de Drive avant de réessayer.
                try {
                    Thread.sleep(200L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", ie);
                }
            }
        }
        throw last != null ? last : new IOException("range request failed");
    }

    /**
     * Version de rangeGetR qui réutilise une HttpURLConnection existante.
     * Cela économise les handshakes TLS et améliore la latence.
     */
    private static Range rangeGetRWithConn(String url, String authHeader, long start, int length, HttpURLConnection baseConn) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return doRangeGetWithConn(url, authHeader, start, length, baseConn);
            } catch (IOException e) {
                last = e;
                // Backoff léger : laisse retomber un éventuel throttling de Drive avant de réessayer.
                try {
                    Thread.sleep(200L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", ie);
                }
            }
        }
        throw last != null ? last : new IOException("range request failed");
    }

    private static Range doRangeGet(String url, String authHeader, long start, int length) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", authHeader);
            conn.setRequestProperty("Range", "bytes=" + start + "-" + (start + length - 1));
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) {
                // Draine le flux d'erreur pour permettre la réutilisation de la connexion.
                drainAndClose(conn.getErrorStream());
                return null;
            }

            long total = parseTotalSize(conn.getHeaderField("Content-Range"));

            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.min(length, 64 * 1024));
            byte[] buf = new byte[8192];
            // On lit la réponse en ENTIER (bornée par la plage demandée) : consommer tout le
            // corps est indispensable pour que HttpURLConnection remette la socket dans le
            // pool keep-alive au lieu de la fermer. On NE fait donc PAS de disconnect().
            try (InputStream in = conn.getInputStream()) {
                int r;
                while ((r = in.read(buf)) != -1) {
                    bos.write(buf, 0, r);
                }
            }
            return new Range(bos.toByteArray(), total);
        } finally {
            // Volontairement pas de conn.disconnect() : cela fermerait la connexion TCP/TLS
            // et empêcherait sa réutilisation pour le fichier suivant.
        }
    }

    /**
     * Effectue une requête Range en réutilisant une HttpURLConnection existante.
     * Cela économise les handshakes TLS/TCP et améliore drastiquement la latence.
     */
    private static Range doRangeGetWithConn(String url, String authHeader, long start, int length, HttpURLConnection baseConn) throws IOException {
        try {
            // Utilise la connexion existante (déjà configurée avec auth, timeouts, etc.)
            // pour faire la requête Range. Cela établit la socket TCP/TLS qu'on veut réutiliser.
            baseConn.setRequestProperty("Range", "bytes=" + start + "-" + (start + length - 1));

            int code = baseConn.getResponseCode();
            if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) {
                drainAndClose(baseConn.getErrorStream());
                return null;
            }

            long total = parseTotalSize(baseConn.getHeaderField("Content-Range"));

            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.min(length, 64 * 1024));
            byte[] buf = new byte[8192];
            // On lit la réponse en ENTIER (bornée par la plage demandée) : consommer tout le
            // corps est indispensable pour que HttpURLConnection remette la socket dans le
            // pool keep-alive au lieu de la fermer.
            try (InputStream in = baseConn.getInputStream()) {
                int r;
                while ((r = in.read(buf)) != -1) {
                    bos.write(buf, 0, r);
                }
            }
            return new Range(bos.toByteArray(), total);
        } catch (IOException e) {
            throw e;
        }
    }

    /** Extrait la taille totale d'un en-tête "Content-Range: bytes start-end/total". */
    private static long parseTotalSize(String contentRange) {
        if (contentRange == null) return -1L;
        int slash = contentRange.indexOf('/');
        if (slash < 0) return -1L;
        String totalStr = contentRange.substring(slash + 1).trim();
        if (totalStr.isEmpty() || "*".equals(totalStr)) return -1L;
        try {
            return Long.parseLong(totalStr);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static void drainAndClose(InputStream in) {
        if (in == null) return;
        try (InputStream s = in) {
            byte[] buf = new byte[4096];
            while (s.read(buf) != -1) {
                // vide
            }
        } catch (IOException ignored) {
            // ignore
        }
    }

    // ---------------------------------------------------------------------------------------------
    // MP3
    // ---------------------------------------------------------------------------------------------

    private static long parseMp3(String url, String authHeader, byte[] head, long fileSize) throws IOException {
        return parseMp3(url, authHeader, head, fileSize, null);
    }

    private static long parseMp3(String url, String authHeader, byte[] head, long fileSize, HttpURLConnection baseConn) throws IOException {
        int dataStart = 0;
        // Skip d'un éventuel tag ID3v2 (peut être volumineux : pochette embarquée).
        if (head.length >= 10 && head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
            int tagSize = ((head[6] & 0x7F) << 21) | ((head[7] & 0x7F) << 14)
                    | ((head[8] & 0x7F) << 7) | (head[9] & 0x7F);
            dataStart = 10 + tagSize;
        }

        byte[] d = head;
        int searchStart = dataStart;
        // Si la première trame est au-delà de ce qu'on a téléchargé (gros tag ID3v2),
        // on récupère un second bloc directement à l'endroit des données audio.
        if (dataStart + 64 > head.length) {
            // Requête followup : crée une nouvelle connexion qui réutilisera la socket
            // du pool (keep-alive) au lieu de réutiliser baseConn qui a déjà été utilisée.
            d = rangeGet(url, authHeader, dataStart, HEAD_SIZE);
            if (d == null || d.length < 8) return 0L;
            searchStart = 0;
        }

        int limit = d.length - 4;
        for (int i = searchStart; i <= limit; i++) {
            if ((d[i] & 0xFF) != 0xFF || (d[i + 1] & 0xE0) != 0xE0) continue;

            int h1 = d[i + 1] & 0xFF, h2 = d[i + 2] & 0xFF, h3 = d[i + 3] & 0xFF;
            int versionBits = (h1 >> 3) & 0x3; // 3=MPEG1, 2=MPEG2, 0=MPEG2.5, 1=reserved
            int layerBits = (h1 >> 1) & 0x3;   // 1=Layer3, 2=Layer2, 3=Layer1, 0=reserved
            if (versionBits == 1 || layerBits == 0) continue;
            int bitrateIndex = (h2 >> 4) & 0xF;
            int sampleRateIndex = (h2 >> 2) & 0x3;
            if (bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) continue;
            int channelMode = (h3 >> 6) & 0x3;

            int mpegVersion = (versionBits == 3) ? 1 : (versionBits == 2 ? 2 : 25);
            int layer = 4 - layerBits;
            int bitrate = mp3Bitrate(mpegVersion, layer, bitrateIndex); // kbps
            int sampleRate = mp3SampleRate(mpegVersion, sampleRateIndex);
            if (bitrate <= 0 || sampleRate <= 0) continue;

            int samplesPerFrame = (layer == 1) ? 384 : (layer == 2 ? 1152 : (mpegVersion == 1 ? 1152 : 576));

            // Tag Xing/Info (VBR) à un offset dépendant de version/canaux.
            int xing;
            if (mpegVersion == 1) xing = (channelMode == 3) ? i + 21 : i + 36;
            else xing = (channelMode == 3) ? i + 13 : i + 21;
            if (xing + 12 <= d.length && isTag(d, xing, 'X', 'i', 'n', 'g') || (xing + 12 <= d.length && isTag(d, xing, 'I', 'n', 'f', 'o'))) {
                int flags = readInt32(d, xing + 4);
                if ((flags & 0x1) != 0) {
                    int frames = readInt32(d, xing + 8);
                    if (frames > 0) {
                        return Math.round((double) frames * samplesPerFrame * 1000.0 / sampleRate);
                    }
                }
            }
            // Tag VBRI (Fraunhofer), offset fixe i+36.
            int vbri = i + 36;
            if (vbri + 26 <= d.length && isTag(d, vbri, 'V', 'B', 'R', 'I')) {
                int frames = readInt32(d, vbri + 14);
                if (frames > 0) {
                    return Math.round((double) frames * samplesPerFrame * 1000.0 / sampleRate);
                }
            }
            // CBR : durée = octets audio * 8 / bitrate.
            if (fileSize > 0) {
                long audioBytes = fileSize - dataStart;
                if (audioBytes > 0) {
                    return Math.round(audioBytes * 8.0 / (bitrate * 1000.0) * 1000.0);
                }
            }
            return 0L;
        }
        return 0L;
    }

    private static boolean isTag(byte[] d, int off, char a, char b, char c, char e) {
        return d[off] == a && d[off + 1] == b && d[off + 2] == c && d[off + 3] == e;
    }

    private static final int[][] MP3_BITRATE_V1 = {
            // Layer1
            {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0},
            // Layer2
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0},
            // Layer3
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0}
    };
    private static final int[][] MP3_BITRATE_V2 = {
            // Layer1
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0},
            // Layer2 & Layer3
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0},
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0}
    };

    private static int mp3Bitrate(int mpegVersion, int layer, int index) {
        if (index <= 0 || index >= 15) return 0;
        if (mpegVersion == 1) return MP3_BITRATE_V1[layer - 1][index];
        return MP3_BITRATE_V2[layer - 1][index];
    }

    private static int mp3SampleRate(int mpegVersion, int index) {
        if (index < 0 || index > 2) return 0;
        switch (mpegVersion) {
            case 1:
                return new int[]{44100, 48000, 32000}[index];
            case 2:
                return new int[]{22050, 24000, 16000}[index];
            default: // 2.5
                return new int[]{11025, 12000, 8000}[index];
        }
    }

    // ---------------------------------------------------------------------------------------------
    // FLAC
    // ---------------------------------------------------------------------------------------------

    private static long parseFlac(byte[] d) {
        // "fLaC" + bloc STREAMINFO. Corps STREAMINFO à l'offset 8.
        int body = 8;
        int q = body + 10; // saute min/maxBlockSize (4) + min/maxFrameSize (6)
        if (q + 8 > d.length) return 0L;
        long b0 = d[q] & 0xFF, b1 = d[q + 1] & 0xFF, b2 = d[q + 2] & 0xFF;
        long b3 = d[q + 3] & 0xFF, b4 = d[q + 4] & 0xFF, b5 = d[q + 5] & 0xFF;
        long b6 = d[q + 6] & 0xFF, b7 = d[q + 7] & 0xFF;
        long sampleRate = (b0 << 12) | (b1 << 4) | (b2 >> 4);
        long totalSamples = ((b3 & 0x0F) << 32) | (b4 << 24) | (b5 << 16) | (b6 << 8) | b7;
        if (sampleRate <= 0 || totalSamples <= 0) return 0L;
        return Math.round(totalSamples * 1000.0 / sampleRate);
    }

    // ---------------------------------------------------------------------------------------------
    // WAV
    // ---------------------------------------------------------------------------------------------

    private static long parseWav(byte[] d) {
        int p = 12; // saute "RIFF"(4) size(4) "WAVE"(4)
        long byteRate = 0;
        while (p + 8 <= d.length) {
            String id = new String(d, p, 4, StandardCharsets.US_ASCII);
            long size = readUInt32LE(d, p + 4);
            int content = p + 8;
            if ("fmt ".equals(id)) {
                if (content + 16 <= d.length) {
                    byteRate = readUInt32LE(d, content + 8); // octets/seconde
                }
            } else if ("data".equals(id)) {
                if (byteRate > 0) {
                    return Math.round(size * 1000.0 / byteRate);
                }
                return 0L;
            }
            // Les chunks sont alignés sur 2 octets.
            p = content + (int) (size + (size & 1));
        }
        return 0L;
    }

    // ---------------------------------------------------------------------------------------------
    // MP4 / M4A
    // ---------------------------------------------------------------------------------------------

    private static long parseMp4(String url, String authHeader, byte[] head, long fileSize) throws IOException {
        return parseMp4(url, authHeader, head, fileSize, null);
    }

    private static long parseMp4(String url, String authHeader, byte[] head, long fileSize, HttpURLConnection baseConn) throws IOException {
        long offset = 0;
        int guard = 0;
        while (guard++ < 128) {
            // Requêtes followup créent de nouvelles connexions (qui réutilisent la socket via keep-alive)
            byte[] hdr = bytesAt(url, authHeader, head, offset, 16, null);
            if (hdr == null || hdr.length < 8) return 0L;

            long size = readUInt32(hdr, 0);
            String type = new String(hdr, 4, 4, StandardCharsets.US_ASCII);
            int headerLen = 8;
            if (size == 1) {
                if (hdr.length < 16) return 0L;
                size = readUInt64(hdr, 8);
                headerLen = 16;
            }
            if (size < headerLen) return 0L;

            if ("moov".equals(type)) {
                int cap = (int) Math.min(size, 1 << 20); // 1 Mo suffit largement pour mvhd
                // Requête followup : crée une nouvelle connexion
                byte[] moov = rangeGet(url, authHeader, offset, cap);
                if (moov == null) return 0L;
                return findMvhdDuration(moov);
            }

            offset += size;
            if (fileSize > 0 && offset >= fileSize) return 0L;
        }
        return 0L;
    }

    /** Retourne 16 octets à partir de {@code offset}, depuis {@code head} si possible, sinon via HTTP. */
    private static byte[] bytesAt(String url, String authHeader, byte[] head, long offset, int len) throws IOException {
        return bytesAt(url, authHeader, head, offset, len, null);
    }

    /** Retourne 16 octets à partir de {@code offset}, depuis {@code head} si possible, sinon via HTTP. */
    private static byte[] bytesAt(String url, String authHeader, byte[] head, long offset, int len, HttpURLConnection baseConn) throws IOException {
        if (offset >= 0 && offset + len <= head.length) {
            byte[] out = new byte[len];
            System.arraycopy(head, (int) offset, out, 0, len);
            return out;
        }
        // Les requêtes followup créent toujours une nouvelle connexion (qui réutilise
        // la socket du pool grâce au keep-alive). On ne réutilise pas baseConn car il
        // a déjà été utilisé pour la première requête Range.
        return rangeGet(url, authHeader, offset, len);
    }

    private static long findMvhdDuration(byte[] moov) {
        int p = 8; // corps de "moov"
        while (p + 8 <= moov.length) {
            long size = readUInt32(moov, p);
            String type = new String(moov, p + 4, 4, StandardCharsets.US_ASCII);
            int headerLen = 8;
            long real = size;
            if (size == 1) {
                if (p + 16 > moov.length) break;
                real = readUInt64(moov, p + 8);
                headerLen = 16;
            }
            if ("mvhd".equals(type)) {
                int b = p + headerLen;
                if (b + 1 > moov.length) return 0L;
                int version = moov[b] & 0xFF;
                if (version == 0) {
                    if (b + 20 > moov.length) return 0L;
                    long timescale = readUInt32(moov, b + 12);
                    long duration = readUInt32(moov, b + 16);
                    if (timescale > 0) return duration * 1000L / timescale;
                } else {
                    if (b + 32 > moov.length) return 0L;
                    long timescale = readUInt32(moov, b + 20);
                    long duration = readUInt64(moov, b + 24);
                    if (timescale > 0) return duration * 1000L / timescale;
                }
                return 0L;
            }
            if (real <= 0) break;
            p += (int) real;
        }
        return 0L;
    }

    // ---------------------------------------------------------------------------------------------
    // OGG (Vorbis / Opus)
    // ---------------------------------------------------------------------------------------------

    private static long parseOgg(String url, String authHeader, byte[] head, long fileSize) throws IOException {
        return parseOgg(url, authHeader, head, fileSize, null);
    }

    private static long parseOgg(String url, String authHeader, byte[] head, long fileSize, HttpURLConnection baseConn) throws IOException {
        int sampleRate = 0;
        boolean opus = false;
        int preSkip = 0;

        // En-tête d'identification dans la première page (présent dans le début du fichier).
        for (int i = 0; i + 16 < head.length; i++) {
            // Opus : "OpusHead"
            if (head[i] == 'O' && head[i + 1] == 'p' && head[i + 2] == 'u' && head[i + 3] == 's'
                    && head[i + 4] == 'H' && head[i + 5] == 'e' && head[i + 6] == 'a' && head[i + 7] == 'd') {
                opus = true;
                preSkip = readUInt16LE(head, i + 10);
                sampleRate = 48000; // le granule Opus est toujours en unités 48 kHz
                break;
            }
            // Vorbis : 0x01 "vorbis"
            if ((head[i] & 0xFF) == 0x01 && head[i + 1] == 'v' && head[i + 2] == 'o' && head[i + 3] == 'r'
                    && head[i + 4] == 'b' && head[i + 5] == 'i' && head[i + 6] == 's') {
                sampleRate = (int) readUInt32LE(head, i + 12);
                break;
            }
        }
        if (sampleRate <= 0) return 0L;

        // Le granule de la DERNIÈRE page OggS donne le nombre total d'échantillons.
        int tailLen = 65536;
        long tailStart = fileSize > tailLen ? fileSize - tailLen : 0;
        // Requête followup : crée une nouvelle connexion qui réutilisera la socket
        byte[] tail = rangeGet(url, authHeader, tailStart, (int) Math.min(tailLen, fileSize > 0 ? fileSize : tailLen));
        if (tail == null || tail.length < 14) return 0L;

        long granule = -1;
        for (int i = tail.length - 14; i >= 0; i--) {
            if (tail[i] == 'O' && tail[i + 1] == 'g' && tail[i + 2] == 'g' && tail[i + 3] == 'S') {
                granule = readUInt64LE(tail, i + 6);
                break;
            }
        }
        if (granule <= 0) return 0L;

        long samples = opus ? Math.max(0, granule - preSkip) : granule;
        return Math.round(samples * 1000.0 / sampleRate);
    }

    // ---------------------------------------------------------------------------------------------
    // Lecture d'entiers
    // ---------------------------------------------------------------------------------------------

    private static int readInt32(byte[] d, int off) {
        return ((d[off] & 0xFF) << 24) | ((d[off + 1] & 0xFF) << 16)
                | ((d[off + 2] & 0xFF) << 8) | (d[off + 3] & 0xFF);
    }

    private static long readUInt32(byte[] d, int off) {
        return ((long) (d[off] & 0xFF) << 24) | ((d[off + 1] & 0xFF) << 16)
                | ((d[off + 2] & 0xFF) << 8) | (d[off + 3] & 0xFF);
    }

    private static long readUInt32LE(byte[] d, int off) {
        return ((long) (d[off + 3] & 0xFF) << 24) | ((d[off + 2] & 0xFF) << 16)
                | ((d[off + 1] & 0xFF) << 8) | (d[off] & 0xFF);
    }

    private static int readUInt16LE(byte[] d, int off) {
        return ((d[off + 1] & 0xFF) << 8) | (d[off] & 0xFF);
    }

    private static long readUInt64LE(byte[] d, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (d[off + i] & 0xFF);
        }
        return v;
    }

    private static long readUInt64(byte[] d, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (d[off + i] & 0xFF);
        }
        return v;
    }
}


