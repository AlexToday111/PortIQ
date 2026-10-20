# Dividend Intelligence — Task 4.2

The module records provider observations for a specific security. It does not assert that the investor received money, reconstruct account transactions, calculate taxes, or forecast future distributions. A declaration may change or be cancelled; historical frequency cannot establish a guaranteed future cash flow.

## Source and verified SDK contract

The existing official Core SDK **1.51** and generated `java-sdk-grpc-contract:1.51` were inspected with the project JDK. The adapter uses `InstrumentsServiceBlockingStub.getDividends(GetDividendsRequest)` on the existing authenticated production TLS channel. It inherits shared per-attempt deadlines, bounded transient retries, sanitized errors and provider rate-limit cooldown. There is no new dependency or account API.

Official sources: [GetDividends](https://developer.tbank.ru/invest/api/instruments-service-get-dividends) and [Instruments request/response definitions](https://developer.tbank.ru/invest/services/instruments/methods#dividend). The request accepts FIGI, instrument UID, or `ticker_classCode` in `instrument_id`; the old `figi` field remains in SDK 1.51 and is deprecated. This module sends only the safely resolved **instrument UID**, never asset UID, company name or unqualified ticker.

`from` and `to` are UTC instants filtering **record_date**, not payment or declaration date. The official description does not specify endpoint inclusivity or a hard maximum GetDividends span. The implementation requests one padded interval (one day before the desired first date, through the start of the second day after the desired last date), then filters known record dates to the inclusive desired UTC dates. Missing/invalid record dates remain diagnostic observations. No undocumented provider guarantee about complete history, query-span support or publication timeliness is assumed; provider rejection follows the normal error/cache path. The local configured/requested span is bounded to five historical years plus 366 future days, with a 10,000-record response cap.

| SDK field | Stored meaning |
| --- | --- |
| `dividend_net` (`MoneyValue`, explicit presence) | Exact amount per security and explicit currency; missing is not zero |
| `declared_date` | Provider declaration date, UTC calendar |
| `last_buy_date` | Last purchase date inclusive according to provider, UTC calendar |
| `record_date` | Register date and the sole historical aggregation/query-date basis |
| `payment_date` | Provider-indicated payment date; documentation calls this actual payment date, but it is not account receipt confirmation |
| `dividend_type` | Raw provider type; `Cancelled` is explicit cancellation evidence |
| `regularity` | Provider label, e.g. `Annual`, `Semi-Anl`; never used to generate future events |
| `yield_value` (`Quotation`) | Separate provider value; documentation does not establish a percentage scale/denominator/date, so no guessed `%` conversion |
| `close_price` (`MoneyValue`) | Provider reference price/currency, described as price at `ex_dividend_date` |
| `created_at` | Source-record creation instant, not an event ID or revision sequence |

Source is `T_INVEST_PRODUCTION`; retrieval time is independent of provider creation time. `QuotationDecimals` supplies exact units+nanos conversion with sign/nanos validation, without `double`. Calendar dates use `DATE`; actual instants use `TIMESTAMPTZ`. The complete original Dividend protobuf is retained as Base64 in `rawPayload`, alongside normalized JSON. This preserves original date instants, unknown fields and nanoseconds; PostgreSQL's microsecond timestamp precision does not become an identity key.

Unsupported by this contract: authoritative event ID/revision number, explicit ex-dividend date, dated reference-price observation, confirmation of investor receipt, account-specific amount/tax treatment, verified gross/net investor semantics, guaranteed issuer coverage and a reliable cancellation-decision timestamp. None is fabricated. Despite its name, the documented `dividend_net` is simply an amount per security; the bot labels it **provider-defined**, never the investor's after-tax credit or a verified gross amount.

## Domain and lifecycle

`DividendEvent` contains transport-independent values, provenance and issue codes. Its stored `Observation` wrapper adds first/last observation time and an ambiguity flag. `DividendAnalytics` is deterministic domain code; `DividendService` coordinates resolution, caching, synchronization and calendar construction through provider/store ports. SDK and JDBC dependencies stay in infrastructure. Telegram handlers format plain-text results behind the existing owner/private-chat guard, and dynamic help discovers four new beans.

Statuses are evidence labels evaluated at an explicit UTC reference date:

- `CANCELLED`: provider type equals `Cancelled`, regardless of amount/date completeness.
- `HISTORICAL_RECORDED`: a non-cancelled observation with record date on/before the reference date. It means a historical entry is recorded, **not PAID**.
- `ANNOUNCED`: a future record date and a provider declaration date on/before the reference date.
- `UNKNOWN`: insufficient date/declaration evidence. Quality warnings can accompany any status.

Passing the record/payment date never confirms cash receipt. The SQL `event_status` captures the label at ingestion; reads evaluate the label at the requested reference date, so an old announcement becomes a historical record without becoming a payment transaction.

## Identity, corrections and cancellation safety

There is no trustworthy unique provider event ID. Consequently the database stores immutable **observations**, rather than pretending every revision can be confidently assigned to one economic payment.

1. Within `(instrument_uid, source)`, SHA-256 of the original serialized Dividend record is the content identity. Exact repeats, including duplicate entries within one response and overlapping windows, update `last_seen_at` without adding rows. First observation remains unchanged. Retrieval time is excluded from the hash.
2. A separate collision anchor hashes the exact nanosecond `created_at`. If absent, the anchor hashes record/declaration/last-buy/payment dates and the observation is immediately ambiguous. Creation time is evidence for detecting conflicts, **not a globally unique or guaranteed-stable event identifier**.
3. Different contents sharing an anchor are retained as ambiguous versions. Additionally, different creation timestamps with identical record/declaration/last-buy/payment dates and regularity are potential corrections, not additive payments: both remain ambiguous. A cancellation makes all same-record-date candidates ambiguous even if the cancellation has sparse fields. It does not silently mark those candidates as authoritatively cancelled.
4. Distinct same-record-date observations with distinct creation and other calendar evidence remain separate. Record date alone never forms a uniqueness constraint. Exact indistinguishable provider duplicates cannot be proved to represent two different payments; they collapse to one observation.
5. Ambiguity is sticky across later responses and reversions. No heuristic automatically discards or overwrites the original amount. An ambiguous version contributes neither to the period sum nor to yields. Source-confirmed cancellation observations remain visible. Absent rows are never deleted or interpreted as cancellations.

`updated` in refresh results means a newly observed content version sharing an existing creation anchor, not an asserted confirmed correction. Cross-anchor potential corrections are also visible through the total ambiguity count. The module cannot reliably link a correction that changes **all** identifying/calendar evidence; a future authoritative event identifier or manual reconciliation workflow would be needed. Complete economic identity and source completeness cannot be guaranteed by this API. This conservative strategy deliberately makes some calculations unavailable rather than double-counting questionable revisions.

## Persistence and synchronization

Additive Flyway **V5** creates `dividend_events` and `dividend_sync_state`; previous migrations are unchanged. Event rows have an instruments FK, `NUMERIC(40,9)` financial fields, date/instant columns, source/content uniqueness, collision/date indexes and full normalized/original evidence. No company/asset relation can transfer SBER dividends to SBERP. Same-asset instruments retain separate histories.

Defaults: three years backward, 365 days forward, 24h cache TTL and 60s manual cooldown. `/divhistory ... 5y` expands the fetch window when needed. The latest successful queried interval is stored directly; disjoint/overlapping windows never create a falsely continuous union. Cache hits still resolve ticker ambiguity through the existing instrument service; using UID allows its metadata cache behavior.

Admission uses an atomic durable per-instrument cooldown and a two-minute active lease. A short transaction records admission and closes before the RPC. A second short transaction locks synchronization state, verifies the attempt token, inserts the entire response, flags collisions and records successful coverage. SQL failure rolls back the entire event batch/coverage change. Late responses from an attempt superseded after lease expiry cannot overwrite newer synchronization state. The shared SDK policy handles retries/rate limits; no extra retry loop is added.

Empty successful responses advance queried coverage but preserve previous observations. Errors preserve events and successful coverage, record a sanitized failure code, release admission and return timestamped cache with a warning. Recent-period observations not reconfirmed within TTL also prevent aggregation. A fresh dividend response never refreshes a market quote; only an existing stored quote is considered. No transaction spans network calls and no startup/background schedule is introduced.

## Calculations and eligibility

All calculations use `BigDecimal`; percentage results round HALF_UP to six decimal places after division at 12 decimals. Currencies are never converted or filled from the security's trading currency.

- Per-share amount: original `dividend_net` amount and currency.
- Per-lot equivalent: `amountPerShare × positive lotSize`, still **provider-defined**, not a tax calculation.
- Local event yield: `eligible event amount / same-currency positive reference price × 100`. This uses a **saved exchange-trade reference dated explicitly in UTC**, no later than the calculation date and no older than seven calendar days. For a historical event it is an amount-to-reference-price comparison, not its original ex-date yield or an investor return. Missing identity/dates, cancellation, non-regular-cash type, suspicious data, ambiguity, stale cache or failed refresh prevent the displayed event yield.
- Provider `yield_value` remains separately labelled with unknown scale. Provider `close_price` is displayed diagnostically; its missing explicit ex-date prevents using it for a dated local yield. `last_buy_date` is not substituted for the missing ex-date.
- Historical sum: use the UTC interval **(reference date − 365 days, reference date]** on **record dates**. Include only `HISTORICAL_RECORDED` observations of `Regular Cash` with positive valid amounts, explicit one currency, creation evidence, reliable local identity and no suspicious validation issues. Exact duplicate contents are counted once. Explicit cancellations are excluded. Ambiguous/invalid/unknown-type non-cancelled observations in the period, mixed currencies or an unknown record date make the result unavailable. Fetch coverage must cover all 365 dates and be fresh; failure/cooldown warnings or stale individual observations prevent it. This is the sum of eligible **recorded provider amounts**, never money received.
- Trailing yield: the above eligible observed sum divided by the dated positive same-currency reference price ×100. No single future event is annualized. Query coverage means the interval was requested successfully, not independently audited completeness of the provider's issuer history.
- Historical year statistics show observation counts and eligible regular-cash amounts grouped by currency, with partial first/current years bounded by the selected dates. Counts can include ambiguous versions and must not be read as unique economic payments. Years with no entries mean **no observed events**, not a proven zero distribution. Payout changes are available only for unambiguous valid historical `Regular Cash`/`Annual` observations, the same instrument/currency and consecutive record years with exactly one observed event per year. These are event comparisons, not a forecast or reconstructed fiscal payout series.

## Quality handling

Valid observations retain available dates and exact values. Missing amount/currency/record date/identity evidence and optional payment date have separate `MISSING_*` codes. Zero and negative amounts are retained diagnostically but are not usable payouts. Bad nanos/signs/timestamps, creation timestamps in the future and inconsistent declaration/last-buy/payment versus record dates produce `SUSPECT_*`; original source evidence remains inspectable. Reference-price currency mismatch has its own flag. Ambiguity and retrieval staleness are separate from source-field validation. Old creation dates are normal for historical dividends and are not themselves evidence of stale retrieval.

Cancelled records remain visible but do not enter sums/yields. Return of Capital, Daily Accrual and unrecognized types are not silently treated as eligible regular cash. Missing payment date alone does not invalidate a historical recorded amount; missing record date prevents reliable period assignment. Missing/zero/negative/old/future/wrong-currency reference price produces an explicit unavailable reason. An unavailable result never becomes zero. No observed event is never a claim about an issuer's dividend policy or future reliability.

## Commands and calendar

```text
/divrefresh SBER
/dividends SBER
/divhistory SBER 1y
/divhistory SBER 3y
/divhistory SBER 5y
/divcalendar
```

Single-instrument commands also accept instrument UID. Ambiguous ticker results offer explicit UID choices. `/divcalendar` accepts no arguments and uses existing `INVEST_TRACKED_TICKERS` (maximum 20 configured tickers); empty configuration is reported. It resolves each instrument independently, lazily loads expired/missing cache and reports partial failures/ambiguities without selecting another share class.

Calendar rows cover record, last-buy and indicated-payment dates in **[today, today+90 days]**, plus provider declaration dates from the last 30 days and recently observed explicit cancellations. Cancellation observation time is labelled as such, never as the date of a cancellation decision. Chronological order is deterministic, with a maximum of 20 rows and an omitted-row count. Unknown/ambiguous/quality-limited rows carry status/warnings. A historical record can have a future indicated-payment row; this does not imply receipt. Record-date-filtered source coverage can miss a payment whose record date lies outside the requested window, an undated announcement or a source event not yet published.

`/dividends` shows six recent, two nearest future and at most two undated diagnostic observations, guarded 365-day analytics, price date, source and observation/sync timestamps. `/divhistory` shows at most 20 newest observations plus annual statistics. Existing Telegram splitting preserves long plain-text replies without breaking Unicode. `/divrefresh` reports new/conflicting/cancelled/unchanged/ambiguous observations or sanitized failure/cooldown. `/help` is dynamic. Existing owner/private-chat guard runs before every handler.

There are no notifications, subscriptions, broker receipt synchronization, portfolio cash flows/yields, taxes, scoring, recommendations, allocation, trading, AI or fabricated forecasts. Work stops at Task 4.2.

## Verification and files

Automated tests use synthetic protobufs, scripted gRPC calls, mocked providers/Telegram transport and PostgreSQL 17 Testcontainers. No real T-Invest/Telegram token is required. They cover exact normalization/precision, all calculations/invalid references, cancellation exclusion, deduplication, distinct same-date observations, ambiguous corrections/reversions, missing dates, empty/errors/cache/cooldown, date bounds, exclusive admission/late writes, SQL rollback, common/preferred isolation, calendar sorting/limits, all four commands, dynamic help and unauthorized access. The complete existing suite also runs.

Actual final verification on **2026-10-20**, Java 25 / Docker Engine 29.2.1 / PostgreSQL 17: `mvnw.cmd clean verify` completed **BUILD SUCCESS** in 43.304s. **203 unit/configuration tests + 35 integration tests = 238**, with **0 failures, 0 errors, 0 skipped**. This includes **39 dividend unit/configuration tests and 11 dividend PostgreSQL/full-flow tests**; the previous market/fundamental/Telegram suites also passed. `git diff --check` was clean. The packaged artifact is `target/tradelab-portfolio-0.0.1-SNAPSHOT.jar`; detailed local build output is in ignored `.tools/dividends/verify-final.log`, with standard Surefire/Failsafe XML reports under `target/`.

Real production API/Telegram smoke checks have not been performed because personal credentials were not provided; synthetic fixtures are never presented as production figures. To repeat the complete verification with JDK 25 and Docker running:

```powershell
.\mvnw.cmd clean verify
```

Created production files (base `src/main/java/ru/ba6kir/tradelab/dividends/`):

- `domain/model/DividendEvent.java`, `domain/service/DividendAnalytics.java`.
- `application/port/DividendProvider.java`, `DividendStore.java`; `application/service/DividendService.java`.
- `infrastructure/tinvest/DividendProperties.java`, `DividendConfiguration.java`, `TInvestDividendAdapter.java`.
- `infrastructure/persistence/JdbcDividendStore.java`.
- `telegram/DividendCommand.java`, `DividendTelegramConfiguration.java`.

Also created: `src/main/resources/db/migration/V5__create_dividends.sql`, this document and seven files under `src/test/java/ru/ba6kir/tradelab/dividends/` (test fixtures, analytics, adapter, service, commands, configuration and PostgreSQL/full-flow tests). Modified: `.env.example`, `compose.yaml`, `src/main/resources/application.yml`, `README.md`, `docs/ARCHITECTURE.md` and the current migration assertions in `TradeLabApplicationIT.java`. Earlier uncommitted work remains intact.
