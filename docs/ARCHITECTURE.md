# Architecture

TradeLab Portfolio starts as one Spring Boot application and one PostgreSQL database: a modular monolith. A single deployable application keeps local development, transactions, migrations, and operations straightforward for a private assistant. There is no current requirement that justifies distributed services, network coordination, or a message broker.

## Current packages

The base package is `ru.ba6kir.tradelab`:

- `TradeLabApplication` is the bootstrap entry point and enables typed configuration scanning.
- `config` contains the validated immutable `DatabaseProperties` and connection-pool wiring. These classes configure infrastructure and contain no investment logic. Credentials come from the environment, are separate from the JDBC URL, and are redacted in the properties' diagnostic representation.
- `common.metadata` contains the read-only `ApplicationMetadata` JPA mapping for the installation record owned by Flyway. There is no metadata API, service, or repository because the foundation needs none.
- `telegram` is an independent feature for private command access and operational status; its adapters do not implement investment logic.

The application has no investment business modules yet. Do not pre-create empty packages, interfaces, adapters, or generic exception hierarchies. Add a feature when it has working behavior and clear ownership. Keep `common` small; it must not become a container for unrelated domain logic.

## Telegram feature

The TelegramBots 10.3.0 long-polling starter/client are pinned together and compiled against the existing Java 25 / Spring Boot 3.5.16 application. The SDK starter's automatic initializer is explicitly excluded: `TelegramBotConfiguration` creates feature beans only when `tradelab.telegram.enabled=true`. `TelegramProperties` binds environment-backed values and validates mandatory credentials only when enabled. Constructor validation and a redacted diagnostic representation prevent rejected bot tokens from appearing in binding errors. Disabling the feature creates no Telegram transport, polling session, commands, or polling executor and requires no token/owner ID.

The inbound path is `TelegramUpdateConsumer → TelegramCommandDispatcher → TelegramAccessGuard → registered TelegramCommand`. The guard is inside the dispatcher's public entry point, before parsing and handler lookup. It requires an enabled feature, the numeric owner ID, a non-bot sender, a matching private chat ID/type, and no sender-chat impersonation. Missing identity fields fail closed. Only regular message updates are considered. Unauthorized updates receive no response. The outbound sender additionally restricts recipients to the same owner ID.

Each command bean implements `TelegramCommand`, exposing its name, Russian help description, optional argument support, and a plain-text response. Handlers receive arguments, not raw updates, identities, chat IDs, or transport clients; future registered commands cannot accidentally skip dispatcher authorization. Registration rejects duplicate names. `/help` derives its contents lazily from registered command beans. The dispatcher normalizes whitespace/case, validates syntax and arguments, checks bot-name suffixes against the identity returned by `getMe`, and executes at most one handler per update. It never echoes raw command input or logs arguments.

`/start`, `/help`, and `/ping` provide basic interaction. `/status` delegates to `TelegramStatusService`, which reads Spring application readiness, performs `SELECT 1`, uses an injectable UTC clock, and reports scheduled polling state. It returns only safe availability labels; JDBC exception details and internal addresses stay private. This does not add an investment use case or database schema change.

`TelegramPollingLifecycle` is a `SmartLifecycle` bean. After database/JPA initialization, it validates remote bot identity through the SDK, registers an SDK polling session requesting only message updates, and owns shutdown. One managed scheduled thread processes updates sequentially with no extra update queue. HTTP concurrency is limited, connect/read/call timeouts are finite, and polling failures back off to at most 60 seconds. Shutdown closes sessions, cancels HTTP calls, interrupts/awaits the polling executor, and closes HTTP resources. The SDK's default/static update executors are not used.

SDK logging is suppressed because upstream exceptions can contain token-bearing URLs. The adapter emits sanitized startup, stop, polling-failure, delivery-failure, and known-command execution events, without raw exceptions, message contents, credentials, usernames, or chat IDs. Registration failures discard secret-bearing causes; malformed updates and command failures are contained individually. Messages use plain text and are split without breaking UTF-16 surrogate pairs. Sends are not automatically retried; polling offsets follow SDK semantics rather than durable exactly-once delivery. A process crash may replay an update or lose a response, so future mutating business commands must introduce explicit idempotency before performing side effects.

## Dependency boundaries

Organize future code by feature (`telegram`, `marketdata`, `analytics`, `portfolio`), rather than putting all controllers, services, and repositories into application-wide layer packages. Within a feature, introduce application/domain/adapter subpackages only when their contents justify them.

Use constructor injection. Domain rules and application use cases must be independent of Telegram SDKs, broker SDKs, HTTP clients, and Spring configuration. Define a provider interface in the feature that consumes the capability once that integration is implemented; place provider-specific code in an adapter implementing it. Do not create empty provider interfaces now.

Features should communicate through small application-facing contracts, not another feature's repositories or persistence entities. Dependencies should point toward domain/use-case code; infrastructure implements those needs. Keep dependencies acyclic. Add events only when a concrete interaction requires them. Cross-feature transactions can remain local database transactions while the application is a monolith.

## Persistence and operations

Flyway is the only schema owner. All changes are versioned SQL migrations under `src/main/resources/db/migration`. Hibernate validates the schema on startup and never mutates it. The V1 installation record proves that migrations ran and is read-only through JPA. Future features own their tables and their persistence mappings.

Use `Instant` and PostgreSQL `TIMESTAMPTZ` for event timestamps. JDBC sessions and Hibernate use UTC. Presentation time zones belong at the user-facing boundary. Future financial values use `BigDecimal`, declared currency/unit semantics, deliberate rounding rules, and `NUMERIC` columns with explicit precision and scale; never binary floating-point money.

Actuator exposes health and info only. Database health participates in overall health and readiness; liveness remains independent of database availability. The JSON console log format is consistent across local and container runs. Do not log tokens, passwords, or sensitive connection material. Spring Boot performs graceful HTTP shutdown; Compose allows it to complete before termination.

## Future features

1. **Telegram commands:** extend the existing authenticated dispatcher with thin handlers calling application use cases. Keep presentation time zones and message formatting outside investment rules; preserve the common authorization gate and add idempotency before introducing mutations.
2. **Market data:** define the consuming feature's data-provider contract and implement a concrete MOEX or broker adapter with explicit timeouts, error handling, and tests. Store data through feature-owned migrations. Do not let provider transport objects become shared domain types.
3. **Analytics:** implement deterministic, testable calculations over clearly defined domain inputs. Separate calculations from fetching data and message formatting. Document assumptions and rounding.
4. **Portfolio:** introduce holdings and transaction use cases, precise money types, and persistence in its own feature. Connect to analytics through application contracts rather than repository sharing.

Investment modules are extension directions, not implemented functionality. Revisit process separation only when measured scaling, operational isolation, or ownership requirements justify the added cost. The system currently adds no broker integration, market data, investment advice, AI, or trading capability.

## Verification

Unit tests cover credential-free JDBC URL construction and redaction. Configuration tests bind the actual YAML/environment placeholders, reject invalid database settings, and check pool settings without opening connections. The integration suite starts the full web application with PostgreSQL 17 via Testcontainers and verifies migrations, JPA schema validation, UTC behavior, migration repeatability, health/readiness, and endpoint exposure. Container tests explicitly skip when Docker is unavailable and must not be described as executed in that case.

Telegram tests use mock transport exclusively. They cover owner-only/private-chat authorization and missing identities, future-handler authorization, all four commands, unknown/malformed input, whitespace/arguments/suffixes, duplicate registrations, disabled startup, enabled validation and registration, long Unicode replies, sanitized transport failures, per-update isolation, and resource cleanup. An enabled full-application test combines mock Telegram transport with a real PostgreSQL Testcontainer. No test proves live Telegram connectivity or needs a real token.
