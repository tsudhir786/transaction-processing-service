package com.mphasis.transactions.model;

import java.math.BigDecimal;

public record Account(String accountId, String currency, BigDecimal balance, long lastSequence) { }
