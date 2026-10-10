package org.facility.updater;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Typed access to updater.properties. Relative paths resolve against the config file's folder. */
public final class Config {

    private final Properties p = new Properties();
    private final Path baseDir;

    public Config(Path file) throws IOException {
        this.baseDir = file.toAbsolutePath().getParent();
        try (InputStream in = Files.newInputStream(file);
             Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            p.load(r);
        }
    }

    /** For tests. */
    public Config(Properties props, Path baseDir) {
        this.p.putAll(props);
        this.baseDir = baseDir;
    }

    public String get(String key, String def) {
        String env = System.getenv("UPDATER_" + key.toUpperCase().replace('.', '_').replace('-', '_'));
        if (env != null && !env.isEmpty()) return env;
        String v = p.getProperty(key);
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }

    public String get(String key) { return get(key, null); }

    public String require(String key) {
        String v = get(key);
        if (v == null) throw new IllegalStateException("Missing required setting: " + key);
        return v;
    }

    public int getInt(String key, int def) {
        String v = get(key);
        return v == null ? def : Integer.parseInt(v);
    }

    public boolean getBool(String key, boolean def) {
        String v = get(key);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    public Path path(String key, String def) {
        String v = get(key, def);
        if (v == null) return null;
        Path path = Paths.get(v);
        return path.isAbsolute() ? path : baseDir.resolve(path).normalize();
    }

    public List<String> list(String key) {
        List<String> out = new ArrayList<String>();
        String v = get(key);
        if (v == null) return out;
        for (String s : v.split("[,;]")) {
            if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    public Path baseDir() { return baseDir; }

    // ---------------------------------------------------------------- derived

    public Path stateDir() { return path("updater.state-dir", "state"); }

    public Path cacheDir() { return path("updater.cache-dir", "cache"); }

    public Path logDir() { return path("updater.log-dir", "logs"); }

    /**
     * Maintenance window "HH:mm-HH:mm" in local time; may span midnight ("20:00-06:00").
     * "always" installs whenever an update is ready.
     */
    public boolean inInstallWindow(LocalTime now) {
        String w = get("install.window", "20:00-06:00");
        if ("always".equalsIgnoreCase(w)) return true;
        String[] parts = w.split("-");
        LocalTime start = LocalTime.parse(parts[0].trim());
        LocalTime end = LocalTime.parse(parts[1].trim());
        if (start.equals(end)) return true;
        if (start.isBefore(end)) return !now.isBefore(start) && now.isBefore(end);
        return !now.isBefore(start) || now.isBefore(end);
    }

    /** Folders scanned for an offline (USB) update bundle, plus every drive root if enabled. */
    public List<File> offlineCandidates() {
        List<File> dirs = new ArrayList<File>();
        String folderName = get("offline.bundle-folder", "module-updates");
        for (String d : list("offline.scan-dirs")) {
            dirs.add(new File(d));
            dirs.add(new File(d, folderName));
        }
        if (getBool("offline.scan-drive-roots", true)) {
            File[] roots = File.listRoots();
            if (roots != null) {
                for (File r : roots) dirs.add(new File(r, folderName));
            }
        }
        return dirs;
    }
}
