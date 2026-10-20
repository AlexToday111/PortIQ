package ru.ba6kir.tradelab.analysis.scoring.infrastructure.persistence;

import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ba6kir.tradelab.analysis.scoring.application.service.ScoringResultRepository;
import ru.ba6kir.tradelab.analysis.scoring.domain.model.ScoringResult;
import ru.ba6kir.tradelab.analysis.scoring.domain.profile.ScoringModel;
import ru.ba6kir.tradelab.analysis.scoring.infrastructure.configuration.ScoringFingerprints;

public class JdbcScoringResultRepository implements ScoringResultRepository {
    private final JdbcTemplate jdbc; private final TransactionTemplate tx; private final ObjectMapper json; private final ScoringFingerprints fingerprints;
    public JdbcScoringResultRepository(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper json,ScoringFingerprints fingerprints) {
        this.jdbc=jdbc; this.tx=new TransactionTemplate(manager); this.json=json; this.fingerprints=fingerprints;
    }
    private String encode(Object o) { try { return json.writeValueAsString(o); } catch (java.io.IOException e) { throw new IllegalStateException("Cannot encode scoring evidence"); } }
    private ScoringResult decode(String text,long id) { try { return json.readValue(text,ScoringResult.class).withId(id); } catch (java.io.IOException e) { throw new IllegalStateException("Cannot read scoring evidence"); } }
    private static OffsetDateTime utc(Instant t) { return t==null?null:t.atOffset(ZoneOffset.UTC); }
    @Override public ScoringResult save(ScoringResult r,ScoringModel model) {
        return Objects.requireNonNull(tx.execute(status -> {
            if (!fingerprints.hash(model).equals(r.configurationFingerprint())) throw new IllegalArgumentException("Scoring configuration fingerprint mismatch");
            jdbc.update("""
                    INSERT INTO scoring_model_versions(configuration_fingerprint,model_version,configuration_version,profile_configuration,weights,thresholds)
                    VALUES(?,?,?,CAST(? AS JSONB),CAST(? AS JSONB),CAST(? AS JSONB)) ON CONFLICT(model_version,configuration_version) DO NOTHING
                    """,r.configurationFingerprint(),model.modelVersion(),model.configurationVersion(),encode(model),encode(model.weights()),encode(model.profiles()));
            String existing=jdbc.queryForObject("SELECT configuration_fingerprint FROM scoring_model_versions WHERE model_version=? AND configuration_version=?",
                    String.class,model.modelVersion(),model.configurationVersion());
            if (!r.configurationFingerprint().equals(existing)) throw new ModelVersionConflictException();
            // Keep time-dependent eligibility in the dedup key: expired input must never resurrect an earlier SCORED result.
            var semantic=new TreeMap<String,Object>(); semantic.put("config",r.configurationFingerprint()); semantic.put("input",r.inputFingerprint());
            semantic.put("status",r.status());semantic.put("scores",r.pillarScores());semantic.put("overall",r.overallScore());
            semantic.put("contributions",r.contributions());semantic.put("risks",r.risks());semantic.put("reasons",r.eligibilityReasons());
            String key=fingerprints.hash(semantic);
            var ids=jdbc.query("""
                    INSERT INTO stock_score_snapshots(instrument_uid,asset_uid,evaluation_time,data_cutoff,score_status,overall_score,q_score,f_score,v_score,d_score,
                      profile_id,model_version,configuration_fingerprint,data_coverage,input_fingerprint,evaluation_fingerprint,input_evidence,result)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS JSONB),?,?,CAST(? AS JSONB),CAST(? AS JSONB))
                    ON CONFLICT(evaluation_fingerprint) DO NOTHING RETURNING id
                    """,(row,n) -> row.getLong(1),r.instrumentUid(),r.assetUid(),utc(r.evaluatedAt()),utc(r.dataCutoff()),r.status().name(),r.overallScore(),
                    r.pillarScores().get(ScoringResult.Pillar.Q),r.pillarScores().get(ScoringResult.Pillar.F),r.pillarScores().get(ScoringResult.Pillar.V),r.pillarScores().get(ScoringResult.Pillar.D),
                    r.profileId(),r.modelVersion(),r.configurationFingerprint(),encode(r.coverage()),r.inputFingerprint(),key,encode(r.input()),encode(r));
            if (ids.isEmpty()) return jdbc.queryForObject("SELECT id,result FROM stock_score_snapshots WHERE evaluation_fingerprint=?",(row,n) -> decode(row.getString("result"),row.getLong("id")),key);
            long id=ids.getFirst();
            for (var c:r.contributions()) jdbc.update("""
                    INSERT INTO scoring_metric_contributions(score_snapshot,rule_id,pillar,metric_identifier,observation_reference,normalized_metric_value,
                      metric_score,applied_metric_weight,pillar_weight,contribution,status,reason,methodology) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,id,c.ruleId(),c.pillar().name(),c.metric().name(),c.observationId(),c.normalizedValue(),c.metricScore(),c.metricWeight(),c.pillarWeight(),c.contribution(),c.status(),c.reason(),c.methodology());
            for (var flag:r.risks()) jdbc.update("""
                    INSERT INTO scoring_risk_flags(score_snapshot,rule_id,severity,explanation,supporting_inputs,condition,actual_value,observed_at)
                    VALUES(?,?,?,?,CAST(? AS JSONB),?,?,?)
                    """,id,flag.ruleId(),flag.severity().name(),flag.explanation(),encode(flag),flag.condition(),flag.actualValue(),utc(flag.observedAt()));
            return r.withId(id);
        }));
    }
    @Override public Optional<ScoringResult> latest(String uid) { return history(uid,1).stream().findFirst(); }
    @Override public List<ScoringResult> history(String uid,int limit) {
        if (limit<1 || limit>200) throw new IllegalArgumentException("Score history limit must be 1..200");
        return jdbc.query("SELECT id,result FROM stock_score_snapshots WHERE instrument_uid=? ORDER BY id DESC LIMIT ?",(r,n) -> decode(r.getString("result"),r.getLong("id")),uid,limit);
    }
}
