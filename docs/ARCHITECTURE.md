# Architecture

TradeLab Portfolio starts as one Spring Boot application and one PostgreSQL database: a modular monolith. A single deployable application keeps local development, transactions, migrations, and operations straightforward for a private assistant. There is no current requirement that justifies distributed services, network coordination, or a message broker.

## Current packages

The base package is `ru.ba6kir.tradelab`:

- `TradeLabApplication` is the bootstrap entry point and enables typed configuration scanning.
- `config` contains the validated immutable `DatabaseProperties` and connection-pool wiring. These classes configure infrastructure and contain no investment logic. Credentials come from the environment, are separate from the JDBC URL, and are redacted in the properties' diagnostic representation.
- `common.metadata` contains the read-only `ApplicationMetadata` JPA mapping for the installation record owned by Flyway. There is no metadata API, service, or repository because the foundation needs none.

The application has no business modules yet. Do not pre-create empty packages, interfaces, adapters, or generic exception hierarchies. Add a feature when it has working behavior and clear ownership. Keep `common` small; it must not become a container for unrelated domain logic.

## Dependency boundaries

Organize future code by feature (`telegram`, `marketdata`, `analytics`, `portfolio`), rather than putting all controllers, services, and repositories into application-wide layer packages. Within a feature, introduce application/domain/adapter subpackages only when their contents justify them.

Use constructor injection. Domain rules and application use cases must be independent of Telegram SDKs, broker SDKs, HTTP clients, and Spring configuration. Define a provider interface in the feature that consumes the capability once that integration is implemented; place provider-specific code in an adapter implementing it. Do not create empty provider interfaces now.

Features should communicate through small application-facing contracts, not another feature's repositories or persistence entities. Dependencies should point toward domain/use-case code; infrastructure implements those needs. Keep dependencies acyclic. Add events only when a concrete interaction requires them. Cross-feature transactions can remain local database transactions while the application is a monolith.

## Persistence and operations

Flyway is the only schema owner. All changes are versioned SQL migrations under `src/main/resources/db/migration`. Hibernate validates the schema on startup and never mutates it. The V1 installation record proves that migrations ran and is read-only through JPA. Future features own their tables and their persistence mappings.

Use `Instant` and PostgreSQL `TIMESTAMPTZ` for event timestamps. JDBC sessions and Hibernate use UTC. Presentation time zones belong at the user-facing boundary. Future financial values use `BigDecimal`, declared currency/unit semantics, deliberate rounding rules, and `NUMERIC` columns with explicit precision and scale; never binary floating-point money.

Actuator exposes health and info only. Database health participates in overall health and readiness; liveness remains independent of database availability. The JSON console log format is consistent across local and container runs. Do not log tokens, passwords, or sensitive connection material. Spring Boot performs graceful HTTP shutdown; Compose allows it to complete before termination.

## Future features

1. **Telegram:** add a thin inbound adapter translating authorized private-user commands into application use cases and formatting results. Keep communication, presentation time zones, and command handling outside investment rules. Introduce access controls as part of that feature.
2. **Market data:** define the consuming feature's data-provider contract and implement a concrete MOEX or broker adapter with explicit timeouts, error handling, and tests. Store data through feature-owned migrations. Do not let provider transport objects become shared domain types.
3. **Analytics:** implement deterministic, testable calculations over clearly defined domain inputs. Separate calculations from fetching data and message formatting. Document assumptions and rounding.
4. **Portfolio:** introduce holdings and transaction use cases, precise money types, and persistence in its own feature. Connect to analytics through application contracts rather than repository sharing.

These are extension directions, not implemented functionality. Revisit process separation only when measured scaling, operational isolation, or ownership requirements justify the added cost. The current foundation adds no Telegram/broker integration, market data, investment advice, AI, or trading capability.

## Verification

Unit tests cover credential-free JDBC URL construction and redaction. Configuration tests bind the actual YAML/environment placeholders, reject invalid database settings, and check pool settings without opening connections. The integration suite starts the full web application with PostgreSQL 17 via Testcontainers and verifies migrations, JPA schema validation, UTC behavior, migration repeatability, health/readiness, and endpoint exposure. Container tests explicitly skip when Docker is unavailable and must not be described as executed in that case.
