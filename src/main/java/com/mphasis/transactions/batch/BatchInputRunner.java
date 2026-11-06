package com.mphasis.transactions.batch;

import com.mphasis.transactions.processing.BatchProcessor;
import com.mphasis.transactions.repository.InMemoryStore;
import com.mphasis.transactions.summary.ReportFormatter;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** A background batch worker on the command-line lifecycle; no HTTP server is started. */
public class BatchInputRunner implements CommandLineRunner {
    private final BatchProcessor processor;
    private final InMemoryStore store;
    private final Resource input;

    public BatchInputRunner(BatchProcessor processor, InMemoryStore store, Resource input) {
        this.processor = processor;
        this.store = store;
        this.input = input;
    }

    @Override
    public void run(String... args) throws Exception {
        try (var reader = new InputStreamReader(input.getInputStream(), StandardCharsets.UTF_8)) {
            var summary = processor.process(new CsvBatchReader().read(reader));
            System.out.print(new ReportFormatter().format(summary, store.snapshot()));
        }
    }
}
