package com.mphasis.transactions.retry;

/** maxRetries excludes the initial attempt; 0 means one attempt only. */
public record RetryPolicy(int maxRetries) {
    public RetryPolicy {
        if (maxRetries < 0 || maxRetries > 100) {
            throw new IllegalArgumentException("maxRetries must be between 0 and 100");
        }
    }

    public boolean canRetry(int completedAttempts) {
        return completedAttempts <= maxRetries;
    }
}
