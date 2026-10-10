package org.facility.updater;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persistent agent state (state/state.json). Also serves as the human-readable status report. */
public final class State {

    public long lastSequence = -1;
    public String lastRun;
    public String lastResult = "never-run";
    public String lastManifestCheckOk;
    public String lastError;
    /** Module name -> version last seen installed in the application. */
    public Map<String, String> installed = new LinkedHashMap<String, String>();
    /** Module name -> version that is available but not yet installed. */
    public Map<String, String> pending = new LinkedHashMap<String, String>();
    /** "name@version" -> number of failed install attempts. */
    public Map<String, Long> failedAttempts = new LinkedHashMap<String, Long>();
    public List<Map<String, Object>> history = new ArrayList<Map<String, Object>>();

    public static String now() {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    public void event(String type, String module, String version, String detail) {
        Map<String, Object> e = new LinkedHashMap<String, Object>();
        e.put("at", now());
        e.put("event", type);
        if (module != null) e.put("module", module);
        if (version != null) e.put("version", version);
        if (detail != null) e.put("detail", detail);
        history.add(0, e);
        while (history.size() > 100) history.remove(history.size() - 1);
    }

    @SuppressWarnings("unchecked")
    public static State load(Path file) {
        State s = new State();
        if (!Files.isRegularFile(file)) return s;
        try {
            Map<String, Object> m = Json.parseObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            s.lastSequence = Json.lng(m.get("last_sequence"), -1);
            s.lastRun = Json.str(m.get("last_run"));
            s.lastResult = Json.str(m.get("last_result"));
            s.lastManifestCheckOk = Json.str(m.get("last_manifest_check_ok"));
            s.lastError = Json.str(m.get("last_error"));
            if (m.get("installed") instanceof Map) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("installed")).entrySet()) {
                    s.installed.put(e.getKey(), Json.str(e.getValue()));
                }
            }
            if (m.get("pending") instanceof Map) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("pending")).entrySet()) {
                    s.pending.put(e.getKey(), Json.str(e.getValue()));
                }
            }
            if (m.get("failed_attempts") instanceof Map) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("failed_attempts")).entrySet()) {
                    s.failedAttempts.put(e.getKey(), Json.lng(e.getValue(), 0));
                }
            }
            if (m.get("history") instanceof List) {
                for (Object o : (List<Object>) m.get("history")) {
                    if (o instanceof Map) s.history.add((Map<String, Object>) o);
                }
            }
        } catch (Exception e) {
            // A corrupt state file must never stop updates; start fresh (sequence protection resets to cache).
        }
        return s;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("agent_version", Main.VERSION);
        m.put("last_run", lastRun);
        m.put("last_result", lastResult);
        m.put("last_error", lastError);
        m.put("last_sequence", lastSequence);
        m.put("last_manifest_check_ok", lastManifestCheckOk);
        m.put("installed", installed);
        m.put("pending", pending);
        m.put("failed_attempts", failedAttempts);
        m.put("history", history);
        return m;
    }

    public void save(Path file) throws java.io.IOException {
        Files.createDirectories(file.getParent());
        ManifestSource.writeAtomic(file, Json.write(toMap()).getBytes(StandardCharsets.UTF_8));
    }
}
