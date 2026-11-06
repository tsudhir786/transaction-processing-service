package com.mphasis.transactions.repository;

import com.mphasis.transactions.model.*;
import com.mphasis.transactions.processing.BusinessRuleException;
import com.mphasis.transactions.retry.FailureSimulator;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static com.mphasis.transactions.model.ProcessingStatus.*;

/** All published data is immutable. One reference replacement publishes a financial commit. */
public class InMemoryStore {
    public record Snapshot(Map<String, TransactionRecord> transactions, Map<String, Account> accounts,
                           Map<String, LedgerEntry> ledger) {
        public Snapshot {
            transactions = Map.copyOf(transactions);
            accounts = Map.copyOf(accounts);
            ledger = Map.copyOf(ledger);
        }
    }

    private Snapshot state = new Snapshot(Map.of(), Map.of(), Map.of());

    public synchronized Snapshot snapshot() {
        return state;
    }

    public synchronized TransactionRecord find(String id) {
        return state.transactions().get(id);
    }

    /** Called under the batch lock after shape validation and transactionId deduplication. */
    public synchronized String reserve(TransactionRequest request) {
        for (var existing : state.transactions().values()) {
            var other = existing.request();
            if (other.accountId().equals(request.accountId())
                    && other.sequenceNumber().equals(request.sequenceNumber())) {
                return "SEQUENCE_CONFLICT";
            }
        }
        var transactions = new HashMap<>(state.transactions());
        transactions.put(request.transactionId(), TransactionRecord.received(request));
        state = new Snapshot(transactions, state.accounts(), state.ledger());
        return null;
    }

    public synchronized void transition(String id, ProcessingStatus next, String reason) {
        var transactions = new HashMap<>(state.transactions());
        transactions.put(id, transactions.get(id).transition(next, reason));
        state = new Snapshot(transactions, state.accounts(), state.ledger());
    }

    public synchronized void commit(String id, FailureSimulator simulator) {
        var record = find(id);
        if (record.status() == PROCESSED) return;
        if (record.status() != PROCESSING) throw new IllegalStateException("Transaction must be PROCESSING");
        var request = record.request();
        var account = state.accounts().getOrDefault(request.accountId(),
                new Account(request.accountId(), request.currency(), BigDecimal.ZERO, 0));
        if (!account.currency().equals(request.currency())) throw new BusinessRuleException("CURRENCY_MISMATCH");
        if (request.sequenceNumber() <= account.lastSequence()) throw new BusinessRuleException("LATE_SEQUENCE");

        var delta = "CREDIT".equals(request.transactionType()) ? request.amount() : request.amount().negate();
        var balance = account.balance().add(delta);
        if (balance.signum() < 0) throw new BusinessRuleException("INSUFFICIENT_FUNDS");

        simulator.check(id, record.attempts(), FailureSimulator.Stage.BEFORE_COMMIT);
        var accounts = new HashMap<>(state.accounts());
        accounts.put(request.accountId(), new Account(request.accountId(), request.currency(),
                balance, request.sequenceNumber()));
        var ledger = new HashMap<>(state.ledger());
        ledger.put(id, new LedgerEntry(id, request.accountId(), request.sequenceNumber(),
                request.transactionType(), request.amount(), request.currency(), balance));
        var transactions = new HashMap<>(state.transactions());
        transactions.put(id, record.transition(PROCESSED, "APPLIED"));
        var next = new Snapshot(transactions, accounts, ledger);
        simulator.check(id, record.attempts(), FailureSimulator.Stage.AFTER_STAGING);
        // Nothing above has mutated the published financial state.
        state = next;
        simulator.check(id, record.attempts(), FailureSimulator.Stage.AFTER_COMMIT);
    }
}
