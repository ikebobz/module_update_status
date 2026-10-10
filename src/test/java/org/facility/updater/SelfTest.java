package org.facility.updater;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * End-to-end tests with an in-process fake release server and fake Spring Boot module API.
 * Run: java -cp target/classes:target/test-classes org.facility.updater.SelfTest
 */
public final class SelfTest {

    static int passed = 0, failed = 0;

    // ---- fake application state
    static final Map<String, String> appModules = new LinkedHashMap<String, String>();
    static final Set<String> brokenReleases = new LinkedHashSet<String>();
    static final List<String> uploads = new ArrayList<String>();
    static volatile boolean appUp = true;

    // ---- fake release server state
    static Path releaseDir;
    static volatile boolean releaseServerUp = true;
    static volatile int cutNextDownloadAt = -1;
    static final List<String> rangeRequests = new ArrayList<String>();

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            failed++;
        }
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    static void run() throws Exception {
        Path tmp = Files.createTempDirectory("updater-test");
        releaseDir = Files.createDirectories(tmp.resolve("release"));
        Path agentDir = Files.createDirectories(tmp.resolve("agent"));
        Path priv = tmp.resolve("private.pem");
        Signatures.generateKeyPair(priv, agentDir.resolve("release-public.pem"));

        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/release/", SelfTest::release);
        srv.createContext("/actuator/health", ex -> respond(ex, appUp ? 200 : 503, "{\"status\":\"UP\"}"));
        srv.createContext("/api/v1/authenticate", ex -> respond(ex, 200, "{\"id_token\":\"tok123\"}"));
        srv.createContext("/api/v1/modules/upload", SelfTest::upload);
        srv.createContext("/api/v1/modules", SelfTest::list);
        srv.start();
        String base = "http://127.0.0.1:" + srv.getAddress().getPort();

        Properties p = new Properties();
        p.setProperty("manifest.url", base + "/release/manifest.json");
        p.setProperty("app.base-url", base);
        p.setProperty("app.auth.username", "admin");
        p.setProperty("app.auth.password", "secret");
        p.setProperty("app.restart.settle-seconds", "0");
        p.setProperty("app.restart.timeout-seconds", "5");
        p.setProperty("download.retries", "3");
        p.setProperty("download.retry-initial-seconds", "0");
        p.setProperty("offline.scan-drive-roots", "false");
        p.setProperty("install.window", "20:00-06:00");
        Config cfg = new Config(p, agentDir);
        UpdateAgent agent = new UpdateAgent(cfg);

        // ------------------------------------------------------------- unit: versions
        check("1.4.10 > 1.4.9", Version.of("1.4.10").isNewerThan(Version.of("1.4.9")));
        check("2.0.0 > 2.0.0-RC1", Version.of("2.0.0").isNewerThan(Version.of("2.0.0-RC1")));
        check("v1.4 == 1.4.0", Version.of("v1.4").equals(Version.of("1.4.0")));
        check("1.0.0-RC2 > 1.0.0-RC1", Version.of("1.0.0-RC2").isNewerThan(Version.of("1.0.0-RC1")));
        check("window spans midnight", cfg.inInstallWindow(LocalTime.of(23, 0)) && cfg.inInstallWindow(LocalTime.of(5, 59))
                && !cfg.inInstallWindow(LocalTime.of(12, 0)));

        // Facility starts on old versions
        appModules.put("hiv", "1.4.0");
        appModules.put("laboratory", "2.1.0");
        appModules.put("prep", "1.0.0");          // not in the release list: must be left alone

        // ------------------------------------------------------------- 1. daytime: download only
        jar("hiv", "1.4.2", 300_000);
        jar("laboratory", "2.2.0", 50_000);
        jar("pharmacy", "1.0.0", 1000);
        publish(priv, mod("hiv", "1.4.2", false), mod("laboratory", "2.2.0", false), mod("pharmacy", "1.0.0", false));
        cutNextDownloadAt = 120_000;               // connection drops mid-way through the first JAR
        agent.setClock(LocalTime.of(10, 0));
        State s = agent.runOnce(false);
        check("daytime: waits for window", "downloaded-waiting-for-window".equals(s.lastResult));
        check("daytime: nothing uploaded", uploads.isEmpty());
        check("resume used HTTP Range after drop", !rangeRequests.isEmpty());
        check("JARs cached and verified", Files.isRegularFile(agentDir.resolve("cache/hiv-1.4.2.jar"))
                && Files.isRegularFile(agentDir.resolve("cache/laboratory-2.2.0.jar")));
        check("module not installed at facility is skipped", !Files.exists(agentDir.resolve("cache/pharmacy-1.0.0.jar")));
        check("pending lists both", s.pending.size() == 2);

        // ------------------------------------------------------------- 2. night: install + verify
        agent.setClock(LocalTime.of(22, 0));
        s = agent.runOnce(false);
        check("night: updated", "updated".equals(s.lastResult));
        check("hiv now 1.4.2", "1.4.2".equals(appModules.get("hiv")));
        check("laboratory now 2.2.0", "2.2.0".equals(appModules.get("laboratory")));
        check("prep untouched", "1.0.0".equals(appModules.get("prep")));
        check("nothing pending", s.pending.isEmpty());
        uploads.clear();
        s = agent.runOnce(false);
        check("second run: up-to-date, no uploads", "up-to-date".equals(s.lastResult) && uploads.isEmpty());

        // ------------------------------------------------------------- 3. tampered manifest rejected
        String good = new String(Files.readAllBytes(releaseDir.resolve("manifest.json")), StandardCharsets.UTF_8);
        jar("hiv", "9.9.9", 1000);
        Files.write(releaseDir.resolve("manifest.json"), good.replace("\"1.4.2\"", "\"9.9.9\"").getBytes(StandardCharsets.UTF_8));
        s = agent.runOnce(false);
        check("tampered manifest ignored", "up-to-date".equals(s.lastResult) && uploads.isEmpty());

        // ------------------------------------------------------------- 4. broken release -> rollback
        jar("hiv", "1.5.0", 2000);
        publish(priv, mod("hiv", "1.5.0", false), mod("laboratory", "2.2.0", false));
        brokenReleases.add("hiv@1.5.0");
        s = agent.runOnce(false);
        check("broken release reported", "partially-updated".equals(s.lastResult));
        check("rolled back to 1.4.2", "1.4.2".equals(appModules.get("hiv")));
        check("rollback event recorded", hasEvent(s, "rolled-back"));
        agent.runOnce(false);
        agent.runOnce(false);
        uploads.clear();
        s = agent.runOnce(false);
        check("gives up after 3 failed attempts", uploads.isEmpty() && s.failedAttempts.get("hiv@1.5.0") == 3L);

        // ------------------------------------------------------------- 5. urgent fix installs in daytime
        brokenReleases.clear();
        jar("hiv", "1.5.1", 2000);
        publish(priv, mod("hiv", "1.5.1", true), mod("laboratory", "2.2.0", false));
        agent.setClock(LocalTime.of(11, 0));
        s = agent.runOnce(false);
        check("urgent release installed outside window", "1.5.1".equals(appModules.get("hiv")));

        // ------------------------------------------------------------- 6. offline USB bundle
        releaseServerUp = false;
        Path usb = Files.createDirectories(tmp.resolve("usb/module-updates"));
        Path lab = jarAt(usb, "laboratory", "2.3.0", 4000);
        Map<String, Object> m = manifest(mod("hiv", "1.5.1", false), modAt("laboratory", "2.3.0", lab));
        writeSigned(priv, usb, m);
        Properties p2 = new Properties();
        p2.putAll(p);
        p2.setProperty("offline.scan-dirs", tmp.resolve("usb").toString());
        UpdateAgent agent2 = new UpdateAgent(new Config(p2, agentDir));
        agent2.setClock(LocalTime.of(21, 0));
        s = agent2.runOnce(false);
        check("USB bundle installed while server offline", "2.3.0".equals(appModules.get("laboratory")));

        // ------------------------------------------------------------- 7. downgrade attempt via old manifest
        releaseServerUp = true;
        Files.write(releaseDir.resolve("manifest.json"), good.getBytes(StandardCharsets.UTF_8));
        // restore the original (valid but older-sequence) signature
        publishRaw(priv, good);
        uploads.clear();
        s = agent.runOnce(false);
        check("older-sequence manifest rejected (no downgrade)", uploads.isEmpty() && "2.3.0".equals(appModules.get("laboratory")));

        // ------------------------------------------------------------- 8. app down: no install, no crash
        appUp = false;
        jar("hiv", "1.6.0", 2000);
        publish(priv, mod("hiv", "1.6.0", false));
        uploads.clear();
        s = agent.runOnce(false);
        check("app down: downloads but does not install", "app-unavailable".equals(s.lastResult) && uploads.isEmpty()
                && Files.isRegularFile(agentDir.resolve("cache/hiv-1.6.0.jar")));
        appUp = true;

        srv.stop(0);
    }

    // ===================================================================== fake servers

    static void release(HttpExchange ex) throws IOException {
        if (!releaseServerUp) { ex.close(); return; }
        String name = ex.getRequestURI().getPath().substring("/release/".length());
        Path f = releaseDir.resolve(name);
        if (!Files.isRegularFile(f)) { respond(ex, 404, "not found"); return; }
        byte[] all = Files.readAllBytes(f);
        String range = ex.getRequestHeaders().getFirst("Range");
        int from = 0;
        if (range != null) {
            synchronized (rangeRequests) { rangeRequests.add(name + " " + range); }
            from = Integer.parseInt(range.replace("bytes=", "").replace("-", ""));
            ex.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + (all.length - 1) + "/" + all.length);
        }
        int len = all.length - from;
        ex.sendResponseHeaders(range != null ? 206 : 200, len);
        OutputStream out = ex.getResponseBody();
        if (cutNextDownloadAt > 0 && name.endsWith(".jar") && len > cutNextDownloadAt) {
            out.write(all, from, cutNextDownloadAt);
            out.flush();
            cutNextDownloadAt = -1;
            ex.getHttpContext().getServer();
            ex.close(); // abrupt close -> client sees a short read
            return;
        }
        out.write(all, from, len);
        out.close();
    }

    static void list(HttpExchange ex) throws IOException {
        if (!"Bearer tok123".equals(ex.getRequestHeaders().getFirst("Authorization"))) { respond(ex, 401, "{}"); return; }
        List<Object> l = new ArrayList<Object>();
        int id = 1;
        for (Map.Entry<String, String> e : appModules.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", id++);
            m.put("name", e.getKey());
            m.put("version", e.getValue());
            l.add(m);
        }
        respond(ex, 200, Json.write(l));
    }

    static void upload(HttpExchange ex) throws IOException {
        if (!"Bearer tok123".equals(ex.getRequestHeaders().getFirst("Authorization"))) { respond(ex, 401, "{}"); return; }
        String body = new String(Http.readAll(ex.getRequestBody()), StandardCharsets.ISO_8859_1);
        Matcher mm = Pattern.compile("filename=\"(.+)-(\\d[^\"]*)\\.jar\"").matcher(body);
        if (!mm.find()) { respond(ex, 400, "{\"error\":\"no file\"}"); return; }
        String name = mm.group(1), ver = mm.group(2);
        uploads.add(name + "@" + ver);
        if (!brokenReleases.contains(name + "@" + ver)) appModules.put(name, ver);
        respond(ex, 200, "{\"id\": 7, \"name\": \"" + name + "\"}");
    }

    static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    // ===================================================================== release helpers

    static void jar(String name, String ver, int size) throws IOException { jarAt(releaseDir, name, ver, size); }

    static Path jarAt(Path dir, String name, String ver, int size) throws IOException {
        byte[] b = new byte[size];
        new java.util.Random((name + ver).hashCode()).nextBytes(b);
        Path f = dir.resolve(name + "-" + ver + ".jar");
        Files.write(f, b);
        return f;
    }

    static Map<String, Object> mod(String name, String ver, boolean urgent) throws IOException {
        Map<String, Object> m = modAt(name, ver, releaseDir.resolve(name + "-" + ver + ".jar"));
        m.put("urgent", urgent);
        return m;
    }

    static Map<String, Object> modAt(String name, String ver, Path jar) throws IOException {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("name", name);
        m.put("version", ver);
        m.put("url", jar.getFileName().toString());
        m.put("sha256", Signatures.sha256(jar));
        m.put("size", Files.size(jar));
        return m;
    }

    static long seq = 1000;

    @SafeVarargs
    static Map<String, Object> manifest(Map<String, Object>... mods) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("sequence", ++seq);
        m.put("channel", "stable");
        List<Object> l = new ArrayList<Object>();
        for (Map<String, Object> x : mods) l.add(x);
        m.put("modules", l);
        return m;
    }

    @SafeVarargs
    static void publish(Path priv, Map<String, Object>... mods) throws Exception {
        writeSigned(priv, releaseDir, manifest(mods));
    }

    static void writeSigned(Path priv, Path dir, Map<String, Object> m) throws Exception {
        byte[] b = Json.write(m).getBytes(StandardCharsets.UTF_8);
        Files.write(dir.resolve("manifest.json"), b);
        Files.write(dir.resolve("manifest.json.sig"), Signatures.sign(priv, b));
    }

    static void publishRaw(Path priv, String json) throws Exception {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        Files.write(releaseDir.resolve("manifest.json"), b);
        Files.write(releaseDir.resolve("manifest.json.sig"), Signatures.sign(priv, b));
    }

    static boolean hasEvent(State s, String type) {
        for (Map<String, Object> e : s.history) if (type.equals(e.get("event"))) return true;
        return false;
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (ok) passed++; else failed++;
    }
}
