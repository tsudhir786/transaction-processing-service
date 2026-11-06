package com.mphasis.transactions.ordering;

import com.mphasis.transactions.model.TransactionRequest;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** Finite-batch policy: sort observed sequences per account; absent numbers do not block. */
public class BatchOrdering {
    public List<TransactionRequest> order(Collection<TransactionRequest> requests) {
        return requests.stream().sorted(Comparator.comparing(TransactionRequest::accountId)
                .thenComparing(TransactionRequest::sequenceNumber)).toList();
    }
}
