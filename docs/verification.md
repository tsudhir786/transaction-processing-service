# Executed verification

Verification date: 5 November 2026, Asia/Calcutta.

Environment: Eclipse Temurin Java **17.0.20**, Maven **3.9.16**, Windows 11. Compilation uses `--release 17`. Tests use JUnit Jupiter **5.14.4** with Maven Surefire **3.5.6**.

## Automated build

Executed from the independent project directory using the installed Maven path and Java 17:

```text
mvn test
mvn clean verify
```

Both completed successfully. The final clean verification compiled the source, executed the tests, and produced the executable Spring Boot JAR.

| Suite | Executions | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| BatchProcessorTest | 27 | 0 | 0 | 0 |
| InputAndApplicationTest | 23 | 0 | 0 | 0 |
| Total | 50 | 0 | 0 | 0 |

Parameterized invocations are counted separately. Machine-readable XML and text reports are under `target/surefire-reports/`. The full local final build output is in `verification.log` (ignored by Git).

An initial packaging attempt encountered a sandbox restriction writing a missing dependency to the local Maven cache. After permission to download the packaging dependencies, `clean verify` completed with BUILD SUCCESS. No source compilation or test assertion failures occurred, and no assertions were weakened. IntelliJ's build connector could not build this directory because it was not an open IDE project; the executed Maven clean build is the verification source.

## Executable smoke checks

The following checks run the packaged executable in fresh JVMs, so each starts with empty in-memory state:

```text
java -jar target/transaction-processing-service-1.0.0.jar
java -jar target/transaction-processing-service-1.0.0.jar --simulation.failures=10 --processing.max-retries=2
```

The default run exited with code 0 and is captured in [sample-output.txt](sample-output.txt). Assertions against the captured report passed: four processed, one duplicate, one validation failure, one retried transaction, one retry attempt, no pending rows, balances INR 85 / INR 500, and four ledger entries.

The forced-exhaustion run exited with code 0 and is captured in [retry-exhaustion-output.txt](retry-exhaustion-output.txt). Assertions against the captured report passed: three processed, one duplicate, two failures, one retried transaction, two retry attempts, no pending rows, ACC-201 balance INR 85, no ACC-203 account, and three ledger entries. TXN-2005 ended FAILED / RETRY_EXHAUSTED after exactly three attempts.

The captured output checks are asserted against these report lines after execution. Application process success alone is not used to infer the financial results.
