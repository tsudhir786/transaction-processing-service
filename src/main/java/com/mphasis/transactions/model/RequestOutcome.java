package com.mphasis.transactions.model;

import java.util.List;

/** One final outcome per input row; duplicates do not overwrite canonical state. */
public record RequestOutcome(int inputIndex, TransactionRequest request, ProcessingStatus status,
                             int attempts, int retries, String message,
                             List<ProcessingStatus> history) {
    public RequestOutcome {
        history = List.copyOf(history);
    }
}
