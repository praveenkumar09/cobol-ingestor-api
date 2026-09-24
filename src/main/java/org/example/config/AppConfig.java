package org.example.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Central configuration loader.
 *
 * Resolution order for every value (first match wins):
 *   1. Real environment variable (runtime override — CI, Docker, k8s)
 *   2. .env file                 (local dev / IntelliJ run configs — see
 *                                 loadDotEnv below; NOT committed to source)
 *   3. application.properties    (project defaults — committed to source)
 *   4. Hardcoded fallback        (last-resort safety net)
 */
public class AppConfig {

    private static final Properties PROPS = new Properties();
    private static final Map<String, String> DOTENV = loadDotEnv();

    static {
        try (InputStream is = AppConfig.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (is != null) PROPS.load(is);
            else System.err.println("WARNING: application.properties not found on classpath");
        } catch (Exception e) {
            System.err.println("WARNING: Could not load application.properties: " + e.getMessage());
        }
    }

    /**
     * Reads a plain KEY=VALUE .env file (one per line, '#' comments and
     * blank lines skipped, optional surrounding quotes stripped) so secrets
     * like OPENAI_API_KEY work from an IDE "Run" button — which launches the
     * JVM with the IDE's own process environment, NOT a shell that has
     * sourced .env, unlike `mvn`/`java` run from a terminal after `. .env`.
     * Looked up relative to the working directory (matches Main's own
     * findResourcesDir() convention: this module's root when run from
     * IntelliJ or `mvn`/`java` invoked from within cobol-ingestor-api/, one
     * level up if invoked from a parent directory). Silently absent is fine
     * — a real environment variable or application.properties still work.
     */
    private static Map<String, String> loadDotEnv() {
        Map<String, String> result = new LinkedHashMap<>();
        Path cwd = Path.of(System.getProperty("user.dir"));
        Path envFile = cwd.resolve(".env");
        if (!Files.isRegularFile(envFile)) envFile = cwd.resolve("../.env").normalize();
        if (!Files.isRegularFile(envFile)) return result;

        try {
            List<String> lines = Files.readAllLines(envFile);
            for (String line : lines) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int eq = trimmed.indexOf('=');
                if (eq < 0) continue;
                String key = trimmed.substring(0, eq).strip();
                String value = trimmed.substring(eq + 1).strip();
                if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                if (!key.isEmpty()) result.put(key, value);
            }
            System.out.println("  .env          : loaded " + result.size() + " value(s) from " + envFile);
        } catch (IOException e) {
            System.err.println("WARNING: could not read " + envFile + ": " + e.getMessage());
        }
        return result;
    }

    /** Real environment variable → .env file → null. The one place every
     * env-driven lookup in this class (and any direct System.getenv(...)
     * call site that switches to this) ultimately goes through, so .env
     * support is automatic everywhere, not just for OPENAI_API_KEY. */
    public static String getenv(String key) {
        String real = System.getenv(key);
        if (real != null && !real.isBlank()) return real;
        return DOTENV.get(key);
    }

    /** env var (or .env) → properties file → fallback */
    public static String get(String envKey, String propKey, String fallback) {
        String envVal = getenv(envKey);
        if (envVal != null && !envVal.isBlank()) return envVal;
        return PROPS.getProperty(propKey, fallback);
    }

    /** properties file → fallback (for values with no env var override) */
    public static String get(String propKey, String fallback) {
        return PROPS.getProperty(propKey, fallback);
    }

    /** env var → properties file → fallback (integer variant) */
    public static int getInt(String envKey, String propKey, int fallback) {
        String val = get(envKey, propKey, null);
        if (val == null) return fallback;
        try { return Integer.parseInt(val.trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    /** properties file → fallback (integer, no env var override) */
    public static int getInt(String propKey, int fallback) {
        String val = PROPS.getProperty(propKey);
        if (val == null) return fallback;
        try { return Integer.parseInt(val.trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    /** env var → properties file → fallback (boolean variant) */
    public static boolean getBoolean(String envKey, String propKey, boolean fallback) {
        String val = get(envKey, propKey, null);
        if (val == null) return fallback;
        return Boolean.parseBoolean(val.trim());
    }
}