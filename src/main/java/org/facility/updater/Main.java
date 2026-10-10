package org.facility.updater;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Facility module update agent.
 *
 * <pre>
 * Facility side:
 *   java -jar module-updater.jar once     one update cycle (use from Windows Task Scheduler)
 *   java -jar module-updater.jar run      keep running, check every check.interval-minutes
 *   java -jar module-updater.jar check    show what would be updated, change nothing
 *   java -jar module-updater.jar status   print the last saved status
 *
 * Release team side:
 *   java -jar module-updater.jar keygen release-private.pem release-public.pem
 *   java -jar module-updater.jar sign release-private.pem manifest.json [jars-folder]
 *
 * Option: --config path/to/updater.properties (default: next to the JAR, then current folder)
 * </pre>
 */
public final class Main {

    public static final String VERSION = "1.0.0";
    private static final Logger LOG = Logger.getLogger("org.facility.updater");

    public static void main(String[] argv) throws Exception {
        String cmd = "once";
        String configArg = null;
        java.util.List<String> rest = new java.util.ArrayList<String>();
        for (int i = 0; i < argv.length; i++) {
            if ("--config".equals(argv[i]) && i + 1 < argv.length) configArg = argv[++i];
            else rest.add(argv[i]);
        }
        if (!rest.isEmpty()) cmd = rest.remove(0);

        switch (cmd) {
            case "keygen":
                if (rest.size() != 2) usage();
                Signatures.generateKeyPair(Paths.get(rest.get(0)), Paths.get(rest.get(1)));
                System.out.println("Wrote " + rest.get(0) + " (KEEP SECRET) and " + rest.get(1) + " (ship to facilities)");
                return;
            case "sign":
                if (rest.size() < 2) usage();
                sign(Paths.get(rest.get(0)), Paths.get(rest.get(1)), rest.size() > 2 ? Paths.get(rest.get(2)) : null);
                return;
            case "version":
                System.out.println(VERSION);
                return;
            case "once": case "run": case "check": case "status":
                break;
            default:
                usage();
        }

        Config cfg = new Config(findConfig(configArg));
        if ("status".equals(cmd)) {
            Path f = cfg.stateDir().resolve("state.json");
            System.out.println(Files.isRegularFile(f) ? new String(Files.readAllBytes(f), StandardCharsets.UTF_8) : "No status yet");
            return;
        }
        setupLogging(cfg);
        LOG.info("Module update agent " + VERSION + " starting (" + cmd + ")");

        Files.createDirectories(cfg.stateDir());
        try (FileChannel ch = FileChannel.open(cfg.stateDir().resolve("updater.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = ch.tryLock()) {
            if (lock == null) {
                LOG.info("Another update run is in progress; exiting");
                return;
            }
            UpdateAgent agent = new UpdateAgent(cfg);
            if ("check".equals(cmd)) {
                State s = agent.runOnce(true);
                System.out.println("Result:    " + s.lastResult);
                System.out.println("Installed: " + s.installed);
                System.out.println("Updates:   " + (s.pending.isEmpty() ? "none" : s.pending.toString()));
                return;
            }
            if ("once".equals(cmd)) {
                State s = agent.runOnce(false);
                System.exit("error".equals(s.lastResult) || "install-failed".equals(s.lastResult) ? 1 : 0);
            }
            // run: loop forever with jitter so many facilities don't hit the server at the same moment
            long intervalMs = cfg.getInt("check.interval-minutes", 60) * 60_000L;
            Random rnd = new Random();
            while (true) {
                agent.runOnce(false);
                long jitter = (long) (intervalMs * 0.2 * rnd.nextDouble());
                Thread.sleep(intervalMs + jitter);
            }
        }
    }

    // ------------------------------------------------------------------ release-team signing

    @SuppressWarnings("unchecked")
    static void sign(Path privateKey, Path manifestFile, Path jarsDir) throws Exception {
        Map<String, Object> root = Json.parseObject(new String(Files.readAllBytes(manifestFile), StandardCharsets.UTF_8));
        // Sequence is a UTC timestamp so it always increases without anyone having to remember the last value.
        String ts = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        long seq = Math.max(Long.parseLong(ts), Json.lng(root.get("sequence"), 0) + 1);
        root.put("sequence", seq);
        root.put("published_at", ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT));

        if (jarsDir != null) {
            for (Object o : (List<Object>) root.get("modules")) {
                Map<String, Object> m = (Map<String, Object>) o;
                String url = Json.str(m.get("url"));
                String fileName = url.substring(url.lastIndexOf('/') + 1);
                Path jar = jarsDir.resolve(fileName);
                if (!Files.isRegularFile(jar)) throw new IllegalArgumentException("JAR not found for " + m.get("name") + ": " + jar);
                m.put("sha256", Signatures.sha256(jar));
                m.put("size", Files.size(jar));
            }
        }
        byte[] bytes = Json.write(root).getBytes(StandardCharsets.UTF_8);
        ReleaseManifest parsed = ReleaseManifest.parse(new String(bytes, StandardCharsets.UTF_8)); // validate
        Files.write(manifestFile, bytes);
        Path sig = manifestFile.resolveSibling(manifestFile.getFileName() + ".sig");
        Files.write(sig, Signatures.sign(privateKey, bytes));
        System.out.println("Signed " + manifestFile + " -> " + sig.getFileName() + " (sequence " + seq + ", "
                + parsed.modules.size() + " modules)");
        System.out.println("Publish BOTH files (and the JARs) unchanged; any edit invalidates the signature.");
    }

    // ------------------------------------------------------------------ plumbing

    private static Path findConfig(String arg) throws IOException {
        if (arg != null) return Paths.get(arg);
        try {
            File jar = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path p = jar.getParentFile().toPath().resolve("updater.properties");
            if (Files.isRegularFile(p)) return p;
        } catch (Exception ignored) { }
        Path p = Paths.get("updater.properties");
        if (Files.isRegularFile(p)) return p;
        throw new IOException("updater.properties not found (use --config)");
    }

    private static void setupLogging(Config cfg) throws IOException {
        Logger root = Logger.getLogger("");
        for (Handler h : root.getHandlers()) root.removeHandler(h);
        Formatter f = new Formatter() {
            private final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            @Override
            public String format(LogRecord r) {
                String line = ZonedDateTime.now().format(fmt) + " " + r.getLevel() + " " + formatMessage(r)
                        + System.lineSeparator();
                if (r.getThrown() != null) {
                    StringWriter sw = new StringWriter();
                    r.getThrown().printStackTrace(new PrintWriter(sw));
                    line += sw;
                }
                return line;
            }
        };
        Files.createDirectories(cfg.logDir());
        FileHandler fh = new FileHandler(cfg.logDir().resolve("updater.%g.log").toString(), 5 * 1024 * 1024, 5, true);
        fh.setFormatter(f);
        fh.setEncoding("UTF-8");
        root.addHandler(fh);
        ConsoleHandler ch = new ConsoleHandler();
        ch.setFormatter(f);
        root.addHandler(ch);
        root.setLevel(Level.INFO);
    }

    private static void usage() {
        System.err.println("Usage: java -jar module-updater.jar [once|run|check|status] [--config updater.properties]");
        System.err.println("       java -jar module-updater.jar keygen <private.pem> <public.pem>");
        System.err.println("       java -jar module-updater.jar sign <private.pem> <manifest.json> [jars-folder]");
        System.exit(2);
    }
}
