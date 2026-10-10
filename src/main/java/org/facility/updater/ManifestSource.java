package org.facility.updater;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.PublicKey;
import java.util.logging.Logger;

/**
 * Finds the newest trustworthy release manifest from, in order of preference:
 * the online release server, an offline USB bundle, or the last verified copy cached on disk.
 * Every manifest must carry a valid signature and a sequence number not lower than the last one accepted.
 */
public final class ManifestSource {

    private static final Logger LOG = Logger.getLogger(ManifestSource.class.getName());
    static final String MANIFEST = "manifest.json";
    static final String SIG = "manifest.json.sig";

    private final Config cfg;
    private final Http http;
    private final PublicKey key;

    public ManifestSource(Config cfg, Http http) throws Exception {
        this.cfg = cfg;
        this.http = http;
        String inline = cfg.get("manifest.public-key");
        this.key = inline != null
                ? Signatures.parsePublicKey(inline)
                : Signatures.loadPublicKey(cfg.path("manifest.public-key-file", "release-public.pem"));
    }

    /**
     * @param minSequence lowest acceptable sequence (the last one already accepted)
     * @return the best verified manifest, or null if none is available at all
     */
    public ReleaseManifest best(long minSequence, State state) {
        ReleaseManifest online = fetchOnline(minSequence);
        ReleaseManifest usb = scanOffline(minSequence);

        ReleaseManifest chosen = online;
        if (usb != null && (chosen == null || usb.sequence > chosen.sequence)) chosen = usb;

        if (chosen != null) {
            state.lastManifestCheckOk = chosen.offline ? state.lastManifestCheckOk : State.now();
            return chosen;
        }
        return loadCached(minSequence);
    }

    ReleaseManifest fetchOnline(long minSequence) {
        String url = cfg.get("manifest.url");
        if (url == null) return null;
        try {
            Http.Response m = http.get(url, null);
            if (!m.ok()) {
                LOG.warning("Release server returned HTTP " + m.status + " for " + url);
                return null;
            }
            Http.Response s = http.get(url + ".sig", null);
            if (!s.ok()) {
                LOG.warning("Could not fetch manifest signature (HTTP " + s.status + ")");
                return null;
            }
            ReleaseManifest rm = accept(m.body, s.body, url, false, minSequence);
            if (rm != null) cache(m.body, s.body, url, false);
            return rm;
        } catch (Exception e) {
            LOG.info("Release server not reachable (" + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + ") - will use offline/cached manifest");
            return null;
        }
    }

    ReleaseManifest scanOffline(long minSequence) {
        ReleaseManifest best = null;
        for (File dir : cfg.offlineCandidates()) {
            File mf = new File(dir, MANIFEST);
            File sf = new File(dir, SIG);
            try {
                if (!mf.isFile() || !sf.isFile()) continue;
                byte[] m = Files.readAllBytes(mf.toPath());
                byte[] s = Files.readAllBytes(sf.toPath());
                ReleaseManifest rm = accept(m, s, dir.getAbsolutePath(), true, minSequence);
                if (rm != null && (best == null || rm.sequence > best.sequence)) {
                    LOG.info("Found offline update bundle at " + dir + " (sequence " + rm.sequence + ")");
                    best = rm;
                    cache(m, s, dir.getAbsolutePath(), true);
                }
            } catch (Exception e) {
                LOG.warning("Ignoring offline bundle at " + dir + ": " + e.getMessage());
            }
        }
        return best;
    }

    ReleaseManifest loadCached(long minSequence) {
        Path dir = cfg.stateDir();
        Path m = dir.resolve(MANIFEST), s = dir.resolve(SIG), src = dir.resolve("manifest.source");
        if (!Files.isRegularFile(m) || !Files.isRegularFile(s)) return null;
        try {
            String[] meta = new String(Files.readAllBytes(src), StandardCharsets.UTF_8).split("\n", 2);
            boolean offline = "offline".equals(meta[0].trim());
            String source = meta.length > 1 ? meta[1].trim() : "";
            ReleaseManifest rm = accept(Files.readAllBytes(m), Files.readAllBytes(s), source, offline, minSequence);
            if (rm != null) LOG.info("Using cached manifest (sequence " + rm.sequence + ")");
            return rm;
        } catch (Exception e) {
            LOG.warning("Cached manifest unusable: " + e.getMessage());
            return null;
        }
    }

    private ReleaseManifest accept(byte[] manifest, byte[] sig, String source, boolean offline, long minSequence) {
        if (!Signatures.verify(key, manifest, sig)) {
            LOG.severe("REJECTED manifest from " + source + ": signature is not valid");
            return null;
        }
        ReleaseManifest rm = ReleaseManifest.parse(new String(manifest, StandardCharsets.UTF_8));
        if (rm.sequence < minSequence) {
            LOG.warning("Ignoring manifest from " + source + ": sequence " + rm.sequence
                    + " is older than already-accepted " + minSequence);
            return null;
        }
        String channel = cfg.get("manifest.channel");
        if (channel != null && rm.channel != null && !channel.equalsIgnoreCase(rm.channel)) {
            LOG.warning("Ignoring manifest from " + source + ": channel '" + rm.channel + "' != '" + channel + "'");
            return null;
        }
        rm.source = source;
        rm.offline = offline;
        return rm;
    }

    private void cache(byte[] manifest, byte[] sig, String source, boolean offline) {
        try {
            Path dir = cfg.stateDir();
            Files.createDirectories(dir);
            // Only overwrite the cache with an equal or newer sequence.
            ReleaseManifest current = null;
            Path existing = dir.resolve(MANIFEST);
            if (Files.isRegularFile(existing)) {
                try {
                    current = ReleaseManifest.parse(new String(Files.readAllBytes(existing), StandardCharsets.UTF_8));
                } catch (Exception ignored) { /* corrupt cache: overwrite */ }
            }
            ReleaseManifest incoming = ReleaseManifest.parse(new String(manifest, StandardCharsets.UTF_8));
            if (current != null && current.sequence > incoming.sequence) return;
            writeAtomic(dir.resolve(MANIFEST), manifest);
            writeAtomic(dir.resolve(SIG), sig);
            writeAtomic(dir.resolve("manifest.source"),
                    ((offline ? "offline" : "online") + "\n" + source).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOG.warning("Could not cache manifest: " + e.getMessage());
        }
    }

    static void writeAtomic(Path target, byte[] data) throws java.io.IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
