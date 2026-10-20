import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

public final class JfrSummary {

    record Rule(String component, String prefix) {
    }

    static final String JWT_SIGNATURE = "JWT signature check (JDK RSA, SHA-256 under the JWT decoder)";
    static final String TLS = "TLS (encrypted connections, e.g. to MySQL)";
    static final String JDK_SECURITY = "JDK security and crypto, other";
    static final String JWT_VALIDATION = "JWT parsing and validation (Nimbus, Spring OAuth2)";
    static final String HIBERNATE = "Hibernate, JPA";
    static final String SPRING_DATA = "Spring Data (repositories)";
    static final String REDIS_CLIENT = "Redis client (Lettuce, Netty, Spring Data Redis)";
    static final String JDBC = "JDBC (MySQL driver, Hikari)";
    static final String JSON = "JSON (Jackson)";
    static final String LOGGING = "Logging";
    static final String SPRING_MVC = "Spring MVC, HTTP";
    static final String TOMCAT = "Tomcat";

    static final List<Rule> COMPONENTS = List.of(
            new Rule(JDK_SECURITY, "sun.security."),
            new Rule(JWT_VALIDATION, "com.nimbusds."),
            new Rule(JWT_VALIDATION, "org.springframework.security.oauth2."),
            new Rule("Spring Security (filter chain, context)", "org.springframework.security."),
            new Rule("Bean validation", "org.hibernate.validator."),
            new Rule(HIBERNATE, "org.hibernate."),
            new Rule(HIBERNATE, "org.springframework.orm."),
            new Rule(REDIS_CLIENT, "org.springframework.data.redis."),
            new Rule(REDIS_CLIENT, "io.lettuce."),
            new Rule(REDIS_CLIENT, "io.netty."),
            new Rule(SPRING_DATA, "org.springframework.data."),
            new Rule(JDBC, "com.mysql."),
            new Rule(JDBC, "com.zaxxer.hikari."),
            new Rule(JSON, "tools.jackson."),
            new Rule(JSON, "com.fasterxml.jackson."),
            new Rule("Micrometer (metrics, observations)", "io.micrometer."),
            new Rule(LOGGING, "ch.qos.logback."),
            new Rule(LOGGING, "org.slf4j."),
            new Rule("Spring transactions", "org.springframework.transaction."),
            new Rule(SPRING_MVC, "org.springframework.web."),
            new Rule(SPRING_MVC, "org.springframework.http."),
            new Rule(TOMCAT, "org.apache.catalina."),
            new Rule(TOMCAT, "org.apache.coyote."),
            new Rule(TOMCAT, "org.apache.tomcat."),
            new Rule("Application code (io.github.eytoptas.teamchest)", "io.github.eytoptas.teamchest."),
            new Rule("Spring core (AOP, beans, context)", "org.springframework."));
    static final String OTHER_LIBRARIES = "Other libraries";
    static final String JDK_ONLY = "JDK only (no library frame on the stack)";

    record Feature(String label, Predicate<RecordedFrame> matches) {
    }

    static Predicate<RecordedFrame> type(String prefix) {
        return frame -> className(frame).startsWith(prefix);
    }

    static Predicate<RecordedFrame> method(String type, String method) {
        return frame -> className(frame).equals(type) && frame.getMethod().getName().equals(method);
    }

    static final String APP = "io.github.eytoptas.teamchest.";

    static final List<Feature> FEATURES = List.of(
            new Feature("Security filter chain (JWT check included)",
                    type("org.springframework.security.web.FilterChainProxy")),
            new Feature("JWT decode (signature check included)",
                    method("org.springframework.security.oauth2.jwt.NimbusJwtDecoder", "decode")),
            new Feature("RSA (JWT signatures; also TLS handshakes)", type("sun.security.rsa.")),
            new Feature("TLS (connection encryption)", type("sun.security.ssl.")),
            new Feature("Rate limit interceptor (Redis call included)", type(APP + "api.RateLimitInterceptor")),
            new Feature("Rate limiter Redis script", type(APP + "infrastructure.redis.RedisTokenBucketRateLimiter")),
            new Feature("Controller (services included)", type(APP + "api.TeamChestController")),
            new Feature("Current event lookup (database query)",
                    method(APP + "application.EventCatalog", "requireCurrentEvent")),
            new Feature("Event lookup by id", method(APP + "application.EventCatalog", "requireEvent")),
            new Feature("/get service", type(APP + "application.TeamQueryService")),
            new Feature("/progress service", type(APP + "application.ProgressService")),
            new Feature("/join service", type(APP + "application.JoinService")),
            new Feature("/claim service", type(APP + "application.ClaimService")),
            new Feature("Idempotency (stored outcomes)", type(APP + "application.IdempotentExecutor")),
            new Feature("Transaction retry runner", type(APP + "application.RetryingTransactionRunner")),
            new Feature("Team cache", type(APP + "infrastructure.redis.RedisTeamStandingCache")),
            new Feature("Membership cache", type(APP + "infrastructure.redis.RedisMembershipCache")),
            new Feature("Cache invalidation after commit", type(APP + "application.TeamCacheInvalidation")),
            new Feature(HIBERNATE, frame -> className(frame).startsWith("org.hibernate.")
                    && !className(frame).startsWith("org.hibernate.validator.")),
            new Feature("JDBC driver (MySQL)", type("com.mysql.")),
            new Feature("Hikari pool", type("com.zaxxer.hikari.")),
            new Feature("Redis client (Lettuce)", type("io.lettuce.")),
            new Feature(JSON, frame -> className(frame).startsWith("tools.jackson.")
                    || className(frame).startsWith("com.fasterxml.jackson.")),
            new Feature("HTTP message converters (request and response bodies)",
                    type("org.springframework.http.converter.")),
            new Feature("Micrometer", type("io.micrometer.")),
            new Feature(LOGGING, type("ch.qos.logback.")),
            new Feature("Spring transactions", type("org.springframework.transaction.")),
            new Feature("Bean validation", type("org.hibernate.validator.")));

    static String className(RecordedFrame frame) {
        RecordedClass type = frame.getMethod() == null ? null : frame.getMethod().getType();
        return type == null ? "" : type.getName();
    }

    static boolean isGeneric(String className) {
        if (className.startsWith("sun.security.")) {
            return false;
        }
        return className.isEmpty() || className.startsWith("java.") || className.startsWith("javax.")
                || className.startsWith("jdk.") || className.startsWith("sun.") || className.startsWith("com.sun.")
                || className.startsWith("jakarta.");
    }

    static String componentOf(RecordedStackTrace stack, RecordedFrame own) {
        if (own == null) {
            return JDK_ONLY;
        }
        String name = className(own);
        if (!name.startsWith("sun.security.")) {
            return componentOf(name);
        }
        if (hasFrame(stack, "com.nimbusds.") || hasFrame(stack, "org.springframework.security.oauth2.")) {
            return JWT_SIGNATURE;
        }
        if (name.startsWith("sun.security.ssl.") || hasFrame(stack, "sun.security.ssl.") || hasFrame(stack, "javax.net.ssl.")) {
            return TLS;
        }
        return JDK_SECURITY;
    }

    static boolean hasFrame(RecordedStackTrace stack, String prefix) {
        if (stack != null) {
            for (RecordedFrame frame : stack.getFrames()) {
                if (className(frame).startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    static String componentOf(String className) {
        for (Rule rule : COMPONENTS) {
            if (className.startsWith(rule.prefix())) {
                return rule.component();
            }
        }
        return OTHER_LIBRARIES;
    }

    static RecordedFrame firstOwnFrame(RecordedStackTrace stack) {
        if (stack == null) {
            return null;
        }
        for (RecordedFrame frame : stack.getFrames()) {
            if (frame.isJavaFrame() && !isGeneric(className(frame))) {
                return frame;
            }
        }
        return null;
    }

    static String site(RecordedStackTrace stack) {
        RecordedFrame frame = firstOwnFrame(stack);
        if (frame == null) {
            return "(JDK only)";
        }
        String name = className(frame);
        String simple = name.substring(name.lastIndexOf('.') + 1);
        return simple + "." + frame.getMethod().getName() + "  [" + componentOf(stack, frame) + "]";
    }

    static boolean containsFrame(RecordedStackTrace stack, String className) {
        if (stack != null) {
            for (RecordedFrame frame : stack.getFrames()) {
                if (className(frame).equals(className)) {
                    return true;
                }
            }
        }
        return false;
    }

    static final String WORKERS = "Tomcat 8080 workers (API requests)";
    static final String JIT = "JIT compiler";

    static String threadGroup(String name) {
        if (name == null) {
            return "unknown";
        }
        if (name.startsWith("http-nio-8080-exec-")) {
            return WORKERS;
        }
        if (name.startsWith("http-nio-8080-")) {
            return "Tomcat 8080 acceptor and poller";
        }
        if (name.startsWith("http-nio-8081-")) {
            return "Tomcat 8081 (actuator, sampled by run.ps1)";
        }
        if (name.startsWith("lettuce-")) {
            return "Redis I/O (Lettuce event loop)";
        }
        if (name.contains("CompilerThread")) {
            return JIT;
        }
        if (name.startsWith("HikariPool")) {
            return "Hikari housekeeping";
        }
        if (name.contains("JFR")) {
            return "JFR";
        }
        return "other Java threads";
    }

    static String threadName(RecordedThread thread) {
        if (thread == null) {
            return null;
        }
        return thread.getJavaName() != null ? thread.getJavaName() : thread.getOSName();
    }

    record Wait(long count, Duration total, Duration max) {
        static Wait of(Duration d) {
            return new Wait(1, d, d);
        }

        Wait plus(Wait other) {
            return new Wait(count + other.count, total.plus(other.total),
                    other.max.compareTo(max) > 0 ? other.max : max);
        }
    }

    record Cumulative(Instant time, long value) {
    }

    final Instant from;
    final Instant to;
    final Integer cpusOption;
    final Double rps;

    Instant recordingStart;
    Instant recordingEnd;
    Integer jvmCpuCount;
    Long containerQuota;
    Long containerPeriod;

    long samples;
    long truncatedStacks;
    final Map<String, Map<String, Long>> exclusive = new HashMap<>();
    final Map<String, Map<String, Long>> inclusive = new HashMap<>();
    final Map<String, Long> leafMethods = new HashMap<>();
    final Map<String, String> leafComponent = new HashMap<>();
    final Map<String, Long> samplesByThreadGroup = new HashMap<>();
    final TreeMap<Long, Long> workerSamplesPer10s = new TreeMap<>();

    record LoadReport(long threadId, String group, Instant time, double user, double system) {
    }

    final List<LoadReport> loadReports = new ArrayList<>();
    final Map<Long, Instant> threadStarts = new HashMap<>();
    final List<double[]> jvmLoads = new ArrayList<>();
    final List<Cumulative> containerCpu = new ArrayList<>();
    final List<long[]> throttling = new ArrayList<>();
    Integer hardwareThreads;

    double allocatedBytes;
    final Map<String, Double> allocationByClass = new HashMap<>();
    final Map<String, Double> allocationBySite = new HashMap<>();

    long gcCount;
    Duration gcPauses = Duration.ZERO;
    Duration gcLongest = Duration.ZERO;
    final Map<String, Long> gcByName = new TreeMap<>();
    long compilations;

    final Map<String, Wait> parks = new HashMap<>();
    final Map<String, Wait> monitors = new HashMap<>();
    final Map<String, Wait> socketReads = new HashMap<>();

    JfrSummary(Instant from, Instant to, Integer cpusOption, Double rps) {
        this.from = from;
        this.to = to;
        this.cpusOption = cpusOption;
        this.rps = rps;
    }

    boolean inWindow(Instant time) {
        return (from == null || !time.isBefore(from)) && (to == null || !time.isAfter(to));
    }

    void accept(RecordedEvent event) {
        Instant time = event.getStartTime();
        if (recordingStart == null || time.isBefore(recordingStart)) {
            recordingStart = time;
        }
        if (recordingEnd == null || time.isAfter(recordingEnd)) {
            recordingEnd = time;
        }
        String type = event.getEventType().getName();
        switch (type) {
            case "jdk.ContainerConfiguration" -> {
                jvmCpuCount = (int) event.getLong("effectiveCpuCount");
                containerQuota = event.getLong("cpuQuota");
                containerPeriod = event.getLong("cpuSlicePeriod");
            }
            case "jdk.CPUInformation" -> hardwareThreads = event.getInt("hwThreads");
            case "jdk.ThreadStart" -> {
                RecordedThread started = event.getThread("thread");
                if (started != null) {
                    threadStarts.put(started.getId(), time);
                }
            }
            case "jdk.ThreadCPULoad" -> {
                RecordedThread thread = event.getThread();
                if (thread != null) {
                    loadReports.add(new LoadReport(thread.getId(), threadGroup(threadName(thread)), time,
                            event.getFloat("user"), event.getFloat("system")));
                }
            }
            case "jdk.ExecutionSample" -> {
                if (WORKERS.equals(threadGroup(threadName(event.getThread("sampledThread"))))) {
                    workerSamplesPer10s.merge(Math.floorDiv(time.toEpochMilli(), 10_000L), 1L, Long::sum);
                }
            }
            default -> {
            }
        }
        if (!inWindow(time)) {
            return;
        }
        switch (type) {
            case "jdk.ExecutionSample" -> sample(event);
            case "jdk.CPULoad" -> jvmLoads.add(new double[] {event.getFloat("jvmUser"), event.getFloat("jvmSystem")});
            case "jdk.ContainerCPUUsage" -> containerCpu.add(new Cumulative(time, event.getLong("cpuTime")));
            case "jdk.ContainerCPUThrottling" -> throttling.add(new long[] {event.getLong("cpuElapsedSlices"),
                    event.getLong("cpuThrottledSlices"), event.getLong("cpuThrottledTime")});
            case "jdk.ObjectAllocationSample" -> allocation(event);
            case "jdk.GarbageCollection" -> {
                gcCount++;
                gcPauses = gcPauses.plus(event.getDuration("sumOfPauses"));
                Duration longest = event.getDuration("longestPause");
                if (longest.compareTo(gcLongest) > 0) {
                    gcLongest = longest;
                }
                gcByName.merge(event.getString("name"), 1L, Long::sum);
            }
            case "jdk.Compilation" -> compilations++;
            case "jdk.ThreadPark" -> waitEvent(parks, event, siteOf(event));
            case "jdk.JavaMonitorEnter" -> waitEvent(monitors, event, siteOf(event));
            case "jdk.SocketRead" -> {
                int port = event.getInt("port");
                String peer = port == 3306 ? "MySQL (port 3306): queries, row-lock waits, commit flushes"
                        : port == 6379 ? "Redis (port 6379)" : "port " + port;
                waitEvent(socketReads, event, peer);
            }
            default -> {
            }
        }
    }

    void sample(RecordedEvent event) {
        samples++;
        String group = threadGroup(threadName(event.getThread("sampledThread")));
        samplesByThreadGroup.merge(group, 1L, Long::sum);
        RecordedStackTrace stack = event.getStackTrace();
        if (stack != null && stack.isTruncated()) {
            truncatedStacks++;
        }
        RecordedFrame own = firstOwnFrame(stack);
        String component = componentOf(stack, own);
        exclusive.computeIfAbsent(group, k -> new HashMap<>()).merge(component, 1L, Long::sum);
        if (stack == null || stack.getFrames().isEmpty()) {
            return;
        }
        RecordedFrame leaf = stack.getFrames().get(0);
        String key = className(leaf) + "." + (leaf.getMethod() == null ? "?" : leaf.getMethod().getName());
        leafMethods.merge(key, 1L, Long::sum);
        leafComponent.putIfAbsent(key, component);
        Set<String> found = new HashSet<>();
        for (RecordedFrame frame : stack.getFrames()) {
            if (frame.getMethod() == null) {
                continue;
            }
            for (Feature feature : FEATURES) {
                if (feature.matches().test(frame)) {
                    found.add(feature.label());
                }
            }
        }
        Map<String, Long> groupFeatures = inclusive.computeIfAbsent(group, k -> new HashMap<>());
        found.forEach(label -> groupFeatures.merge(label, 1L, Long::sum));
    }

    void allocation(RecordedEvent event) {
        double weight = event.getLong("weight");
        allocatedBytes += weight;
        RecordedClass objectClass = event.getClass("objectClass");
        allocationByClass.merge(objectClass == null ? "?" : objectClass.getName(), weight, Double::sum);
        allocationBySite.merge(site(event.getStackTrace()), weight, Double::sum);
    }

    String siteOf(RecordedEvent event) {
        RecordedStackTrace stack = event.getStackTrace();
        String site = site(stack);
        if (containsFrame(stack, "com.zaxxer.hikari.pool.HikariPool")) {
            return site + "  (waiting for a free database connection)";
        }
        return site;
    }

    void waitEvent(Map<String, Wait> target, RecordedEvent event, String label) {
        if (!WORKERS.equals(threadGroup(threadName(event.getThread())))) {
            return;
        }
        if (containsFrame(event.getStackTrace(), "org.apache.tomcat.util.threads.TaskQueue")) {
            return;
        }
        target.merge(label, Wait.of(event.getDuration()), Wait::plus);
    }

    record Result(String markdown, String json) {
    }

    double windowSeconds() {
        Instant start = windowStart();
        Instant end = windowEnd();
        if (start == null || end == null || end.isBefore(start)) {
            return 0;
        }
        return Duration.between(start, end).toMillis() / 1000.0;
    }

    Instant windowStart() {
        return from != null && (recordingStart == null || from.isAfter(recordingStart)) ? from : recordingStart;
    }

    Instant windowEnd() {
        return to != null && (recordingEnd == null || to.isBefore(recordingEnd)) ? to : recordingEnd;
    }

    Duration loadPeriod() {
        Map<Long, List<Instant>> byThread = reportTimesByThread();
        List<Long> gaps = new ArrayList<>();
        byThread.values().forEach(times -> {
            for (int i = 1; i < times.size(); i++) {
                gaps.add(Duration.between(times.get(i - 1), times.get(i)).toMillis());
            }
        });
        if (gaps.isEmpty()) {
            return Duration.ofSeconds(10);
        }
        Collections.sort(gaps);
        return Duration.ofMillis(gaps.get(gaps.size() / 2));
    }

    Map<Long, List<Instant>> reportTimesByThread() {
        Map<Long, List<Instant>> byThread = new HashMap<>();
        for (LoadReport report : loadReports) {
            byThread.computeIfAbsent(report.threadId(), k -> new ArrayList<>()).add(report.time());
        }
        byThread.values().forEach(Collections::sort);
        return byThread;
    }

    Map<String, double[]> threadCpuSeconds(int cpus) {
        Instant start = windowStart();
        Instant end = windowEnd();
        Map<String, double[]> seconds = new TreeMap<>();
        if (start == null || end == null) {
            return seconds;
        }
        Duration period = loadPeriod();
        List<LoadReport> reports = new ArrayList<>(loadReports);
        reports.sort(Comparator.comparingLong(LoadReport::threadId).thenComparing(LoadReport::time));
        long thread = Long.MIN_VALUE;
        Instant previous = null;
        for (LoadReport report : reports) {
            if (report.threadId() != thread) {
                thread = report.threadId();
                previous = threadStarts.get(thread);
            }
            Instant begin = report.time().minus(period);
            if (previous != null && previous.isAfter(begin) && previous.isBefore(report.time())) {
                begin = previous;
            }
            Instant overlapStart = begin.isAfter(start) ? begin : start;
            Instant overlapEnd = report.time().isBefore(end) ? report.time() : end;
            double overlap = overlapEnd.isAfter(overlapStart) ? Duration.between(overlapStart, overlapEnd).toNanos() / 1e9 : 0;
            double[] sum = seconds.computeIfAbsent(report.group(), k -> new double[2]);
            sum[0] += report.user() * cpus * overlap;
            sum[1] += report.system() * cpus * overlap;
            previous = report.time();
        }
        return seconds;
    }

    record CpuPicture(Map<String, double[]> groupCores, double javaThreads, double jvm, String jvmSource) {
        double group(String name) {
            double[] cores = groupCores.get(name);
            return cores == null ? 0 : cores[0] + cores[1];
        }
    }

    CpuPicture cpuPicture(Integer cpus, double seconds) {
        Map<String, double[]> cores = new TreeMap<>();
        double javaThreads = Double.NaN;
        if (cpus != null && seconds > 0 && !loadReports.isEmpty()) {
            javaThreads = 0;
            for (Map.Entry<String, double[]> entry : threadCpuSeconds(cpus).entrySet()) {
                double[] value = {entry.getValue()[0] / seconds, entry.getValue()[1] / seconds};
                cores.put(entry.getKey(), value);
                javaThreads += value[0] + value[1];
            }
        }
        double container = containerCores();
        if (!Double.isNaN(container)) {
            return new CpuPicture(cores, javaThreads, container, "container CPU time from the cgroup (jdk.ContainerCPUUsage)");
        }
        if (!jvmLoads.isEmpty() && hardwareThreads != null) {
            double user = jvmLoads.stream().mapToDouble(l -> l[0]).average().orElse(Double.NaN);
            double system = jvmLoads.stream().mapToDouble(l -> l[1]).average().orElse(Double.NaN);
            return new CpuPicture(cores, javaThreads, (user + system) * hardwareThreads,
                    "jdk.CPULoad x " + hardwareThreads + " hardware threads, approximate");
        }
        return new CpuPicture(cores, javaThreads, Double.NaN, "not measured");
    }

    Result report() {
        StringBuilder md = new StringBuilder();
        Map<String, Object> json = new LinkedHashMap<>();
        double seconds = windowSeconds();
        Integer cpus = jvmCpuCount != null ? jvmCpuCount : cpusOption;
        String cpuSource = jvmCpuCount != null ? "reported by the JVM in the recording"
                : cpusOption != null ? "from --cpus (the recording has no container configuration)" : "unknown";

        md.append("## Java Flight Recording\n\n");
        md.append(String.format(Locale.ROOT, "Window: %s to %s, %.0f s covered by the recording. CPUs available to the "
                        + "JVM: %s (%s)%s. Execution samples in the window: %d (%.0f per second).%s%n%n",
                from == null ? "recording start" : from, to == null ? "recording end" : to, seconds,
                cpus == null ? "-" : cpus, cpuSource,
                containerQuota != null && containerQuota > 0 && containerPeriod != null && containerPeriod > 0
                        ? String.format(Locale.ROOT, ", container CPU quota %.2f CPUs", (double) containerQuota / containerPeriod)
                        : "",
                samples, samples / Math.max(1, seconds),
                truncatedStacks > 0 ? String.format(Locale.ROOT, " %d stacks were truncated at the recording's stack "
                        + "depth, so inclusive shares can be too low.", truncatedStacks) : ""));
        json.put("from", from == null ? null : from.toString());
        json.put("to", to == null ? null : to.toString());
        json.put("windowSeconds", seconds);
        json.put("cpus", cpus);
        json.put("samples", samples);
        json.put("truncatedStacks", truncatedStacks);
        json.put("rps", rps);
        double requested = from != null && to != null ? Duration.between(from, to).toMillis() / 1000.0 : seconds;
        json.put("windowCoverage", requested > 0 ? seconds / requested : null);
        if (seconds <= 0) {
            md.append("**The recording does not cover the window.** Nothing below is measured; the timeline at the end "
                    + "shows when the recording has samples.\n\n");
            timeline(md);
            return new Result(md.toString(), toJson(json));
        }
        if (seconds < requested - 1) {
            md.append(String.format(Locale.ROOT, "**The recording covers only %.0f of the %.0f s window**; everything "
                    + "below refers to the covered part.%n%n", seconds, requested));
        }
        if (samples == 0) {
            md.append("**No execution samples in the window**, so the CPU breakdown by component is not measured.\n\n");
        }

        CpuPicture cpu = cpuPicture(cpus, seconds);
        cpuTables(md, json, cpu);
        if (samples > 0) {
            componentTables(md, json, cpu);
        }
        allocationTables(md, json, seconds);
        gcAndJit(md, json, seconds);
        waitTables(md);
        timeline(md);
        return new Result(md.toString(), toJson(json));
    }

    void cpuTables(StringBuilder md, Map<String, Object> json, CpuPicture cpu) {
        md.append("### CPU by thread group\n\n");
        md.append("| thread group | user (cores) | kernel (cores) | total (cores) | execution samples |\n"
                + "|---|---:|---:|---:|---:|\n");
        Map<String, Object> groups = new LinkedHashMap<>();
        if (Double.isNaN(cpu.javaThreads())) {
            md.append("| Java threads | | | not measured | |\n");
        } else {
            List<Map.Entry<String, double[]>> entries = new ArrayList<>(cpu.groupCores().entrySet());
            entries.sort(Comparator.comparingDouble((Map.Entry<String, double[]> e) -> -(e.getValue()[0] + e.getValue()[1])));
            for (Map.Entry<String, double[]> entry : entries) {
                double total = entry.getValue()[0] + entry.getValue()[1];
                groups.put(entry.getKey(), total);
                md.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %d |%n", entry.getKey(),
                        number(entry.getValue()[0], 2), number(entry.getValue()[1], 2), number(total, 2),
                        samplesByThreadGroup.getOrDefault(entry.getKey(), 0L)));
            }
            md.append(String.format(Locale.ROOT, "| **all Java threads** | | | **%s** | %d |%n", number(cpu.javaThreads(), 2),
                    samples));
        }
        double rest = cpu.jvm() - cpu.javaThreads();
        md.append(String.format(Locale.ROOT, "| GC, VM and other native threads (JVM total minus Java threads) | | | %s | |%n",
                number(rest, 2)));
        md.append(String.format(Locale.ROOT, "| **JVM total**: %s | | | **%s** | |%n%n", cpu.jvmSource(),
                Double.isNaN(cpu.jvm()) ? "not measured" : number(cpu.jvm(), 2)));
        md.append(String.format(Locale.ROOT, "Java thread CPU comes from jdk.ThreadCPULoad: every %s s JFR reports each "
                        + "thread's CPU since it last looked at it (threads below 1 ms are not reported); the intervals are "
                        + "intersected with the window. With SerialGC the collections run on the VM thread, so they are in "
                        + "the GC/VM row. A small negative remainder is measurement noise between the two sources. JIT "
                        + "compiler threads still busy in the steady phase mean the warm-up did not finish compiling.%n%n",
                number(loadPeriod().toMillis() / 1000.0, 0)));
        String throttled = throttlingLine();
        if (throttled != null) {
            md.append(throttled).append("\n\n");
        }
        json.put("threadGroupCores", groups);
        json.put("javaThreadCores", nanToNull(cpu.javaThreads()));
        json.put("jvmCores", nanToNull(cpu.jvm()));
        json.put("jvmCoresSource", cpu.jvmSource());
        json.put("throttledPeriodShare", nanToNull(throttledShare()));
    }

    double containerCores() {
        if (containerCpu.size() < 2) {
            return Double.NaN;
        }
        List<Cumulative> sorted = new ArrayList<>(containerCpu);
        sorted.sort(Comparator.comparing(Cumulative::time));
        Cumulative first = sorted.get(0);
        Cumulative last = sorted.get(sorted.size() - 1);
        double elapsedNs = Duration.between(first.time(), last.time()).toNanos();
        return elapsedNs <= 0 ? Double.NaN : (last.value() - first.value()) / elapsedNs;
    }

    double throttledShare() {
        if (throttling.size() < 2) {
            return Double.NaN;
        }
        long periods = throttling.get(throttling.size() - 1)[0] - throttling.get(0)[0];
        return periods <= 0 ? Double.NaN
                : (double) (throttling.get(throttling.size() - 1)[1] - throttling.get(0)[1]) / periods;
    }

    String throttlingLine() {
        double share = throttledShare();
        if (Double.isNaN(share)) {
            return null;
        }
        long[] first = throttling.get(0);
        long[] last = throttling.get(throttling.size() - 1);
        return String.format(Locale.ROOT, "CPU quota throttling (jdk.ContainerCPUThrottling): the container used up its "
                        + "CPU quota in %d of %d scheduler periods (%.1f %%) and waited %s s in total for the next period.",
                last[1] - first[1], last[0] - first[0], 100 * share, number((last[2] - first[2]) / 1e9, 1));
    }

    Map<String, Double> msPerSampleByGroup(CpuPicture cpu) {
        Map<String, Double> perSample = new HashMap<>();
        if (rps == null || rps <= 0 || Double.isNaN(cpu.javaThreads())) {
            return perSample;
        }
        samplesByThreadGroup.forEach((group, count) -> {
            if (!JIT.equals(group) && count > 0) {
                perSample.put(group, cpu.group(group) * 1000.0 / rps / count);
            }
        });
        return perSample;
    }

    static double weighted(Map<String, Map<String, Long>> countsByGroup, String key, Map<String, Double> perSample) {
        if (perSample.isEmpty()) {
            return Double.NaN;
        }
        double ms = 0;
        for (Map.Entry<String, Map<String, Long>> group : countsByGroup.entrySet()) {
            Double factor = perSample.get(group.getKey());
            long count = group.getValue().getOrDefault(key, 0L);
            if (count > 0 && factor != null) {
                ms += count * factor;
            }
        }
        return ms;
    }

    static Map<String, Long> totals(Map<String, Map<String, Long>> countsByGroup) {
        Map<String, Long> totals = new HashMap<>();
        countsByGroup.values().forEach(counts -> counts.forEach((key, n) -> totals.merge(key, n, Long::sum)));
        return totals;
    }

    void componentTables(StringBuilder md, Map<String, Object> json, CpuPicture cpu) {
        Map<String, Double> perSample = msPerSampleByGroup(cpu);
        double toMs = rps != null && rps > 0 ? 1000.0 / rps : Double.NaN;
        md.append("### CPU by component, exclusive (each sample counted once; adds up to 100 %)\n\n");
        md.append("| component | samples | share of samples | est. CPU ms per request |\n|---|---:|---:|---:|\n");
        Map<String, Object> shares = new LinkedHashMap<>();
        Map<String, Object> ms = new LinkedHashMap<>();
        long total = 0;
        double totalMs = 0;
        for (Map.Entry<String, Long> entry : sortedByValue(totals(exclusive))) {
            double componentMs = weighted(exclusive, entry.getKey(), perSample);
            total += entry.getValue();
            totalMs += componentMs;
            md.append(String.format(Locale.ROOT, "| %s | %d | %.1f %% | %s |%n", entry.getKey(), entry.getValue(),
                    100.0 * entry.getValue() / samples, number(componentMs, 3)));
            shares.put(entry.getKey(), (double) entry.getValue() / samples);
            ms.put(entry.getKey(), nanToNull(componentMs));
        }
        md.append(String.format(Locale.ROOT, "| **Java code on sampled threads** | **%d** | **%.1f %%** | **%s** |%n", total,
                100.0 * total / samples, number(totalMs, 3)));
        double unsampled = 0;
        for (Map.Entry<String, double[]> group : cpu.groupCores().entrySet()) {
            if (!JIT.equals(group.getKey()) && samplesByThreadGroup.getOrDefault(group.getKey(), 0L) == 0) {
                unsampled += group.getValue()[0] + group.getValue()[1];
            }
        }
        double jit = cpu.group(JIT);
        double rest = cpu.jvm() - cpu.javaThreads();
        md.append(String.format(Locale.ROOT, "| Java threads with CPU but no execution sample (kernel, native) | | | %s |%n",
                number(unsampled * toMs, 3)));
        md.append(String.format(Locale.ROOT, "| JIT compiler threads | | | %s |%n", number(jit * toMs, 3)));
        md.append(String.format(Locale.ROOT, "| GC, VM and other native threads | | | %s |%n", number(rest * toMs, 3)));
        md.append(String.format(Locale.ROOT, "| **JVM total** | | | **%s** |%n%n", number(cpu.jvm() * toMs, 3)));
        md.append(String.format(Locale.ROOT, "Each sample belongs to the component of the frame closest to the top of its "
                        + "stack that is not a JDK class: JDK work (collections, strings, I/O) is charged to the code that "
                        + "called it. *Estimated* CPU per request: each thread group's CPU from the table above is spread "
                        + "evenly over that group's samples, divided by %s requests per second. This assumes kernel and "
                        + "native time is spread like the Java samples and that JFR's samples (at most 5 threads per "
                        + "round, threads in Java code even while the OS holds them back) are a fair picture, so the "
                        + "column is an estimate; only the JVM total is measured.%n%n",
                rps == null ? "(not given)" : number(rps, 1)));
        json.put("exclusiveShares", shares);
        json.put("exclusiveMsPerRequest", ms);
        json.put("unsampledThreadMsPerRequest", nanToNull(unsampled * toMs));
        json.put("jitMsPerRequest", nanToNull(jit * toMs));
        json.put("gcVmNativeMsPerRequest", nanToNull(rest * toMs));
        json.put("jvmMsPerRequest", nanToNull(cpu.jvm() * toMs));

        md.append("### CPU by feature, inclusive (features overlap: do not add these up)\n\n");
        md.append("| feature (anywhere on the stack) | samples | share of all samples | est. CPU ms per request |\n"
                + "|---|---:|---:|---:|\n");
        Map<String, Long> featureTotals = totals(inclusive);
        Map<String, Object> inclusiveShares = new LinkedHashMap<>();
        Map<String, Object> inclusiveMs = new LinkedHashMap<>();
        for (Feature feature : FEATURES) {
            long n = featureTotals.getOrDefault(feature.label(), 0L);
            double featureMs = weighted(inclusive, feature.label(), perSample);
            md.append(String.format(Locale.ROOT, "| %s | %d | %.1f %% | %s |%n", feature.label(), n,
                    100.0 * n / samples, number(featureMs, 3)));
            inclusiveShares.put(feature.label(), (double) n / samples);
            inclusiveMs.put(feature.label(), nanToNull(featureMs));
        }
        md.append("\nA sample counts once for every feature on its stack: an RSA check inside the JWT decoder inside the "
                + "security filter chain counts for all three. Each row is that feature's total cost including what it "
                + "calls; the rows overlap and their sum means nothing.\n\n");
        json.put("inclusiveShares", inclusiveShares);
        json.put("inclusiveMsPerRequest", inclusiveMs);

        md.append("### Hottest methods (top of the stack)\n\n| method | component | samples | share |\n|---|---|---:|---:|\n");
        for (Map.Entry<String, Long> entry : top(sortedByValue(leafMethods), 25)) {
            md.append(String.format(Locale.ROOT, "| `%s` | %s | %d | %.1f %% |%n", entry.getKey(),
                    leafComponent.get(entry.getKey()), entry.getValue(), 100.0 * entry.getValue() / samples));
        }
        md.append('\n');
    }

    void allocationTables(StringBuilder md, Map<String, Object> json, double seconds) {
        md.append("### Allocation (jdk.ObjectAllocationSample, weighted samples)\n\n");
        if (allocatedBytes <= 0 || seconds <= 0) {
            md.append("Not measured: no allocation samples in the window.\n\n");
            return;
        }
        double perSecond = allocatedBytes / seconds;
        md.append(String.format(Locale.ROOT, "About %s MB/s allocated%s. The sample weights are a statistical estimate.%n%n",
                number(perSecond / 1e6, 1), rps != null && rps > 0
                        ? String.format(Locale.ROOT, ", about %s KB per request", number(perSecond / rps / 1e3, 1)) : ""));
        json.put("allocatedBytesPerSecond", perSecond);
        json.put("allocatedBytesPerRequest", rps != null && rps > 0 ? perSecond / rps : null);
        md.append("| class | share of allocated bytes |\n|---|---:|\n");
        for (Map.Entry<String, Double> entry : top(sortedByValue(allocationByClass), 15)) {
            md.append(String.format(Locale.ROOT, "| `%s` | %.1f %% |%n", entry.getKey(), 100 * entry.getValue() / allocatedBytes));
        }
        md.append("\n| allocation site (closest non-JDK frame) | share of allocated bytes |\n|---|---:|\n");
        for (Map.Entry<String, Double> entry : top(sortedByValue(allocationBySite), 15)) {
            md.append(String.format(Locale.ROOT, "| `%s` | %.1f %% |%n", entry.getKey(), 100 * entry.getValue() / allocatedBytes));
        }
        md.append('\n');
    }

    void gcAndJit(StringBuilder md, Map<String, Object> json, double seconds) {
        StringBuilder names = new StringBuilder();
        gcByName.forEach((name, count) -> names.append(names.length() == 0 ? "" : ", ").append(name).append(' ').append(count));
        double pauseMs = gcPauses.toNanos() / 1e6;
        md.append(String.format(Locale.ROOT, "### Garbage collection and JIT%n%n%d collections in the window (%s), "
                        + "pauses %s ms in total (%s %% of the window), longest %s ms. JIT compilations longer than "
                        + "100 ms in the window: %d.%n%n",
                gcCount, names.length() == 0 ? "none" : names, number(pauseMs, 0),
                seconds > 0 ? number(pauseMs / 10 / seconds, 2) : "-", number(gcLongest.toNanos() / 1e6, 1), compilations));
        json.put("gcCount", gcCount);
        json.put("gcPauseMs", pauseMs);
        json.put("gcLongestPauseMs", gcLongest.toNanos() / 1e6);
        json.put("compilationsOver100ms", compilations);
    }

    void waitTables(StringBuilder md) {
        md.append("### Where API worker threads wait (time off CPU; only waits longer than the recording's event threshold)\n\n");
        waitTable(md, "Parked (java.util.concurrent locks, pools, futures)", parks);
        waitTable(md, "Blocked entering a synchronized block", monitors);
        waitTable(md, "Socket reads (waiting for a reply)", socketReads);
        md.append("Waits are not CPU time; with many worker threads they add up to far more seconds than the window. "
                + "Idle workers waiting for a request are left out.\n\n");
    }

    void waitTable(StringBuilder md, String title, Map<String, Wait> waits) {
        md.append("**").append(title).append("**\n\n");
        if (waits.isEmpty()) {
            md.append("none recorded\n\n");
            return;
        }
        md.append("| where (closest non-JDK frame) | events | total s | avg ms | max ms |\n|---|---:|---:|---:|---:|\n");
        waits.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, Wait> e) -> e.getValue().total()).reversed())
                .limit(10)
                .forEach(e -> md.append(String.format(Locale.ROOT, "| `%s` | %d | %s | %s | %s |%n", e.getKey(),
                        e.getValue().count(), number(e.getValue().total().toNanos() / 1e9, 1),
                        number(e.getValue().total().toNanos() / 1e6 / e.getValue().count(), 1),
                        number(e.getValue().max().toNanos() / 1e6, 1))));
        md.append('\n');
    }

    void timeline(StringBuilder md) {
        md.append("### API worker samples over the whole recording (to check the window)\n\n");
        if (workerSamplesPer10s.isEmpty()) {
            md.append("No samples of API worker threads in the recording.\n\n");
            return;
        }
        md.append("| 10 s starting at | samples | inside the window |\n|---|---:|---|\n");
        for (Map.Entry<Long, Long> entry : workerSamplesPer10s.entrySet()) {
            Instant start = Instant.ofEpochMilli(entry.getKey() * 10_000L);
            String inside = inWindow(start) && inWindow(start.plusSeconds(10)) ? "yes"
                    : inWindow(start) || inWindow(start.plusSeconds(10)) ? "partly" : "";
            md.append(String.format(Locale.ROOT, "| %s | %d | %s |%n", start, entry.getValue(), inside));
        }
        md.append('\n');
    }

    static <V extends Comparable<V>> List<Map.Entry<String, V>> sortedByValue(Map<String, V> map) {
        List<Map.Entry<String, V>> list = new ArrayList<>(map.entrySet());
        list.sort(Map.Entry.<String, V>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        return list;
    }

    static <T> List<T> top(List<T> list, int n) {
        return list.subList(0, Math.min(n, list.size()));
    }

    static String number(double value, int digits) {
        return Double.isNaN(value) || Double.isInfinite(value) ? "-" : String.format(Locale.ROOT, "%." + digits + "f", value);
    }

    static Double nanToNull(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? null : value;
    }

    static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (sb.length() > 1) {
                    sb.append(',');
                }
                sb.append(quote(String.valueOf(entry.getKey()))).append(':').append(toJson(entry.getValue()));
            }
            return sb.append('}').toString();
        }
        if (value instanceof Number number) {
            double d = number.doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? "null" : number.toString();
        }
        return quote(String.valueOf(value));
    }

    static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static final Set<String> OPTIONS = Set.of("--from-ms", "--to-ms", "--cpus", "--rps");

    public static void main(String[] args) throws IOException {
        if (args.length < 2 || args.length % 2 != 0) {
            usage();
        }
        Map<String, String> options = new HashMap<>();
        for (int i = 2; i < args.length; i += 2) {
            if (!OPTIONS.contains(args[i])) {
                usage();
            }
            options.put(args[i], args[i + 1]);
        }
        JfrSummary summary = new JfrSummary(
                options.containsKey("--from-ms") ? Instant.ofEpochMilli(Long.parseLong(options.get("--from-ms"))) : null,
                options.containsKey("--to-ms") ? Instant.ofEpochMilli(Long.parseLong(options.get("--to-ms"))) : null,
                options.containsKey("--cpus") ? (int) Math.ceil(Double.parseDouble(options.get("--cpus"))) : null,
                options.containsKey("--rps") ? Double.valueOf(options.get("--rps")) : null);
        try (RecordingFile recording = new RecordingFile(Path.of(args[0]))) {
            while (recording.hasMoreEvents()) {
                summary.accept(recording.readEvent());
            }
        }
        Result result = summary.report();
        Path out = Path.of(args[1]);
        Files.writeString(out, result.markdown(), StandardCharsets.UTF_8);
        Path json = out.resolveSibling(out.getFileName().toString().replaceAll("\\.md$", "") + ".json");
        Files.writeString(json, result.json() + "\n", StandardCharsets.UTF_8);
        System.out.println("Wrote " + out + " and " + json.getFileName() + ": " + summary.samples
                + " execution samples in the window");
    }

    static void usage() {
        System.err.println("Usage: java JfrSummary.java <recording.jfr> <summary.md> [--from-ms N] [--to-ms N] "
                + "[--cpus N] [--rps R]");
        System.exit(2);
    }
}
