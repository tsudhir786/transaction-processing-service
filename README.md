# Idempotent Background Transaction Processing Service

A Java 17, Spring Boot command-line application for the Mphasis SDET/FDE L2 practical assessment. It reads a finite transaction batch, orders transactions within each account, applies credits and debits once, retries simulated transient failures, and prints a report. It exits when the batch finishes.

This directory is an independent Maven project. It does not depend on the Personal Finance App in the surrounding checkout. There is no frontend, HTTP server, message broker, or database.

## Build and run

Requirements: JDK 17 and Maven 3.9+. Dependencies are pinned through Spring Boot 4.1.0; JUnit Jupiter is explicitly pinned to **5.14.4** to satisfy the JUnit 5 requirement.

From this directory, with Java 17 and Maven on your PATH:

```text
mvn clean verify
java -jar target/transaction-processing-service-1.0.0.jar
```

Alternatively:

```text
mvn spring-boot:run
```

Exact PowerShell commands for the installed tools on this computer:

```powershell
Set-Location 'C:\Users\tsudh\Projects\personal-finance-app\transaction-processing-service'
$env:JAVA_HOME = 'C:\Users\tsudh\.jdks\temurin-17.0.20'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$transactionMaven = 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.4\plugins\maven-plugin\lib\maven3\bin\mvn.cmd'
& $transactionMaven clean verify
& "$env:JAVA_HOME\bin\java.exe" -jar target/transaction-processing-service-1.0.0.jar
```

Use your own input, disable failure simulation, or force exhaustion:

```text
java -jar target/transaction-processing-service-1.0.0.jar --batch.input=file:./my-input.csv
java -jar target/transaction-processing-service-1.0.0.jar --simulation.failures=0
java -jar target/transaction-processing-service-1.0.0.jar --simulation.failures=10 --processing.max-retries=2
```

`processing.max-retries` counts **additional** attempts: 2 means at most 3 attempts. It accepts 0 through 100. `simulation.transaction-id` defaults to `TXN-2005`; `simulation.failures` defaults to 1. Only the selected transaction is simulated to fail, before commit, on its first configured number of attempts. No sleeps are needed for this deterministic local simulation.

The packaged [sample input](src/main/resources/sample-input.csv) uses seven columns, including currency. This small CSV reader requires the exact header and unquoted comma-separated fields. It trims surrounding whitespace and ignores blank lines. Malformed column counts or numeric syntax reject the entire file **before processing starts**. Well-formed rows with invalid business values receive individual FAILED outcomes. Missing amounts and sequences are passed to validation as null values.

The program exits successfully when it has produced a complete report, including reported row failures. Startup, configuration, unreadable input, or malformed CSV errors fail the process. A scheduler must inspect the report for business failures; process exit code alone does not mean every row succeeded.

## Architecture and important classes

One Maven module contains packages with distinct responsibilities:

```text
batch input -> validation and identity admission -> per-account ordering
            -> attempt / retry -> atomic in-memory commit -> summary
```

| Package / class | Responsibility |
| --- | --- |
| `TransactionApplication` | Starts Spring Boot, wires the small service graph, closes the context after the finite batch. |
| `batch.CsvBatchReader` | Parses the entire batch before any account changes. |
| `batch.BatchInputRunner` | Loads the configured resource and invokes the processor. |
| `model.TransactionRequest` | Raw request and comparison of immutable business payloads. |
| `model.TransactionRecord` | Canonical business transaction status, attempts, retries, and immutable history. |
| `model.RequestOutcome` | Individual input-row result, including rejected and duplicate requests. |
| `model.Account`, `LedgerEntry` | Immutable balances, sequence watermark, and successful effects. |
| `validation.TransactionValidator` | Required fields, positive amounts and sequences, CREDIT/DEBIT, recognized ISO currencies. |
| `ordering.BatchOrdering` | Account grouping and ascending observed sequence order. |
| `processing.BatchProcessor` | Coordinates admission, ordering, attempts, duplicate detection, and row outcomes. |
| `repository.InMemoryStore` | Owns transaction, account, and ledger maps under a shared monitor. Publishes immutable snapshots. |
| `retry.RetryPolicy`, `FailureSimulator` | Bounded retry decisions and deterministic failure injection. |
| `summary.ProcessingSummary`, `ReportFormatter` | Outcome counts and readable report. |

## Idempotency

`transactionId` is the globally unique business identity. `requestId` is delivery metadata and is excluded from business equality. The same transaction arriving under a new request ID is still a duplicate. Reusing a request ID for a different transaction does not merge their business identities.

For an existing transaction ID, compare account, transaction type, numeric amount, currency, and sequence number. `BigDecimal.compareTo` makes `100.0` and `100.00` equivalent. A matching payload receives DUPLICATE with no further processing. A conflicting payload receives FAILED / IDEMPOTENCY_CONFLICT, and the original canonical state is preserved. If the first payload is valid, the first arrival owns the identity; this design cannot determine which conflicting caller is authoritative.

Shape validation happens before reservation. An invalid negative amount therefore does not poison an identity; a corrected valid row may be submitted. After reservation, a transaction reaching terminal FAILED remains reserved. Its matching replay is DUPLICATE / ALREADY_RECEIVED, **not evidence that the original succeeded**. Inspect canonical transaction status or the original report. Replays do not reset an exhausted retry budget.

Duplicate is a **request outcome**, not a replacement for canonical PROCESSED or FAILED state. Canonical histories are retained separately. The ledger is keyed by business transaction ID, and the commit guard also checks processed state. Both admission and commit protection matter; a ledger map alone would not prevent a second balance update.

## Ordering and gaps

This is a finite-batch strategy, not a continuous stream resequencer:

1. Validate and reserve eligible unique transactions before processing.
2. Sort by account ID, then ascending sequence number. Global sequence numbers may be sparse within any account.
3. Process the sequences **present** in the batch. Missing numbers do not produce an indefinite wait. An initially empty account may start at sequence 5, as ACC-203 does in the sample.
4. Each successful commit stores that account's last applied sequence. A new transaction at or below that watermark fails with LATE_SEQUENCE in a later batch. Previously processed identities are recognized as duplicates before this check.
5. Two distinct valid identities cannot claim the same sequence for the same account. The first admitted row owns that slot; the other fails SEQUENCE_CONFLICT. Equal sequence numbers on different accounts are permitted. Reserved slots remain reserved even if the owner later fails permanently.
6. Invalid rows and exhausted transactions are reported and skipped. Later valid rows can continue, including on the same account. This can make a later debit fail for insufficient funds if an earlier credit failed. There is no rollback of the entire batch or automatic resurrection of failed transactions.

For example, sequences `[3, 1]` apply as `[1, 3]`. A later new sequence 2 is rejected, because it can no longer be inserted into account history safely. This is a deliberate tradeoff that must be agreed with the business. PENDING represents the short wait for sorting within the loaded batch; normal completion leaves no pending rows. No missing sequence from one account can prevent another account from completing.

## Retry, money, and atomicity

Only `TransientProcessingException` is retried. Business rejections such as currency mismatch, late sequence, and insufficient funds are permanent. The simulation succeeds after the configured failing attempts, or reports RETRY_EXHAUSTED at the limit.

Normal history:

```text
RECEIVED -> PENDING -> PROCESSING -> PROCESSED
```

Retry history:

```text
RECEIVED -> PENDING -> PROCESSING -> RETRY_PENDING -> PROCESSING -> PROCESSED / FAILED
```

An account starts with zero balance and takes its currency from its first successful transaction. Currency codes are case-sensitive ISO codes recognized by Java's `Currency`. Every subsequent effect must use that currency. Credits add, debits subtract, and overdrafts are rejected. Amounts use exact `BigDecimal` operations without floating point or implicit rounding; currency-specific decimal-place restrictions are intentionally not imposed by this exercise.

The store creates prospective account, ledger, and processed-state maps without modifying the published snapshot. After all business checks and prepublication failure hooks pass, **one snapshot reference replacement** publishes all three changes. A staged failure leaves the previous balance and ledger intact. A transient acknowledgement failure after publication is reconciled against canonical PROCESSED state and returned as successful without another financial effect. This reconciliation does not count as a retry because no additional attempt was made.

All admission and processing for a batch synchronize on the shared store, and snapshot reads also synchronize. The lock prevents two processors sharing this store from claiming an identity concurrently. Immutable published maps and records prevent callers from modifying state. This deliberately serial execution keeps the in-memory proof small; finite immediate retries and a bounded attempt count avoid indefinite waits. It trades throughput for clarity: accounts have independent ordering rules, but threads and account work are serialized. A batch is **not** one transaction: earlier successful effects survive later row failures within the running process.

## Summary and sample result

Counts are per input row. Processed + duplicate + failed + pending equals input rows after normal completion. `retried` counts rows that made an additional attempt, and overlaps success/failure counts. `retryAttempts` counts all additional attempts. A duplicate of a transaction that retried has zero attempts in its own row outcome.

Expected sample result:

```text
SUMMARY processed=4 duplicate=1 failed=1 retried=1 retryAttempts=1 pending=0
ACCOUNT ACC-201 currency=INR balance=85 lastSequence=3
ACCOUNT ACC-203 currency=INR balance=500 lastSequence=5
LEDGER entries=4
```

| Input row | Transaction | Final request outcome |
| --- | --- | --- |
| 0 | TXN-2002 / REQ-2 | PROCESSED |
| 1 | TXN-2001 | PROCESSED |
| 2 | TXN-2002 / REQ-2-DUP | DUPLICATE |
| 3 | TXN-2003 | PROCESSED |
| 4 | TXN-2004 | FAILED: AMOUNT_MUST_BE_POSITIVE |
| 5 | TXN-2005 | PROCESSED: 2 attempts, 1 retry |

ACC-202 is not created. The invalid TXN-2004 is represented in the row report, not admitted to the canonical transaction map. Report rows retain input order; processing follows account sequence order.

## Testing and AI-assisted verification

Run `mvn test` for the automated suite or `mvn clean verify` for a clean compile, tests, and executable packaging. Surefire reports are written under `target/surefire-reports/`.

The 50 JUnit 5 executions cover successful credit/debit; same/new request-ID replays; all conflicting business payload fields; equivalent decimal scales; out-of-order and missing sequences; late arrivals; sequence collisions; validation failures; currency mismatch; insufficient funds; retry success/exhaustion/zero retries; continuation after failures; immutable snapshots; concurrent duplicate submission; prepublication failure atomicity; lost postcommit acknowledgement; CSV errors; and the actual Spring Boot runner with the packaged sample.

Assertions check balances, ledger cardinality, absent failed effects, preserved canonical state, exact failure reasons, attempt budgets, and status histories. Failure hooks test inside the commit boundary, beyond merely checking that a simulated exception was caught. The application test boots the real configuration and validates both final sample balances and exactly four ledger entries. See [verification notes](docs/verification.md) for executed checks.

AI assisted the design, implementation, tests, and documentation. Verification consisted of executing the compiler and JUnit suite on Java 17, checking real packaged application output, and reviewing the rules against the assessment examples. Generated code is not itself proof of correctness. Tests were not weakened to accommodate outcomes. Before presenting this assessment, the candidate should inspect the source and be able to explain the choices below; automated checks do not replace that understanding or an independent review.

## Observability

SLF4J/Logback emits readable key=value events: `retry_scheduled`, `commit_reconciled`, `request_completed`, and `batch_completed`. A generated batch correlation ID plus zero-based row index links retry events to row outcomes. Events include statuses, attempts, retry counts, and fixed reason codes; they omit raw request payloads, amounts, account IDs, and exception messages containing user input.

The explicitly requested console report includes transaction/request IDs, balances, and account IDs for the local assessment. Treat that report as financial data, restrict access if exporting it, and separate it from operational logging in a production deployment. Detailed status history is retained in memory. This demo has no metrics backend, distributed tracing, or REST actuator endpoints.

## Limitations and production hardening

- **Process lifetime only:** balances, ledger, deduplication, failures, and ordering watermarks disappear on restart. Replaying a file in a new process starts from zero. There is no durable exactly-once guarantee.
- **Single JVM:** synchronization is sufficient only for callers using this processor/store in one process. It does not coordinate multiple instances. Store mutation methods are internal collaborators, not a supported public integration API.
- **Finite batch:** no event-time window, persisted pending queue, delayed scheduler, contiguous-stream guarantee, or late-event repair. No automated recovery of permanently failed identities.
- **Resource use:** input and outcomes are fully loaded; canonical state is never evicted; snapshots copy maps. Admission scans existing transactions for sequence collisions. This suits small assessment batches, not high volume.
- **Simulation:** retries are immediate and deterministic. There is no external financial provider, settlement, interest, exchange-rate conversion, reconciliation workflow, or double-entry accounting system.
- **Unexpected infrastructure/programming failure:** it propagates and fails the run; only known transient failures are retried. An abrupt stop may leave a transaction at PROCESSING until the in-memory state disappears. There is no resumable checkpoint.

For production, first agree on identity scope, late/gap policy, retryable error classes, overdraft/precision rules, and recovery semantics. Then use durable transactions covering account state, unique transaction identity, ledger, and sequence ownership; optimistic versioning or account row locks; and unique database constraints as independent protection. Add bounded exponential backoff with jitter, durable retry/dead-letter state, operator-controlled recovery, access control, encrypted persistence, money/range validation, audit retention, metrics/alerts, backup/restore, and crash/multiple-instance tests. An outbox/inbox may be appropriate if external side effects are later introduced. A broker and microservices are not prerequisites for these guarantees.

## Interview discussion checklist

Be prepared to explain:

1. Why business transaction identity differs from delivery request identity, and how a changed payload is detected.
2. Why decimal amount equality ignores scale, and why floating point is unsuitable here.
3. Why sparse per-account sorting satisfies this finite sample, and exactly what happens to a missing sequence arriving later.
4. Why duplicate is a row outcome while the original canonical state remains PROCESSED or FAILED.
5. Where the commit becomes visible, how staging prevents partial effects, and why a lock alone would not undo already-mutated maps.
6. Which errors retry, what “two retries” means, and how an acknowledgement lost after commit differs from a failure before commit.
7. Why failed rows can be skipped, and the business consequence for later debits.
8. Why the single-JVM proof does not survive restarts or extend to multiple service instances.
9. Which assertions exercise the financial guarantees and what additional crash/concurrency tests a durable implementation needs.

No GitHub push is part of this project setup.
