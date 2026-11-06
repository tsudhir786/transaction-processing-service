package com.mphasis.transactions.processing;

public class BusinessRuleException extends RuntimeException {
    public BusinessRuleException(String reasonCode) {
        super(reasonCode);
    }
}
