package com.mphasis.transactions.validation;

import com.mphasis.transactions.model.TransactionRequest;

import java.util.Currency;

public class TransactionValidator {
    /** Return stable reason codes, never user-supplied text or amounts. */
    public String validate(TransactionRequest request) {
        if (request == null) return "REQUEST_REQUIRED";
        if (blank(request.transactionId())) return "TRANSACTION_ID_REQUIRED";
        if (blank(request.requestId())) return "REQUEST_ID_REQUIRED";
        if (blank(request.accountId())) return "ACCOUNT_ID_REQUIRED";
        if (!"CREDIT".equals(request.transactionType()) && !"DEBIT".equals(request.transactionType())) {
            return "UNSUPPORTED_TRANSACTION_TYPE";
        }
        if (request.amount() == null || request.amount().signum() <= 0) return "AMOUNT_MUST_BE_POSITIVE";
        if (request.sequenceNumber() == null || request.sequenceNumber() <= 0) return "SEQUENCE_MUST_BE_POSITIVE";
        try {
            if (request.currency() == null || request.currency().length() != 3) return "UNSUPPORTED_CURRENCY";
            Currency.getInstance(request.currency());
        } catch (IllegalArgumentException ex) {
            return "UNSUPPORTED_CURRENCY";
        }
        return null;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
