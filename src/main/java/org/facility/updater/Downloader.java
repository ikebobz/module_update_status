package org.facility.updater;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.logging.Logger;

/**
 * Fetches module JARs into the local cache.
 * - Resumes interrupted downloads with HTTP Range requests (partial file kept as *.part across runs)
 * - Retries with exponential backoff
 * - Optional bandwidth cap so updates don't saturate a facility's link
 * - Never hands back a file whose SHA-256 doesn't match the signed manifest
 */
public final class Downloader {

    private static final Logger LOG = Logger.getLogger(Downloader.class.getName());

    private final Config cfg;
    private final Http http;
    private final Path cacheDir;

    public Downloader(Config cfg, Http http) {
        this.cfg = cfg;
        this.http = http;
        this.cacheDir = cfg.cacheDir();
    }

    public Path cached(ReleaseManifest.ModuleRelease r) {
        return cacheDir.resolve(r.fileName());
    }

    /** @return path of the verified JAR in the cache */
    public Path ensure(ReleaseManifest.ModuleRelease r, ReleaseManifest m) throws IOException {
        Files.createDirectories(cacheDir);
        Path target = cached(r);
        if (Files.isRegularFile(target)) {
            if (r.sha256.equals(Signatures.sha256(target))) return target;
            LOG.warning("Cached " + target.getFileName() + " is corrupt; fetching again");
            Files.delete(target);
        }

        if (isRemote(r.url) || !m.offline) {
            downloadWithRetry(resolve(r.url, m), target, r);
        } else {
            Path src = Paths.get(m.source).resolve(r.url).normalize();
            LOG.info("Copying " + r + " from offline bundle " + src);
            Path tmp = target.resolveSibling(target.getFileName() + ".part");
            Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING);
            verifyAndPromote(tmp, target, r);
        }
        return target;
    }

    static boolean isRemote(String url) {
        String u = url.toLowerCase();
        return u.startsWith("http://") || u.startsWith("https://");
    }

    static String resolve(String url, ReleaseManifest m) {
        if (isRemote(url)) return url;
        return URI.create(m.source).resolve(url).toString();
    }

    private void downloadWithRetry(String url, Path target, ReleaseManifest.ModuleRelease r) throws IOException {
        int attempts = cfg.getInt("download.retries", 5);
        long backoffMs = cfg.getInt("download.retry-initial-seconds", 5) * 1000L;
        IOException last = null;
        for (int a = 1; a <= attempts; a++) {
            try {
                download(url, target, r);
                return;
            } catch (ChecksumException e) {
                throw e; // a bad file won't get better by retrying
            } catch (IOException e) {
                last = e;
                LOG.warning("Download of " + r + " failed (attempt " + a + "/" + attempts + "): " + e.getMessage());
                if (a < attempts) sleep(Math.min(backoffMs, 10 * 60 * 1000L));
                backoffMs *= 2;
            }
        }
        throw last;
    }

    private void download(String url, Path target, ReleaseManifest.ModuleRelease r) throws IOException {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        long have = Files.isRegularFile(part) ? Files.size(part) : 0;
        if (r.size > 0 && have > r.size) { Files.delete(part); have = 0; }

        HttpURLConnection c = http.open(url, "GET",
                have > 0 ? Collections.singletonMap("Range", "bytes=" + have + "-") : null);
        int status = c.getResponseCode();
        boolean append;
        if (status == 206) {
            append = true;
            LOG.info("Resuming " + r + " from " + have + " bytes");
        } else if (status == 200) {
            append = false;
            if (have > 0) LOG.info("Server does not support resume; restarting " + r);
        } else if (status == 416 && have > 0) {
            c.disconnect();
            verifyAndPromote(part, target, r); // already complete
            return;
        } else {
            c.disconnect();
            throw new IOException("HTTP " + status + " for " + url);
        }

        long maxBytesPerSec = cfg.getInt("download.max-kbps", 0) * 1024L;
        long started = System.nanoTime(), sent = 0;
        try (InputStream in = c.getInputStream();
             OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE,
                     append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.WRITE)) {
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                sent += n;
                if (maxBytesPerSec > 0) {
                    long expectedNs = sent * 1_000_000_000L / maxBytesPerSec;
                    long aheadMs = (expectedNs - (System.nanoTime() - started)) / 1_000_000L;
                    if (aheadMs > 0) sleep(aheadMs);
                }
            }
        } finally {
            c.disconnect();
        }
        verifyAndPromote(part, target, r);
        LOG.info("Downloaded " + r + " (" + Files.size(target) + " bytes)");
    }

    private void verifyAndPromote(Path tmp, Path target, ReleaseManifest.ModuleRelease r) throws IOException {
        if (r.size > 0 && Files.size(tmp) < r.size) {
            throw new IOException("Incomplete download of " + r + " (" + Files.size(tmp) + "/" + r.size + " bytes)");
        }
        String actual = Signatures.sha256(tmp);
        if (!actual.equals(r.sha256)) {
            Files.deleteIfExists(tmp);
            throw new ChecksumException("Checksum mismatch for " + r + ": expected " + r.sha256 + " got " + actual);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Keeps the newest {@code keep} cached versions of each module (current + rollback copy). */
    public void prune(int keep) {
        try {
            if (!Files.isDirectory(cacheDir)) return;
            java.util.Map<String, java.util.List<Path>> byModule = new java.util.HashMap<String, java.util.List<Path>>();
            try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(cacheDir, "*.jar")) {
                for (Path p : ds) {
                    String f = p.getFileName().toString();
                    int dash = lastVersionDash(f);
                    if (dash < 0) continue;
                    String mod = f.substring(0, dash).toLowerCase();
                    if (!byModule.containsKey(mod)) byModule.put(mod, new java.util.ArrayList<Path>());
                    byModule.get(mod).add(p);
                }
            }
            for (java.util.List<Path> files : byModule.values()) {
                java.util.Collections.sort(files, new java.util.Comparator<Path>() {
                    @Override
                    public int compare(Path a, Path b) { return versionOf(b).compareTo(versionOf(a)); }
                });
                for (int i = keep; i < files.size(); i++) Files.deleteIfExists(files.get(i));
            }
        } catch (IOException e) {
            LOG.warning("Cache prune failed: " + e.getMessage());
        }
    }

    /** Cached JAR of a specific version, if present (used for rollback). */
    public Path cachedVersion(String module, String version) {
        Path p = cacheDir.resolve(module + "-" + version + ".jar");
        return Files.isRegularFile(p) ? p : null;
    }

    private static int lastVersionDash(String fileName) {
        // "<name>-<version>.jar": version starts after the last '-' followed by a digit
        for (int i = fileName.length() - 1; i > 0; i--) {
            if (fileName.charAt(i - 1) == '-' && Character.isDigit(fileName.charAt(i))) return i - 1;
        }
        return -1;
    }

    private static Version versionOf(Path p) {
        String f = p.getFileName().toString();
        return Version.of(f.substring(lastVersionDash(f) + 1, f.length() - 4));
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public static final class ChecksumException extends IOException {
        private static final long serialVersionUID = 1L;
        ChecksumException(String m) { super(m); }
    }
}
