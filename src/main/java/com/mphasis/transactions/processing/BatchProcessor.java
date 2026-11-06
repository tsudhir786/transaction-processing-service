package com.mphasis.transactions.processing;

import com.mphasis.transactions.model.*;
import com.mphasis.transactions.ordering.BatchOrdering;
import com.mphasis.transactions.repository.InMemoryStore;
import com.mphasis.transactions.retry.*;
import com.mphasis.transactions.summary.ProcessingSummary;
import com.mphasis.transactions.validation.TransactionValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

import static com.mphasis.transactions.model.ProcessingStatus.*;

public class BatchProcessor {
    private static final Logger log = LoggerFactory.getLogger(BatchProcessor.class);
    private final InMemoryStore store;
    private final TransactionValidator validator;
    private final BatchOrdering ordering;
    private final RetryPolicy retryPolicy;
    private final FailureSimulator simulator;

    public BatchProcessor(InMemoryStore store, TransactionValidator validator, BatchOrdering ordering,
                          RetryPolicy retryPolicy, FailureSimulator simulator) {
        this.store = store;
        this.validator = validator;
        this.ordering = ordering;
        this.retryPolicy = retryPolicy;
        this.simulator = simulator;
    }

    public ProcessingSummary process(List<TransactionRequest> input) {
        Objects.requireNonNull(input, "input");
        // One shared lock also protects admission if multiple processors share this store.
        synchronized (store) {
            var batchId = java.util.UUID.randomUUID().toString();
            var rows = new ArrayList<>(input);
            var results = new ArrayList<RequestOutcome>();
            var admitted = new LinkedHashMap<String, TransactionRequest>();
            var indexes = new LinkedHashMap<String, Integer>();
            for (int i = 0; i < rows.size(); i++) {
                var request = rows.get(i);
                var reason = validator.validate(request);
                if (reason != null) {
                    results.add(rejected(i, request, FAILED, reason));
                    continue;
                }
                var existing = store.find(request.transactionId());
                if (existing != null) {
                    boolean same = existing.request().sameBusinessPayload(request);
                    results.add(rejected(i, request, same ? DUPLICATE : FAILED,
                            same ? "ALREADY_RECEIVED" : "IDEMPOTENCY_CONFLICT"));
                    continue;
                }
                reason = store.reserve(request);
                if (reason != null) {
                    results.add(rejected(i, request, FAILED, reason));
                    continue;
                }
                store.transition(request.transactionId(), PENDING, "WAITING_FOR_BATCH_ORDER");
                admitted.put(request.transactionId(), request);
                indexes.put(request.transactionId(), i);
            }
            for (var request : ordering.order(admitted.values())) {
                processOne(request.transactionId(), batchId, indexes.get(request.transactionId()));
                var record = store.find(request.transactionId());
                results.add(new RequestOutcome(indexes.get(request.transactionId()), request,
                        record.status(), record.attempts(), record.retries(), record.message(), record.history()));
            }
            results.sort(java.util.Comparator.comparingInt(RequestOutcome::inputIndex));
            // Row indexes correlate logs without logging financial payloads or account identifiers.
            for (var result : results) {
                log.info("event=request_completed batch={} row={} status={} attempts={} retries={} reason={}",
                        batchId, result.inputIndex(), result.status(), result.attempts(), result.retries(), result.message());
            }
            var summary = new ProcessingSummary(results);
            log.info("event=batch_completed batch={} {}", batchId, summary.counts());
            return summary;
        }
    }

    private void processOne(String id, String batchId, int row) {
        while (true) {
            store.transition(id, PROCESSING, "ATTEMPT_STARTED");
            try {
                store.commit(id, simulator);
                return;
            } catch (TransientProcessingException ex) {
                // A lost acknowledgement after publication is resolved by reading canonical state.
                if (store.find(id).status() == PROCESSED) {
                    log.info("event=commit_reconciled batch={} row={} reason=ACKNOWLEDGEMENT_LOST", batchId, row);
                    return;
                }
                var attempts = store.find(id).attempts();
                if (!retryPolicy.canRetry(attempts)) {
                    store.transition(id, FAILED, "RETRY_EXHAUSTED");
                    return;
                }
                store.transition(id, RETRY_PENDING, "TRANSIENT_FAILURE");
                log.info("event=retry_scheduled batch={} row={} attempt={} nextAttempt={} reason=TRANSIENT_FAILURE",
                        batchId, row, attempts, attempts + 1);
            } catch (BusinessRuleException ex) {
                store.transition(id, FAILED, ex.getMessage());
                return;
            }
        }
    }

    private RequestOutcome rejected(int index, TransactionRequest request, ProcessingStatus status, String reason) {
        return new RequestOutcome(index, request, status, 0, 0, reason, List.of(RECEIVED, status));
    }
}
