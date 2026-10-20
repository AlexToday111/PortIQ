# Task 3.1 verification — 2026-10-20

## Implemented scope

Stock instrument metadata only: official T-Invest Core SDK 1.51, equity search, exact ticker resolution with explicit UID selection for ambiguity, complete UID lookup, PostgreSQL persistence, configurable metadata caching/refresh, and owner-authorized `/stock` and `/search` commands. Existing operational commands and dynamic `/help` remain in place. No prices, historical candles, company financials, dividends, portfolios, investment analysis or order operations were implemented.

The module is `market/{application,domain,infrastructure/{tinvest,persistence},telegram}`. Application contracts isolate the SDK and JDBC adapters. V2 adds `instruments` with UID primary key, non-unique ticker/class-code indexes, nullable optional metadata, explicit exchange/currency, source and UTC timestamp. Atomic PostgreSQL upserts update an existing UID and reject older metadata overwrites. See [architecture](ARCHITECTURE.md) for the complete flow and failure policy.

## Executed checks

Environment: Windows, JDK 25.0.4.1, Maven Wrapper 3.9.12, Spring Boot 3.5.16. The official Core 1.51 artifact and source JAR were downloaded from Maven Central; published release metadata identified 1.51 as the stable release. Actual compiled API signatures include SDK `ru.ttech.piapi.core.connector` and generated contracts `ru.tinkoff.piapi.contract.v1`.

- Compile: passed with `release 25`.
- `clean verify` initially passed with **101 unit/configuration tests** and **8 skipped integration tests** while Docker was unavailable. That initial run was not database validation.
- Started the installed Docker Desktop through its CLI. Docker Engine 29.2.1 became available.
- Final `verify`: **BUILD SUCCESS**, **101 unit/configuration tests + 8 PostgreSQL integration tests passed**, zero failures, errors or skips. Integration tests used disposable PostgreSQL 17 Testcontainers; no personal database or real API credentials were used.
- Existing full application tests passed with V1 and V2 migrations, Hibernate validation, UTC session behavior, HTTP health/readiness, hidden operational endpoints and mock Telegram transport.
- `InstrumentPersistenceIT`: two tests passed, covering insert/update, duplicate synchronization, optional SQL nulls, UTC timestamp type, stale-write protection and multiple UIDs sharing ticker/class code.
- SDK adapter tests used real generated blocking stubs over scripted in-memory gRPC responses. They verified share filtering, MOEX board ordering, `FindInstrument`/`ShareBy` requests, missing metadata, deadlines, bounded retries, authentication without retries, rate-limit cooldown and channel shutdown.
- Configuration tests created the actual production SDK client and closed it without sending network requests. Disabled mode created no SDK transport and required no token. Rejected token diagnostics and configuration representations were checked for redaction.
- Command/service tests covered authorized output, unauthorized/group rejection, help discovery, unknown/ambiguous instruments, input validation, bounded output, cache expiry and forced refresh, errors, and source/cache labels.
- Git whitespace checks passed. No real token was entered, logged or added to repository files. Build outputs and downloaded SDK material remain in ignored `target/` and `.tools/`.

Local detailed logs are in ignored `.tools/tinvest/verify-docker.log`; machine-readable results are in `target/surefire-reports` and `target/failsafe-reports`. The packaged JAR is `target/tradelab-portfolio-0.0.1-SNAPSHOT.jar`. Docker Desktop was left running; disposable test containers were managed by Testcontainers.

## Configuration and manual validation

Defaults in `.env.example`:

```text
INVEST_API_ENABLED=false
INVEST_API_TOKEN=
INVEST_METADATA_TTL=24h
INVEST_API_TIMEOUT=3s
INVEST_API_MAX_ATTEMPTS=3
INVEST_API_RETRY_BACKOFF=200ms
```

Issue a **production read-only** token following the [official token guide](https://developer.tbank.ru/invest/intro/intro/token), store it only in private environment configuration, and enable `INVEST_API_ENABLED=true`. Environment is fixed to production `invest-public-api.tbank.ru:443`, with TLS verification and no sandbox fallback. Configure Telegram independently. Follow the [README](../README.md) instructions to import `.env` for local execution or use Docker Compose.

```powershell
.\mvnw.cmd test
.\mvnw.cmd clean verify
```

For this workspace's private tool installation, the executed build also set `JAVA_HOME` to `.tools/jdk/jdk-25.0.4.1+1`, `MAVEN_USER_HOME` to `.tools/maven-home` and used `-Dmaven.repo.local=.tools/repository`. These paths are not required for a normal JDK 25 installation.

Optional owner-private-chat smoke commands after enabling the integration:

```text
/help
/stock SBER
/search SBER
/search Сбер
/stock <UID returned by search>
/stock <UID returned by search> refresh
/ping
/status
```

Repeat the UID lookup to see cached metadata. `refresh` must return fresh metadata or an explicit failure. An ambiguous ticker must return choices instead of an automatically selected instrument. Disabling the integration must make market commands report that access is not configured while operational commands continue working.

**Not executed:** real T-Invest authentication/search/metadata requests or live Telegram connectivity checks. No valid personal read-only token was supplied. Automated mocks do not establish external connectivity.

## Limitations

- Search depends on the provider response and is not a complete exchange directory. `TQ…` class-code ordering is a MOEX equity preference, not a guarantee of listing status. Every returned equity candidate is retained for ambiguity resolution.
- Search responses are short metadata; full persistence occurs after successful `ShareBy`. Missing fields remain unknown, and broker metadata is not company-level business information.
- Trading status and API availability are metadata snapshots and may be cached; there is no live-session/price retrieval or account-specific trading eligibility check.
- Read-only scope must be verified when issuing/configuring the opaque token. The client validates local nonblank input and handles server authentication failures, but does not claim to prove token scope locally.
- Rate limiting fails clearly and establishes a cooldown rather than occupying the polling thread with a long wait. Transient timeouts/outages have bounded exponential retries; authentication errors never retry.

## Changed files

Existing files updated:

- `.env.example`
- `compose.yaml`
- `pom.xml`
- `README.md`
- `docs/ARCHITECTURE.md`
- `src/main/resources/application.yml`
- `src/test/java/ru/ba6kir/tradelab/TradeLabApplicationIT.java`

New production files (relative to `src/main/java/ru/ba6kir/tradelab/market/`):

- `domain/StockInstrument.java`
- `application/StockInstrumentProvider.java`
- `application/InstrumentStore.java`
- `application/InstrumentService.java`
- `application/MarketException.java`
- `infrastructure/tinvest/InvestProperties.java`
- `infrastructure/tinvest/MarketConfiguration.java`
- `infrastructure/tinvest/TInvestStockInstrumentProvider.java`
- `infrastructure/persistence/JdbcInstrumentStore.java`
- `telegram/MarketCommand.java`
- `telegram/MarketTelegramConfiguration.java`

Other new files:

- `src/main/resources/db/migration/V2__create_instruments.sql`
- `src/test/java/ru/ba6kir/tradelab/market/InstrumentServiceTest.java`
- `src/test/java/ru/ba6kir/tradelab/market/MarketCommandTest.java`
- `src/test/java/ru/ba6kir/tradelab/market/MarketConfigurationTest.java`
- `src/test/java/ru/ba6kir/tradelab/market/TInvestProviderTest.java`
- `src/test/java/ru/ba6kir/tradelab/market/InstrumentPersistenceIT.java`
- `docs/MARKET_VERIFICATION.md`
