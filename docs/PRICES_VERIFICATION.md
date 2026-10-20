# Task 3.2 — market-data MVP verification

Date: **2026-10-20**, Europe/Moscow.

## Delivered behavior

Latest available exchange trade prices and stored daily OHLCV history extend the existing market module. `/price`, `/history` and `/refresh` use the existing owner/private-chat dispatcher. `/stock` can include a stored quote without coupling metadata availability to price availability; `/status` includes enabled state and synchronization timestamps. `/refresh tracked` explicitly processes the environment-configured universe. Nothing synchronizes automatically, and Task 4 was not started.

The flow is `Telegram → MarketPriceService → existing instrument resolver → MarketPriceProvider / MarketPriceStore`. SDK 1.51 `GetLastPrices` and `GetCandles` responses are normalized into immutable SDK-independent models. Both adapters use the same authenticated SDK channel and the extracted `TInvestRpc` timeout/retry/rate-limit policy. Currency comes from instrument metadata. Prices are exact `BigDecimal` values formed from units plus nanos; historical summaries use finalized closes only.

Migration **V3__create_market_prices.sql** adds:

- `market_quotes`: latest stored quote per instrument UID, `NUMERIC(29,9)`, currency, original trade time, retrieval time and source; foreign key to instruments.
- `daily_candles`: UID/time/source primary key, OHLC `NUMERIC(29,9)`, provider volume in lots as `BIGINT`, trading date, completeness and retrieval time; foreign key, price/volume checks and date/incomplete indexes. Finalized records cannot be downgraded by incomplete responses.
- `market_sync_state`: queried coverage, durable cooldown/active lease, last successful/failed attempt times and safe failure codes. Success/failure timestamps identify the attempt producing the outcome.

No existing migration was edited or destructive schema operation introduced. Candle batches are transactional, external calls stay outside production DB transactions, and failures preserve previous stored values. Default history covers 365 calendar dates including today and is split into at most 180-day requests. Subsequent requests backfill missing older coverage and overlap the recent tail/earliest unfinished candle. Empty exchange responses record queried coverage without creating artificial candles.

## Actual execution results

Final command: `clean verify` using JDK **25.0.4.1**, Maven Wrapper **3.9.12**, Spring Boot **3.5.16** and the existing official Core SDK **1.51**.

| Suite | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Unit/configuration (Surefire) | 134 | 0 | 0 | 0 |
| PostgreSQL integration (Failsafe) | 16 | 0 | 0 | 0 |
| Total | **150** | **0** | **0** | **0** |

**BUILD SUCCESS**; the executable JAR was packaged at `target/tradelab-portfolio-0.0.1-SNAPSHOT.jar`. PostgreSQL 17 Testcontainers ran against the available Docker Engine. No personal database, brokerage credentials or live Telegram server was used. Tests use mock providers/Telegram transport, scripted in-memory gRPC responses and a fixed clock for price application flows.

Verified coverage includes:

- Positive, negative, sub-unit and signed nano conversion, extreme long units, malformed sign/nano rejection, exact nine-decimal prices and currency validation.
- Latest exchange-price request/response identity, original quote/retrieval times, missing prices, invalid timestamps and safe cache fallback.
- `CANDLE_INTERVAL_DAY` and exchange source, empty intervals, sparse trading days, incomplete candles and invalid response batches.
- First/last actual finalized closes, exact absolute change, rounded decimal percentage, one-observation history and zero-baseline prevention.
- Initial request splitting, incremental tail/older-gap loading, requerying an earlier incomplete candle, cooldown, independent quote/history failures and configured-universe error reporting.
- `/price`, `/history`, `/refresh`, cached stock quotes, status output and unauthorized/group rejection for all new commands.
- Real full application/Telegram dispatcher → service → Flyway/PostgreSQL flow with mock external providers.
- Database precision and UTC types, uniqueness and instrument foreign keys, repeated synchronization without duplicate rows, incomplete→complete updates, protection against complete→incomplete/stale regressions, durable lease/cooldown, provider-failure preservation and whole-batch SQL rollback.
- Existing instrument/Telegram commands, disabled integration startup, configuration validation, JPA schema validation, health/readiness and operational endpoint restrictions.

Reports: `target/surefire-reports/TEST-*.xml`, `target/failsafe-reports/TEST-*.xml`. Detailed local build log: ignored `.tools/tinvest/prices-verify-final.log`. Git whitespace checks passed. Production market code was checked for floating-point monetary conversion, scheduled jobs and order creation/cancellation/modification calls; none were introduced. No real tokens were supplied or added to files. No commit was created.

## Run and manual smoke commands

Use a production **read-only** token, following the [official token instructions](https://developer.tbank.ru/invest/intro/intro/token), and the existing Telegram owner configuration. Keep the values private in the environment/untracked `.env`. Import `.env` before running locally; Compose imports it and forwards the settings.

```text
INVEST_API_ENABLED=true
INVEST_API_TOKEN=<your private production read-only token>
INVEST_TRACKED_TICKERS=SBER,LKOH,GAZP,ROSN,NVTK,GMKN,PLZL
INVEST_HISTORY_DAYS=365
INVEST_REFRESH_COOLDOWN=60s
```

The example tickers are configuration examples, not recommendations. Omitting `INVEST_TRACKED_TICKERS` leaves the universe empty; individual ticker/UID requests remain supported. Integration remains disabled by default in the checked-in configuration. The environment is production only, with TLS and no sandbox fallback. The existing bounded `INVEST_API_TIMEOUT`, `INVEST_API_MAX_ATTEMPTS` and `INVEST_API_RETRY_BACKOFF` settings still apply.

With JDK 25 selected:

```powershell
docker compose up -d --wait postgres
.\mvnw.cmd clean verify
.\mvnw.cmd spring-boot:run
# Alternative full-stack run:
docker compose --profile app up -d --build --wait
```

This workspace's build set `JAVA_HOME` to `.tools/jdk/jdk-25.0.4.1+1`, `MAVEN_USER_HOME` to `.tools/maven-home`, and used `-Dmaven.repo.local=.tools/repository`; these local tool paths are optional for a normal JDK 25 setup.

In the configured owner's private chat:

```text
/help
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

Select a returned UID if the ticker is ambiguous. Single-stock commands accept UID directly. Repeating `/refresh` inside cooldown must produce a clear admission message. After cooldown, repeated refresh must update existing records rather than duplicate candles. `/history` can use the just-loaded local history while cooldown is active. `/stock` reads a cached quote without making a new price request.

**Live T-Invest/Telegram verification is pending.** No valid personal brokerage token was supplied; no real quote, candle synchronization or owner Telegram smoke result is claimed. Application startup in an enabled mocked test and SDK client construction do not establish external connectivity.

## Known limitations

- Last trade price is not a guaranteed execution price. A fresh API request can return an old trade; the response shows both timestamps and never assumes the market is open.
- Price change excludes dividends and is not total investment return. Prices are per share; candle volume is in lots. No local dividend/corporate-action adjustment is applied.
- Only exchange last prices and daily exchange candles are requested. Dealer/weekend prices, other candle intervals and charts are not implemented.
- Provider/session availability determines freshness and coverage. Missing days are not interpolated. Incomplete candles remain stored and excluded from finalized summaries.
- A failed initial multi-chunk scan retains valid rows but retries its uncheckpointed coverage later. Finalized corrections older than the incremental overlap are not automatically rescanned.
- Cached quote fallback requires safe UID resolution first; an API outage can prevent fresh ticker resolution. Known UIDs with valid cached instrument metadata avoid the ticker search.
- A refresh interrupted by a process crash can retain its active lease for up to one hour. A database outage can prevent failure-state persistence; status then reports that synchronization state is unavailable.
- PostgreSQL `TIMESTAMPTZ` stores microsecond timestamp precision; UTC semantics and all nine price decimal places are preserved. Raw Java/provider timestamps retain their precision before database conversion.
- There is no production scheduler, notification system, financial-statement analysis, portfolio management, recommendation/backtesting algorithm, AI analysis or trading operation.

## Task 3.2 changed files

Existing files updated in this stage (earlier Task 3.1 work was preserved):

- `.env.example`
- `compose.yaml`
- `README.md`
- `docs/ARCHITECTURE.md`
- `src/main/resources/application.yml`
- `src/main/java/ru/ba6kir/tradelab/market/infrastructure/tinvest/MarketConfiguration.java`
- `src/main/java/ru/ba6kir/tradelab/market/infrastructure/tinvest/TInvestStockInstrumentProvider.java`
- `src/main/java/ru/ba6kir/tradelab/market/telegram/MarketCommand.java`
- `src/main/java/ru/ba6kir/tradelab/market/telegram/MarketTelegramConfiguration.java`
- `src/main/java/ru/ba6kir/tradelab/telegram/config/TelegramBotConfiguration.java`
- `src/main/java/ru/ba6kir/tradelab/telegram/service/TelegramStatusService.java`
- `src/test/java/ru/ba6kir/tradelab/TradeLabApplicationIT.java`
- `src/test/java/ru/ba6kir/tradelab/market/MarketConfigurationTest.java`
- `src/test/java/ru/ba6kir/tradelab/market/TInvestProviderTest.java`

New production Java files, relative to `src/main/java/ru/ba6kir/tradelab/market/`:

- `application/price/MarketPriceProvider.java`
- `application/price/MarketPriceStore.java`
- `application/price/MarketPriceService.java`
- `application/price/PriceException.java`
- `application/price/AmbiguousInstrumentException.java`
- `domain/price/MarketQuote.java`
- `domain/price/DailyCandle.java`
- `domain/price/HistorySummary.java`
- `domain/price/PriceValues.java`
- `infrastructure/tinvest/TInvestRpc.java`
- `infrastructure/tinvest/TInvestMarketPriceProvider.java`
- `infrastructure/tinvest/QuotationDecimals.java`
- `infrastructure/tinvest/MarketDataProperties.java`
- `infrastructure/persistence/JdbcMarketPriceStore.java`
- `telegram/PriceCommand.java`
- `telegram/PriceText.java`

Other new files:

- `src/main/resources/db/migration/V3__create_market_prices.sql`
- `src/test/java/ru/ba6kir/tradelab/market/PriceDomainTest.java`
- `src/test/java/ru/ba6kir/tradelab/market/TInvestPriceProviderTest.java`
- `src/test/java/ru/ba6kir/tradelab/market/MarketPriceServiceTest.java`
- `src/test/java/ru/ba6kir/tradelab/market/PriceCommandTest.java`
- `src/test/java/ru/ba6kir/tradelab/market/MarketPricesIT.java`
- `docs/PRICES_VERIFICATION.md`
