package ru.ba6kir.tradelab.analysis.ranking.persistence;

import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ba6kir.tradelab.analysis.ranking.application.RankingStore;
import ru.ba6kir.tradelab.analysis.ranking.domain.*;

public class JdbcRankingStore implements RankingStore {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final TransactionTemplate tx;private final Clock clock;
    public JdbcRankingStore(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager manager) { this(jdbc,json,manager,Clock.systemUTC()); }
    public JdbcRankingStore(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager manager,Clock clock) { this.jdbc=jdbc;this.json=json;this.clock=clock;tx=new TransactionTemplate(manager); }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch (java.io.IOException e) { throw new IllegalStateException("Ranking serialization failed"); } }
    private <T> T decode(String value,Class<T> type) { try { return json.readValue(value,type); } catch (java.io.IOException e) { throw new IllegalStateException("Ranking deserialization failed"); } }
    private static OffsetDateTime utc(Instant value) { return value==null?null:value.atOffset(ZoneOffset.UTC); }
    private static Instant instant(java.sql.ResultSet r,String key) throws java.sql.SQLException { var value=r.getObject(key,OffsetDateTime.class);return value==null?null:value.toInstant(); }
    @Override public boolean tryStart(Instant token,Duration cooldown) {
        return jdbc.update("""
                UPDATE ranking_refresh_state SET last_attempt=?,lease_until=?,last_failure=NULL
                WHERE id=1 AND (lease_until IS NULL OR lease_until<=?) AND (last_attempt IS NULL OR last_attempt<=?)
                """,utc(token),utc(token.plus(Duration.ofMinutes(10))),utc(token),utc(token.minus(cooldown)))==1;
    }
    @Override public RankingRun save(RankingRun run,Instant token) {
        return Objects.requireNonNull(tx.execute(status -> {
            var state=jdbc.queryForObject("SELECT last_attempt,lease_until FROM ranking_refresh_state WHERE id=1 FOR UPDATE",(r,n) -> new State(instant(r,"last_attempt"),instant(r,"lease_until"),null,null));
            if (state==null || !token.equals(state.lastAttempt()) || state.leaseUntil()==null || !state.leaseUntil().isAfter(clock.instant())
                    || !run.expiresAt().isAfter(clock.instant())) throw new IllegalStateException("Superseded ranking lease or expired evidence");
            long id=Objects.requireNonNull(jdbc.queryForObject("""
                    INSERT INTO ranking_runs(created_at,expires_at,model_version,configuration_fingerprint,eligibility_policy_version,policy_fingerprint,
                      universe_hash,ranking_scope,status,eligible_count,excluded_count,universe,policy)
                    VALUES(?,?,?,?,?,?,?,'PROFILE_PERIOD_GROUPS','COMPLETE',?,?,CAST(? AS JSONB),CAST(? AS JSONB)) RETURNING id
                    """,Long.class,utc(run.createdAt()),utc(run.expiresAt()),run.modelVersion(),run.configurationFingerprint(),run.policy().version(),run.policyFingerprint(),
                    run.universeHash(),run.eligibleCount(),run.excludedCount(),encode(run.universe()),encode(run.policy())));
            for (var e:run.entries()) {
                // Unresolved UIDs have no instruments row. Keep the requested UID in the immutable evidence JSON.
                String foreignUid=e.instrumentUid()!=null && jdbc.queryForObject("SELECT COUNT(*) FROM instruments WHERE uid=?",Integer.class,e.instrumentUid())==1?e.instrumentUid():null;
                jdbc.update("""
                        INSERT INTO ranking_entries(ranking_run_id,member_key,instrument_uid,score_snapshot_id,sector,comparison_group,rank,score,eligibility_status,exclusion_reasons,evidence)
                        VALUES(?,?,?,?,?,?,?,?,?,CAST(? AS JSONB),CAST(? AS JSONB))
                        """,id,e.memberKey(),foreignUid,e.scoreSnapshotId(),e.sector(),e.comparisonGroup(),e.rank(),e.score(),e.status().name(),encode(e.reasons()),encode(e));
            }
            jdbc.update("UPDATE ranking_refresh_state SET lease_until=NULL,last_success=?,last_failure=NULL WHERE id=1 AND last_attempt=?",utc(token),utc(token));
            return run.withId(id);
        }));
    }
    @Override public void fail(Instant token,String reason) {
        jdbc.update("UPDATE ranking_refresh_state SET lease_until=NULL,last_failure=? WHERE id=1 AND last_attempt=?",reason,utc(token));
    }
    @Override public Optional<RankingRun> latest() {
        return jdbc.query("SELECT * FROM ranking_runs ORDER BY id DESC LIMIT 1",(r,n) -> {
            long id=r.getLong("id");
            var members=Arrays.asList(decode(r.getString("universe"),RankingRun.Member[].class));
            var entries=jdbc.query("SELECT evidence FROM ranking_entries WHERE ranking_run_id=? ORDER BY (rank IS NULL),comparison_group,rank,member_key",
                    (entry,k) -> decode(entry.getString(1),RankingRun.Entry.class),id);
            return new RankingRun(id,instant(r,"created_at"),instant(r,"expires_at"),r.getString("model_version"),r.getString("configuration_fingerprint"),
                    decode(r.getString("policy"),RankingPolicy.class),r.getString("policy_fingerprint"),r.getString("universe_hash"),members,entries);
        }).stream().findFirst();
    }
    @Override public State state() { return jdbc.queryForObject("SELECT * FROM ranking_refresh_state WHERE id=1",(r,n) -> new State(instant(r,"last_attempt"),instant(r,"lease_until"),instant(r,"last_success"),r.getString("last_failure"))); }
}
