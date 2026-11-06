package com.mphasis.transactions.model;

import java.math.BigDecimal;
import java.util.Objects;

/** Raw boundary model: strings allow unsupported types to become row validation failures. */
public record TransactionRequest(String transactionId, String requestId, String accountId,
                                 String transactionType, BigDecimal amount, String currency,
                                 Long sequenceNumber) {
    public boolean sameBusinessPayload(TransactionRequest other) {
        return Objects.equals(transactionId, other.transactionId)
                && Objects.equals(accountId, other.accountId)
                && Objects.equals(transactionType, other.transactionType)
                && amount.compareTo(other.amount) == 0
                && Objects.equals(currency, other.currency)
                && Objects.equals(sequenceNumber, other.sequenceNumber);
    }
}
