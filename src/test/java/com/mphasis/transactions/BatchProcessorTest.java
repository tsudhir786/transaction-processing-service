package com.mphasis.transactions;

import com.mphasis.transactions.model.*;
import com.mphasis.transactions.ordering.BatchOrdering;
import com.mphasis.transactions.processing.BatchProcessor;
import com.mphasis.transactions.repository.InMemoryStore;
import com.mphasis.transactions.retry.*;
import com.mphasis.transactions.summary.ProcessingSummary;
import com.mphasis.transactions.validation.TransactionValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.mphasis.transactions.model.ProcessingStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class BatchProcessorTest {
    private final InMemoryStore store = new InMemoryStore();

    private BatchProcessor processor(int retries, FailureSimulator simulator) {
        return new BatchProcessor(store, new TransactionValidator(), new BatchOrdering(),
                new RetryPolicy(retries), simulator);
    }

    private BatchProcessor processor() { return processor(2, FailureSimulator.none()); }

    private TransactionRequest request(String id, String requestId, String account, String type,
                                       String amount, String currency, long sequence) {
        return new TransactionRequest(id, requestId, account, type, new BigDecimal(amount), currency, sequence);
    }

    private TransactionRequest credit(String id, String amount, long sequence) {
        return request(id, "R-" + id, "A", "CREDIT", amount, "INR", sequence);
    }

    private void balance(String accountId, String expected) {
        assertEquals(0, new BigDecimal(expected).compareTo(store.snapshot().accounts().get(accountId).balance()));
    }

    private void counts(ProcessingSummary summary, long processed, long duplicate, long failed, long retried) {
        assertAll(() -> assertEquals(processed, summary.processed()),
                () -> assertEquals(duplicate, summary.duplicate()),
                () -> assertEquals(failed, summary.failed()),
                () -> assertEquals(retried, summary.retried()),
                () -> assertEquals(0, summary.pending()),
                () -> assertEquals(summary.outcomes().size(), processed + duplicate + failed));
    }

    @Test
    void appliesCreditThenDebitUsingExactDecimals() {
        var summary = processor().process(List.of(credit("T1", "100.10", 1),
                request("T2", "R2", "A", "DEBIT", "40.05", "INR", 2)));
        counts(summary, 2, 0, 0, 0);
        balance("A", "60.05");
        assertEquals(2, store.snapshot().ledger().size());
        assertEquals(List.of(RECEIVED, PENDING, PROCESSING, PROCESSED), store.find("T1").history());
    }

    @Test
    void newRequestIdAndEquivalentAmountScaleAreDuplicates() {
        var summary = processor().process(List.of(credit("T1", "100.0", 1),
                request("T1", "OTHER", "A", "CREDIT", "100.00", "INR", 1)));
        counts(summary, 1, 1, 0, 0);
        balance("A", "100");
        assertEquals(1, store.snapshot().ledger().size());
        assertEquals(PROCESSED, store.find("T1").status());
        assertEquals(DUPLICATE, summary.outcomes().get(1).status());
    }

    @Test
    void replayingWholeBatchDoesNotApplyItAgain() {
        var input = List.of(credit("T1", "100", 1), credit("T2", "25", 2));
        var processor = processor();
        processor.process(input);
        var before = store.snapshot();
        counts(processor.process(input), 0, 2, 0, 0);
        assertEquals(before, store.snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "type", "amount", "currency", "sequence"})
    void rejectsEveryConflictingBusinessPayloadField(String changed) {
        var original = credit("T1", "100", 1);
        processor().process(List.of(original));
        var before = store.snapshot();
        var conflict = request("T1", "NEW", changed.equals("account") ? "B" : "A",
                changed.equals("type") ? "DEBIT" : "CREDIT", changed.equals("amount") ? "101" : "100",
                changed.equals("currency") ? "USD" : "INR", changed.equals("sequence") ? 2 : 1);
        var summary = processor().process(List.of(conflict));
        counts(summary, 0, 0, 1, 0);
        assertEquals("IDEMPOTENCY_CONFLICT", summary.outcomes().get(0).message());
        assertEquals(before, store.snapshot());
    }

    @Test
    void sortsOutOfOrderTransactionsBeforeCheckingFunds() {
        var summary = processor().process(List.of(request("T2", "R2", "A", "DEBIT", "40", "INR", 2),
                credit("T3", "25", 3), credit("T1", "100", 1)));
        counts(summary, 3, 0, 0, 0);
        balance("A", "85");
        assertEquals(0, new BigDecimal("60").compareTo(store.snapshot().ledger().get("T2").balanceAfter()));
        assertEquals(3, store.snapshot().accounts().get("A").lastSequence());
    }

    @Test
    void missingSequencesAndNonOneStartingSequencesDoNotBlockAccounts() {
        var summary = processor().process(List.of(credit("T3", "25", 3), credit("T1", "100", 1),
                request("B5", "R5", "B", "CREDIT", "500", "INR", 5)));
        counts(summary, 3, 0, 0, 0);
        balance("A", "125");
        balance("B", "500");
    }

    @Test
    void lateMissingSequenceInLaterBatchIsRejected() {
        var processor = processor();
        processor.process(List.of(credit("T3", "25", 3)));
        var summary = processor.process(List.of(credit("T2", "100", 2)));
        counts(summary, 0, 0, 1, 0);
        assertEquals("LATE_SEQUENCE", summary.outcomes().get(0).message());
        balance("A", "25");
        assertEquals(1, store.snapshot().ledger().size());
    }

    @Test
    void twoDifferentTransactionsCannotClaimSameAccountSequence() {
        var summary = processor().process(List.of(credit("T1", "100", 1), credit("OTHER", "50", 1)));
        counts(summary, 1, 0, 1, 0);
        assertEquals("SEQUENCE_CONFLICT", summary.outcomes().get(1).message());
        balance("A", "100");
        assertEquals(1, store.snapshot().ledger().size());
    }

    @Test
    void sameSequenceOnDifferentAccountsIsAllowed() {
        var summary = processor().process(List.of(credit("T1", "100", 1),
                request("T2", "R2", "B", "CREDIT", "50", "INR", 1)));
        counts(summary, 2, 0, 0, 0);
        balance("A", "100");
        balance("B", "50");
    }

    @Test
    void invalidNegativeAmountDoesNotCreateAccountOrReserveIdentity() {
        var processor = processor();
        var summary = processor.process(List.of(credit("BAD", "-10", 1)));
        counts(summary, 0, 0, 1, 0);
        assertEquals("AMOUNT_MUST_BE_POSITIVE", summary.outcomes().get(0).message());
        assertTrue(store.snapshot().accounts().isEmpty());
        assertTrue(store.snapshot().ledger().isEmpty());
        assertTrue(store.snapshot().transactions().isEmpty());
        counts(processor.process(List.of(credit("BAD", "10", 1))), 1, 0, 0, 0);
    }

    @Test
    void invalidRowDoesNotBlockLaterValidRows() {
        var summary = processor().process(List.of(credit("BAD", "-10", 1), credit("GOOD", "25", 2)));
        counts(summary, 1, 0, 1, 0);
        balance("A", "25");
    }

    @Test
    void rejectsCurrencyMismatchWithoutChangingFinancialState() {
        processor().process(List.of(credit("T1", "100", 1)));
        var before = store.snapshot();
        var summary = processor().process(List.of(request("T2", "R2", "A", "CREDIT", "20", "USD", 2)));
        counts(summary, 0, 0, 1, 0);
        assertEquals("CURRENCY_MISMATCH", summary.outcomes().get(0).message());
        assertEquals(before.accounts(), store.snapshot().accounts());
        assertEquals(before.ledger(), store.snapshot().ledger());
    }

    @Test
    void insufficientFundsIsPermanentAndDoesNotRetry() {
        var summary = processor(2, FailureSimulator.selected("T1", 10)).process(List.of(
                request("T1", "R1", "A", "DEBIT", "10", "INR", 1)));
        counts(summary, 0, 0, 1, 0);
        assertEquals("INSUFFICIENT_FUNDS", summary.outcomes().get(0).message());
        assertEquals(1, store.find("T1").attempts());
        assertTrue(store.snapshot().accounts().isEmpty());
        assertTrue(store.snapshot().ledger().isEmpty());
    }

    @Test
    void retriesOnceThenAppliesExactlyOneFinancialEffect() {
        var summary = processor(2, FailureSimulator.selected("T1", 1))
                .process(List.of(credit("T1", "500", 5)));
        counts(summary, 1, 0, 0, 1);
        assertEquals(1, summary.retryAttempts());
        assertEquals(2, store.find("T1").attempts());
        assertEquals(List.of(RECEIVED, PENDING, PROCESSING, RETRY_PENDING, PROCESSING, PROCESSED),
                store.find("T1").history());
        balance("A", "500");
        assertEquals(1, store.snapshot().ledger().size());
    }

    @Test
    void retryExhaustionIsTerminalAndOtherAccountsStillComplete() {
        var processor = processor(2, FailureSimulator.selected("T1", 10));
        var summary = processor.process(List.of(credit("T1", "500", 1),
                request("B1", "RB", "B", "CREDIT", "25", "INR", 1)));
        counts(summary, 1, 0, 1, 1);
        assertEquals(2, summary.retryAttempts());
        assertEquals(3, store.find("T1").attempts());
        assertEquals(FAILED, store.find("T1").status());
        assertEquals("RETRY_EXHAUSTED", store.find("T1").message());
        assertFalse(store.snapshot().accounts().containsKey("A"));
        assertFalse(store.snapshot().ledger().containsKey("T1"));
        balance("B", "25");
        counts(processor.process(List.of(credit("T1", "500", 1))), 0, 1, 0, 0);
        assertEquals(FAILED, store.find("T1").status());
    }

    @Test
    void exhaustedTransactionIsSkippedBeforeLaterSequenceOnSameAccount() {
        var summary = processor(0, FailureSimulator.selected("T1", 1)).process(List.of(
                credit("T2", "25", 2), credit("T1", "100", 1)));
        counts(summary, 1, 0, 1, 0);
        balance("A", "25");
        assertEquals(1, store.snapshot().ledger().size());
    }

    @Test
    void zeroRetriesMeansOneAttempt() {
        var summary = processor(0, FailureSimulator.selected("T1", 1)).process(List.of(credit("T1", "10", 1)));
        counts(summary, 0, 0, 1, 0);
        assertEquals(1, store.find("T1").attempts());
        assertEquals(0, summary.retryAttempts());
    }

    @Test
    void stagedBalanceAndLedgerRemainInvisibleOnFailureAndRetrySucceeds() {
        processor().process(List.of(credit("SEED", "100", 1)));
        var before = store.snapshot();
        FailureSimulator simulator = (id, attempt, stage) -> {
            if (stage == FailureSimulator.Stage.AFTER_STAGING && attempt == 1) {
                assertEquals(before.accounts(), store.snapshot().accounts());
                assertEquals(before.ledger(), store.snapshot().ledger());
                assertEquals(PROCESSING, store.find(id).status());
                throw new TransientProcessingException();
            }
        };
        var summary = processor(1, simulator).process(List.of(
                request("T2", "R2", "A", "DEBIT", "40", "INR", 2)));
        counts(summary, 1, 0, 0, 1);
        balance("A", "60");
        assertEquals(2, store.snapshot().ledger().size());
    }

    @Test
    void stagedFailureWithNoRetriesLeavesAllFinancialStateUnchanged() {
        processor().process(List.of(credit("SEED", "100", 1)));
        var before = store.snapshot();
        FailureSimulator simulator = (id, attempt, stage) -> {
            if (stage == FailureSimulator.Stage.AFTER_STAGING) throw new TransientProcessingException();
        };
        var summary = processor(0, simulator).process(List.of(credit("T2", "40", 2)));
        counts(summary, 0, 0, 1, 0);
        assertEquals(before.accounts(), store.snapshot().accounts());
        assertEquals(before.ledger(), store.snapshot().ledger());
        assertEquals(FAILED, store.find("T2").status());
    }

    @Test
    void lostAcknowledgementAfterCommitIsReconciledWithoutApplyingAgain() {
        FailureSimulator simulator = (id, attempt, stage) -> {
            if (stage == FailureSimulator.Stage.AFTER_COMMIT) throw new TransientProcessingException();
        };
        var processor = processor(2, simulator);
        var summary = processor.process(List.of(credit("T1", "100", 1)));
        counts(summary, 1, 0, 0, 0);
        assertEquals(PROCESSED, store.find("T1").status());
        counts(processor.process(List.of(credit("T1", "100", 1))), 0, 1, 0, 0);
        balance("A", "100");
        assertEquals(1, store.snapshot().ledger().size());
    }

    @Test
    void concurrentSubmissionsSharingStoreCannotDoubleApply() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> {
                start.await();
                return processor().process(List.of(credit("T1", "100", 1)));
            });
            var second = executor.submit(() -> {
                start.await();
                return processor().process(List.of(credit("T1", "100", 1)));
            });
            start.countDown();
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            assertEquals(1, a.processed() + b.processed());
            assertEquals(1, a.duplicate() + b.duplicate());
            balance("A", "100");
            assertEquals(1, store.snapshot().ledger().size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void snapshotCollectionsCannotBeModifiedByCallers() {
        processor().process(List.of(credit("T1", "100", 1)));
        assertThrows(UnsupportedOperationException.class, () -> store.snapshot().accounts().clear());
        assertThrows(UnsupportedOperationException.class, () -> store.snapshot().ledger().clear());
        assertThrows(UnsupportedOperationException.class, () -> store.snapshot().transactions().clear());
        assertThrows(UnsupportedOperationException.class, () -> store.find("T1").history().clear());
    }

    @Test
    void nullRowIsReportedAndEmptyBatchIsValid() {
        var rows = new ArrayList<TransactionRequest>();
        rows.add(null);
        rows.add(credit("T1", "10", 1));
        counts(processor().process(rows), 1, 0, 1, 0);
        counts(processor().process(List.of()), 0, 0, 0, 0);
        balance("A", "10");
    }
}
