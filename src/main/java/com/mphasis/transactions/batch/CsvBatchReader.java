package com.mphasis.transactions.batch;

import com.mphasis.transactions.model.TransactionRequest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Deliberately small CSV dialect: exact header, seven unquoted fields, optional blank lines. */
public class CsvBatchReader {
    public static final String HEADER = "transactionId,requestId,accountId,transactionType,amount,currency,sequenceNumber";

    public List<TransactionRequest> read(Reader source) throws IOException {
        var reader = new BufferedReader(source);
        if (!HEADER.equals(reader.readLine())) throw new IllegalArgumentException("Invalid CSV header");
        var requests = new ArrayList<TransactionRequest>();
        String line;
        int lineNumber = 1;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.isBlank()) continue;
            var fields = line.split(",", -1);
            if (fields.length != 7) throw new IllegalArgumentException("Expected seven fields at line " + lineNumber);
            for (int i = 0; i < fields.length; i++) fields[i] = fields[i].trim();
            try {
                requests.add(new TransactionRequest(fields[0], fields[1], fields[2], fields[3],
                        fields[4].isEmpty() ? null : new BigDecimal(fields[4]), fields[5],
                        fields[6].isEmpty() ? null : Long.valueOf(fields[6])));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid numeric value at line " + lineNumber);
            }
        }
        // Parse the complete finite batch before applying any financial effects.
        return List.copyOf(requests);
    }
}
