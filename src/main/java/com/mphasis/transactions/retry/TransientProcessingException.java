package com.mphasis.transactions.retry;

public class TransientProcessingException extends RuntimeException {
    public TransientProcessingException() {
        super("SIMULATED_TRANSIENT_FAILURE");
    }
}
