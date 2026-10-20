# Financial Metrics & Sector Classification — Task 5.1

This module provides typed financial observations, conservative sector profiles and descriptive peer comparisons. It does not calculate a company score, choose attractive stocks, produce recommendations or price targets, allocate portfolios, trade or call an LLM. Missing inputs remain missing.

## Existing source capabilities and boundaries

The implementation reuses the existing T-Invest Core SDK 1.51, instrument resolution, verified instrument→asset mapping, fundamental snapshots, Stage 4 quality statuses and dividend analytics. The checked [Instruments contract](https://developer.tbank.ru/invest/services/instruments/methods) exposes a free-text `GetAssetBy.AssetFull.brand.sector` label and financial statistics with field-specific horizon names. It does not provide a trusted bank/non-bank business-type enum or a legal issuer ID through the selected mapping. The adapter now preserves that raw sector label; it never claims a brand/company name is a legal identifier.

Stage 4 has 27 metric codes: 22 collected provider fields and five explicitly unsupported fields. Financial values already normalized in `fundamental_metric_values` are reused as canonical projections, without a duplicate provider-observation table. No new financial data source or implicit money/rate conversion was introduced. Original provider statistics, snapshot fiscal dates, raw evidence and source quality remain intact.

The analysis catalogue has 39 metric concepts, including explicit missing formula inputs, bank-specific measures and dividend-history concepts. A definition's canonical unit does not change an actual provider observation's unit: an uncertain provider rate stays `UNVERIFIED_RATE`, with its original `SUSPECT` quality, even when the canonical concept uses percent.

## Architecture

`analysis/domain/sector` owns taxonomy, source evidence, metric profiles and readiness; `domain/metric` owns canonical observations and pure formula calculations; `domain/comparison` owns deterministic descriptive statistics. Application services coordinate existing application contracts and an `AnalysisStore` port. JDBC/configuration and Telegram formatting remain at the boundary. No domain model imports broker/Telegram SDKs or Spring.

`FinancialMetricService` requests the subject through `FundamentalsService.getLatest`, preserving existing UID/ticker ambiguity and cache/error behavior. It attaches a classification, canonical provider observations and separately labelled local derived variants. Existing `DividendService` supplies optional security-level yield/history; errors become warnings rather than fabricated numbers. Network work finishes before local persistence transactions begin.

`SectorComparisonService` requests the subject normally but reads **only already stored peer snapshots** through the new read-only `FundamentalsService.cachedAsset` contract. It does not crawl/fetch all peers, refresh the tracked list or query another module's tables directly. To populate missing peer data, the owner explicitly uses existing `/fundrefresh` commands. There is no background scheduling.

## Taxonomy and evidence

Sectors: `FINANCIALS`, `OIL_AND_GAS`, `METALS_AND_MINING`, `INFORMATION_TECHNOLOGY`, `TELECOMMUNICATIONS`, `UTILITIES`, `CONSUMER`, `TRANSPORT`, `CONSTRUCTION`, `CHEMICALS`, `INDUSTRIALS`, `OTHER`, `UNKNOWN`.

Business types: `BANK`, `NON_BANK_FINANCIAL`, `NON_FINANCIAL`, `UNKNOWN`. Financial companies are never automatically treated as banks. Bank/non-bank classifications require `FINANCIALS`; non-financial business types cannot use `FINANCIALS`/`UNKNOWN` sectors.

Classification belongs to **asset UID**, not ticker or company-name text. Securities linked to the same verified asset share a classification; separate assets are not automatically consolidated. A manually verified `issuerKey` (for example, an operator-checked legal issuer identifier) groups ordinary/preferred or other assets for company counting. Neither company names nor brand UIDs are substituted for it. Without that explicit company identity, company percentile comparisons are unavailable.

Evidence hierarchy:

1. A maintained explicit manual override controls the asset; expiry makes it stale rather than silently changing business type.
2. A recognized provider sector label otherwise has priority. Exact lowercase labels `financial`/`financials`, `it`, `telecom`, `utilities`, `consumer`, `industrials` and `other` map to the corresponding taxonomy sectors. Financial labels leave business type `UNKNOWN`; mapped non-financial sectors use `NON_FINANCIAL`.
3. A fresh verified manual record can refine the business type/issuer key when its sector agrees with provider evidence (`PROVIDER_AND_MANUAL`). Conflicting manual sectors need an explicit override. Where no provider label maps reliably, the maintained manual classification applies.
4. Otherwise return `UNKNOWN`, preserving the raw provider label. Broad `energy`/`materials` labels do not prove oil-and-gas/mining rather than utilities/chemicals. No ticker-prefix or name guessing occurs.

Records preserve source, verification timestamp, issuer key, evidence text, raw provider label and override status. Provider verification means the label was observed on the verified provider asset; it is not an independent corporate audit. Manual verification is an operator assertion, validated structurally, not an automated investigation of the supplied evidence. Default classification maximum age is 365 days. Existing upgraded asset rows initially have a null raw sector until their normal mapping refresh; that is not filled with a guess.

## Maintained seed configuration

No fictional production identifiers or hardcoded SBER/LKOH/GMKN classifications are shipped. First resolve each candidate with `/sector SBER`, `/sector LKOH`, `/sector GMKN` (or the returned unambiguous instrument UID); confirm the actual asset identity, issuer and corporate evidence independently. Unavailable/historical ticker matches are not assumed actively traded. UNKNOWN is a valid outcome until the missing evidence is supplied.

Supply a private `analysis-local.yml` through Spring's normal additional-location mechanism. The file is ignored by Git. The placeholders below **must be replaced with actual verified values**, and are not runnable seed data:

```yaml
tradelab:
  analysis:
    classifications:
      - asset-uid: <actual-asset-UID-from-sector-command>
        issuer-key: <verified-stable-issuer-identifier>
        sector: FINANCIALS
        business-type: BANK
        verified-at: <actual-verification-time-in-UTC>
        override: false
        evidence: <authoritative-source-and-verification-note>
```

```powershell
java -jar target/tradelab-portfolio-0.0.1-SNAPSHOT.jar --spring.config.additional-location=file:./analysis-local.yml
```

Use the normal existing database/token environment configuration. External configuration can also be supplied through Spring's standard structured configuration mechanisms; container deployments must explicitly provide/mount their chosen configuration. No new credentials are required.

At most 200 unique configured asset records are accepted. UUIDs, explicit sector/business combinations, issuer keys, evidence, past verification times and duplicate/conflicting issuer records are validated. The same issuer key across several assets must use one sector/business group. Seeds are persisted lazily only after the real target asset has been resolved/stored. No seed causes startup network calls. The peer universe consists of configured classifications plus persisted classifications, bounded deterministically to 200 assets; it is not asserted to be a complete industry index or all MOEX issuers.

| Setting | Default / bounds |
| --- | --- |
| `ANALYSIS_MINIMUM_PEERS` | 5, configurable 5–100 valid other companies |
| `ANALYSIS_CLASSIFICATION_MAX_AGE` | 365d, configurable 1–3650d |
| Peer snapshot freshness | Existing `FUNDAMENTALS_SNAPSHOT_TTL`, default 24h |
| Old fiscal-context warning | Existing `FUNDAMENTALS_REPORTING_MAX_AGE`, default 548d |

## Canonical observations and date policy

Each observation contains a stable logical ID, asset and source-snapshot identity, metric code, exact value, actual unit/currency, `ANNUAL`/`QUARTERLY`/`TTM`/`POINT_IN_TIME`/`UNKNOWN` type, original horizon label, optional period start/end, provider-as-of, retrieval time, source, `PROVIDER`/`DERIVED` origin, Stage 4 quality set/reason, methodology and input IDs. Security-derived dividend observations additionally carry instrument UID.

Provider horizon mapping: TTM→TTM, FY→ANNUAL, MRQ→QUARTERLY except balance-sheet total debt→POINT_IN_TIME. A label such as 1Y does not establish an exact annual accounting interval and remains UNKNOWN. Original labels are retained. Currency codes are normalized to uppercase without converting money.

The current source supplies **snapshot fiscal context, not reliable dates for each individual TTM/MRQ/FY metric**. Consequently its global fiscal dates are displayed separately and are not assigned to every statistic. Canonical per-metric dates remain unknown with `METRIC_REPORTING_DATES_UNKNOWN`. Fresh download time never becomes publication/as-of/financial-period time. This means many current production observations cannot yet participate in peer comparisons or multi-input derived formulas, even if their numeric values are present. That limitation is intentional and visible.

A usable observation must have a valid value/quality, known type and end date, plus an ordered explicit start date for flows. Unknown rate scale, invalid monetary currency, non-positive valuation multiples and inherited suspicious/stale quality prevent usability. Annual and TTM observations never match just because both cover roughly a year. Comparisons require the same type, exact start/end, horizon, units, definition/origin and currency. Valuation comparisons additionally require an explicit equal UTC as-of date. Future source/retrieval dates are rejected in calculations/comparisons.

## Metric families, availability and formulas

| Family | Supported input concepts / limits |
| --- | --- |
| Valuation | Provider P/E, P/B, P/S, EV/EBITDA, capitalization; local earnings yield from valid positive P/E |
| Profitability | Provider ROE/ROA/ROIC/net margin with unverified rate scales retained; locally derived margins/ROE/ROIC only with verified compatible inputs |
| Stability | Provider total debt, Debt/EBITDA, Net Debt/EBITDA, current ratio, FCCR; FCCR is never renamed interest coverage |
| Growth | Provider TTM revenue, net income, EBITDA, FCF; provider one-year revenue growth keeps its uncertain scale; no unrelated-period growth reconstruction |
| Dividends | Separate provider yield statistic and instrument-specific Stage 4 trailing yield, recorded-event count/history; none proves investor receipts |
| Context | EPS, share count, explicit missing average equity/capital, NOPAT and other denominator inputs |

Supported local formula variants:

- Earnings yield = `100 / positive P/E`. A unary transformation inherits the P/E horizon/date uncertainty; its number may be shown even when that context prevents comparison/readiness.
- Net profit margin = `net income / positive revenue ×100`.
- EBITDA margin = `EBITDA / positive revenue ×100`.
- ROE = `net income / verified positive average equity ×100`.
- ROIC = `actual NOPAT / verified positive average invested capital ×100`.
- Net debt = `total debt − cash/cash equivalents`, both nonnegative monetary stocks at one verified point-in-time date/currency. A negative result means net cash and is retained.
- Net Debt/EBITDA = point-in-time net debt divided by positive TTM EBITDA with the **same verified end date**. The stock/flow alignment is explicit; annual/quarterly EBITDA is not substituted.

All inputs must belong to the same asset/source snapshot. Multi-input formulas require valid units, explicit currency agreement and compatible dates; missing/stale/suspicious inputs prevent the result. Negative net income/NOPAT/FCF are legitimate observations; negative margin/ROE/ROIC or net-cash leverage can be valid. Nonpositive denominator equity/capital/revenue/EBITDA is explicitly unavailable rather than an attractive negative multiple. Single-snapshot equity is never substituted for average equity, profit is never relabelled FCF, and net income is never relabelled NOPAT.

Calculations use BigDecimal only. Ratios divide at scale 12 and percentages/multiples round HALF_UP to six decimals; net-debt subtraction retains exact input precision. Every local variant is DERIVED with a versioned formula and input IDs. Provider ratios are retained separately and not overwritten/reconciled with a different local definition. Even when both exist, peer comparisons require the same origin, source and methodology.

Currently uncollected inputs include cash, verified average equity, NOPAT and average invested capital. Therefore net-debt amount, local ROE/ROIC and much of local leverage are normally unavailable. Debt/Equity, interest coverage, capital adequacy, asset quality/NPL ratios, net-income/EBITDA/FCF growth and book value/share are explicit missing/unsupported concepts. The selected source has no verified data for them; no synthetic production values or average-balance estimates fill the gap. Current margins are also blocked when exact per-metric reporting dates cannot be established.

Dividend observations retain security scope. A valid event count/history is a count of reliable recorded non-cancelled observations in the retrieved history, not a company receipt count or proof of a zero-distribution policy. Empty/ambiguous/stale history yields unavailable. Trailing yield reuses Stage 4 date/currency/coverage checks and provider-defined amount semantics; the provider's original yield remains another observation. Security metrics are not silently compared as company-wide values.

## Profiles and readiness

| Default profile | Required | Selected optional |
| --- | --- | --- |
| BANK | P/B, ROE | P/E, ROA, profit growth, genuinely sourced capital adequacy/asset quality, dividend history |
| OIL_AND_GAS / METALS_AND_MINING | EV/EBITDA, Net Debt/EBITDA, FCF | P/E, ROIC, net margin, dividends |
| Other known non-financial sectors | P/E, net margin | ROIC, current ratio, Net Debt/EBITDA, FCF, revenue growth, earnings yield, dividend history |
| UNKNOWN / unknown business / non-bank financial | UNSUPPORTED_PROFILE by default | No arbitrary bank/industrial fallback |

An explicit `tradelab.analysis.profiles` list can replace a sector/business pair with `sector`, `business-type`, `required` and `optional` metric-code sets. Required metrics must span at least two families and contain at least two metrics; optional/required sets must be disjoint. This is the small extension mechanism for additional researched profiles, including non-bank financial subgroups. Bank profiles reject industrial Debt/EBITDA, Net Debt/EBITDA, EV/EBITDA, Debt/Equity and current-ratio rules. Default non-bank financial analysis remains unsupported until an appropriate profile is explicitly maintained.

Readiness: missing/unsupported profile→`UNSUPPORTED_PROFILE`; expired classification or relevant stale values→`STALE_DATA`; all required usable metrics→`READY`; some usable relevant metrics→`PARTIAL`; none→`INSUFFICIENT_DATA`. One available valuation multiple cannot establish READY. Required concepts with unknown reporting dates or scales remain unusable. Readiness is data coverage/quality, never a security rating.

## Peer methodology

Candidates come from the bounded maintained classification universe and their cached asset snapshots. Exact sector **and** business type must agree. The subject and all assets with its verified issuer key are excluded. Unverified issuer keys, stale classifications/snapshots, missing or nonmatching observations are excluded with reasons. No fresh provider request for a peer occurs.

Each metric variant compares only identical definitions/origins/methods, horizon/type/start/end, units and explicit currencies; valuation as-of UTC dates must also agree. Absolute amounts never mix financial currencies. Bank profiles cannot introduce industrial debt metrics. Distinct same-company assets count once if compatible observations agree; conflicting security-level values exclude that issuer rather than selecting a convenient share class. Security-scoped dividend observations are explicitly unavailable for company comparison.

Median is the sorted middle value, or the exact mean of two middle values. With fewer than five valid **other companies**, the result is `INSUFFICIENT_PEERS`; a descriptive median may still be shown, but no relative percentile is produced. At/above the configured minimum, numerical midrank is `(count strictly below subject + 0.5 × count equal) / peer count ×100`, rounded HALF_UP to six decimals. This describes value position, not investment merit or a preferred direction. There is no overall score, ordering of stocks, silent trimming or winsorization.

Each result records subject context, calculation time, candidate asset/issuer identities, observation IDs, retrieval timestamps, exclusion reasons, valid-company count, median and optional metric-specific relative position. Results are reusable application/domain records; a comparison-history database is not introduced. Telegram bounds metric/universe output and reports exclusions; it does not claim the candidate set is a complete industry population. Small/incomplete Russian-equity samples frequently make robust relative positions unavailable.

## Database

Additive migration **V6**:

- Adds nullable raw `provider_sector` to `asset_identities`; existing records remain valid.
- Creates `issuer_sector_classifications`, one current classification per existing asset UID, indexed by group and manually verified issuer key, preserving normalized JSON evidence and typed metadata. Identical writes are no-ops. Explicit overrides resist later provider writes; late older same-priority records cannot regress verification time.
- Creates `analysis_metric_definitions`; the 39 code/family/unit/definition rows initialize idempotently with the first metric persistence operation. Definitions are also available in code when no analysis has yet run.
- Creates `derived_financial_observations` with NUMERIC values, dates/UTC instants, original snapshot FK, method, input lineage, quality and full canonical observation. A composite asset/snapshot FK prevents attaching one asset's calculation to another asset's snapshot. Stable hashes of asset, snapshot, versioned method and input IDs prevent duplicate accumulation; corrections to source snapshots get separate IDs.

Provider observations continue to live in V4 tables. Derived batches and definition initialization use one short transaction; failed writes roll back completely. Classification writes are local atomic upserts. Network calls do not span those transactions. No scoring/recommendation tables, destructive migration or portfolio logic is added.

## Telegram verification

```text
/sector SBER
/sector LKOH
/sector GMKN
/metrics SBER
/compare SBER
/help
```

All three commands accept instrument UID in place of ticker, preserve ambiguity, and pass the existing owner/private-chat gate. `/sector` shows the actual asset, classification/source/verification/override; UNKNOWN is explicit. `/metrics` shows provider/derived variants, units, periods/date uncertainty, quality, unavailable inputs, profile readiness and provenance. `/compare` shows company/median/count/context or an explicit unavailable reason, never a stock rating. Existing commands remain registered.

A practical flow is to resolve real candidates, configure independently verified classifications, load their actual fundamentals with `/fundrefresh`, then inspect `/metrics` and `/compare`. The current API may still lack metric-specific dates, in which case comparison correctly remains unavailable even with a large universe. Automated tests validate fully dated synthetic cases; those fixtures are never production data.

## Verification and changed files

Final verification on **2026-10-20** with Java 25 and PostgreSQL 17 Testcontainers: `mvnw.cmd clean verify` completed **BUILD SUCCESS** in **58.895s**. **229 unit/configuration tests + 41 integration tests = 270**, with **0 failures, 0 errors, 0 skipped**. Task 5.1 adds **26 unit/configuration tests and 6 PostgreSQL/full-flow tests**; all prior market/fundamental/dividend/Telegram tests also ran. `git diff --check` is clean. The packaged JAR is `target/tradelab-portfolio-0.0.1-SNAPSHOT.jar`; local detailed output is in ignored `.tools/analysis/verify-final.log`, with standard XML reports in `target/surefire-reports` and `target/failsafe-reports`.

Tests cover exact formulas, legitimate negative values, zero/missing denominators, period/currency/identity checks, missing average equity/NOPAT, provider/local separation, raw rate/date uncertainty, source/manual/override priority and expiry, UNKNOWN/non-bank profiles, same-asset securities, readiness, peer median/midrank/minimum sample, invalid peers, issuer deduplication/conflicts, command authorization and configuration bounds. PostgreSQL tests verify current-source preservation, repeat persistence, shared asset classification, override protection, full owner command flow and transactional/composite-lineage rollback. External inputs/Telegram transport are mocked; callbacks verify no database transaction during source access.

Production T-Invest/Telegram smoke checks remain pending without personal credentials. Synthetic dated test observations prove supported calculation behavior, not that the production provider currently supplies those missing dates/inputs. Repeat the build with JDK 25 and Docker running:

```powershell
.\mvnw.cmd clean verify
```

New production files under `src/main/java/ru/ba6kir/tradelab/analysis/`:

- `domain/sector/SectorClassification.java`, `MetricProfile.java`.
- `domain/metric/AnalysisMetric.java`, `FinancialObservation.java`, `DerivedMetricCalculator.java`.
- `domain/comparison/SectorComparison.java`.
- `application/port/AnalysisStore.java`.
- `application/service/SectorClassificationService.java`, `FinancialMetricNormalizer.java`, `FinancialMetricService.java`, `SectorComparisonService.java`.
- `infrastructure/configuration/AnalysisProperties.java`, `AnalysisConfiguration.java`; `infrastructure/persistence/JdbcAnalysisStore.java`.
- `telegram/AnalysisCommand.java`, `AnalysisTelegramConfiguration.java`.

Also added: V6 migration, this document, and seven test files under `src/test/java/ru/ba6kir/tradelab/analysis/` (fixtures, formulas, classification/readiness, peers, service/commands, configuration, PostgreSQL/full-flow integration).

Modified: `fundamentals/domain/model/AssetIdentity.java`, `fundamentals/infrastructure/tinvest/TInvestFundamentalsAdapter.java`, `fundamentals/infrastructure/persistence/JdbcFundamentalsStore.java`, `fundamentals/application/service/FundamentalsService.java`; source-sector assertions in `TInvestFundamentalsAdapterTest.java`; migration assertions in `TradeLabApplicationIT.java`; `application.yml`, `.env.example`, `compose.yaml`, `.gitignore`, README and architecture documentation. No dependency change or Stage 5.2 implementation was introduced.
