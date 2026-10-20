# Telegram integration verification — 20 October 2026

The checks below were actually executed on Java 25.0.4.1 with Maven Wrapper 3.3.4 / Maven 3.9.12, Spring Boot 3.5.16, TelegramBots 10.3.0, and PostgreSQL 17 Testcontainers.

TelegramBots' published POMs and source JARs were inspected from Maven Central before implementation. The starter targets Spring Boot 3.5.x; the project continues using its existing Boot parent and managed dependencies. Current `TelegramBotsLongPollingApplication`, `LongPollingUpdateConsumer`, `GetMe`, `GetUpdates`, `SendMessage`, and `OkHttpTelegramClient` APIs compiled and were used without legacy `TelegramLongPollingBot` examples.

## Automated tests

Final `mvnw.cmd verify`: **BUILD SUCCESS**.

| Suite | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Existing database configuration and properties tests | 15 | 0 | 0 | 0 |
| `TelegramAccessGuardTest` | 18 | 0 | 0 | 0 |
| `TelegramCommandDispatcherTest` | 23 | 0 | 0 | 0 |
| `TelegramConfigurationTest` | 9 | 0 | 0 | 0 |
| `TelegramMessageSenderTest` | 3 | 0 | 0 | 0 |
| `TelegramPollingLifecycleTest` | 3 | 0 | 0 | 0 |
| `TelegramUpdateConsumerTest` | 1 | 0 | 0 | 0 |
| `TelegramStatusServiceTest` | 2 | 0 | 0 | 0 |
| Existing `TradeLabApplicationIT` | 5 | 0 | 0 | 0 |
| `TelegramEnabledApplicationIT` | 1 | 0 | 0 | 0 |
| **Total** | **80** | **0** | **0** | **0** |

The total comprises 74 unit/configuration tests and 6 database integration tests. Reports are generated under `target/surefire-reports` and `target/failsafe-reports`.

Telegram transports were mocked throughout. The enabled full-application test used real PostgreSQL and mock bot identity/session/message delivery to verify application readiness, database connectivity, and `/status` dispatch together. Existing disabled application integration tests still passed, including migrations, JPA validation, UTC behavior, and restricted Actuator exposure. No schema migration was added or changed.

## Runtime and packaging

- Updated multi-stage Docker image built successfully on Java 25.
- An isolated Compose project, `tradelab-telegram-verify`, started with Telegram disabled and no Telegram credentials, on host ports 18080 and 15432. Both containers became healthy.
- `/actuator/health` and `/actuator/health/readiness` returned `UP`; PostgreSQL retained the successful V1 migration history entry.
- The executable JAR rejected an invalid synthetic Telegram token with a clear `TELEGRAM_BOT_TOKEN` startup error. The supplied token value did not appear in captured startup logs.
- Lifecycle tests verified registration, safe failure handling, session cleanup, HTTP executor shutdown, and polling executor shutdown. Message transport failures and malformed updates were isolated and did not disclose exception details.

**Live Telegram connectivity was not verified:** no real bot token/owner session was supplied. Mock registration and delivery do not prove that a real BotFather token is active or that the deployment can reach Telegram. Follow the README setup and command checklist with the owner's private chat to perform that final live check.

The isolated smoke-test containers, network, database volume, and temporary credential file were cleaned up. Existing containers and data were left untouched. The default host JDK remains Java 17; set `JAVA_HOME` to JDK 25 for local Maven/Java execution, or use the Docker workflow.
