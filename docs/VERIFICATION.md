# Foundation verification — 20 October 2026

These checks were actually executed on this implementation. This is a dated verification record, not a substitute for running checks after future changes.

## Build and automated tests

- Windows host: Eclipse Temurin JDK 25.0.4.1, Apache Maven 3.9.12 through the official Maven Wrapper 3.3.4.
- Spring Boot 3.5.16 parent and managed dependencies resolved successfully from Maven Central. Maven and JDK downloads were checksum-verified.
- `mvnw.cmd clean verify`: **BUILD SUCCESS**, compiling with release 25 and producing the executable JAR.

| Suite | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| `DatabaseConfigurationTest` | 11 | 0 | 0 | 0 |
| `DatabasePropertiesTest` | 4 | 0 | 0 | 0 |
| `TradeLabApplicationIT` | 5 | 0 | 0 | 0 |
| **Total** | **20** | **0** | **0** | **0** |

Docker was initially unavailable, so the first run explicitly skipped the five integration tests. After starting Docker Desktop, a clean verification executed all five successfully against the PostgreSQL 17 Testcontainer. The table above reflects that completed run. Reports are generated under `target/surefire-reports` and `target/failsafe-reports`.

## Runtime checks

- Compose configuration validated successfully.
- Final multi-stage image built successfully on Linux with Java 25 and the checksum-pinned Maven Wrapper. The build image includes `unzip` to keep the wrapper on the pinned ZIP distribution.
- Full stack started with `docker compose -p tradelab-foundation-verify --profile app up -d --build --wait`, using temporary environment values, host HTTP port 18080, and host PostgreSQL port 15432. A pre-existing application occupied 8080 and was left running.
- Both application and PostgreSQL containers reported healthy. The application ran as the non-root `tradelab` user.
- `/actuator/health` and `/actuator/health/readiness` returned HTTP 200 with `status=UP`.
- PostgreSQL contained a successful Flyway V1 history entry and the installation metadata row with a UTC timestamp.
- The packaged JAR also started locally with JDK 25 against that PostgreSQL instance and returned readiness `UP` on port 18081.
- Stopping only the disposable database changed application readiness to HTTP 503 while liveness stayed `UP`. Restarting the database restored readiness to `UP` and preserved its metadata row.
- Stopping the application container produced the `Graceful shutdown complete` log message.
- Running the packaged JAR with an empty database password failed startup with `DB_PASSWORD must be set`.
- Project files were checked for common real-secret patterns; build/download caches, logs, and temporary credentials are excluded from Git and the Docker context.

The local smoke-test Java process, isolated Compose containers, network, temporary database volume, and temporary credential file were removed after verification. Existing application containers and volumes were left intact. Downloaded build tools remain in the ignored `.tools` directory. The host's default Java installation remains Java 17; local development requires setting `JAVA_HOME` to a JDK 25, while the Docker workflow supplies Java 25 itself.
