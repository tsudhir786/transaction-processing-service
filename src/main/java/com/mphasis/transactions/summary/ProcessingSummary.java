package com.mphasis.transactions.summary;

import com.mphasis.transactions.model.RequestOutcome;

import java.util.List;

import static com.mphasis.transactions.model.ProcessingStatus.*;

/** Counts are per input row; retried overlaps terminal outcomes. */
public record ProcessingSummary(List<RequestOutcome> outcomes) {
    public ProcessingSummary {
        outcomes = List.copyOf(outcomes);
    }

    public long processed() { return outcomes.stream().filter(o -> o.status() == PROCESSED).count(); }
    public long duplicate() { return outcomes.stream().filter(o -> o.status() == DUPLICATE).count(); }
    public long failed() { return outcomes.stream().filter(o -> o.status() == FAILED).count(); }
    public long pending() { return outcomes.stream().filter(o -> o.status() == PENDING).count(); }
    public long retried() { return outcomes.stream().filter(o -> o.retries() > 0).count(); }
    public int retryAttempts() { return outcomes.stream().mapToInt(RequestOutcome::retries).sum(); }

    public String counts() {
        return "processed=%d duplicate=%d failed=%d retried=%d retryAttempts=%d pending=%d"
                .formatted(processed(), duplicate(), failed(), retried(), retryAttempts(), pending());
    }
}
