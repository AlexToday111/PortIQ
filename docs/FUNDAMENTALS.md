# Fundamental Data Engine — Task 4.1

## Source and identity

The implementation uses the existing official T-Invest Core SDK **1.51**, the production read-only token, authenticated channel, deadlines, bounded retries and shared rate-limit cooldown. No new SDK module or third-party financial source was added. Contracts were checked in the published 1.51 source JAR and compiled generated classes, alongside the official [GetAssetFundamentals](https://developer.tbank.ru/invest/api/instruments-service-get-asset-fundamentals), [GetAssetBy](https://developer.tbank.ru/invest/api/instruments-service-get-asset-by), [GetAssetReports](https://developer.tbank.ru/invest/api/instruments-service-get-asset-reports) and [Instruments protocol](https://developer.tbank.ru/invest/services/instruments/methods) documentation.

Methods and their identifier roles:

1. Existing instrument resolution searches equities, preserves ticker ambiguity, and retrieves/saves the instrument by **instrument UID**.
2. `ShareBy(INSTRUMENT_ID_TYPE_UID)` supplies the official `asset_uid` for that instrument. No asset is guessed from ticker, ISIN, company name or instrument UID.
3. `GetAssetBy(AssetRequest.id = asset UID)` must confirm the same UID, an undeleted share-security asset and reverse membership of the requested instrument in `AssetFull.instruments`.
4. `GetAssetFundamentals(GetAssetFundamentalsRequest.assets = [asset UID])` fetches one asset's statistics. An empty result is unavailable data; a mismatched/duplicate result is rejected.
5. `GetAssetReports(GetAssetReportsRequest.instrument_id = instrument UID, from, to)` fetches a bounded **calendar** independently of fundamental snapshots.

Instrument UID identifies an exchange-traded security. Asset UID identifies the provider's underlying asset. A legal issuer/company is a third concept. `AssetFull.brand.company` and brand UID are retained as optional provider labels; a brand is not asserted to be a legal issuer identifier. Instruments sharing an asset share one snapshot history. Different asset UIDs are not merged merely because company or brand text matches, and their data is not presented as independently reconstructed company statements. Reliable cross-asset legal-issuer consolidation needs a future authoritative identity source.

## Module and data flow

The package is `fundamentals`, with `domain/model`, `domain/validation`, `application/port`, `application/service`, `infrastructure/tinvest`, `infrastructure/persistence` and `telegram`.

`TelegramCommandDispatcher → FundamentalsCommand → FundamentalsService → InstrumentService / AssetIdentityResolver / FundamentalsProvider / FundamentalsStore`.

SDK protobuf objects remain inside the concrete adapters. A small infrastructure-only Instruments gateway in the existing T-Invest adapter reuses the established SDK stub, deadlines and retry/cooldown policy. Domain/ports, normalization, JDBC storage and command presentation do not use SDK DTOs. `FundamentalAnalysisView` exposes immutable observations, quality flags and `usableMetrics()` for future consumers; it does not calculate scores, averages or investment recommendations.

## Exact metric mapping

There are **22 supported provider fields** and **5 explicitly unsupported requested metrics**. Every supported value is provider-supplied; none is calculated locally. The table describes normalized units and provider horizon labels, not independently verified financial statements. TTM/MRQ/FY and 1Y are field horizons. Exact per-metric reporting dates are not supplied. The optional fiscal-period start/end fields are retained separately as snapshot context and are not automatically assigned to every metric.

`currency` means the financial-data currency from `StatisticResponse.currency`, which can differ from the instrument's quote currency. Monetary magnitudes are kept exactly as returned, without a guessed thousands/millions multiplier or currency conversion. `rate ?` means the checked contract does not unambiguously specify fraction versus percentage-point scale; the number is retained unchanged, marked `SUSPECT`, and never printed with an invented `%` unit.

| Internal metric | Provider field | Unit | Horizon | Meaning / availability |
| --- | --- | --- | --- | --- |
| `MARKET_CAPITALIZATION` | `market_capitalization` | provider currency units | unknown | Market capitalization; supported |
| `PE` | `pe_ratio_ttm` | multiple | TTM | Price/earnings; supported |
| `PB` | `price_to_book_ttm` | multiple | TTM | Price/book; supported |
| `PS` | `price_to_sales_ttm` | multiple | TTM | Price/sales; supported |
| `EV_EBITDA` | `ev_to_ebitda_mrq` | multiple | MRQ | EV/EBITDA; supported |
| `ROE` | `roe` | rate ? | unknown | Provider return-on-equity measure; supported, scale uncertain |
| `ROA` | `roa` | rate ? | unknown | Provider return-on-assets measure; supported, scale uncertain |
| `ROIC` | `roic` | rate ? | unknown | Provider ROIC field; exact formula/scale not asserted |
| `NET_MARGIN` | `net_margin_mrq` | rate ? | MRQ | Net margin; supported, scale uncertain |
| `EBITDA_MARGIN` | — | unavailable | unknown | `UNSUPPORTED`; not reconstructed from other values |
| `TOTAL_DEBT` | `total_debt_mrq` | provider currency units | MRQ | Provider total-debt measure; supported |
| `NET_DEBT` | — | unavailable | unknown | `UNSUPPORTED`; no direct net-debt amount field |
| `DEBT_EBITDA` | `total_debt_to_ebitda_mrq` | multiple | MRQ | Total debt/EBITDA; supported |
| `NET_DEBT_EBITDA` | `net_debt_to_ebitda` | multiple | unknown | Net debt/EBITDA; supported |
| `CURRENT_RATIO` | `current_ratio_mrq` | ratio | MRQ | Current liquidity ratio; supported |
| `INTEREST_COVERAGE` | — | unavailable | unknown | `UNSUPPORTED`; FCCR is not renamed interest coverage |
| `FIXED_CHARGE_COVERAGE` | `fixed_charge_coverage_ratio_fy` | ratio | FY | Fixed-charge coverage (FCCR); supported as its own metric |
| `REVENUE` | `revenue_ttm` | provider currency units | TTM | Revenue; supported |
| `NET_INCOME` | `net_income_ttm` | provider currency units | TTM | Net income/loss; supported |
| `EBITDA` | `ebitda_ttm` | provider currency units | TTM | EBITDA; supported |
| `FREE_CASH_FLOW` | `free_cash_flow_ttm` | provider currency units | TTM | Provider FCF; supported, not fabricated from EBITDA |
| `REVENUE_GROWTH_1Y` | `one_year_annual_revenue_growth_rate` | rate ? | 1Y | Provider revenue growth; scale uncertain |
| `EARNINGS_GROWTH` | — | unavailable | unknown | `UNSUPPORTED` as net-income growth; EPS change is not relabelled net-income growth |
| `DIVIDEND_YIELD` | `dividend_yield_daily_ttm` | rate ? | TTM | Provider yield statistic only; no dividend events/calendar analysis |
| `EPS` | `eps_ttm` | financial currency/share | TTM | EPS; supported |
| `BOOK_VALUE_PER_SHARE` | — | unavailable | unknown | `UNSUPPORTED`; not inferred from price and P/B |
| `SHARES_OUTSTANDING` | `shares_outstanding` | shares | unknown | Outstanding share count; supported |

Other SDK fields are outside this task's selected metric scope. In particular, forecasts, forward dividend estimates and ex-dividend calendar fields are not ingested. A calendar event is not used to fill any missing financial value.

## Numerical and quality rules

Decoded provider doubles enter a transport-independent raw record as their canonical decimal text. The normalizer rejects non-finite values and uses **`BigDecimal.valueOf(double)`**, never `new BigDecimal(double)`, without arbitrary rounding or rate conversion. Provider double precision cannot be recovered beyond what the API sends. Values are stored in unconstrained PostgreSQL `NUMERIC`, preserving the normalized decimal representation. Diagnostic strings such as `NaN` survive as JSON/text; they never become valid numeric observations.

Quality states:

| State | Interpretation |
| --- | --- |
| `VALID` | Present normalized value passes the implemented metric checks; not proof of independently audited financial correctness |
| `MISSING` | Source model has no value for a supported field |
| `SUSPECT` | Value/diagnostic is retained, with a reason: uncertain zero, non-finite input, unknown currency/rate scale, negative context or fractional share count |
| `STALE` | Analysis-view overlay for expired retrieval/mapping freshness or an old fiscal context; original quality remains visible |
| `UNSUPPORTED` | No direct field in the selected current contract; normalized value is SQL `NULL` |

SDK 1.51 statistics use **implicit-presence scalar doubles**: the API does not distinguish an omitted scalar from a genuine zero. The adapter reads the actual descriptor presence semantics; zeros in these fields are retained as numeric zero with `SUSPECT / ZERO_WITHOUT_PRESENCE`, not changed to missing or confidently declared genuine. A source with explicit reliable presence can supply a legitimate zero (for example, zero total debt), which is validated normally. This distinction is covered by tests. An omitted field in a mocked/future source remains `MISSING`.

Negative earnings, EBITDA, EPS and FCF can be valid and are preserved. Negative multiples are retained with contextual warnings rather than discarded; negative revenue/debt/capitalization, negative liquidity/coverage ratios and negative/fractional share counts receive metric-specific suspicion flags. Unknown currency is not replaced with RUB. Ambiguous rates are not multiplied/divided by 100. No arbitrary ratio threshold creates a financial verdict.

`availableCount` counts numeric observations, including visibly flagged ones. Missing/unsupported and suspicious counts are separate; non-finite observations can be suspicious without a numeric value. `usableMetrics()` returns only observations whose effective flags are exactly `VALID`, excluding missing, unsupported, suspect and stale data. No recommendation or scoring algorithm consumes these values in this stage.

## Freshness and dates

Configuration defaults:

```text
FUNDAMENTALS_SNAPSHOT_TTL=24h
FUNDAMENTALS_REPORTING_MAX_AGE=548d
FUNDAMENTALS_REFRESH_COOLDOWN=60s
FUNDAMENTALS_REPORT_WINDOW_DAYS=180
```

- Snapshot TTL (positive, at most 30 days) also governs verified mapping and report-calendar cache age.
- Financial fiscal-period age has a separate configurable threshold (30–3650 days). A fresh request with old fiscal context remains warned/marked stale. This is conservative snapshot-context gating, not an invented per-field publication date.
- An unknown fiscal end date produces unknown reporting age, not a fabricated current period.
- Refresh cooldown is 10 seconds to 10 minutes; admission is shared by asset across instruments. A crashed refresh's active lease expires after 10 minutes.
- Calendar queries cover up to 180 days before and after the request; 180 is the default. They are bounded to 500 returned events, and Telegram displays at most ten.
- `fetched_at` is the first retrieval of that meaningful version; `last_fetched_at` is the latest successful confirmation. Fiscal period and optional `provider_as_of` are separate. **The current contract has no publication/as-of timestamp for fundamentals, so `provider_as_of` remains null.** It is never copied from retrieval time or a calendar event.

UTC instants are used throughout; PostgreSQL `TIMESTAMPTZ` has microsecond precision. Stale cache fallback retains original timestamps and warnings. If mapping expires and cannot be reconfirmed, an old mapped snapshot is explicitly marked with stale mapping and excluded from usable observations. No snapshot from an old asset is attached to a newly resolved asset on a failed identity update.

## Persistence and lifecycle

Additive migration **V4__create_fundamentals.sql** creates:

- `asset_identities`: provider asset/name/company-label/brand metadata and refresh attempt/success/failure/lease state.
- `instrument_asset_links`: instrument foreign key → asset foreign key, verification time, and an asset lookup index. Instrument and asset UIDs have separate columns and roles.
- `fundamental_snapshots`: asset/source/version identity, first/latest retrieval and count, optional provider as-of/fiscal context, SHA-256 content fingerprint, completeness and diagnostic JSONB.
- `fundamental_metric_values`: snapshot/code primary key, nullable `NUMERIC`, explicit unit/currency/horizon, quality/reason and raw decimal/invalid-value text.
- `financial_report_events` and `financial_report_sync`: separate instrument-level calendar observations and latest successful query window. `report_type` remains null because the contract's period type is not an IFRS/RAS statement type.

External calls finish before the snapshot transaction starts. The store locks the asset row, validates refresh admission, compares the latest fingerprint, writes a new version plus all 27 metric states atomically when meaningful content changes, and updates request state in the same transaction. A failure rolls back the entire snapshot and its metrics. Previous versions survive provider/persistence failures.

The fingerprint includes asset/source, financial currency, fiscal/as-of context, selected raw values and presence metadata, using sorted length-prefixed input. Retrieval timestamps are excluded. An unchanged response increments confirmation metadata without duplicating metric rows. A→B→A creates **three traceable versions**, even though first and third hashes match; there is deliberately no global hash uniqueness constraint. Per-asset serialized version numbers and primary keys prevent racing inconsistent writes. An older in-flight retrieval cannot overwrite a newer latest observation.

The diagnostic JSON is the normalized raw **selected metric scope**, currency and dates, not an assertion that the full financial statement or complete SDK payload was archived. Snapshots are provider observations, not an official historical statement dataset.

## Reporting-calendar limitation

`GetAssetReports` returns reporting-calendar events: report/publication date from the calendar, reporting year/number/period type, and optional provider record creation time. They are stored separately from fundamentals. The UI distinguishes calendar publication date, financial reporting period and application retrieval date, and explicitly says an event is **not a financial statement or proof that publication occurred**.

Repeated identical events update retrieval metadata without duplicate rows. A rescheduling can retain earlier event versions for diagnostics, while the user-facing calendar reads only the latest successfully fetched batch; a later empty successful result does not silently resurrect obsolete events. Failed retrieval leaves the previous batch available with a cache/stale warning. No balance sheet, income statement or cash flow statement is reconstructed from these events.

## Commands and configuration

Use the existing production read-only `INVEST_API_TOKEN` and `INVEST_API_ENABLED=true`; secrets stay in private environment configuration. Defaults remain disabled and require no token. Compose forwards all four freshness/window settings. Import `.env` before local Java execution as described in the [README](../README.md); restart to apply configuration changes.

```text
/fundamentals SBER
/reports SBER
/fundrefresh SBER
/fundstatus SBER
/help
```

Commands also accept a verified instrument UID; ambiguous tickers display UID choices. `/fundamentals` prefers a fresh shared snapshot and attempts refresh when needed. `/fundrefresh` explicitly refreshes metrics subject to admission; it reports changed/unchanged/failure/cooldown and metric-quality counts. Verified mappings are cached for the configured TTL. `/fundstatus` reports locally stored mapping/snapshot state after instrument resolution, without automatically fetching a new fundamental snapshot. `/reports` independently fetches/caches calendar events. All handlers pass through the existing owner-only/private-chat guard.

## Verification — 2026-10-20

Executed on Windows with JDK **25.0.4.1**, Maven Wrapper **3.9.12**, Boot **3.5.16**, SDK **1.51**, Docker Engine **29.2.1** and disposable PostgreSQL **17** Testcontainers. Automated tests never used personal brokerage credentials or live Telegram servers.

- `clean verify`: **164 unit/configuration + 24 integration tests passed**, zero failures/errors/skips; executable JAR packaged.
- Final `verify` after the calendar retrieval-timestamp refinement: **164 unit/configuration + 24 integration tests passed**, zero failures/errors/skips; **BUILD SUCCESS**. It confirms the final production code and repackaged JAR.
- Fundamental tests cover identity/reverse membership, shared assets, missing mapping, valid/absent/unsupported metrics, reliable/ambiguous zero, losses/negative FCF/multiples, NaN/Infinity, rate scale, currency, dates, freshness, outages, safe errors, all four commands and authorization.
- PostgreSQL tests cover shared snapshots, unchanged confirmations without duplicate metrics, corrections/reversions, numeric/null/raw round trips, complete transaction rollback, provider-failure preservation, calendar idempotency/rescheduling/empty batches and the complete authorized Telegram → services → database path. The provider mock explicitly verifies no DB transaction is open during external fetching.
- Existing market and Telegram tests still execute, including disabled-provider full startup, Flyway V1–V4, schema validation and health checks.

Reports: `target/surefire-reports`, `target/failsafe-reports`. Local logs: ignored `.tools/fundamentals/verify.log` and `.tools/fundamentals/verify-final.log`. Artifact: `target/tradelab-portfolio-0.0.1-SNAPSHOT.jar`.

**Live verification was not performed.** No personal read-only token was provided. Mocked SBER fixture values are test data only; no real fundamentals or calendar result is claimed.

## Limitations and future extension

Coverage, definitions and update timing depend on T-Invest. Raw provider magnitudes and currency are retained; undocumented rate scales remain uncertain. Source-double precision, publication dates and legal-issuer relationships cannot be invented. Provider outages can prevent ticker/asset resolution even when historical snapshots remain stored. Use known UIDs where valid cached instrument metadata is available. A database outage can also prevent recording a failure state; user errors remain sanitized.

No external official-issuer statement ingestion, dividend events/calendars, financial forecasts, scoring/ranking, recommendations, allocation, portfolio management, news/LLM analysis, automatic trading or scheduling was implemented. Future official-issuer ingestion should add an authoritative issuer identity and statement-specific source/period/currency contracts, keeping observations, calendar events and computed analyses separate. Task 4.2 was not started.

## Created and modified files in Task 4.1

New production files are under `src/main/java/ru/ba6kir/tradelab/fundamentals/`:

- `domain/model/{AssetIdentity,FundamentalData,FundamentalSnapshot,FinancialReportEvent,MetricCode,MetricValue,QualityStatus}.java`
- `domain/validation/FundamentalNormalizer.java`
- `application/port/{AssetIdentityResolver,FundamentalsProvider,FundamentalsStore}.java`
- `application/service/{FundamentalAnalysisView,FundamentalsException,FundamentalsService}.java`
- `infrastructure/tinvest/{FundamentalsConfiguration,FundamentalsProperties,TInvestFundamentalsAdapter}.java`
- `infrastructure/persistence/JdbcFundamentalsStore.java`
- `telegram/{FundamentalsCommand,FundamentalsTelegramConfiguration}.java`

Other additions:

- `src/main/resources/db/migration/V4__create_fundamentals.sql`
- `src/main/java/ru/ba6kir/tradelab/market/application/AmbiguousInstrumentException.java` (shared exception moved out of the price package)
- `src/test/java/ru/ba6kir/tradelab/fundamentals/{FundamentalsTestData,FundamentalNormalizerTest,FundamentalsServiceTest,TInvestFundamentalsAdapterTest,FundamentalsCommandTest,FundamentalsConfigurationTest,FundamentalsIT}.java`
- `docs/FUNDAMENTALS.md`

Updated: `.env.example`, `compose.yaml`, `README.md`, `docs/ARCHITECTURE.md`, `src/main/resources/application.yml`, existing `market/application/InstrumentService.java`, `market/infrastructure/tinvest/TInvestStockInstrumentProvider.java`, `market/telegram/PriceCommand.java`, and `src/test/java/ru/ba6kir/tradelab/TradeLabApplicationIT.java`. The former `market/application/price/AmbiguousInstrumentException.java` was replaced by the shared application exception. Earlier task changes remain preserved; no commit was created.
