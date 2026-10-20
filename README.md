# TradeLab Portfolio

TradeLab Portfolio is a private Telegram assistant being developed for long-term analysis of Russian equities listed on MOEX. It provides PostgreSQL persistence, health monitoring, an optional private Telegram bot, T-Invest stock metadata, latest exchange trade prices, historical daily candles, fundamental-data snapshots, reporting/dividend calendars, sector classification, typed financial analysis and experimental explainable fundamental scores. Portfolio management, investment recommendations, market-wide ranking, AI analysis, trading and scheduled reports remain outside the implemented scope.

## Stack and prerequisites

- JDK **25** (set `JAVA_HOME` to the JDK, not a JRE).
- Spring Boot **3.5.16**, Spring Data JPA, Bean Validation, Actuator, and Flyway.
- PostgreSQL **17**.
- TelegramBots **10.3.0** long-polling starter and client; the feature owns conditional registration and shutdown. Its [published starter POM](https://repo.maven.apache.org/maven2/org/telegram/telegrambots-springboot-longpolling-starter/10.3.0/telegrambots-springboot-longpolling-starter-10.3.0.pom) targets Spring Boot 3.5.x; the application retains Boot 3.5.16 dependency management.
- T-Invest **Core SDK 1.51**, without Strategy or broker Spring starters. Its published artifact targets Java 17 and is compiled/tested here on Java 25 with Boot 3.5.16. See [official SDK documentation](https://developer.tbank.ru/invest/sdk/java_sdk), [Core configuration](https://developer.tbank.ru/invest/sdk/java_sdk/faq_java_core), and the [versioned Maven POM](https://repo.maven.apache.org/maven2/ru/tinkoff/piapi/java-sdk-core/1.51/java-sdk-core-1.51.pom). The stable release and API signatures were checked against Maven Central metadata and the 1.51 source JAR.
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
| `INVEST_API_ENABLED` | Enable production instrument, market-price, fundamental and dividend API access; defaults to false | `false` |
| `INVEST_API_TOKEN` | **Read-only** production token; required only when enabled | Leave empty when disabled |
| `INVEST_METADATA_TTL` | Metadata cache lifetime, positive and at most 30 days | `24h` |
| `INVEST_API_TIMEOUT` | Connect timeout and per-attempt RPC deadline, 1 ms to 30 s | `3s` |
| `INVEST_API_MAX_ATTEMPTS` | Total attempts for timeout/temporary outage, 1–3 | `3` |
| `INVEST_API_RETRY_BACKOFF` | Initial exponential retry delay, 1 ms to 2 s | `200ms` |
| `INVEST_TRACKED_TICKERS` | Comma-separated example universe; at most 20 unique tickers, empty by default | `SBER,LKOH,GAZP,ROSN,NVTK,GMKN,PLZL` |
| `INVEST_HISTORY_DAYS` | Default manual refresh window, 1–365 calendar dates including today | `365` |
| `INVEST_REFRESH_COOLDOWN` | Minimum time between admitted refreshes per UID, 10 s to 10 min | `60s` |
| `FUNDAMENTALS_SNAPSHOT_TTL` | Fundamental snapshot, verified mapping and calendar cache lifetime; positive, at most 30d | `24h` |
| `FUNDAMENTALS_REPORTING_MAX_AGE` | Separate fiscal-context age warning threshold, 30–3650 days | `548d` |
| `FUNDAMENTALS_REFRESH_COOLDOWN` | Manual fundamental refresh cooldown per asset, 10s–10m | `60s` |
| `FUNDAMENTALS_REPORT_WINDOW_DAYS` | Calendar query days before/after now, 1–180 | `180` |
| `DIVIDENDS_LOOKBACK_YEARS` | Default dividend history lookback, 1–5 years | `3` |
| `DIVIDENDS_FORWARD_DAYS` | Dividend record-date query horizon, 90–366 days | `365` |
| `DIVIDENDS_CACHE_TTL` | Dividend retrieval cache lifetime; positive, at most 30d | `24h` |
| `DIVIDENDS_REFRESH_COOLDOWN` | Durable manual dividend refresh protection per instrument, 10s–10m | `60s` |
| `ANALYSIS_MINIMUM_PEERS` | Minimum valid other companies for metric-specific relative position, 5–100 | `5` |
| `ANALYSIS_CLASSIFICATION_MAX_AGE` | Maximum classification verification age, 1–3650 days | `365d` |
| `SCORING_MODEL_VERSION` / `SCORING_CONFIGURATION_VERSION` | Immutable algorithm/configuration version pair | `fundamental-heuristic-1` / `initial-1` |
| `SCORING_WEIGHT_Q/F/V/D` | Experimental pillar weights; exact sum must be 1 | `.35 / .25 / .25 / .15` |
| `SCORING_MANDATORY_PILLARS` | Every positive-weight pillar must be mandatory | `Q,F,V,D` |
| `SCORING_OBSERVATION_MAX_AGE` | Scoring input retrieval freshness, positive and at most 30d | `24h` |
| `SCORING_FINANCIAL_PERIOD_MAX_AGE` | Separate maximum financial-period age, 30–3650d | `548d` |
| `SCORING_MINIMUM_DIVIDEND_DAYS` / `SCORING_MINIMUM_DIVIDEND_YEARS` | Required queried history days and observed record years | `1095` / `3` |
| `SCORING_EXTREME_YIELD` / `SCORING_HIGH_LEVERAGE` | Experimental extreme-yield percent and non-bank leverage risk threshold | `20` / `6` |

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

Telegram credentials are unnecessary while `TELEGRAM_ENABLED=false`, including for automated tests. Broker credentials are unnecessary while `INVEST_API_ENABLED=false`. When Telegram is enabled, token syntax and owner ID are validated before registration; remote token validity is checked with `getMe` during startup. Tokens are redacted in configuration diagnostics.

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
| `/help` | Lists the registered commands and their descriptions, including `/stock` and `/search` |
| `/ping` | Exactly `TradeLab Portfolio: online` |
| `/status` | Application readiness, database connectivity, current UTC time, and polling state |
| `/stock SBER` | Exact ticker resolution and instrument metadata, or a list of ambiguous matches |
| `/stock <UID> [refresh]` | Unambiguous metadata lookup; optional forced refresh |
| `/search <query>` | Up to ten equity matches from T-Invest |
| `/price SBER` | Latest available exchange trade price, original trade time and retrieval time |
| `/history SBER 30d` | First/last finalized closes and price change; supports 30d, 90d, 180d, 365d |
| `/refresh SBER` | Explicit quote and daily history synchronization with cooldown |
| `/refresh tracked` | Manually synchronize configured `INVEST_TRACKED_TICKERS` and report each result |
| `/fundamentals SBER` | Available asset fundamentals, financial currency, provenance, periods and quality warnings |
| `/reports SBER` | Reporting-calendar events, separately from financial statements |
| `/fundrefresh SBER` | Refresh fundamentals; changed/unchanged/failure/cooldown and metric-quality counts |
| `/fundstatus SBER` | Asset mapping, last request/snapshot times, counts and separate freshness information |
| `/dividends SBER` | Recorded/upcoming dividend events, provider-defined share/lot amounts and guarded historical analytics |
| `/divcalendar` | Configured tracked tickers, next 90 days plus recent announcements/cancellation observations |
| `/divrefresh SBER` | Manual dividend refresh with new/conflicting/cancelled/unchanged observation counts |
| `/divhistory SBER 3y` | Record-date history; supports 1y, 3y, 5y and UID in place of ticker |
| `/sector SBER` | Verified asset sector/business type, source, override and verification state, or explicit UNKNOWN |
| `/metrics SBER` | Canonical provider/derived metrics, periods, units, lineage/quality and readiness |
| `/compare SBER` | Compatible cached sector peers, descriptive median/count, or explicit unavailability; no rating |
| `/score SBER` | Evaluate stored financial evidence; show experimental overall/pillars or exact unscored reasons |
| `/scoreinfo SBER` | Dry-run eligibility, metric contributions, rules, source IDs and fingerprints |
| `/scorehistory SBER` | Latest 20 stored distinct assessments with model/configuration versions |
| `/unknown` | Short Russian message pointing to `/help` |

Command names are case-insensitive. Extra whitespace is accepted, including `/PING@YourBotName`; a suffix naming another bot is ignored. `/start`, `/help`, `/ping`, and `/status` take no arguments and report a readable validation error for extra input. Market handlers accept arguments through `TelegramCommand.acceptsArguments()`. Ordinary text and non-message updates are ignored.

Only the configured owner in a matching private chat can execute commands or receive responses. Unauthorized users, group/supergroup/channel chats, missing identities, and ambiguous sender information are silently ignored. The bot does not echo input or disclose internal hosts, connection strings, or exception details. Replies use plain text, split at 4096 UTF-16 units without cutting surrogate pairs. Failed sends are logged generically and are not automatically retried, avoiding duplicate replies.

`/status` reports whether the polling session is scheduled, **not** continuous end-to-end Telegram connectivity. Polling network failures use bounded exponential backoff with safe warnings. SDK exception logging is disabled because its URLs can contain tokens. Do not override that setting or enable HTTP wire logging. Polling sessions, active HTTP calls, and their executors are closed on application shutdown.

## Enable T-Invest stock metadata (Task 3.1)

1. Follow the official [token instructions](https://developer.tbank.ru/invest/intro/intro/token): sign in to the T-Investments profile settings on the T-Bank website and issue a **Read-only token** for the production exchange environment. Verify its access rights in the security settings. Do not supply a full-access, transfer-access, or sandbox token. Save the token privately when it is shown; it cannot be retrieved later.
2. In untracked `.env`, set `INVEST_API_ENABLED=true` and `INVEST_API_TOKEN` to that token. Keep all secrets out of command arguments, screenshots, tickets, and logs. Import `.env` before a local Java run; Compose imports it and forwards the variables. Enabling the integration validates a nonblank token locally; its remote validity and permissions are determined by T-Invest when a request is made. The application cannot infer the scope of an opaque token locally, so issuing a read-only token is a deployment requirement.
3. Configure the private Telegram bot above, then restart the application. This feature uses **production only**, with TLS at `invest-public-api.tbank.ru:443`, and never switches to sandbox. Startup creates the client without a remote smoke request. No credentials are needed for automated tests.
4. From the owner's private chat, send `/help`, `/search SBER`, and `/stock SBER`. Search accepts 1–64 letters/numbers/spaces/`.`/`_`/`-`, and displays at most ten matches. Exact ticker resolution retains every share match returned by the provider. If several match, choose a returned `/stock <UID>` command. MOEX-style equity boards (`TQ…`) sort first; no candidate is automatically selected from an ambiguous ticker.
5. Repeat `/stock <UID>` to see the **кэш** label. Run `/stock <UID> refresh` (or `/stock SBER refresh` when unique) to force **свежие метаданные**. Full metadata is persisted only after successful UID retrieval. Defaults: 24-hour TTL, 3-second deadline per attempt, up to three attempts with 200/400 ms backoff for timeout/temporary outage. Ticker searches are always fresh because a partial local catalog cannot prove ticker uniqueness. They do not download the complete `Shares` catalog or fetch each search result individually.

The card includes name, ticker/class code, exchange, currency, lot size, UID, optional FIGI/ISIN, API availability flag, metadata trading status, update time in UTC, source `T_INVEST_PRODUCTION`, and cache/fresh label. Missing optional fields display `не указан` and remain SQL `NULL`. `/search` returns short search matches; fields absent from `FindInstrument` are never filled with guesses or saved over a full card. Cache expiry causes a fresh request; a failed refresh reports the failure and leaves the stored record intact. Explicit application-level `getCached` can inspect a local record even with integration disabled; Telegram market commands report that access is not configured while disabled.

These are instrument/security attributes, not company-level fundamentals. Search coverage and metadata depend on the broker, can include inactive securities, and are not a complete MOEX directory. Trading availability is a metadata snapshot, potentially cached for the configured lifetime; it is not a live trading-session check or a guarantee that an account can trade. Search is bounded by the provider's response and is not an exhaustive catalog scan. The sections below describe prices, fundamentals and dividends. Portfolios, rankings, recommendations, AI and order operations remain excluded.

Troubleshooting:

- **Not configured:** `INVEST_API_ENABLED` defaults to false independently of Telegram; enable it and restart with a private read-only token.
- **Authentication/permissions:** check that the production read-only token is active and has the intended rights; replace expired/revoked credentials privately. Authentication failures are not retried. Do not replace it with a more privileged token.
- **Rate limit:** the adapter returns a clear message and applies the provider's `x-ratelimit-reset` cooldown (60 seconds if absent/unusable), rather than repeatedly hitting the API. Try again later. Cached UID metadata can still be read.
- **Timeout/outage:** check DNS, firewall/proxy and outbound TLS/HTTP2 on port 443 to the production host, and the machine clock/certificate trust. The SDK supplies its bundled trusted root configuration. TLS verification stays enabled. Transport/debug/wire logs must stay disabled; report only sanitized reason codes. Adjust the bounded timeout settings if appropriate.
- **No match/ambiguous ticker:** use `/search <name or ticker>`, refine the query, and select UID. An unavailable instrument or provider error does not produce fabricated metadata.

Validation without real credentials:

```powershell
.\mvnw.cmd test
# Start Docker Desktop with Linux containers first to execute the PostgreSQL tests.
.\mvnw.cmd clean verify
```

Tests use mocked providers or in-memory scripted gRPC responses. `InstrumentPersistenceIT` uses a disposable PostgreSQL 17 Testcontainer and verifies insert/update, repeated synchronization, null optional attributes, UTC, non-unique tickers and protection against late stale writes. See [Task 3.1 verification](docs/MARKET_VERIFICATION.md) for actual results and limitations. A real `/stock` or `/search` request is an optional manual smoke test and has not been performed with personal credentials.

## Prices and historical data (Task 3.2)

Use the same production **read-only** token configuration above. There are no extra broker credentials, sandbox fallback, automatic polling jobs or scheduled reports. Changes to environment settings require an application restart. The sample universe in `.env.example` is configurable, is not hardcoded in business logic, and is not an investment recommendation. Empty `INVEST_TRACKED_TICKERS` disables only the optional list; on-demand requests for other shares still work. Every ticker goes through the instrument resolver; ambiguous tickers require a returned UID, and unavailable stocks are skipped and reported during `/refresh tracked`. The configured list is never refreshed automatically.

After importing `.env` and starting PostgreSQL/the app as below, use the owner's private Telegram chat:

```text
/refresh SBER
/price SBER
/history SBER 30d
/history SBER 90d
/history SBER 180d
/history SBER 365d
/stock SBER
/status
/refresh tracked
```

All single-stock commands also accept UID. `/stock` appends the last stored quote when present and remains usable if price storage is unavailable. `/status` shows whether T-Invest is enabled and the timestamps of the last successful and failed price synchronization. Instrument-resolution errors without a UID are reported directly; the durable synchronization state belongs to resolved instruments.

`/price` calls [GetLastPrices](https://developer.tbank.ru/invest/api/market-data-service-get-last-prices) for that UID and requests the exchange price type explicitly. A response contains the instrument name/ticker, exact price per share (not per lot), currency from instrument metadata, original trade timestamp, retrieval timestamp, provider and fresh-request/cache label. It **does not imply that the trade just happened, that the market is open, or that a buy/sell order could execute at that price**. Quote freshness depends on the trading session and provider. The command always shows the timestamp instead of calling the price current. If a price request fails and a UID-resolved cached quote exists, it is explicitly marked `кэш`, retains its original timestamps, and includes a safe error message. If the ticker itself cannot be resolved during an outage, use a known UID with valid cached instrument metadata; the application never assumes a locally unique ticker is globally unique.

`/refresh` loads the last 365 calendar dates by default, including today's possible incomplete candle, through [GetCandles](https://developer.tbank.ru/invest/api/market-data-service-get-candles) with `CANDLE_INTERVAL_DAY` and exchange source. Requests use half-open UTC time ranges split into at most **180-day** chunks; default initial loading takes at most three history calls. Coverage is checkpointed only after every requested history chunk succeeds, including genuinely empty intervals. Subsequent refreshes request missing older coverage (if the window grows) and a recent overlapping tail, starting earlier when a stored incomplete candle needs finalization. Existing middle history is not downloaded again. If an initial multi-chunk load partially fails, valid completed chunks stay stored; the incomplete coverage scan is retried on a later refresh. Old finalized corrections outside the overlap are not automatically rescanned in this MVP.

Each refresh independently attempts quote and history retrieval. Success, partial success, total failure and cooldown are reported distinctly; a failed phase cannot delete existing data. Valid chunks use atomic transactional upserts. A repeated candle updates the same `(instrument UID, candle time, source)` key; a finalized candle cannot be downgraded by an incomplete response. Older quote/trade timestamps and older candle retrievals cannot overwrite newer stored observations. Cooldown and an active-run lease are stored in PostgreSQL, so repeated requests after a restart cannot bypass admission. A crashed run's lease expires after one hour. API timeouts/retries/rate-limit cooldown use the existing bounded policy on the same SDK channel; raw exceptions and tokens are never logged.

`/history` performs a bounded incremental synchronization for the requested window (subject to that same cooldown) and summarizes stored, valid, **finalized** daily candles. It uses the actual first and last observations present, skips unfinished candles, reports the available count, and labels cached/partial results with data timestamps. Empty intervals or intervals containing only unfinished candles do not become zero-valued prices. It does not manufacture weekends, holidays or missing trading days. Dates are derived from original candle timestamps using `Europe/Moscow`; raw timestamps stay UTC. A one-observation interval has zero price change. **Historical price change excludes dividends and is not total investment return.** There are no charts or additional analytics.

Prices use `BigDecimal`: `BigDecimal.valueOf(units) + BigDecimal.valueOf(nano, 9)`, with explicit sign/nano validation, including negative conversion tests. No `double`/`float` or intermediate string-to-floating conversion is used. The stock-price domain rejects zero/negative prices, missing currency, future timestamps beyond a 60-second clock-skew allowance, inconsistent OHLC values and negative volumes. Currency is never inferred from a ticker or defaulted to RUB. Percentage change is `(lastClose - firstClose) * 100 / firstClose`, rounded only for this displayed result to six decimal places, `HALF_UP`; the positive baseline check prevents division by zero. Stored prices use `NUMERIC(29,9)`; volume is `BIGINT` and represents provider-reported **lots**, not shares. Provider prices/candles and corporate-action treatment are accepted as supplied; no dividend or corporate-action adjustment is performed locally.

Migration `V3__create_market_prices.sql` adds `market_quotes`, `daily_candles` and `market_sync_state` with instrument foreign keys, UTC timestamps and range/identity indexes. Flyway owns the schema; Hibernate remains `validate`. Provider data can be missing, incomplete or delayed; synchronization can fail. An empty successful exchange response records queried coverage, not a fabricated trading calendar.

Troubleshooting:

- **Missing price / empty history:** confirm UID and requested dates through the instrument commands. Newly listed, suspended or inactive instruments and non-trading periods can legitimately have no data.
- **Only incomplete candles:** wait for finalized provider data and refresh after cooldown. Such candles are stored but never used as a final close.
- **Partial success / cached data:** read the quote/history error separately. Existing data remains stored. `/price` and `/history` include original observation/retrieval information; `/status` retains successful and failed synchronization times.
- **Cooldown / active refresh:** wait at least `INVEST_REFRESH_COOLDOWN`; overlapping runs are blocked by the database lease. A process crash can leave the lease active for up to one hour.
- **Authentication, rate limit or timeout:** use the production read-only token and connectivity checks above. Rate-limit reset applies to both metadata and price calls. Do not enable wire/debug logging or submit a more privileged token.

Run `.\mvnw.cmd test` and, with Docker available, `.\mvnw.cmd clean verify`. Tests mock all external T-Invest/Telegram responses. See [Task 3.2 verification](docs/PRICES_VERIFICATION.md) for actual results, changed files and live-verification limitations.

## Fundamental data (Task 4.1)

Use the same `INVEST_API_ENABLED=true` and private production **read-only** token; no additional API credentials are needed. With integration disabled, fundamental commands return a clear configuration message and the application still starts without broker credentials. There is no scheduled refresh. Import `.env` before local execution or use Compose; restart after changing settings.

```text
/fundrefresh SBER
/fundamentals SBER
/fundstatus SBER
/reports SBER
```

All commands also accept instrument UID, and ambiguous tickers require explicit UID selection. The engine uses official `ShareBy` plus `GetAssetBy` metadata to verify the **asset UID**, then sends that asset UID to `GetAssetFundamentals`. It never substitutes the instrument UID or guesses a company from ticker text. Multiple instruments sharing the same asset share snapshot history. Optional company/brand labels do not constitute a fabricated legal issuer identity.

The implemented provider fields cover capitalization; P/E, P/B, P/S and EV/EBITDA; ROE, ROA, ROIC and net margin; total debt, debt/EBITDA, net debt/EBITDA, current ratio and fixed-charge coverage (FCCR); revenue, net income, EBITDA, FCF and one-year revenue growth; TTM dividend-yield statistic, EPS and outstanding shares. EBITDA margin, net-debt amount, interest coverage, net-income growth and book value/share have explicit `UNSUPPORTED` states. FCCR is not relabelled interest coverage. No missing metric is reconstructed from unrelated values.

Provider doubles are checked for NaN/Infinity and normalized using `BigDecimal.valueOf` without arbitrary rounding or currency conversion. Financial currency comes from the financial response, not the stock's quote currency. SDK 1.51 scalar doubles lack presence metadata: a default zero is retained as uncertain `SUSPECT`, rather than fabricated as a valid zero or changed to missing. Legitimate losses and negative cash flow remain available. Rate fields whose fraction/percentage scale is not specified by the checked contract are retained unchanged with an explicit uncertain-scale label; no guessed factor of 100 is applied.

Freshness has two separate dimensions: retrieval confirmation defaults to 24 hours, while financial fiscal-context age uses `FUNDAMENTALS_REPORTING_MAX_AGE` (548 days by default). A request made today does not imply today's financial results. Unknown dates remain unknown. Failed refresh preserves earlier snapshots and, when returned, cached values keep their timestamps and stale/error warnings. A meaningful correction creates a new version; identical content updates confirmation metadata without duplicate metric records. Snapshots and metrics are persisted in one transaction after external requests complete.

`/reports` uses `GetAssetReports` with the **instrument UID**. It shows bounded calendar events with reporting period, calendar publication date and retrieval date. An event is not a detailed balance sheet, income statement, cash flow statement or proof of publication. No financial value is filled from the calendar. Calendar data has its own batch cache and survives request failures with an explicit warning.

See [docs/FUNDAMENTALS.md](docs/FUNDAMENTALS.md) for the exact 27-metric mapping, units and unsupported fields, quality/fingerprint rules, V4 schema, configuration, changed-file list and actual test results. Live brokerage verification remains pending without personal credentials. No scoring, ranking, investment recommendation, portfolio allocation, forecast or trading logic was implemented.

## Dividend Intelligence (Task 4.2)

Use `/divrefresh SBER`, `/dividends SBER`, `/divhistory SBER 1y|3y|5y`, and `/divcalendar` in the owner's private chat. Single-instrument commands also accept UID. The calendar uses `INVEST_TRACKED_TICKERS`, shows next-90-day dates and recent announcements/cancellation observations, and is limited to 20 chronological rows. No notifications or background synchronization run.

The module retrieves real instrument-specific [GetDividends](https://developer.tbank.ru/invest/api/instruments-service-get-dividends) observations using SDK 1.51. Migration V5 adds dividend observations and durable synchronization state. Defaults are three years back, 365 days forward, 24h retrieval TTL and 60s cooldown. Repeated/overlapping requests are idempotent; failures/empty responses preserve recorded history. Common/preferred share classes never share dividend events.

Amounts and lot equivalents use `BigDecimal` and are labelled provider-defined. `dividend_net` does not establish investor-specific tax treatment. Status labels distinguish announcements, cancellations, historical records and unknown evidence; a date is never proof of account receipt. Without an authoritative event ID, conflicting possible revisions remain visible as ambiguous observations and cannot inflate historical sums/yields. The guarded 365-day sum uses record dates; optional yields use an explicitly dated positive same-currency stored trade reference. Missing/stale/ambiguous inputs produce unavailable calculations. Provider yield is separately labelled; its scale and the provider reference price's exact ex-date are not invented.

See [docs/DIVIDENDS.md](docs/DIVIDENDS.md) for source fields, reconciliation/lifecycle, formulas, quality, migration/files, actual test results and API limitations. Automated verification uses synthetic responses and disposable PostgreSQL 17 containers; a production token/Telegram smoke check remains pending. Task 4.2 adds no recommendations, account accounting, taxes, portfolio yield, trading, schedules or forecasts.

## Financial Metrics & Sector Classification (Task 5.1)

Use `/sector SBER`, `/metrics SBER` and `/compare SBER`; all accept instrument UID. Classifications belong to verified asset identities and distinguish banks, non-bank financial companies and non-financial businesses. The adapter preserves the provider's raw asset-sector label. Explicit mappings and maintained verified issuer/asset configuration determine the profile; unknown/broad labels remain UNKNOWN. No real company/asset IDs or unverified production seed values are hardcoded. See [seed configuration](docs/FINANCIAL_ANALYSIS.md#maintained-seed-configuration) for supplying independently verified SBER/LKOH/GMKN or other mappings through an additional Spring YAML file.

Migration V6 adds asset classifications, canonical metric definitions and derived observations with source-snapshot/asset constraints and input lineage; provider values remain in existing fundamental tables. Calculations use BigDecimal, preserve legitimate losses/net cash, reject missing inputs, invalid denominators and incompatible periods/currencies, and retain provider/derived variants separately. Sector profiles declare multiple required/optional concepts, with explicit readiness and unsupported business-type handling. Bank profiles exclude industrial leverage rules.

Current provider limitations remain visible: uncertain rate scales and missing per-metric reporting dates prevent many comparisons/derived ratios. Snapshot fiscal context is not substituted for every metric's period. Missing cash, average equity, NOPAT, invested capital, capital adequacy/asset quality and unsupported growth measures are never fabricated. `/compare` uses cached, classified peer assets, deduplicates verified issuer identities, requires compatible dates/definitions/currencies and shows no percentile with fewer than five valid other companies. It computes no company score or investment recommendation.

See [docs/FINANCIAL_ANALYSIS.md](docs/FINANCIAL_ANALYSIS.md) for taxonomy, formulas, actual available inputs, profiles, peer methodology, files/migration and executed tests. Task 5.1 itself adds no score or recommendation; scoring is a separate versioned layer below.

## Fundamental Scoring Engine (Task 5.2)

`/score SBER`, `/scoreinfo SBER` and `/scorehistory SBER` accept tickers or UID. Ticker resolution uses the existing metadata lookup; a UID evaluates local cache without broker API access. Scoring captures one read-only consistent database snapshot, calculates outside database/network adapters, then persists an immutable result with its full input/model evidence. Existing `/fundrefresh`, `/divrefresh` and price commands load actual inputs separately. Scoring does not automatically fetch missing data.

The baseline formula is `S = .35Q + .25F + .25V + .15D`. BANK, OIL_AND_GAS, METALS_AND_MINING and GENERAL_NON_FINANCIAL profiles use explicit bounded BigDecimal curves, source-quality/date checks and traceable metric contributions. Every weighted pillar must have sufficient evidence. Missing/uncertain/stale inputs yield PARTIAL, INSUFFICIENT_DATA, STALE_DATA or UNSUPPORTED_PROFILE with a null overall score; no imputed zero/50 or silent weight adjustment occurs. Defined losses/net cash remain observations. Extreme yields and distress context cannot create a cheap-value reward.

V7 adds immutable model versions, score snapshots, metric contributions and risk flags. Identical repeated results deduplicate; changed inputs/model or expired eligibility append history. A changed configuration under the same version pair is rejected. Default thresholds/weights are assumptions and can be explicitly versioned/configured; histories never imply different models share a methodology. There is no peer-based score, ranking, stock-selection list, portfolio recommendation or trading action.

**Fundamental scores are experimental heuristic indicators and have not yet been validated as predictors of investment returns.** Current provider limitations often prevent a real score: SBER's bank profile lacks verified bank capital/NPL inputs, and undated/unscaled observations block industrial profiles too. The complete synthetic demonstration is confined to tests. See [docs/SCORING_METHODOLOGY.md](docs/SCORING_METHODOLOGY.md) for all curves, evidence requirements, configuration/versioning, database/files, actual tests and unvalidated aspects. Task 5.3 is not implemented.

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

`V2__create_instruments.sql` adds instrument metadata keyed by provider UID, non-unique ticker/class-code indexes, nullable provider attributes, provenance, and a `TIMESTAMPTZ` update time. Atomic PostgreSQL upserts update the same UID; older responses cannot overwrite newer metadata. Add each future schema change in a new `V3__description.sql`, etc. Never edit an already-applied migration. Persist timestamps as UTC instants using `TIMESTAMPTZ`; the connection pool sets every session to UTC, and Hibernate uses UTC. Handle user display time zones separately. Future financial amounts must use `BigDecimal` and explicit database precision/scale.

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
