package org.facility.updater;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Optional heartbeat: POSTs this facility's module versions and last result to a central URL so the
 * support team can see which sites are behind. Failure to report never affects updating.
 */
public final class Reporter {

    private static final Logger LOG = Logger.getLogger(Reporter.class.getName());
    private final Config cfg;
    private final Http http;

    public Reporter(Config cfg, Http http) {
        this.cfg = cfg;
        this.http = http;
    }

    public void report(State s) {
        String url = cfg.get("report.url");
        if (url == null) return;
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("facility_id", cfg.get("facility.id", "unknown"));
        body.put("facility_name", cfg.get("facility.name", ""));
        body.put("hostname", hostname());
        body.putAll(s.toMap());
        body.put("history", s.history.subList(0, Math.min(10, s.history.size())));
        String token = cfg.get("report.token");
        try {
            Http.Response r = http.send(url, "POST",
                    token == null ? null : Collections.singletonMap("Authorization", "Bearer " + token),
                    "application/json", Json.write(body).getBytes(StandardCharsets.UTF_8));
            if (!r.ok()) LOG.info("Status report rejected: HTTP " + r.status);
        } catch (Exception e) {
            LOG.info("Status report not sent (offline?): " + e.getMessage());
        }
    }

    private static String hostname() {
        try { return InetAddress.getLocalHost().getHostName(); } catch (Exception e) { return "unknown"; }
    }
}
