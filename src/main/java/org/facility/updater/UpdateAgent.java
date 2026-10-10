package org.facility.updater;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * One update cycle:
 *  1. Find the newest signed manifest (online, USB, or cached)
 *  2. Compare it with what the application reports as installed
 *  3. Download whatever is missing (any time of day; resumable)
 *  4. Inside the maintenance window (or immediately for urgent releases) install via the app's API,
 *     restart, verify the new version is live, and roll back if it is not
 *  5. Save state and report status to the central server
 */
public final class UpdateAgent {

    private static final Logger LOG = Logger.getLogger(UpdateAgent.class.getName());

    private final Config cfg;
    private final ManifestSource source;
    private final Downloader downloader;
    private final AppClient app;
    private final Reporter reporter;
    private final Path stateFile;
    private LocalTime clock; // overridable for tests

    public UpdateAgent(Config cfg) throws Exception {
        this.cfg = cfg;
        Http http = new Http(cfg.getInt("http.connect-timeout-seconds", 20) * 1000,
                cfg.getInt("http.read-timeout-seconds", 120) * 1000);
        this.source = new ManifestSource(cfg, http);
        this.downloader = new Downloader(cfg, http);
        this.app = new AppClient(cfg, http);
        this.reporter = new Reporter(cfg, http);
        this.stateFile = cfg.stateDir().resolve("state.json");
    }

    void setClock(LocalTime t) { this.clock = t; }

    public State runOnce(boolean dryRun) {
        State state = State.load(stateFile);
        state.lastRun = State.now();
        state.lastError = null;
        ReleaseManifest manifest = null;
        try {
            manifest = source.best(state.lastSequence, state);
            if (manifest == null) {
                state.lastResult = "no-manifest";
                LOG.warning("No verified release manifest available (offline and nothing cached yet)");
                return state;
            }
            if (manifest.sequence > state.lastSequence) {
                state.event("new-release-list", null, null,
                        "sequence " + manifest.sequence + " from " + (manifest.offline ? "USB " : "") + manifest.source);
                state.lastSequence = manifest.sequence;
            }

            boolean appUp = app.isHealthy();
            Map<String, AppClient.InstalledModule> installed = appUp ? app.listInstalled() : null;
            Map<String, String> known = installed != null ? lower(AppClient.versions(installed)) : lower(state.installed);
            if (installed != null) state.installed = AppClient.versions(installed);
            if (!appUp) LOG.warning("Application is not responding at its health URL; will download but not install");

            List<ReleaseManifest.ModuleRelease> plan = plan(manifest, known);
            state.pending.clear();
            for (ReleaseManifest.ModuleRelease r : plan) state.pending.put(r.name, r.version.toString());

            if (plan.isEmpty()) {
                state.lastResult = "up-to-date";
                LOG.info("All modules up to date (release list " + manifest.sequence + ")");
                return state;
            }
            LOG.info("Updates needed: " + AppClient.names(plan));
            if (dryRun) {
                state.lastResult = "dry-run";
                return state;
            }

            // ---- download (allowed any time)
            List<ReleaseManifest.ModuleRelease> ready = new ArrayList<ReleaseManifest.ModuleRelease>();
            for (ReleaseManifest.ModuleRelease r : plan) {
                try {
                    downloader.ensure(r, manifest);
                    ready.add(r);
                } catch (IOException e) {
                    state.lastError = e.getMessage();
                    state.event("download-failed", r.name, r.version.toString(), e.getMessage());
                    LOG.warning("Could not obtain " + r + ": " + e.getMessage());
                }
            }
            if (!appUp) { state.lastResult = "app-unavailable"; return state; }

            // ---- choose what to install now
            boolean inWindow = cfg.inInstallWindow(clock != null ? clock : LocalTime.now());
            int maxAttempts = cfg.getInt("install.max-attempts", 3);
            String core = app.coreVersion(installed);
            List<ReleaseManifest.ModuleRelease> toInstall = new ArrayList<ReleaseManifest.ModuleRelease>();
            for (ReleaseManifest.ModuleRelease r : ready) {
                Long attempts = state.failedAttempts.get(r.name + "@" + r.version);
                if (attempts != null && attempts >= maxAttempts) {
                    LOG.warning("Skipping " + r + ": failed " + attempts + " times, waiting for a newer release");
                    continue;
                }
                if (r.minCoreVersion != null) {
                    if (core == null) {
                        LOG.warning(r + " needs core " + r.minCoreVersion + " but core version is unknown; proceeding");
                    } else if (Version.of(core).compareTo(r.minCoreVersion) < 0) {
                        state.event("blocked-needs-core", r.name, r.version.toString(),
                                "requires core " + r.minCoreVersion + ", facility has " + core);
                        continue;
                    }
                }
                if (inWindow || r.urgent) toInstall.add(r);
            }
            if (toInstall.isEmpty()) {
                state.lastResult = inWindow ? "blocked" : "downloaded-waiting-for-window";
                LOG.info("Updates downloaded; installation waits for the maintenance window ("
                        + cfg.get("install.window", "20:00-06:00") + ")");
                return state;
            }

            install(toInstall, known, state);
        } catch (Exception e) {
            state.lastResult = "error";
            state.lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            LOG.severe("Update cycle failed: " + state.lastError);
        } finally {
            if (!dryRun) {
                try { state.save(stateFile); } catch (IOException e) { LOG.severe("Could not save state: " + e.getMessage()); }
                reporter.report(state);
                downloader.prune(cfg.getInt("updater.cache-keep-versions", 2));
            }
        }
        return state;
    }

    private List<ReleaseManifest.ModuleRelease> plan(ReleaseManifest m, Map<String, String> installed) {
        boolean installNew = cfg.getBool("install.new-modules", false);
        List<ReleaseManifest.ModuleRelease> out = new ArrayList<ReleaseManifest.ModuleRelease>();
        for (ReleaseManifest.ModuleRelease r : m.modules) {
            String cur = installed.get(r.key());
            if (cur == null) {
                if (installNew) out.add(r);
                continue;
            }
            Version current = Version.of(cur);
            boolean needed = r.forceVersion ? !r.version.equals(current) : r.version.isNewerThan(current);
            if (needed) out.add(r);
        }
        return out;
    }

    private void install(List<ReleaseManifest.ModuleRelease> toInstall, Map<String, String> before, State state)
            throws IOException {
        List<ReleaseManifest.ModuleRelease> uploaded = new ArrayList<ReleaseManifest.ModuleRelease>();
        for (ReleaseManifest.ModuleRelease r : toInstall) {
            try {
                LOG.info("Installing " + r + " (currently " + before.get(r.key()) + ")");
                app.install(r, downloader.cached(r));
                uploaded.add(r);
            } catch (IOException e) {
                fail(state, r, e.getMessage());
            }
        }
        if (uploaded.isEmpty()) { state.lastResult = "install-failed"; return; }

        boolean healthy = app.restartAndWait();
        Map<String, String> after = healthy ? lower(AppClient.versions(app.listInstalled())) : new LinkedHashMap<String, String>();

        List<ReleaseManifest.ModuleRelease> failed = new ArrayList<ReleaseManifest.ModuleRelease>();
        for (ReleaseManifest.ModuleRelease r : uploaded) {
            String now = after.get(r.key());
            if (healthy && now != null && Version.of(now).equals(r.version)) {
                state.failedAttempts.remove(r.name + "@" + r.version);
                state.event("installed", r.name, r.version.toString(), "was " + before.get(r.key()));
                LOG.info("Verified " + r + " is live");
            } else {
                fail(state, r, healthy ? "application reports version " + now + " after install"
                                       : "application did not come back healthy after install");
                failed.add(r);
            }
        }

        if (!failed.isEmpty() && cfg.getBool("rollback.enabled", true)) rollback(failed, before, state);

        if (healthy || app.isHealthy()) state.installed = AppClient.versions(app.listInstalled());
        state.pending.clear();
        for (ReleaseManifest.ModuleRelease r : toInstall) {
            if (!r.version.toString().equals(lower(state.installed).get(r.key()))) state.pending.put(r.name, r.version.toString());
        }
        state.lastResult = failed.isEmpty() && uploaded.size() == toInstall.size() ? "updated" : "partially-updated";
    }

    private void rollback(List<ReleaseManifest.ModuleRelease> failed, Map<String, String> before, State state)
            throws IOException {
        boolean any = false;
        for (ReleaseManifest.ModuleRelease r : failed) {
            String prev = before.get(r.key());
            Path jar = prev == null ? null : downloader.cachedVersion(r.name, prev);
            if (jar == null) {
                state.event("rollback-unavailable", r.name, prev, "no cached copy of the previous version");
                LOG.severe("Cannot roll back " + r.name + ": previous version " + prev + " not in cache");
                continue;
            }
            ReleaseManifest.ModuleRelease old = new ReleaseManifest.ModuleRelease();
            old.name = r.name;
            old.version = Version.of(prev);
            try {
                app.install(old, jar);
                any = true;
                state.event("rolled-back", r.name, prev, "after failed " + r.version);
            } catch (IOException e) {
                state.event("rollback-failed", r.name, prev, e.getMessage());
            }
        }
        if (any && !app.restartAndWait()) {
            state.lastError = "Application unhealthy after rollback - needs attention";
            LOG.severe(state.lastError);
        }
    }

    private void fail(State state, ReleaseManifest.ModuleRelease r, String why) {
        String k = r.name + "@" + r.version;
        Long n = state.failedAttempts.get(k);
        state.failedAttempts.put(k, n == null ? 1L : n + 1);
        state.lastError = r + ": " + why;
        state.event("install-failed", r.name, r.version.toString(), why);
        LOG.severe("Install of " + r + " failed: " + why);
    }

    private static Map<String, String> lower(Map<String, String> m) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : m.entrySet()) out.put(e.getKey().toLowerCase(), e.getValue());
        return out;
    }
}
