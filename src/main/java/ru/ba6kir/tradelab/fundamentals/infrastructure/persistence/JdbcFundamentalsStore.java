package ru.ba6kir.tradelab.fundamentals.infrastructure.persistence;

import java.sql.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ba6kir.tradelab.fundamentals.application.port.FundamentalsStore;
import ru.ba6kir.tradelab.fundamentals.domain.model.*;
import ru.ba6kir.tradelab.fundamentals.domain.validation.FundamentalNormalizer;

public class JdbcFundamentalsStore implements FundamentalsStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    public JdbcFundamentalsStore(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper json) {
        this.jdbc=jdbc; this.tx=new TransactionTemplate(manager); this.json=json;
    }
    private static OffsetDateTime utc(Instant value) { return value==null?null:value.atOffset(ZoneOffset.UTC); }
    private static Instant instant(ResultSet r,String column) throws SQLException {
        var value=r.getObject(column,OffsetDateTime.class); return value==null?null:value.toInstant();
    }
    @Override public Optional<AssetIdentity> findIdentity(String uid) {
        return jdbc.query("""
                SELECT a.*, l.instrument_uid, l.verified_at FROM asset_identities a
                JOIN instrument_asset_links l USING(asset_uid) WHERE l.instrument_uid=?
                """,(r,n) -> new AssetIdentity(r.getString("asset_uid"),r.getString("asset_name"),r.getString("company_name"),
                r.getString("brand_uid"),r.getString("instrument_uid"),r.getString("source"),instant(r,"verified_at")),uid).stream().findFirst();
    }
    @Override public void saveIdentity(AssetIdentity identity) {
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO asset_identities(asset_uid,asset_name,company_name,brand_uid,source,updated_at) VALUES(?,?,?,?,?,?)
                    ON CONFLICT(asset_uid) DO UPDATE SET asset_name=EXCLUDED.asset_name,company_name=EXCLUDED.company_name,
                      brand_uid=EXCLUDED.brand_uid,source=EXCLUDED.source,updated_at=EXCLUDED.updated_at
                    WHERE asset_identities.updated_at<=EXCLUDED.updated_at
                    """,identity.assetUid(),identity.assetName(),identity.companyName(),identity.brandUid(),identity.source(),utc(identity.verifiedAt()));
            jdbc.update("""
                    INSERT INTO instrument_asset_links(instrument_uid,asset_uid,verified_at) VALUES(?,?,?)
                    ON CONFLICT(instrument_uid) DO UPDATE SET asset_uid=EXCLUDED.asset_uid,verified_at=EXCLUDED.verified_at
                    WHERE instrument_asset_links.verified_at<=EXCLUDED.verified_at
                    """,identity.instrumentUid(),identity.assetUid(),utc(identity.verifiedAt()));
        });
    }
    @Override public Optional<FundamentalSnapshot> latest(String uid) {
        return jdbc.query("SELECT * FROM fundamental_snapshots WHERE asset_uid=? ORDER BY id DESC LIMIT 1",this::snapshot,uid).stream().findFirst();
    }
    private FundamentalSnapshot snapshot(ResultSet r,int row) throws SQLException {
        long id=r.getLong("id");
        var metrics=jdbc.query("SELECT * FROM fundamental_metric_values WHERE snapshot_id=? ORDER BY metric_code",(m,n) ->
                new MetricValue(MetricCode.valueOf(m.getString("metric_code")),m.getBigDecimal("metric_value"),
                        MetricCode.Unit.valueOf(m.getString("unit")),m.getString("currency"),m.getString("reporting_period"),
                        QualityStatus.valueOf(m.getString("quality_status")),m.getString("quality_reason"),m.getString("raw_value")),id);
        Map<String,String> raw;
        try { raw=json.readValue(r.getString("raw_payload"),new TypeReference<Map<String,String>>(){}); }
        catch (Exception ignored) { throw new IllegalStateException("Cannot read normalized fundamentals diagnostics"); }
        return new FundamentalSnapshot(id,r.getString("asset_uid"),r.getString("source"),instant(r,"fetched_at"),instant(r,"last_fetched_at"),
                instant(r,"provider_as_of"),instant(r,"fiscal_period_start"),instant(r,"fiscal_period_end"),r.getString("content_hash"),
                r.getString("completeness_status"),metrics,raw);
    }
    @Override public RequestState requestState(String assetUid) {
        return jdbc.query("SELECT last_success,last_failure,failure_code FROM asset_identities WHERE asset_uid=?",(r,n) ->
                new RequestState(instant(r,"last_success"),instant(r,"last_failure"),r.getString("failure_code")),assetUid)
                .stream().findFirst().orElse(new RequestState(null,null,null));
    }
    @Override public boolean tryStart(String assetUid,Instant attempt,Duration cooldown) {
        return jdbc.update("""
                UPDATE asset_identities SET last_attempt=?,lease_until=? WHERE asset_uid=?
                  AND (last_attempt IS NULL OR last_attempt<=?) AND (lease_until IS NULL OR lease_until<=?)
                """,utc(attempt),utc(attempt.plus(Duration.ofMinutes(10))),assetUid,utc(attempt.minus(cooldown)),utc(attempt))==1;
    }
    @Override public SaveResult saveSnapshot(FundamentalSnapshot incoming,Instant attempt) {
        String raw;
        try { raw=json.writeValueAsString(incoming.rawPayload()); }
        catch (Exception ignored) { throw new IllegalStateException("Cannot encode fundamentals diagnostics"); }
        final String payload=raw;
        return Objects.requireNonNull(tx.execute(status -> {
            jdbc.queryForObject("SELECT asset_uid FROM asset_identities WHERE asset_uid=? FOR UPDATE",String.class,incoming.assetUid());
            Boolean active=jdbc.queryForObject("SELECT last_attempt=? FROM asset_identities WHERE asset_uid=?",Boolean.class,utc(attempt),incoming.assetUid());
            if (!Boolean.TRUE.equals(active)) throw new IllegalStateException("Fundamental refresh admission expired");
            var previous=latest(incoming.assetUid()).orElse(null);
            boolean older=previous!=null && incoming.lastFetchedAt().isBefore(previous.lastFetchedAt());
            boolean changed=previous==null || (!older && !previous.contentHash().equals(incoming.contentHash()));
            int updates=0;
            if (changed) {
                long version=jdbc.queryForObject("SELECT coalesce(max(version_number),0)+1 FROM fundamental_snapshots WHERE asset_uid=? AND source=?",
                        Long.class,incoming.assetUid(),incoming.source());
                Long id=jdbc.queryForObject("""
                        INSERT INTO fundamental_snapshots(asset_uid,source,version_number,fetched_at,last_fetched_at,provider_as_of,
                          fiscal_period_start,fiscal_period_end,content_hash,completeness_status,raw_payload)
                        VALUES(?,?,?,?,?,?,?,?,?,?,CAST(? AS JSONB)) RETURNING id
                        """,Long.class,incoming.assetUid(),incoming.source(),version,utc(incoming.fetchedAt()),utc(incoming.lastFetchedAt()),
                        utc(incoming.providerAsOf()),utc(incoming.periodStart()),utc(incoming.periodEnd()),incoming.contentHash(),incoming.completeness(),payload);
                var oldValues=new EnumMap<MetricCode,MetricValue>(MetricCode.class);
                if (previous!=null) previous.metrics().forEach(m -> oldValues.put(m.code(),m));
                for (var metric:incoming.metrics()) {
                    jdbc.update("""
                            INSERT INTO fundamental_metric_values(snapshot_id,metric_code,metric_value,unit,currency,reporting_period,
                              quality_status,quality_reason,raw_value) VALUES(?,?,?,?,?,?,?,?,?)
                            """,id,metric.code().name(),metric.value(),metric.unit().name(),metric.currency(),metric.reportingPeriod(),
                            metric.quality().name(),metric.reason(),metric.rawValue());
                    if (!metric.equals(oldValues.get(metric.code()))) updates++;
                }
            } else if (!older) jdbc.update("UPDATE fundamental_snapshots SET last_fetched_at=?,retrieval_count=retrieval_count+1 WHERE id=?",
                    utc(incoming.lastFetchedAt()),previous.id());
            jdbc.update("""
                    UPDATE asset_identities SET last_success=GREATEST(last_success,?),lease_until=NULL,failure_code=NULL
                    WHERE asset_uid=? AND last_attempt=?
                    """,utc(incoming.lastFetchedAt()),incoming.assetUid(),utc(attempt));
            return new SaveResult(latest(incoming.assetUid()).orElseThrow(),changed,updates);
        }));
    }
    @Override public void failed(String assetUid,Instant attempt,Instant failedAt,String code) {
        jdbc.update("UPDATE asset_identities SET last_failure=?,failure_code=?,lease_until=NULL WHERE asset_uid=? AND last_attempt=?",
                utc(failedAt),code,assetUid,utc(attempt));
    }
    @Override public Optional<ReportBatch> reports(String uid) {
        return jdbc.query("SELECT * FROM financial_report_sync WHERE instrument_uid=?",(r,n) -> {
            Instant fetched=instant(r,"fetched_at");
            var events=jdbc.query("SELECT * FROM financial_report_events WHERE instrument_uid=? AND fetched_at=? ORDER BY report_date NULLS LAST",
                    (e,i) -> new FinancialReportEvent(uid,instant(e,"report_date"),e.getObject("reporting_year",Integer.class),
                            e.getObject("period_number",Integer.class),e.getString("reporting_period"),instant(e,"provider_created_at"),
                            e.getString("source"),instant(e,"fetched_at")),uid,utc(fetched));
            return new ReportBatch(events,fetched,instant(r,"from_time"),instant(r,"to_time"));
        },uid).stream().findFirst();
    }
    @Override public void saveReports(String uid,List<FinancialReportEvent> events,Instant fetchedAt,Instant from,Instant to) {
        tx.executeWithoutResult(status -> {
            for (var event:events) {
                var fingerprint=new TreeMap<String,String>();
                fingerprint.put("instrument",uid); fingerprint.put("source",event.source()); fingerprint.put("date",String.valueOf(event.reportDate()));
                fingerprint.put("year",String.valueOf(event.reportingYear())); fingerprint.put("number",String.valueOf(event.periodNumber()));
                fingerprint.put("period_type",String.valueOf(event.periodType())); fingerprint.put("created",String.valueOf(event.providerCreatedAt()));
                jdbc.update("""
                        INSERT INTO financial_report_events(instrument_uid,event_hash,report_date,reporting_year,period_number,
                          reporting_period,provider_created_at,source,fetched_at) VALUES(?,?,?,?,?,?,?,?,?)
                        ON CONFLICT(instrument_uid,source,event_hash) DO UPDATE SET fetched_at=GREATEST(financial_report_events.fetched_at,EXCLUDED.fetched_at)
                        """,uid,FundamentalNormalizer.hash(fingerprint),utc(event.reportDate()),event.reportingYear(),event.periodNumber(),
                        event.periodType(),utc(event.providerCreatedAt()),event.source(),utc(fetchedAt));
            }
            jdbc.update("""
                    INSERT INTO financial_report_sync(instrument_uid,fetched_at,from_time,to_time) VALUES(?,?,?,?)
                    ON CONFLICT(instrument_uid) DO UPDATE SET fetched_at=EXCLUDED.fetched_at,from_time=EXCLUDED.from_time,to_time=EXCLUDED.to_time
                    WHERE financial_report_sync.fetched_at<=EXCLUDED.fetched_at
                    """,uid,utc(fetchedAt),utc(from),utc(to));
        });
    }
}
