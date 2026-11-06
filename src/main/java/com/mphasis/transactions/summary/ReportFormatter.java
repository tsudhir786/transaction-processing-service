package com.mphasis.transactions.summary;

import com.mphasis.transactions.repository.InMemoryStore;

public class ReportFormatter {
    public String format(ProcessingSummary summary, InMemoryStore.Snapshot snapshot) {
        var text = new StringBuilder("SUMMARY ").append(summary.counts()).append('\n');
        for (var outcome : summary.outcomes()) {
            var request = outcome.request();
            text.append("row=%d transaction=%s request=%s status=%s attempts=%d retries=%d reason=%s%n"
                    .formatted(outcome.inputIndex(), request == null ? "<null>" : request.transactionId(),
                            request == null ? "<null>" : request.requestId(), outcome.status(),
                            outcome.attempts(), outcome.retries(), outcome.message()));
        }
        snapshot.accounts().values().stream().sorted(java.util.Comparator.comparing(a -> a.accountId()))
                .forEach(account -> text.append("ACCOUNT %s currency=%s balance=%s lastSequence=%d%n"
                        .formatted(account.accountId(), account.currency(), account.balance().toPlainString(),
                                account.lastSequence())));
        text.append("LEDGER entries=").append(snapshot.ledger().size()).append('\n');
        return text.toString();
    }
}
