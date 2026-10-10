package org.facility.updater;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The signed list of current module releases published by the release team.
 *
 * <pre>
 * {
 *   "sequence": 42,                         // must increase with every publish (anti-rollback)
 *   "channel": "stable",
 *   "published_at": "2026-10-10T09:00:00Z",
 *   "modules": [
 *     { "name": "hiv", "version": "1.4.2", "url": "hiv-1.4.2.jar",
 *       "sha256": "...", "size": 1234567, "order": 20,
 *       "urgent": false, "force_version": false, "min_core_version": "3.1.0",
 *       "notes": "Fixes viral load report" }
 *   ]
 * }
 * </pre>
 */
public final class ReleaseManifest {

    public static final class ModuleRelease {
        public String name;
        public Version version;
        public String url;          // absolute, or relative to the manifest location / USB bundle folder
        public String sha256;
        public long size;
        public int order;           // install order (lower first, e.g. dependencies before dependants)
        public boolean urgent;      // install immediately, ignoring the maintenance window
        public boolean forceVersion;// install this exact version even if the facility has a newer one
        public Version minCoreVersion;
        public String notes;

        public String key() { return name.toLowerCase(); }

        public String fileName() { return name + "-" + version + ".jar"; }

        @Override
        public String toString() { return name + " " + version; }
    }

    public long sequence;
    public String channel;
    public String publishedAt;
    public final List<ModuleRelease> modules = new ArrayList<ModuleRelease>();

    /** Where the manifest came from: an http(s) URL or a local folder (USB bundle). */
    public String source;
    public boolean offline;

    @SuppressWarnings("unchecked")
    public static ReleaseManifest parse(String json) {
        Map<String, Object> root = Json.parseObject(json);
        ReleaseManifest m = new ReleaseManifest();
        m.sequence = Json.lng(root.get("sequence"), -1);
        if (m.sequence < 0) throw new IllegalArgumentException("Manifest has no valid 'sequence'");
        m.channel = Json.str(root.get("channel"));
        m.publishedAt = Json.str(root.get("published_at"));
        Object mods = root.get("modules");
        if (!(mods instanceof List)) throw new IllegalArgumentException("Manifest has no 'modules' list");
        for (Object o : (List<Object>) mods) {
            Map<String, Object> mm = (Map<String, Object>) o;
            ModuleRelease r = new ModuleRelease();
            r.name = req(mm, "name");
            r.version = Version.of(req(mm, "version"));
            r.url = req(mm, "url");
            r.sha256 = req(mm, "sha256").toLowerCase();
            r.size = Json.lng(mm.get("size"), -1);
            r.order = (int) Json.lng(mm.get("order"), 100);
            r.urgent = Json.bool(mm.get("urgent"));
            r.forceVersion = Json.bool(mm.get("force_version"));
            String core = Json.str(mm.get("min_core_version"));
            r.minCoreVersion = core == null || core.isEmpty() ? null : Version.of(core);
            r.notes = Json.str(mm.get("notes"));
            if (!r.sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Module " + r.name + " has an invalid sha256");
            }
            m.modules.add(r);
        }
        Collections.sort(m.modules, new Comparator<ModuleRelease>() {
            @Override
            public int compare(ModuleRelease a, ModuleRelease b) { return Integer.compare(a.order, b.order); }
        });
        return m;
    }

    private static String req(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null || v.toString().trim().isEmpty()) {
            throw new IllegalArgumentException("Manifest module entry missing '" + k + "'");
        }
        return v.toString().trim();
    }
}
