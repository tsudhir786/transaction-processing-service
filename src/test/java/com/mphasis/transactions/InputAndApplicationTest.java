package com.mphasis.transactions;

import com.mphasis.transactions.batch.CsvBatchReader;
import com.mphasis.transactions.model.TransactionRequest;
import com.mphasis.transactions.repository.InMemoryStore;
import com.mphasis.transactions.retry.RetryPolicy;
import com.mphasis.transactions.validation.TransactionValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.io.StringReader;
import java.math.BigDecimal;
import java.util.stream.Stream;

import static com.mphasis.transactions.model.ProcessingStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class InputAndApplicationTest {
    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of(null, "REQUEST_REQUIRED"),
                Arguments.of(new TransactionRequest(null, "R", "A", "CREDIT", BigDecimal.ONE, "INR", 1L), "TRANSACTION_ID_REQUIRED"),
                Arguments.of(new TransactionRequest(" ", "R", "A", "CREDIT", BigDecimal.ONE, "INR", 1L), "TRANSACTION_ID_REQUIRED"),
                Arguments.of(new TransactionRequest("T", "", "A", "CREDIT", BigDecimal.ONE, "INR", 1L), "REQUEST_ID_REQUIRED"),
                Arguments.of(new TransactionRequest("T", "R", null, "CREDIT", BigDecimal.ONE, "INR", 1L), "ACCOUNT_ID_REQUIRED"),
                Arguments.of(new TransactionRequest("T", "R", "A", "TRANSFER", BigDecimal.ONE, "INR", 1L), "UNSUPPORTED_TRANSACTION_TYPE"),
                Arguments.of(new TransactionRequest("T", "R", "A", null, BigDecimal.ONE, "INR", 1L), "UNSUPPORTED_TRANSACTION_TYPE"),
                Arguments.of(new TransactionRequest("T", "R", "A", "CREDIT", null, "INR", 1L), "AMOUNT_MUST_BE_POSITIVE"),
                Arguments.of(new TransactionRequest("T", "R", "A", "CREDIT", BigDecimal.ZERO, "INR", 1L), "AMOUNT_MUST_BE_POSITIVE"),
                Arguments.of(new TransactionRequest("T", "R", "A", "CREDIT", BigDecimal.ONE, null, 1L), "UNSUPPORTED_CURRENCY"),
                Arguments.of(new TransactionRequest("T", "R", "A", "CREDIT", BigDecimal.ONE, "ZZZ", 1L), "UNSUPPORTED_CURRENCY"),
                Arguments.of(new TransactionRequest("T", "R", "A", "CREDIT", BigDecimal.ONE, "inr", 1L), "UNSUPPORTED_CURRENCY"),
                Arguments.of(new TransactionRequest("T", "R", "A", "CREDIT", BigDecimal.ONE, "INR", null), "SEQUENCE_MUST_BE_POSITIVE"),
                Arguments.of(new TransactionRequest("T", "R", "A", "CREDIT", BigDecimal.ONE, "INR", 0L), "SEQUENCE_MUST_BE_POSITIVE"),
                Arguments.of(new TransactionRequest("T", "R", "A", "CREDIT", BigDecimal.ONE, "INR", -1L), "SEQUENCE_MUST_BE_POSITIVE")
        );
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void requiredFieldsAndSupportedValuesHaveExplicitValidationReasons(TransactionRequest request, String reason) {
        assertEquals(reason, new TransactionValidator().validate(request));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 101})
    void rejectsInvalidRetryConfiguration(int retries) {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(retries));
    }

    @Test
    void csvPreservesInvalidBusinessRowsForValidation() throws Exception {
        var rows = new CsvBatchReader().read(new StringReader(CsvBatchReader.HEADER
                + "\nT1,R1,A,DEBIT,-10,INR,4\n\nT2,R2,B,TRANSFER,20,USD,5\n"));
        assertEquals(2, rows.size());
        assertEquals("AMOUNT_MUST_BE_POSITIVE", new TransactionValidator().validate(rows.get(0)));
        assertEquals("UNSUPPORTED_TRANSACTION_TYPE", new TransactionValidator().validate(rows.get(1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-header\n", "transactionId,requestId,accountId,transactionType,amount,currency,sequenceNumber\nT1,R1,A",
            "transactionId,requestId,accountId,transactionType,amount,currency,sequenceNumber\nT1,R1,A,CREDIT,abc,INR,1",
            "transactionId,requestId,accountId,transactionType,amount,currency,sequenceNumber\nT1,R1,A,CREDIT,1,INR,abc"})
    void malformedCsvFailsBeforeBatchExecution(String csv) {
        assertThrows(IllegalArgumentException.class, () -> new CsvBatchReader().read(new StringReader(csv)));
    }

    @Test
    void actualSpringBootRunnerProcessesPackagedSampleWithExpectedFinancialOutcomes() {
        try (var context = new SpringApplicationBuilder(TransactionApplication.class).run()) {
            var snapshot = context.getBean(InMemoryStore.class).snapshot();
            assertEquals(0, new BigDecimal("85").compareTo(snapshot.accounts().get("ACC-201").balance()));
            assertEquals(0, new BigDecimal("500").compareTo(snapshot.accounts().get("ACC-203").balance()));
            assertFalse(snapshot.accounts().containsKey("ACC-202"));
            assertFalse(snapshot.transactions().containsKey("TXN-2004"));
            assertEquals(4, snapshot.ledger().size());
            assertEquals(4, snapshot.transactions().size());
            assertTrue(snapshot.transactions().values().stream().allMatch(t -> t.status() == PROCESSED));
            assertEquals(2, snapshot.transactions().get("TXN-2005").attempts());
            assertEquals(1, snapshot.transactions().get("TXN-2005").retries());
        }
    }
}
