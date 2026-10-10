package org.facility.updater;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Small HttpURLConnection wrapper (Java 8 compatible). Honours the JVM's proxy settings. */
public final class Http {

    public static final class Response {
        public final int status;
        public final byte[] body;
        Response(int status, byte[] body) { this.status = status; this.body = body; }
        public String text() { return new String(body, StandardCharsets.UTF_8); }
        public boolean ok() { return status >= 200 && status < 300; }
    }

    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public Http(int connectTimeoutMs, int readTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    public HttpURLConnection open(String url, String method, Map<String, String> headers) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(connectTimeoutMs);
        c.setReadTimeout(readTimeoutMs);
        c.setRequestMethod(method);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "facility-module-updater/" + Main.VERSION);
        if (headers != null) {
            for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        }
        return c;
    }

    public Response get(String url, Map<String, String> headers) throws IOException {
        HttpURLConnection c = open(url, "GET", headers);
        return read(c);
    }

    public Response send(String url, String method, Map<String, String> headers, String contentType, byte[] body)
            throws IOException {
        HttpURLConnection c = open(url, method, headers);
        if (body != null) {
            c.setDoOutput(true);
            if (contentType != null) c.setRequestProperty("Content-Type", contentType);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = c.getOutputStream()) { out.write(body); }
        }
        return read(c);
    }

    /** Streams a file as multipart/form-data without loading it into memory. */
    public Response uploadMultipart(String url, Map<String, String> headers, String fieldName, Path file)
            throws IOException {
        String boundary = "----updater" + Long.toHexString(System.nanoTime());
        HttpURLConnection c = open(url, "POST", headers);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        byte[] head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\""
                + file.getFileName() + "\"\r\n"
                + "Content-Type: application/java-archive\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        long total = head.length + Files.size(file) + tail.length;
        c.setFixedLengthStreamingMode(total);
        try (OutputStream out = c.getOutputStream()) {
            out.write(head);
            Files.copy(file, out);
            out.write(tail);
        }
        return read(c);
    }

    public static Response read(HttpURLConnection c) throws IOException {
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        byte[] body = in == null ? new byte[0] : readAll(in);
        c.disconnect();
        return new Response(status, body);
    }

    public static byte[] readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
