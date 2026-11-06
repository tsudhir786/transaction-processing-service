package com.mphasis.transactions.retry;

@FunctionalInterface
public interface FailureSimulator {
    enum Stage { BEFORE_COMMIT, AFTER_STAGING, AFTER_COMMIT }

    void check(String transactionId, int attempt, Stage stage);

    static FailureSimulator none() {
        return (id, attempt, stage) -> { };
    }

    static FailureSimulator selected(String selectedId, int failures) {
        if (failures < 0) throw new IllegalArgumentException("failures must be non-negative");
        return (id, attempt, stage) -> {
            if (id.equals(selectedId) && attempt <= failures && stage == Stage.BEFORE_COMMIT) {
                throw new TransientProcessingException();
            }
        };
    }
}
