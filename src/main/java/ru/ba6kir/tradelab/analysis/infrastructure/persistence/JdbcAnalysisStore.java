package ru.ba6kir.tradelab.analysis.infrastructure.persistence;

import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ba6kir.tradelab.analysis.application.port.AnalysisStore;
import ru.ba6kir.tradelab.analysis.domain.sector.SectorClassification;
import ru.ba6kir.tradelab.analysis.domain.metric.*;

public class JdbcAnalysisStore implements AnalysisStore {
    private final JdbcTemplate jdbc; private final TransactionTemplate tx; private final ObjectMapper json;
    public JdbcAnalysisStore(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper json) { this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.json=json; }
    private String encode(Object o) { try { return json.writeValueAsString(o); } catch (java.io.IOException e) { throw new IllegalStateException("Cannot encode financial analysis"); } }
    private SectorClassification decode(String value) { try { return json.readValue(value,SectorClassification.class); } catch (java.io.IOException e) { throw new IllegalStateException("Cannot read sector classification"); } }
    private static OffsetDateTime utc(Instant t) { return t==null?null:t.atOffset(ZoneOffset.UTC); }
    @Override public Optional<SectorClassification> classification(String uid) {
        return jdbc.query("SELECT classification FROM issuer_sector_classifications WHERE asset_uid=?",(r,n) -> decode(r.getString(1)),uid).stream().findFirst();
    }
    @Override public List<SectorClassification> classifications() {
        return jdbc.query("SELECT classification FROM issuer_sector_classifications ORDER BY asset_uid LIMIT 200",(r,n) -> decode(r.getString(1)));
    }
    @Override public void saveClassification(SectorClassification c) {
        jdbc.update("""
                INSERT INTO issuer_sector_classifications(asset_uid,issuer_key,sector,business_type,source,verified_at,is_override,evidence,classification)
                VALUES(?,?,?,?,?,?,?,?,CAST(? AS JSONB)) ON CONFLICT(asset_uid) DO UPDATE SET issuer_key=EXCLUDED.issuer_key,sector=EXCLUDED.sector,
                  business_type=EXCLUDED.business_type,source=EXCLUDED.source,verified_at=EXCLUDED.verified_at,is_override=EXCLUDED.is_override,
                  evidence=EXCLUDED.evidence,classification=EXCLUDED.classification
                WHERE issuer_sector_classifications.classification<>EXCLUDED.classification
                  AND (NOT issuer_sector_classifications.is_override OR EXCLUDED.is_override)
                  AND (EXCLUDED.verified_at>=issuer_sector_classifications.verified_at
                    OR EXCLUDED.is_override AND NOT issuer_sector_classifications.is_override
                    OR EXCLUDED.source='PROVIDER_AND_MANUAL' AND issuer_sector_classifications.source='PROVIDER_VERIFIED')
                """,c.assetUid(),c.issuerKey(),c.sector().name(),c.businessType().name(),c.source().name(),utc(c.verifiedAt()),c.override(),c.evidence(),encode(c));
    }
    @Override public void saveDerived(List<FinancialObservation> observations) {
        tx.executeWithoutResult(status -> {
            for (var code:AnalysisMetric.values()) jdbc.update("""
                    INSERT INTO analysis_metric_definitions(metric_code,family,canonical_unit,definition) VALUES(?,?,?,?) ON CONFLICT DO NOTHING
                    """,code.name(),code.family().name(),code.unit().name(),code.definition());
            for (var o:observations) {
                if (o.origin()!=FinancialObservation.Origin.DERIVED || o.snapshotId()==null || o.instrumentUid()!=null) continue;
                jdbc.update("""
                        INSERT INTO derived_financial_observations(observation_id,asset_uid,snapshot_id,metric_code,metric_value,unit,currency,
                          observation_type,reporting_period,period_start,reporting_end_date,provider_as_of,fetched_at,source,quality_status,
                          quality_reason,calculation_method,input_lineage,observation)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS JSONB),?,?,CAST(? AS JSONB),CAST(? AS JSONB))
                        ON CONFLICT(observation_id) DO UPDATE SET metric_value=EXCLUDED.metric_value,unit=EXCLUDED.unit,currency=EXCLUDED.currency,
                          fetched_at=EXCLUDED.fetched_at,quality_status=EXCLUDED.quality_status,
                          quality_reason=EXCLUDED.quality_reason,observation=EXCLUDED.observation,updated_at=CURRENT_TIMESTAMP
                        WHERE derived_financial_observations.observation<>EXCLUDED.observation
                        """,o.id(),o.assetUid(),o.snapshotId(),o.metricCode().name(),o.value(),o.unit().name(),o.currency(),o.observationType().name(),
                        o.reportingPeriod(),o.periodStart(),o.reportingEndDate(),utc(o.providerAsOfDate()),utc(o.fetchedAt()),o.source(),encode(o.qualityStatus()),
                        o.qualityReason(),o.methodology(),encode(o.inputIds()),encode(o));
            }
        });
    }
}
