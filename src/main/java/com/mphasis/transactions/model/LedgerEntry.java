package com.mphasis.transactions.model;

import java.math.BigDecimal;

/** One immutable entry per successful business transaction. */
public record LedgerEntry(String transactionId, String accountId, long sequenceNumber,
                          String transactionType, BigDecimal amount, String currency,
                          BigDecimal balanceAfter) { }
