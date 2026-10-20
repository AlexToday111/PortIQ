package ru.ba6kir.tradelab.dividends.infrastructure.persistence;

import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ba6kir.tradelab.dividends.application.port.DividendStore;
import ru.ba6kir.tradelab.dividends.domain.model.DividendEvent;
import ru.ba6kir.tradelab.dividends.domain.model.DividendEvent.Observation;

public class JdbcDividendStore implements DividendStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    public JdbcDividendStore(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper json) {
        this.jdbc=jdbc; this.tx=new TransactionTemplate(manager); this.json=json;
    }
    private static OffsetDateTime utc(Instant t) { return t==null?null:t.atOffset(ZoneOffset.UTC); }
    @Override public List<Observation> find(String uid) {
        return jdbc.query("SELECT observation,ambiguous,first_seen_at,last_seen_at FROM dividend_events WHERE instrument_uid=? ORDER BY record_date NULLS LAST,content_hash",(r,n) -> {
            try { return new Observation(json.readValue(r.getString("observation"),DividendEvent.class),r.getBoolean("ambiguous"),
                    r.getObject("first_seen_at",OffsetDateTime.class).toInstant(),r.getObject("last_seen_at",OffsetDateTime.class).toInstant()); }
            catch (java.io.IOException e) { throw new IllegalStateException("Cannot read dividend observation"); }
        },uid);
    }
    @Override public State state(String uid) {
        return jdbc.query("SELECT * FROM dividend_sync_state WHERE instrument_uid=?",(r,n) -> {
            var success=r.getObject("last_success",OffsetDateTime.class); var failure=r.getObject("last_failure",OffsetDateTime.class);
            return new State(r.getObject("coverage_from",LocalDate.class),r.getObject("coverage_to",LocalDate.class),
                    success==null?null:success.toInstant(),failure==null?null:failure.toInstant(),r.getString("failure_code"));
        },uid).stream().findFirst().orElse(new State(null,null,null,null,null));
    }
    @Override public boolean tryStart(String uid,Instant attempt,Duration cooldown) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            jdbc.update("INSERT INTO dividend_sync_state(instrument_uid) VALUES(?) ON CONFLICT DO NOTHING",uid);
            return jdbc.update("""
                    UPDATE dividend_sync_state SET last_attempt=?,lease_until=? WHERE instrument_uid=?
                      AND (last_attempt IS NULL OR last_attempt<=?) AND (lease_until IS NULL OR lease_until<=?)
                    """,utc(attempt),utc(attempt.plusSeconds(120)),uid,utc(attempt.minus(cooldown)),utc(attempt))==1;
        }));
    }
    @Override public Counts save(String uid,List<DividendEvent> events,LocalDate from,LocalDate to,Instant attempt) {
        return Objects.requireNonNull(tx.execute(status -> {
            Boolean active=jdbc.queryForObject("SELECT last_attempt=? FROM dividend_sync_state WHERE instrument_uid=? FOR UPDATE",
                    Boolean.class,utc(attempt),uid);
            if (!Boolean.TRUE.equals(active)) throw new IllegalStateException("Dividend refresh admission expired");
            int added=0,updated=0,cancelled=0,unchanged=0;
            var unique=new LinkedHashMap<String,DividendEvent>();
            for (var event:events) {
                if (!uid.equals(event.instrumentUid())) throw new IllegalArgumentException("Dividend instrument mismatch");
                unique.putIfAbsent(event.source()+"|"+event.contentHash(),event);
            }
            for (var e:unique.values()) {
                Instant observed=e.retrievedAt().isAfter(attempt)?e.retrievedAt():attempt;
                String payload;
                try { payload=json.writeValueAsString(e); }
                catch (java.io.IOException ex) { throw new IllegalStateException("Cannot encode dividend observation"); }
                int inserted=jdbc.update("""
                        INSERT INTO dividend_events(instrument_uid,amount_per_share,currency,declared_date,last_buy_date,record_date,payment_date,
                          dividend_type,regularity,event_status,provider_yield,provider_reference_price,reference_currency,source,provider_created_at,
                          first_seen_at,last_seen_at,updated_at,content_hash,identity_anchor,ambiguous,observation)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS JSONB)) ON CONFLICT DO NOTHING
                        """,uid,e.amountPerShare(),e.currency(),e.declaredDate(),e.lastBuyDate(),e.recordDate(),e.paymentDate(),e.dividendType(),e.regularity(),
                        e.status(attempt.atOffset(ZoneOffset.UTC).toLocalDate()).name(),e.providerYield(),e.providerReferencePrice(),e.referenceCurrency(),e.source(),
                        utc(e.providerCreatedAt()),utc(observed),utc(observed),utc(observed),e.contentHash(),e.identityAnchor(),e.providerCreatedAt()==null,payload);
                if (inserted==0) {
                    unchanged++;
                    jdbc.update("UPDATE dividend_events SET last_seen_at=GREATEST(last_seen_at,?) WHERE instrument_uid=? AND source=? AND content_hash=?",
                            utc(observed),uid,e.source(),e.contentHash());
                } else {
                    int siblings=jdbc.queryForObject("SELECT count(*) FROM dividend_events WHERE instrument_uid=? AND source=? AND identity_anchor=?",Integer.class,uid,e.source(),e.identityAnchor());
                    if (siblings>1) updated++; else added++;
                    if (e.status(attempt.atOffset(ZoneOffset.UTC).toLocalDate())==DividendEvent.Status.CANCELLED) cancelled++;
                }
            }
            // No stable provider ID exists. Colliding versions remain visible and cannot contribute to yields.
            jdbc.update("""
                    UPDATE dividend_events e SET ambiguous=TRUE,updated_at=? WHERE e.instrument_uid=? AND NOT e.ambiguous
                    AND EXISTS(SELECT 1 FROM dividend_events other WHERE other.instrument_uid=e.instrument_uid
                      AND other.source=e.source AND other.content_hash<>e.content_hash AND
                        (other.identity_anchor=e.identity_anchor OR
                         (e.record_date IS NOT NULL AND other.record_date=e.record_date AND
                           (lower(e.dividend_type)='cancelled' OR lower(other.dividend_type)='cancelled' OR
                             (other.declared_date IS NOT DISTINCT FROM e.declared_date AND
                              other.last_buy_date IS NOT DISTINCT FROM e.last_buy_date AND
                              other.payment_date IS NOT DISTINCT FROM e.payment_date AND
                              other.regularity IS NOT DISTINCT FROM e.regularity)))))
                    """,utc(attempt),uid);
            int ambiguous=jdbc.queryForObject("SELECT count(*) FROM dividend_events WHERE instrument_uid=? AND ambiguous",Integer.class,uid);
            jdbc.update("""
                    UPDATE dividend_sync_state SET coverage_from=?,coverage_to=?,last_success=?,lease_until=NULL,failure_code=NULL
                    WHERE instrument_uid=? AND last_attempt=?
                    """,from,to,utc(attempt),uid,utc(attempt));
            return new Counts(added,updated,cancelled,unchanged,ambiguous);
        }));
    }
    @Override public void fail(String uid,Instant attempt,String code) {
        jdbc.update("UPDATE dividend_sync_state SET last_failure=?,failure_code=?,lease_until=NULL WHERE instrument_uid=? AND last_attempt=?",
                utc(attempt),code,uid,utc(attempt));
    }
}
