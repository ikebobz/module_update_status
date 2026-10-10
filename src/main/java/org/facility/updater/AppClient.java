package org.facility.updater;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Talks to the Spring Boot application's module-management REST API - the same endpoints the
 * "upload module" screen uses. Every path and JSON field name is configurable (app.* settings) so the
 * agent can be pointed at the exact API of your core version without code changes.
 */
public final class AppClient {

    private static final Logger LOG = Logger.getLogger(AppClient.class.getName());

    public static final class InstalledModule {
        public final String id, name, version;
        InstalledModule(String id, String name, String version) { this.id = id; this.name = name; this.version = version; }
        @Override public String toString() { return name + " " + version; }
    }

    private final Config cfg;
    private final Http http;
    private final String base;
    private String token;

    public AppClient(Config cfg, Http http) {
        this.cfg = cfg;
        this.http = http;
        String b = cfg.get("app.base-url", "http://localhost:8080");
        this.base = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    // -------------------------------------------------------------- health

    public boolean isHealthy() {
        try {
            Http.Response r = http.get(url(cfg.get("app.api.health", "/actuator/health")), null);
            if (!r.ok()) return false;
            String expect = cfg.get("app.api.health.expect");
            return expect == null || r.text().contains(expect);
        } catch (IOException e) {
            return false;
        }
    }

    public boolean waitHealthy(long timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        while (System.currentTimeMillis() < deadline) {
            if (isHealthy()) return true;
            sleep(5000);
        }
        return false;
    }

    // -------------------------------------------------------------- modules

    @SuppressWarnings("unchecked")
    public Map<String, InstalledModule> listInstalled() throws IOException {
        Http.Response r = authed("GET", url(cfg.get("app.api.list-modules", "/api/v1/modules")), null, null);
        if (!r.ok()) throw new IOException("Listing modules failed: HTTP " + r.status + " " + snippet(r));
        Object root = Json.parse(r.text());
        Object list = Json.path(root, cfg.get("app.api.list-modules.items-path", ""));
        if (!(list instanceof List)) throw new IOException("Module list response is not a JSON array (check app.api.list-modules.items-path)");

        String nameF = cfg.get("app.api.modules.name-field", "name");
        String versionF = cfg.get("app.api.modules.version-field", "version");
        String idF = cfg.get("app.api.modules.id-field", "id");
        String activeF = cfg.get("app.api.modules.active-field");

        Map<String, InstalledModule> out = new LinkedHashMap<String, InstalledModule>();
        for (Object o : (List<Object>) list) {
            String name = Json.str(Json.path(o, nameF));
            String ver = Json.str(Json.path(o, versionF));
            if (name == null || ver == null) continue;
            if (activeF != null && !Json.bool(Json.path(o, activeF))) continue;
            InstalledModule im = new InstalledModule(Json.str(Json.path(o, idF)), name, ver);
            InstalledModule existing = out.get(name.toLowerCase());
            // If the app lists several versions of one module, the highest is the one that counts.
            if (existing == null || Version.of(ver).isNewerThan(Version.of(existing.version))) {
                out.put(name.toLowerCase(), im);
            }
        }
        return out;
    }

    /** Core application version, from a dedicated endpoint or from the module list. Null if unknown. */
    public String coreVersion(Map<String, InstalledModule> installed) {
        String coreName = cfg.get("app.core.module-name");
        if (coreName != null && installed.containsKey(coreName.toLowerCase())) {
            return installed.get(coreName.toLowerCase()).version;
        }
        String path = cfg.get("app.api.core-version");
        if (path == null) return null;
        try {
            Http.Response r = authed("GET", url(path), null, null);
            if (!r.ok()) return null;
            String field = cfg.get("app.api.core-version.field", "version");
            Object v = Json.path(Json.parse(r.text()), field);
            return Json.str(v);
        } catch (Exception e) {
            LOG.warning("Could not read core version: " + e.getMessage());
            return null;
        }
    }

    /**
     * Uploads a module JAR and, if configured, calls the separate install/activate endpoint.
     */
    public void install(ReleaseManifest.ModuleRelease release, Path jar) throws IOException {
        String uploadPath = cfg.get("app.api.upload", "/api/v1/modules/upload");
        String field = cfg.get("app.api.upload.file-field", "file");
        Http.Response up = authedUpload(url(uploadPath), field, jar);
        if (!up.ok()) throw new IOException("Upload of " + release + " failed: HTTP " + up.status + " " + snippet(up));
        LOG.info("Uploaded " + jar.getFileName());

        String installTpl = cfg.get("app.api.install");
        if (installTpl == null) return;
        String id = null;
        try {
            Object body = Json.parse(up.text());
            id = Json.str(Json.path(body, cfg.get("app.api.upload.id-field", "id")));
        } catch (Exception ignored) { /* non-JSON upload response */ }
        if (installTpl.contains("{id}") && id == null) {
            // Fall back to looking the module up by name after upload.
            InstalledModule im = listInstalled().get(release.key());
            id = im == null ? null : im.id;
        }
        if (installTpl.contains("{id}") && id == null) {
            throw new IOException("Upload succeeded but no module id was returned for " + release
                    + " (check app.api.upload.id-field)");
        }
        String path = installTpl.replace("{id}", id == null ? "" : id)
                .replace("{name}", release.name).replace("{version}", release.version.toString());
        String method = cfg.get("app.api.install.method", "POST");
        Http.Response ins = authed(method, url(path), "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        if (!ins.ok()) throw new IOException("Install of " + release + " failed: HTTP " + ins.status + " " + snippet(ins));
        LOG.info("Install call accepted for " + release);
    }

    /** Runs the configured restart command (e.g. a Windows service restart) and waits for health. */
    public boolean restartAndWait() throws IOException {
        String cmd = cfg.get("app.restart.command");
        long timeout = cfg.getInt("app.restart.timeout-seconds", 600);
        if (cmd != null) {
            LOG.info("Restarting application: " + cmd);
            boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
            ProcessBuilder pb = windows ? new ProcessBuilder("cmd.exe", "/c", cmd) : new ProcessBuilder("sh", "-c", cmd);
            pb.redirectErrorStream(true);
            final Process p = pb.start();
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            Thread drain = new Thread(new Runnable() {
                @Override public void run() {
                    try { out.write(Http.readAll(p.getInputStream())); } catch (IOException ignored) { }
                }
            }, "restart-output");
            drain.setDaemon(true);
            drain.start();
            try {
                if (!p.waitFor(timeout, TimeUnit.SECONDS)) {
                    p.destroy();
                    throw new IOException("Restart command timed out");
                }
                drain.join(5000);
                if (p.exitValue() != 0) {
                    LOG.warning("Restart command exited " + p.exitValue() + ": " + new String(out.toByteArray(), StandardCharsets.UTF_8).trim());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted during restart");
            }
        }
        sleep(cfg.getInt("app.restart.settle-seconds", 20) * 1000L);
        token = null; // sessions do not survive a restart
        return waitHealthy(timeout);
    }

    // -------------------------------------------------------------- auth + plumbing

    private Http.Response authed(String method, String url, String contentType, byte[] body) throws IOException {
        Http.Response r = http.send(url, method, authHeaders(), contentType, body);
        if (r.status == 401 && "jwt".equals(authType())) {
            token = null;
            r = http.send(url, method, authHeaders(), contentType, body);
        }
        return r;
    }

    private Http.Response authedUpload(String url, String field, Path jar) throws IOException {
        Http.Response r = http.uploadMultipart(url, authHeaders(), field, jar);
        if (r.status == 401 && "jwt".equals(authType())) {
            token = null;
            r = http.uploadMultipart(url, authHeaders(), field, jar);
        }
        return r;
    }

    private String authType() { return cfg.get("app.auth.type", "jwt").toLowerCase(); }

    private Map<String, String> authHeaders() throws IOException {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Accept", "application/json");
        String type = authType();
        if ("basic".equals(type)) {
            String up = cfg.require("app.auth.username") + ":" + cfg.require("app.auth.password");
            h.put("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString(up.getBytes(StandardCharsets.UTF_8)));
        } else if ("token".equals(type)) {
            h.put("Authorization", "Bearer " + cfg.require("app.auth.token"));
        } else if ("jwt".equals(type)) {
            if (token == null) token = login();
            h.put("Authorization", "Bearer " + token);
        }
        return h;
    }

    private String login() throws IOException {
        String userF = cfg.get("app.auth.username-field", "username");
        String passF = cfg.get("app.auth.password-field", "password");
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put(userF, cfg.require("app.auth.username"));
        body.put(passF, cfg.require("app.auth.password"));
        body.put("rememberMe", Boolean.FALSE);
        Http.Response r = http.send(url(cfg.get("app.auth.path", "/api/v1/authenticate")), "POST", null,
                "application/json", Json.write(body).getBytes(StandardCharsets.UTF_8));
        if (!r.ok()) throw new IOException("Login to application failed: HTTP " + r.status);
        Object tok = Json.path(Json.parse(r.text()), cfg.get("app.auth.token-field", "id_token"));
        if (tok == null) throw new IOException("Login response had no token (check app.auth.token-field)");
        return tok.toString();
    }

    private String url(String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) return path;
        return base + (path.startsWith("/") ? path : "/" + path);
    }

    private static String snippet(Http.Response r) {
        String t = r.text();
        return t.length() > 300 ? t.substring(0, 300) + "..." : t;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Snapshot as plain map for reporting. */
    public static Map<String, String> versions(Map<String, InstalledModule> installed) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (InstalledModule im : installed.values()) m.put(im.name, im.version);
        return m;
    }

    static List<String> names(List<ReleaseManifest.ModuleRelease> rs) {
        List<String> out = new ArrayList<String>();
        for (ReleaseManifest.ModuleRelease r : rs) out.add(r.toString());
        return out;
    }
}
