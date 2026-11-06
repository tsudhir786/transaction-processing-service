package com.mphasis.transactions.model;

import java.util.ArrayList;
import java.util.List;

/** Canonical state for a business transaction, separate from duplicate request outcomes. */
public record TransactionRecord(TransactionRequest request, ProcessingStatus status, int attempts,
                                int retries, String message, List<ProcessingStatus> history) {
    public TransactionRecord {
        history = List.copyOf(history);
    }

    public static TransactionRecord received(TransactionRequest request) {
        return new TransactionRecord(request, ProcessingStatus.RECEIVED, 0, 0,
                "RECEIVED", List.of(ProcessingStatus.RECEIVED));
    }

    public TransactionRecord transition(ProcessingStatus next, String reason) {
        var updated = new ArrayList<>(history);
        updated.add(next);
        return new TransactionRecord(request, next,
                attempts + (next == ProcessingStatus.PROCESSING ? 1 : 0),
                retries + (next == ProcessingStatus.PROCESSING && attempts > 0 ? 1 : 0),
                reason, updated);
    }
}
