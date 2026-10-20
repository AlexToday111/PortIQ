# TradeLab Portfolio

TradeLab Portfolio is a private Telegram assistant being developed for long-term analysis of Russian equities listed on MOEX. It currently provides a runnable Java application, PostgreSQL persistence, versioned migrations, health monitoring, and an optional private Telegram bot with basic operational commands. Broker APIs, market data, portfolio tracking, analysis, and allocation recommendations are outside the implemented scope.

## Stack and prerequisites

- JDK **25** (set `JAVA_HOME` to the JDK, not a JRE).
- Spring Boot **3.5.16**, Spring Data JPA, Bean Validation, Actuator, and Flyway.
- PostgreSQL **17**.
- TelegramBots **10.3.0** long-polling starter and client; the feature owns conditional registration and shutdown. Its [published starter POM](https://repo.maven.apache.org/maven2/org/telegram/telegrambots-springboot-longpolling-starter/10.3.0/telegrambots-springboot-longpolling-starter-10.3.0.pom) targets Spring Boot 3.5.x; the application retains Boot 3.5.16 dependency management.
- Maven **3.9.12**, downloaded and checksum-verified by Maven Wrapper **3.3.4**; no global Maven installation is necessary.
- JUnit 5, AssertJ, Mockito (through Spring Boot's test starter), and Testcontainers.
- Docker Engine / Docker Desktop with Linux containers and Docker Compose v2 or later. Docker must be running for integration tests and container workflows.
- Internet access on the first build for Maven, dependencies, and container images. Ports 5432 and 8080 must be available, or change the bindings described below.
- On Linux/macOS: `unzip` and `sha256sum` or `shasum`, so the wrapper downloads and verifies the pinned Maven ZIP. The Docker build installs `unzip` itself.

Spring Boot manages dependency versions. Java 25 compatibility is documented in the [Spring Boot system requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html). The wrapper comes from the [official Apache Maven Wrapper distribution](https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper-distribution/3.3.4/).

## Configuration

Copy the example and replace its non-sensitive password example in the untracked `.env` file:

```powershell
Copy-Item .env.example .env
notepad .env
```

On Bash, use `cp .env.example .env` and your preferred editor. Never commit real passwords or tokens. `.env`, tool downloads, build outputs, and logs are ignored by Git and excluded from Docker builds.

| Variable | Purpose | Example |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | Local development defaults | `local` |
| `DB_HOST` | Database hostname, IPv4, or bracketed IPv6 | `localhost` |
| `DB_PORT` | Database port (1–65535) | `5432` |
| `DB_NAME` | Database name; letters, digits, underscores | `tradelab` |
| `DB_USERNAME` | Database login | `tradelab` |
| `DB_PASSWORD` | Mandatory, nonblank password | Set privately |
| `TELEGRAM_ENABLED` | Enable the private bot; defaults to false | `false` |
| `TELEGRAM_BOT_TOKEN` | BotFather token; mandatory only when enabled | Leave empty when disabled |
| `TELEGRAM_OWNER_ID` | Positive numeric Telegram User ID; mandatory only when enabled | Your own numeric ID |

The local profile defaults host, port, database name, and username; it never supplies a password. Without the local profile, host, name, username, and password must be supplied. The default port is 5432. Typed configuration rejects invalid values before a database connection is opened. Credentials are passed separately from the JDBC URL.

Docker Compose reads `.env` automatically. **Spring Boot and Maven do not read `.env` automatically.** Import its values into the current terminal before running locally. The following PowerShell importer handles the simple `KEY=value` format used by the example and preserves `=` inside values; use unquoted values in this file:

```powershell
Get-Content .env | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#')) {
        $pair = $line.Split('=', 2)
        [Environment]::SetEnvironmentVariable($pair[0].Trim(), $pair[1], 'Process')
    }
}
```

Bash alternative (use shell-compatible values and quote any shell metacharacters):

```bash
set -a
. ./.env
set +a
```

Telegram credentials are unnecessary while `TELEGRAM_ENABLED=false`, including for automated tests. Broker credentials are never required. When Telegram is enabled, token syntax and owner ID are validated before registration; remote token validity is checked with `getMe` during startup. Tokens are redacted in configuration diagnostics.

## Set up the private Telegram bot

1. Open the official [@BotFather](https://t.me/BotFather), send `/newbot`, and follow its prompts. See Telegram's [BotFather documentation](https://core.telegram.org/bots/features#botfather). Keep the resulting token private, set `TELEGRAM_BOT_TOKEN` in untracked `.env`, and leave `TELEGRAM_ENABLED=false` during setup.
2. Obtain **your Telegram User ID**, not your username or the bot's ID. You can read `message.from.id` from an update sent to your own bot using the official [getUpdates API](https://core.telegram.org/bots/api#getupdates). Stop any other polling instance first, open a private chat with your new bot, and send exactly `tradelab-owner-setup`.
3. After importing `.env` into PowerShell as shown above, run this one-off setup helper. It prints only matching numeric sender IDs and never prints the token, request URL, or full updates. Do not run it alongside the application:

```powershell
try {
    $requestUri = "https://api.telegram.org/bot$($env:TELEGRAM_BOT_TOKEN)/getUpdates"
    $reply = Invoke-RestMethod -Method Post -Uri $requestUri `
        -ContentType 'application/json' -Body '{"allowed_updates":["message"],"timeout":0}'
    $reply.result | Where-Object {
        $_.message.text -eq 'tradelab-owner-setup' -and $_.message.chat.type -eq 'private'
    } | ForEach-Object { $_.message.from.id } | Sort-Object -Unique
} catch {
    Write-Error 'Cannot read Telegram updates. Check the token, network, and other polling instances.'
} finally {
    Remove-Variable requestUri -ErrorAction SilentlyContinue
}
```

4. Set `TELEGRAM_OWNER_ID` to the ID from **your own** setup message and set `TELEGRAM_ENABLED=true`. Reimport `.env` before local Java execution. IDs use Java `Long`, so IDs exceeding 32 bits are supported. Authorization never uses a username.
5. Start PostgreSQL and the application using either workflow below. For Compose, the three Telegram variables are passed explicitly to the app service. Restart/recreate the application after changing configuration. Run exactly one polling instance per token. No webhook or public inbound Telegram port is needed; library registration clears an existing webhook.

Check these commands from the owner's private chat:

| Command | Expected behavior |
| --- | --- |
| `/start` | Russian welcome message introducing the personal assistant |
| `/help` | Lists only the four registered commands and their descriptions |
| `/ping` | Exactly `TradeLab Portfolio: online` |
| `/status` | Application readiness, database connectivity, current UTC time, and polling state |
| `/unknown` | Short Russian message pointing to `/help` |

Command names are case-insensitive. Extra whitespace is accepted, including `/PING@YourBotName`; a suffix naming another bot is ignored. The current four commands take no arguments and report a readable validation error for extra input. Future handlers can opt into argument lists through `TelegramCommand.acceptsArguments()`. Ordinary text and non-message updates are ignored.

Only the configured owner in a matching private chat can execute commands or receive responses. Unauthorized users, group/supergroup/channel chats, missing identities, and ambiguous sender information are silently ignored. The bot does not echo input or disclose internal hosts, connection strings, or exception details. Replies use plain text, split at 4096 UTF-16 units without cutting surrogate pairs. Failed sends are logged generically and are not automatically retried, avoiding duplicate replies.

`/status` reports whether the polling session is scheduled, **not** continuous end-to-end Telegram connectivity. Polling network failures use bounded exponential backoff with safe warnings. SDK exception logging is disabled because its URLs can contain tokens. Do not override that setting or enable HTTP wire logging. Polling sessions, active HTTP calls, and their executors are closed on application shutdown.

## Run PostgreSQL and Spring Boot locally

After creating `.env` and importing it into the current terminal:

```powershell
docker compose up -d --wait postgres
.\mvnw.cmd -version
.\mvnw.cmd spring-boot:run
```

On Bash, run `chmod +x mvnw` once, then use `./mvnw` instead of `.\mvnw.cmd`. Maven enforces JDK 25 and compiles with release 25. Stop the application with Ctrl+C; Spring finishes active requests and closes the connection pool gracefully.

Alternatively, build and run the executable JAR in the same configured terminal:

```powershell
.\mvnw.cmd clean verify
java -jar target/tradelab-portfolio-0.0.1-SNAPSHOT.jar
```

## Run the full Docker stack

Only Docker is needed on the host for this workflow. Do not leave the locally running application occupying port 8080.

```powershell
docker compose --profile app up -d --build --wait
docker compose --profile app ps
docker compose logs -f app
```

Compose replaces `DB_HOST` with the service name `postgres` and uses the container port 5432 for the application. The application waits for the PostgreSQL healthcheck; its own image healthcheck uses database readiness. The multi-stage image uses Eclipse Temurin Java 25 and runs the application as an unprivileged user. Image builds package the code with tests skipped; run `verify` separately to execute tests.

PostgreSQL and application ports bind to loopback for private local access. `DB_PORT` changes the host PostgreSQL port; set optional `APP_PORT=18080` in `.env` if 8080 is already occupied. The application still listens on port 8080 inside its container. PostgreSQL data persists in the named `postgres-data` volume. Stop the stack without deleting data:

```powershell
docker compose --profile app down
```

Deleting the named volume destroys the database. Do so only when intentionally resetting a disposable development database. PostgreSQL initialization variables apply when the volume is first initialized; changing `.env` later does not change existing database users or passwords.

## Health and logging

```powershell
curl.exe --fail http://localhost:8080/actuator/health
curl.exe --fail http://localhost:8080/actuator/health/readiness
curl.exe --fail http://localhost:8080/actuator/info
```

Healthy health responses include `"status":"UP"`. The root health endpoint includes standard database health. Readiness includes both the application readiness state and database connectivity; `/actuator/health/liveness` uses the standard liveness state. Health details stay hidden. Only the `health` and `info` Actuator endpoints are exposed, including health subpaths; discovery, other web endpoints, and all Actuator JMX endpoints are disabled. `info` currently returns an empty object because no additional information is published.

Console logs use Spring Boot's ECS JSON format and INFO-level logging. SQL parameter logging is disabled. Do not enable debug configuration/pool logging or add credentials to log messages. An unreachable database fails startup through Flyway; malformed or missing configuration fails earlier through Bean Validation with the relevant environment variable in the message.

## Tests

```powershell
# Isolated unit and configuration-binding tests; no database or Docker required
.\mvnw.cmd test

# Also builds the executable JAR and runs PostgreSQL integration tests
.\mvnw.cmd clean verify
```

Surefire runs `*Test`; Failsafe runs `*IT` during `verify`. Telegram unit/configuration tests mock the transport and cover authorization, malformed updates, every command, command registration/arguments, validation, long messages, safe errors, and lifecycle cleanup.

`TradeLabApplicationIT` starts the complete Spring application against a disposable PostgreSQL 17 Testcontainer with Telegram explicitly disabled, verifies Flyway and Hibernate validation, reads metadata through JPA, checks UTC storage/session settings, repeats migrations, and checks HTTP health and endpoint exposure. `TelegramEnabledApplicationIT` starts the full application with Telegram enabled, real PostgreSQL, and mock Telegram clients, verifying `/status` against the real database. Both use container-generated database credentials and never connect to the configured personal database. No `.env` import is required, and no test calls a real Telegram server or requires a real bot token.

If Docker is unavailable, container test methods are **skipped, not passed** (`disabledWithoutDocker = true`). Unit/configuration tests still execute. Inspect `target/surefire-reports` and `target/failsafe-reports`; do not treat a Docker-free build as evidence that database integration was executed. Mocked Telegram checks do not establish live connectivity; a real owner session is still needed to verify BotFather credentials and Telegram network access.

## Database changes

Migrations live in `src/main/resources/db/migration`. Flyway applies and validates migrations automatically at startup before JPA initialization. `V1__create_application_metadata.sql` creates a singleton installation metadata row with a `TIMESTAMPTZ` installation timestamp. Its read-only JPA mapping gives Hibernate a real schema to validate. `ddl-auto=validate` never creates or updates tables. Flyway clean is disabled.

Add each future schema change in a new `V2__description.sql`, `V3__description.sql`, etc. Never edit an already-applied migration. Persist timestamps as UTC instants using `TIMESTAMPTZ`; the connection pool sets every session to UTC, and Hibernate uses UTC. Handle user display time zones separately. Future financial amounts must use `BigDecimal` and explicit database precision/scale.

## Troubleshooting

- **Wrong Java / unsupported release 25:** check `.\mvnw.cmd -version`, set `JAVA_HOME` to JDK 25, and reopen the terminal if necessary.
- **Missing `DB_*` / validation failure:** populate `.env`, import it for local Java execution, and check `SPRING_PROFILES_ACTIVE`. Database names cannot contain URL parameters or punctuation.
- **Docker pipe/socket unavailable:** start Docker Desktop, switch to Linux containers, and check `docker info`. Testcontainers also needs access to the engine and permission to pull images.
- **Connection refused / startup failure:** check `docker compose ps`, `docker compose logs postgres`, host/port, and whether PostgreSQL is healthy. From Compose, the database hostname is `postgres`; from the host, use `localhost`.
- **Authentication failed after changing `.env`:** an existing volume retains its original credentials. Update the actual database login deliberately or restore the matching local credentials.
- **Port already in use:** change `DB_PORT` for the PostgreSQL host binding, `APP_PORT` for the Compose HTTP host binding, or `SERVER_PORT` for local Java execution. The container's internal HTTP port and healthcheck stay at 8080.
- **Migration checksum mismatch:** restore the original applied migration and create a new migration for the change; do not bypass validation or enable Hibernate schema updates.
- **First build cannot download:** check HTTPS/proxy access to Maven Central and container registries. The wrapper verifies the Maven distribution checksum.
- **Telegram configuration rejected:** with `TELEGRAM_ENABLED=true`, supply a BotFather-format token and a positive numeric `TELEGRAM_OWNER_ID`. Leave the feature disabled to run without credentials. Do not paste tokens or raw HTTP exception text into diagnostics.
- **Telegram startup failed / polling unavailable:** check outbound HTTPS access to `api.telegram.org`, whether BotFather revoked the token, and whether another process/container uses the same token. Stop duplicate pollers. Registration uses `getMe` and clears existing webhooks before long polling; no webhook deployment is supported.
- **Bot stays silent:** open a private chat with the bot, verify your numeric user ID, and send `/help`. Groups, other users, normal text, and commands suffixed with another bot name are intentionally ignored. Never use a username or the bot's own ID as the owner ID.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for module boundaries and future extension guidance.

The dated [foundation verification record](docs/VERIFICATION.md) lists the build, tests, and runtime checks actually completed during this stage.

The [Telegram verification record](docs/TELEGRAM_VERIFICATION.md) lists Stage 2 checks and the explicit live-connectivity limitation.
