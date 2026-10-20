# Fundamental Scoring Engine — Task 5.2

**Fundamental scores are experimental heuristic indicators and have not yet been validated as predictors of investment returns.**

A score is an internal assessment under a particular finite set of financial rules. It is not a probability of profit, expected return, intrinsic value, price target, instruction to trade or portfolio recommendation. No ranking, stock selection, strategy backtest or empirical optimization was added. Work stops at Task 5.2.

## Architecture and consistent input capture

The engine extends `analysis/scoring`, with domain models/profiles/rules/risks, application use cases, configuration/persistence adapters and thin Telegram handlers. `FundamentalScoringEngine` is pure: its inputs are a frozen `ScoringInput`, a versioned `ScoringModel`, fingerprints and an explicit evaluation instant. It has no API, JDBC, Spring, clock or Telegram calls. Contributions and eligibility codes form its structured explanation; no generated narrative invents reasons.

The production input adapter captures existing data in one **read-only REPEATABLE READ** transaction. It uses new local-only application contracts: `FundamentalsService.cachedInstrument`, `cachedInstrumentUids`, `DividendService.cached` and `FinancialMetricService.cached`. These never resolve tickers, refresh providers, persist classifications or save derived analysis rows. The capture transaction closes before scoring and the separate atomic score-save transaction. No network request spans a database transaction.

Ticker resolution for `/score SBER` remains the existing instrument lookup, outside capture/calculation. Supplying instrument UID accesses already stored data without a provider token or enabled broker integration. An absent cache is an explicit failure, not an automatic provider fetch or synthetic observation. Populate genuine inputs separately through `/stock`, `/fundrefresh`, `/divrefresh` and existing price commands.

The frozen input contains instrument/asset identity, classification/evidence/freshness, every canonical observation with its value, units, dates, origin/quality/method/lineage, recorded-dividend evidence, trading metadata and warnings. Actual snapshot identifiers and dividend content hashes remain traceable. The original fund/dividend stores remain the source of detailed immutable source observations.

## Formula and model policy

Baseline version: `fundamental-heuristic-1`, configuration `initial-1`.

```text
S = 0.35 × Q + 0.25 × F + 0.25 × V + 0.15 × D
```

| Pillar | Meaning | Baseline weight |
| --- | --- | --- |
| Q — Quality | Supported profitability/capital-efficiency observations, not proof of sustainable economics | 0.35 |
| F — Financial Stability | Profile-appropriate leverage/liquidity or genuinely defined bank measures | 0.25 |
| V — Valuation | Guarded positive multiples with explicit profitability/financial context | 0.25 |
| D — Dividends | Reliable recorded history and historical amount-to-reference-price yield, not cash receipts | 0.15 |

All arithmetic uses BigDecimal. Each metric curve returns 0–100, rounded HALF_UP to six decimals after interpolation at scale 12. Multiplication by within-pillar weights is exact; contributions are summed before rounding the pillar to six decimals. Overall weighting is exact before final six-decimal rounding. Financial subtraction/ratios from Stage 5.1 retain their own documented input validation. There is no floating-point financial arithmetic.

Both pillar weights and each profile's metric weights must sum **exactly** to one. Every nonzero pillar must be mandatory. Baseline eligibility requires all four pillars and all their weighted metrics. Coverage is the sum of usable metric weights, not a financial value and not an imputed score. A missing metric has a null metric score/contribution; a missing pillar has a null pillar score; any missing mandatory pillar prevents a comparable final score. No missing input becomes zero, 50 or an average, and remaining weights are not automatically renormalized.

An operator may explicitly create a differently versioned model with a zero-weight, nonmandatory pillar and explicitly chosen remaining weights summing to one. It carries `CUSTOM_PILLAR_POLICY`, and is not comparable to the four-pillar baseline. Merely removing a pillar from the mandatory list while keeping its positive weight fails configuration validation. This is an explicit methodology change, not a missing-data shortcut.

## Profiles and minimum evidence

Profile resolution requires a reliable known sector/business type: FINANCIALS/BANK→BANK; OIL_AND_GAS/NON_FINANCIAL and METALS_AND_MINING/NON_FINANCIAL select their profiles; other known non-financial sectors select GENERAL_NON_FINANCIAL. Unknown classifications and non-bank financial companies remain `UNSUPPORTED_PROFILE`. There is no bank/industrial fallback for an unsupported business.

| Profile | Q metrics / within-pillar weights | F metrics / weights | V metrics / weights | D metrics / weights |
| --- | --- | --- | --- | --- |
| BANK | ROE .6, ROA .4 | Capital adequacy .7, NPL/gross loans .3 | P/B .6, P/E .4 | Recorded trailing yield .5, observed record years .5 |
| OIL_AND_GAS | ROIC .6, net margin .4 | Net Debt/EBITDA .7, current ratio .3 | EV/EBITDA .6, P/E .4 | Same D measures |
| METALS_AND_MINING | ROIC .6, mining-specific net margin .4 | Net Debt/EBITDA .7, current ratio .3 | Mining-specific EV/EBITDA .6, P/E .4 | Same D measures |
| GENERAL_NON_FINANCIAL | ROE .6, net margin .4 | Net Debt/EBITDA .7, current ratio .3 | P/E .6, P/S .4 | Same D measures |

All eight baseline weighted metrics are required. Net income is an additional mandatory valuation/context input. Non-bank profiles retain FCF and profit-growth concepts as optional context; missing optional context never receives points, and available negative industrial FCF generates a warning without an invented cash-quality formula. Bank cash flow is not interpreted using that industrial FCF rule. Growth is not fabricated from unrelated periods.

Q/F/V default rules select **PROVIDER** observations; D selects the validated Stage 4 **DERIVED** security observations. A model can explicitly configure another origin/definition, but never silently choose whichever provider/local variant scores better. More than one actual candidate of the selected origin is ambiguous. Invalid/suspicious candidates are not cherry-picked away in favor of a convenient value. A missing placeholder cannot hide an additional real variant.

Bank profiles cannot contain industrial leverage/liquidity rules. Their F inputs must be percentages with exact methodologies `verified:total-regulatory-capital-to-rwa-percent` and `verified:npl-to-gross-loans-percent`. Generic capital ratios or vaguely labelled asset quality do not qualify. These are distinct measures; see the [IMF FSI definitions](https://data.imf.org/api/document/download?key=60212398). That reference defines concepts, **not** the model's scoring thresholds or Russian regulatory compliance. The selected provider adapter does not currently supply these verified bank inputs, and none is invented.

## Normalization and explicit experimental thresholds

The only implemented scoring method is **ABSOLUTE_PIECEWISE_LINEAR_V1**. Each row below lists `input : score` knots. Between adjacent knots `(x0,s0)` and `(x1,s1)`:

```text
score(x) = s0 + (x − x0) × (s1 − s0) / (x1 − x0)
```

Outside the range, the endpoint score is retained; there is no extrapolation. Knots have strictly increasing input values and scores in [0,100]. HIGHER/LOWER directions must agree with monotonic knots; HEALTHY_INTERVAL intentionally need not be monotonic. NOT_SUITABLE cannot carry positive weight. A rule must match its metric's canonical unit and appropriate pillar family, and a metric has only one rule per pillar/profile.

These are manually chosen **model assumptions**, not historical reference distributions, optimized investment thresholds, legal minima or evidence of future returns. Default peaks may be below 100; the engine does not rescale them to imply greater confidence.

| Metric / applicability | Unit, direction | Knots |
| --- | --- | --- |
| ROE — BANK, GENERAL | percent; healthy interval | -30:0, 0:10, 10:60, 20:85, 30:75, 50:20, 100:0 |
| ROA — BANK | percent; healthy interval | -10:0, 0:10, 1:60, 2:85, 4:65, 10:0 |
| ROIC — OIL/GAS, MINING | percent; healthy interval | -20:0, 0:15, 8:55, 15:85, 25:75, 50:20, 100:0 |
| Net margin — OIL/GAS, GENERAL | percent; healthy interval | -30:0, 0:10, 5:45, 15:75, 30:85, 60:50, 100:10 |
| Net margin — MINING | percent; healthy interval | -30:0, 0:10, 5:45, 12:75, 20:85, 50:50, 100:10 |
| Bank regulatory capital/RWA | percent; healthy interval | 0:0, 8:20, 12:60, 16:85, 22:90, 40:70 |
| Bank NPL/gross loans | percent; lower | 0:90, 1:85, 3:65, 5:35, 10:0 |
| Net Debt/EBITDA — non-bank | multiple; healthy interval | -5:65, -1:80, 0:80, 1:85, 2:70, 4:30, 6:0 |
| Current ratio — non-bank | ratio; healthy interval | 0:0, .5:20, 1:55, 1.5:80, 2.5:85, 4:60, 10:20 |
| P/B — BANK | positive multiple; healthy interval | 0:0, .3:0, .7:70, 1.2:85, 2:60, 4:10, 8:0 |
| P/E — all | positive multiple; healthy interval | 0:0, 2:0, 6:75, 12:80, 20:50, 40:10, 80:0 |
| EV/EBITDA — OIL/GAS | positive multiple; healthy interval | 0:0, 1:0, 4:80, 7:70, 12:35, 20:0 |
| EV/EBITDA — MINING | positive multiple; healthy interval | 0:0, 1:0, 4:70, 7:80, 12:35, 20:0 |
| P/S — GENERAL | positive multiple; healthy interval | 0:0, .1:0, .7:80, 1.5:75, 3:40, 6:0 |
| Recorded trailing yield | percent; healthy interval | 0:20, 2:50, 5:80, 8:70, 12:30, 20:0 |
| Observed distinct record years | count of years; higher, bounded | 0:0, 1:20, 3:60, 5:80, 10:90 |

Zero/negative valuation multiples are **unavailable**, not valid zero-point cheapness observations. Negative earnings, margins, FCF or net cash are not automatically invalid. Zero debt does not get the maximal leverage score; very high ROE and dividend yield do not always earn more points. A yield of zero without reliable absence evidence is not scored merely because a curve has a zero endpoint. Near-zero **positive** values interpolate on that curve.

Valuation is capped at zero when valid aligned context shows nonpositive net income or non-bank leverage at/above `SCORING_HIGH_LEVERAGE`. It is also capped when an eligible positively weighted F rule actually scores zero on its configured curve. This covers severely weak bank capital/loan-quality observations without using industrial leverage. The original multiples/values remain visible, contributions carry `CONTEXT_CAPPED`/`DISTRESS_VALUATION_CAP`, and a zero-stability rule produces an evidence-linked critical flag. This is an explicit model condition, not a regulatory or account restriction. Missing/invalid context instead produces an unavailable V pillar; it is never treated as an observed zero.

There is **no peer-based scoring** in this version. Stage 5.1 peer comparisons remain separate. Small/missing peer groups therefore never trigger a fabricated median, global-average fallback or unrecorded percentile methodology. Supporting peer normalization in a later model would require explicitly versioned rules, validated comparable samples and a frozen peer universe; it cannot be silently enabled under this absolute model.

## Eligibility, freshness and reporting periods

The validator checks actual identity, source observation reference, source snapshot/lineage, origin, unit, quality, explicit reporting dates and freshness before evaluating a curve. Financial values must match the subject asset/source snapshot and instrument-scoped observations must match the selected security. UNKNOWN/UNVERIFIED_RATE, SUSPECT, stale or malformed values cannot become valid merely because the numeric threshold looks favorable.

Financial flows require explicit compatible type, horizon and start/end dates. An annual ratio is not combined with TTM income. Balance-sheet point-in-time measures may align to the same verified income-period end. Available net-income context also anchors Q/F periods; V always requires it. Within each financial pillar dates must agree, and V's explicit UTC valuation-as-of dates must agree. Default maximum valuation/reference age is seven days. Source/retrieval/reporting dates in the future are invalid. Default observation age is 24h and financial-period age 548d, independently of download freshness. Classification must pass the inherited verified-source/mapping freshness gate.

Dividend evidence requires the Stage 4 recorded-365-day sum/yield checks, a fresh successful coverage window, at least **1095 requested historical days** and **three distinct observed record years**, actual reliable non-cancelled regular-cash records and matching security identity. The history transform uses explicit distinct record years, not the number of events relabelled as years. No duplicate/cancelled/ambiguous record can inflate that evidence. Retained archived events outside the latest queried window neither add to history nor invalidate the covered window. Old unconfirmed records inside the window do prevent reliable history. An empty response is not proof of no policy/payments, and absence never becomes a zero D score.

`minimumDividendDays`/`minimumDividendYears` are eligibility assumptions, not proof of payout consistency or source completeness. Calendar-year observations do not establish fiscal recurrence or future payment. The current five-year maximum ingestion window also limits attainable history evidence. This model does not calculate payout sustainability, accrual quality or cash-coverage ratios without their missing compatible inputs.

Outcomes:

- `SCORED`: every positively weighted mandatory pillar and required context is supported; overall is nonnull.
- `PARTIAL`: some pillars can be assessed, but overall remains null.
- `INSUFFICIENT_DATA`: insufficient usable evidence for any complete pillar.
- `STALE_DATA`: supported profile but stale/unreliable classification or financial evidence blocks scoring.
- `UNSUPPORTED_PROFILE`: unknown/inapplicable sector/business profile.
- `FAILED`: cache capture, identity resolution or persistence failed; no arbitrary overall score is returned.

Reasons are machine-readable, for example `MISSING_ROIC`, `MISSING_F_PILLAR_EVIDENCE`, `AMBIGUOUS_PE`, `INCOMPATIBLE_REPORTING_PERIOD_NET_MARGIN`, `STALE_FINANCIAL_DATA_PE`, `UNVERIFIED_DEFINITION_CAPITAL_ADEQUACY`, `UNRELIABLE_DIVIDEND_HISTORY` and `MODEL_VERSION_CONFLICT`. `getScoringEligibility` is a complete dry evaluation, not a provider fetch or score save.

## Risk flags

Each flag contains rule ID, INFO/WARNING/CRITICAL severity, actual metric/value when available, source IDs, condition, explanation and source observation timestamp. Missing-data flags may have no actual metric/value because inventing one would be misleading. Flags cover negative net income, negative **non-bank** FCF, high eligible industrial leverage, extreme/undefined P/E relative to the configured endpoint, extreme dividend yield, inadequate dividend evidence, stale/insufficient coverage, zero-scoring stability evidence, custom pillar policy and metadata trading availability. Mining receives a cyclical-valuation context note; it does not receive an invented cycle forecast.

Instrument API availability/trading status is a dated metadata observation, not a live account restriction or trading-session check. No sanctions, geopolitical event or company-specific risk is fabricated. Profitability deterioration is not asserted without a genuine compatible historical series; the current input adapter does not manufacture that series. Warnings never become an automatic purchase/sale decision. Optional FCF warnings currently have no hidden numerical penalty; their role and zero scoring weight are explicit.

## Configuration and versioning

| Environment setting | Default |
| --- | --- |
| `SCORING_MODEL_VERSION` | fundamental-heuristic-1 |
| `SCORING_CONFIGURATION_VERSION` | initial-1 |
| `SCORING_WEIGHT_Q/F/V/D` | .35 / .25 / .25 / .15 |
| `SCORING_MANDATORY_PILLARS` | Q,F,V,D |
| `SCORING_OBSERVATION_MAX_AGE` | 24h; positive, at most 30d |
| `SCORING_FINANCIAL_PERIOD_MAX_AGE` | 548d; 30–3650d |
| `SCORING_MINIMUM_DIVIDEND_DAYS` | 1095; 365–1827 |
| `SCORING_MINIMUM_DIVIDEND_YEARS` | 3; 1–5 |
| `SCORING_EXTREME_YIELD` | 20 percent, positive |
| `SCORING_HIGH_LEVERAGE` | 6, positive |

All defaults/placeholders are bound by typed configuration and forwarded by Compose. Profile curves, directions, origins, definitions, requirements and within-pillar weights can be explicitly replaced with a `tradelab.scoring.profiles` list in an additional Spring YAML file (the existing ignored `analysis-local.yml` can hold both analysis and scoring settings). Each replacement supplies a complete supported profile ID, appropriately grouped rules covering all four pillars and context sets; it is validated before startup. The baseline uses eight weighted rules; an explicit replacement changes the declared evidence set and must receive a new configuration version. No generic plugin system is introduced. Bank metric restrictions and the two bank measure-definition guards cannot be bypassed through configuration.

A rule record has `id`, `pillar`, `metric`, `unit`, `origin`, `direction`, `required`, `weight`, `points: [{value, score}, ...]`, optional `required-methodology`, and `transformation` (`IDENTITY` or `DISTINCT_RECORDED_YEARS`). Profile records contain `id`, `rules`, `required-context` and `optional-context`. Metric/weight sums, curve order/bounds/directions, pillar families, duplicate metrics and canonical units are validated. The full effective model, including default curves, is stored in the model table; an empty override list does not hide defaults from the audit trail.

Any behavior/algorithm change requires a model-version change; threshold/policy/weight changes require an explicit configuration-version change. A SHA-256 configuration fingerprint covers the full effective model. The `(model_version, configuration_version)` pair is immutable: attempting to bind it to a different configuration returns `MODEL_VERSION_CONFLICT`, preserves prior history and does not overwrite the old model.

## Persistence and reproducibility

Additive Flyway **V7** creates:

- `scoring_model_versions`: immutable version pair, full effective profile/policy JSON, weights, thresholds and configuration fingerprint.
- `stock_score_snapshots`: nullable instrument/asset FKs, profile/version, evaluation/cutoff timestamps, status, nullable overall and Q/F/V/D, coverage, exact frozen input/result JSON and fingerprints.
- `scoring_metric_contributions`: source observation reference, normalized value, rule, metric/pillar weights, metric score, contribution and unavailable/context-cap reasons.
- `scoring_risk_flags`: severity, explanation, actual value/condition and complete supporting evidence.

Scores/contributions use NUMERIC. Scores are constrained to [0,100]; only SCORED rows may contain an overall value. One short transaction saves model, snapshot, all contributions and flags; an error rolls back the entire batch. Previous results are never destructively updated. Source financial/dividend snapshots remain separate and unchanged.

Input and model fingerprints use canonical JSON with sorted object keys/sets and stable observation/event ordering. Replay uses the frozen input, stored effective model and **original evaluation instant**; it does not re-fetch data or reinterpret dates using today's clock. The data-cutoff field is the latest received-at timestamp among captured input observations, not a claimed financial-publication date. Individual reporting/as-of/retrieval timestamps remain available for assessing their different ages.

Exact repeated inputs/configuration and the same eligibility state reuse the original result ID/evaluation timestamp; no duplicate contribution/flag rows accumulate. A changed source snapshot, value, quality, classification or configuration appends a new result. Temporal expiry may change eligibility even when raw input bytes are identical: a separate semantic evaluation fingerprint includes score status, reasons, contributions and risks. Thus a stale evaluation can never deduplicate to an earlier fresh SCORED row. Histories show the latest 20 stored distinct results and their model/configuration identities, without a cross-version score delta.

Application API: `evaluateInstrument(uid)`; `evaluateInstrument(uid, false)`; `evaluateAsset(assetUid)`; `getLatestScore(uid)`; `getScoreHistory(uid)`; `getScoringEligibility(uid)`. Asset evaluation requires exactly one cached security context because valuation/dividends can differ by share class. Zero or several cached securities yields an explicit non-scored failure rather than choosing ordinary/preferred arbitrarily. Capture failures with no proven stored identity return typed unsaved failures; write failures also return FAILED without pretending a snapshot was saved.

## Telegram and practical limits

```text
/score SBER
/scoreinfo SBER
/scorehistory SBER
/help
```

All commands accept instrument UID and use the existing owner/private-chat authorization. `/score` shows the actual score or exact unscored status/reasons, profile/version, Q/F/V/D, coverage, timestamps and main risks. `/scoreinfo` explains metric contributions, source IDs, methodology and fingerprints without saving. `/scorehistory` labels versions/configuration hashes and does not equate different methodologies. No notification or background schedule is added.

The current selected dataset generally cannot justify production baseline scores. In particular, **SBER under BANK remains ineligible without genuinely sourced, defined capital adequacy and NPL inputs**; the current adapter has neither. **LKOH/GMKN under their applicable profiles remain unscored when per-metric periods and percentage scales are unverified**, as in the current normalized source contract. Unknown/unconfigured asset classifications also prevent scoring. This describes structural input limitations, not a live audit of those companies or a claim about their financial health. No real-company score was fabricated or verified with personal credentials.

An explicitly synthetic oil/gas fixture in tests supplies complete dated/defined inputs and produces Q=81, F=83.5, V=78, D=75 and S=79.975. **Those are synthetic test values, not any real company's financial observations or score.** The same tests show incomplete/stale/invalid inputs refusing a comparable total. Current default requirements exclude many incomplete or non-dividend datasets; that is a coverage constraint, not a judgment about future performance.

Threshold calibration, explanatory power, sector stability, accounting-definition comparability, survivorship/coverage biases, cycle behavior and predictive usefulness remain unvalidated. No return study or backtest was performed. More complete verified financial statements, per-metric dates, bank data and independent model research are needed before interpreting these heuristic points beyond the stated methodology.

## Files and verification

Added 19 production files under `src/main/java/ru/ba6kir/tradelab/analysis/scoring/`:

- `domain/model/ScoringInput.java`, `ScoringResult.java`.
- `domain/profile/ScoringModel.java`, `ScoringProfiles.java`.
- `domain/rule/MetricScoringRule.java`, `ScoringEligibilityValidator.java`, `FundamentalScoringEngine.java`.
- `domain/risk/FundamentalRiskEvaluator.java`.
- `application/service/ScoringInputProvider.java`, `ScoringResultRepository.java`, `ScoringFingerprint.java`, `FundamentalScoringService.java`.
- `infrastructure/configuration/ScoringProperties.java`, `ScoringFingerprints.java`, `CachedScoringInputAdapter.java`, `ScoringConfiguration.java`.
- `infrastructure/persistence/JdbcScoringResultRepository.java`.
- `telegram/ScoreCommand.java`, `ScoringTelegramConfiguration.java`.

Also added V7, this document and five files in `src/test/java/ru/ba6kir/tradelab/scoring/` (synthetic fixtures, engine tests, service/command tests, configuration tests and PostgreSQL/full-flow tests). Modified existing Fundamentals store/service contracts and JDBC adapter, DividendService, sector/metric application services for local-only reads and covered-window history, application YAML, `.env.example`, Compose, migration assertions, README and architecture. No new dependency or production fixture was added. Task 5.1 changes remain intact.

Verification executed on 2026-10-20: Maven `clean verify` finished with **BUILD SUCCESS** in 58.093 seconds. All **310 tests passed: 260 unit/configuration tests and 50 integration tests**, with zero failures, errors or skipped tests. This includes 31 new scoring unit/configuration tests and nine new scoring integration tests, plus all 270 previous tests. The build produced `target/tradelab-portfolio-0.0.1-SNAPSHOT.jar`; detailed reports are in `target/surefire-reports/` and `target/failsafe-reports/`, with the local run log at `.tools/scoring/verify-final.log` (generated artifacts are ignored by Git).

Automated checks use explicitly synthetic fixtures/mock external inputs and disposable PostgreSQL 17. They cover normalization boundaries and interpolation, deterministic precision, all profiles, eligibility failures, distress caps, configuration guards, risk evidence, frozen snapshots, version conflicts, deduplication, changed inputs, freshness expiry, transaction rollback, read-only cache capture, dividend coverage, Telegram owner authorization and regression behavior. Real T-Invest/Telegram smoke checks remain pending without personal credentials.
