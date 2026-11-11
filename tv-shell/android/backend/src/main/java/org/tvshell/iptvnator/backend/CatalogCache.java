package org.tvshell.iptvnator.backend;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Xtream catalogues (channel, movie and series lists and their categories) kept on disk for a
 * day. The web app holds them in memory only, so every start downloaded about 160 MB again.
 * <p>
 * An entry is the payload the backend sent, saved only once it arrived whole as a non-empty JSON
 * array (a provider error, an empty list or a cut transfer is never kept). Files are named by a
 * hash of the request, which carries the login; expired and abandoned files are deleted.
 */
final class CatalogCache {

    static final Set<String> ACTIONS = new HashSet<String>(Arrays.asList(
        "get_live_streams", "get_vod_streams", "get_series",
        "get_live_categories", "get_vod_categories", "get_series_categories"));
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final long PART_MAX_AGE_MS = 60L * 60 * 1000;

    private final File dir;
    /** How long an entry is served; a field so tests need not wait a day. */
    long ttlMs = 24L * 60 * 60 * 1000;

    CatalogCache(File dir) {
        this.dir = dir;
        dir.mkdirs();
        prune();
    }

    static boolean cacheable(String action) {
        return action != null && ACTIONS.contains(action);
    }

    /** The saved payload while it is fresh, else null. */
    InputStream open(String requestUrl) {
        File entry = entry(requestUrl);
        long modified = entry.lastModified(); // 0 when missing
        if (modified == 0 || System.currentTimeMillis() - modified >= ttlMs) return null;
        try {
            return new FileInputStream(entry);
        } catch (IOException e) {
            return null;
        }
    }

    /** A payload the backend buffered (validated JSON text). */
    void save(String requestUrl, String payload) {
        if (!nonEmptyArray(payload)) return;
        File part = null;
        try {
            part = File.createTempFile("entry", ".part", dir);
            OutputStream out = new FileOutputStream(part);
            try {
                out.write(payload.getBytes(UTF8));
            } finally {
                out.close();
            }
            commit(part, requestUrl);
        } catch (IOException e) {
            if (part != null) part.delete();
        }
    }

    /** A streamed payload: copied as the web app reads it, kept only at a clean end. */
    InputStream tee(String requestUrl, InputStream payload) {
        try {
            return new Tee(payload, File.createTempFile("entry", ".part", dir), requestUrl);
        } catch (IOException e) {
            return payload; // no room for a copy: the web app still gets its data
        }
    }

    void prune() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File file : files) {
            long age = now - file.lastModified();
            if (file.getName().endsWith(".part") ? age > PART_MAX_AGE_MS : age >= ttlMs) file.delete();
        }
    }

    private File entry(String requestUrl) {
        return new File(dir, "xtream-" + WebBackend.sha256Hex(requestUrl) + ".json");
    }

    private void commit(File part, String requestUrl) {
        File entry = entry(requestUrl);
        if (!part.renameTo(entry)) {
            entry.delete();
            if (!part.renameTo(entry)) part.delete();
        }
        prune();
    }

    static boolean nonEmptyArray(String text) {
        if (text == null) return false;
        String t = text.trim();
        return t.length() > 2 && t.charAt(0) == '[' && t.charAt(t.length() - 1) == ']' && !t.substring(1, t.length() - 1).trim().isEmpty();
    }

    private final class Tee extends FilterInputStream {
        private final File part;
        private final String requestUrl;
        private OutputStream copy;
        // Structure seen so far, outside strings: the copy is kept only if it is one whole array.
        private int first = -1;
        private long significant; // bytes other than whitespace
        private int depth;
        private boolean inString, escaped, closed, malformed;

        Tee(InputStream in, File part, String requestUrl) throws IOException {
            super(in);
            this.part = part;
            this.requestUrl = requestUrl;
            this.copy = new FileOutputStream(part);
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n;
            try {
                n = super.read(b, off, len);
            } catch (IOException e) {
                abandon();
                throw e;
            }
            if (n == -1) {
                finish();
                return -1;
            }
            if (copy != null) {
                try {
                    copy.write(b, off, n);
                } catch (IOException e) {
                    abandon(); // the disk is full: the web app still gets its data
                }
                for (int i = off; i < off + n; i++) track(b[i] & 0xff);
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            abandon(); // closed before the end: the transfer was cut
            super.close();
        }

        /** UTF-8 continuation bytes are >= 0x80: they never look like a delimiter. */
        private void track(int c) {
            if (inString) {
                significant++;
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                return;
            }
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') return;
            significant++;
            if (first == -1) first = c;
            if (closed) malformed = true; // more after the top-level array
            else if (c == '"') inString = true;
            else if (c == '[' || c == '{') depth++;
            else if (c == ']' || c == '}') {
                if (--depth == 0) closed = true;
                else if (depth < 0) malformed = true;
            }
        }

        private void finish() {
            if (copy == null) return;
            try {
                copy.close();
                copy = null;
                // One whole non-empty array: "[", an element, "]" at least, every bracket closed.
                if (first == '[' && closed && !malformed && significant > 2) commit(part, requestUrl);
                else part.delete();
            } catch (IOException e) {
                part.delete();
            }
        }

        private void abandon() {
            if (copy == null) return;
            try {
                copy.close();
            } catch (IOException ignored) {
                // deleted below either way
            }
            copy = null;
            part.delete();
        }
    }
}
