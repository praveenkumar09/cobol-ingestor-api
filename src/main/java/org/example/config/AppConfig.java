package org.example.config;

import java.io.InputStream;
import java.util.Properties;

/**
 * Central configuration loader.
 *
 * Resolution order for every value (first match wins):
 *   1. Environment variable  (runtime override — CI, Docker, k8s)
 *   2. application.properties (project defaults — committed to source)
 *   3. Hardcoded fallback    (last-resort safety net)
 */
public class AppConfig {

    private static final Properties PROPS = new Properties();

    static {
        try (InputStream is = AppConfig.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (is != null) PROPS.load(is);
            else System.err.println("WARNING: application.properties not found on classpath");
        } catch (Exception e) {
            System.err.println("WARNING: Could not load application.properties: " + e.getMessage());
        }
    }

    /** env var → properties file → fallback */
    public static String get(String envKey, String propKey, String fallback) {
        String envVal = System.getenv(envKey);
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
}