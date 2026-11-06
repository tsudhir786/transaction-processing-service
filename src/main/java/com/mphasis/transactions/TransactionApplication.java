package com.mphasis.transactions;

import com.mphasis.transactions.batch.BatchInputRunner;
import com.mphasis.transactions.ordering.BatchOrdering;
import com.mphasis.transactions.processing.BatchProcessor;
import com.mphasis.transactions.repository.InMemoryStore;
import com.mphasis.transactions.retry.FailureSimulator;
import com.mphasis.transactions.retry.RetryPolicy;
import com.mphasis.transactions.validation.TransactionValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;

@SpringBootApplication
public class TransactionApplication {
    public static void main(String[] args) {
        try (var context = SpringApplication.run(TransactionApplication.class, args)) {
            // The finite batch has completed when SpringApplication.run returns.
        }
    }

    @Bean
    InMemoryStore store() { return new InMemoryStore(); }

    @Bean
    BatchProcessor processor(InMemoryStore store,
                             @Value("${processing.max-retries:2}") int maxRetries,
                             @Value("${simulation.transaction-id:TXN-2005}") String selectedId,
                             @Value("${simulation.failures:1}") int failures) {
        return new BatchProcessor(store, new TransactionValidator(), new BatchOrdering(),
                new RetryPolicy(maxRetries), FailureSimulator.selected(selectedId, failures));
    }

    @Bean
    @ConditionalOnProperty(name = "app.run-batch", havingValue = "true", matchIfMissing = true)
    BatchInputRunner runner(BatchProcessor processor, InMemoryStore store,
                           @Value("${batch.input:classpath:sample-input.csv}") Resource input) {
        return new BatchInputRunner(processor, store, input);
    }
}
