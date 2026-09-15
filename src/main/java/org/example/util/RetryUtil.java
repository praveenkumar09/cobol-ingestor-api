package org.example.util;

import org.example.config.AppConfig;

/**
 * Shared retry-with-backoff helper for calls to the OpenAI API, used by both
 * chunk analysis (LlmChunkAnalyzer) and enrichment (LlmEnricher).
 *
 * <p>Exponential backoff (doubling, capped) for transient failures. An
 * {@link Attempt} that knows a retry can never help (e.g. the model needs a
 * smaller input, not another try at the same size) should throw
 * {@link NoRetryException} to skip remaining attempts immediately and let
 * the caller escalate to its own recovery strategy instead.
 */
public class RetryUtil {

    public static final int MAX_ATTEMPTS = AppConfig.getInt("openai.retry.max-attempts", 3);
    public static final long BASE_BACKOFF_MS = AppConfig.getInt("openai.retry.backoff-ms", 2000);
    private static final long MAX_BACKOFF_MS = 30_000;

    private RetryUtil() {}

    @FunctionalInterface
    public interface Attempt<T> {
        T run(int attemptNumber) throws Exception;
    }

    /** Signals "retrying this exact call will never help" — RetryUtil skips remaining attempts. */
    public static class NoRetryException extends RuntimeException {
        public NoRetryException(String message) { super(message); }
        public NoRetryException(String message, Throwable cause) { super(message, cause); }
    }

    /** A transient failure worth retrying, optionally with a known wait hint (e.g. a Retry-After header). */
    public static class RetryableApiException extends RuntimeException {
        public final Long retryAfterMs;
        public RetryableApiException(String message, Long retryAfterMs) {
            super(message);
            this.retryAfterMs = retryAfterMs;
        }
    }

    public static <T> T withRetry(String label, Attempt<T> attempt) throws Exception {
        return withRetry(label, MAX_ATTEMPTS, BASE_BACKOFF_MS, attempt);
    }

    public static <T> T withRetry(String label, int maxAttempts, long baseBackoffMs, Attempt<T> attempt) throws Exception {
        Exception last = null;
        for (int i = 1; i <= maxAttempts; i++) {
            try {
                return attempt.run(i);
            } catch (NoRetryException e) {
                throw e;
            } catch (Exception e) {
                last = e;
                if (i == maxAttempts) break;
                long sleepMs = (e instanceof RetryableApiException rae && rae.retryAfterMs != null)
                    ? rae.retryAfterMs
                    : Math.min(baseBackoffMs * (1L << (i - 1)), MAX_BACKOFF_MS);
                System.out.println("  " + label + " ... attempt " + i + "/" + maxAttempts
                    + " failed (" + e.getMessage() + "), retrying in " + sleepMs + "ms");
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw last;
                }
            }
        }
        throw last;
    }
}
