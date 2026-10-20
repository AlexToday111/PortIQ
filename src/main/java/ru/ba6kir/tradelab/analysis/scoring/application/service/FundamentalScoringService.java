package ru.ba6kir.tradelab.analysis.scoring.application.service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.slf4j.*;
import ru.ba6kir.tradelab.market.application.InstrumentService;
import ru.ba6kir.tradelab.analysis.scoring.domain.model.*;
import ru.ba6kir.tradelab.analysis.scoring.domain.profile.ScoringModel;
import ru.ba6kir.tradelab.analysis.scoring.domain.rule.FundamentalScoringEngine;
import static ru.ba6kir.tradelab.analysis.scoring.domain.model.ScoringResult.*;

public class FundamentalScoringService {
    private static final Logger log=LoggerFactory.getLogger(FundamentalScoringService.class);
    private final ScoringInputProvider inputs; private final ScoringResultRepository repository; private final ScoringModel model;
    private final ScoringFingerprint fingerprints; private final Clock clock;
    public FundamentalScoringService(ScoringInputProvider inputs,ScoringResultRepository repository,ScoringModel model,ScoringFingerprint fingerprints,Clock clock) {
        this.inputs=inputs;this.repository=repository;this.model=model;this.fingerprints=fingerprints;this.clock=clock;
    }
    public ScoringResult evaluateInstrument(String uid) { return evaluateInstrument(uid,true); }
    public ScoringResult evaluateInstrument(String uid,boolean save) { return evaluate(InstrumentService.validateUid(uid),false,save); }
    public ScoringResult evaluateAsset(String uid) { return evaluate(InstrumentService.validateUid(uid),true,true); }
    private ScoringResult evaluate(String uid,boolean asset,boolean save) {
        ScoringResult result;
        try {
            var input=asset?inputs.asset(uid):inputs.instrument(uid);
            if (input==null || !Objects.equals(uid,asset?input.assetUid():input.instrumentUid())) throw new IllegalStateException("ASSET_MAPPING_MISMATCH");
            result=new FundamentalScoringEngine().evaluate(input,model,fingerprints.hash(model),fingerprints.hash(input),clock.instant());
        } catch (RuntimeException e) {
            String reason=e instanceof ru.ba6kir.tradelab.market.application.MarketException m?"INPUT_"+m.reason().name():
                    Set.of("ASSET_INSTRUMENT_AMBIGUOUS","ASSET_INSTRUMENT_NOT_FOUND","ASSET_MAPPING_MISMATCH").contains(String.valueOf(e.getMessage()))?e.getMessage():"INPUT_CAPTURE_FAILED";
            log.warn("Scoring input unavailable reason={}",reason);
            return failed(asset?null:uid,asset?uid:null,reason,null);
        }
        if (!save) return result;
        try { return repository.save(result,model); }
        catch (RuntimeException e) {
            String reason=e instanceof ScoringResultRepository.ModelVersionConflictException?"MODEL_VERSION_CONFLICT":"PERSISTENCE_FAILED";
            log.warn("Scoring snapshot persistence failed reason={}",reason);
            return failed(result.instrumentUid(),result.assetUid(),reason,result);
        }
    }
    private ScoringResult failed(String instrument,String asset,String reason,ScoringResult original) {
        var scores=new EnumMap<Pillar,BigDecimal>(Pillar.class); var coverage=new EnumMap<Pillar,BigDecimal>(Pillar.class);
        for (var p:Pillar.values()) { scores.put(p,null);coverage.put(p,BigDecimal.ZERO); }
        return new ScoringResult(null,instrument,asset,original==null?null:original.name(),original==null?null:original.classification(),original==null?"UNSUPPORTED":original.profileId(),
                model.modelVersion(),model.configurationVersion(),fingerprints.hash(model),original==null?fingerprints.hash(Map.of("identity",String.valueOf(instrument)+asset,"reason",reason)):original.inputFingerprint(),
                clock.instant(),original==null?null:original.dataCutoff(),Status.FAILED,null,original==null?scores:original.pillarScores(),List.of(),original==null?coverage:original.coverage(),
                original==null?List.of():original.risks(),List.of(reason),original==null?List.of():original.inputSnapshotIds(),original==null?null:original.input(),List.of());
    }
    public Optional<ScoringResult> getLatestScore(String uid) { return repository.latest(InstrumentService.validateUid(uid)); }
    public List<ScoringResult> getScoreHistory(String uid) { return repository.history(InstrumentService.validateUid(uid),20); }
    public ScoringResult getScoringEligibility(String uid) { return evaluateInstrument(uid,false); }
}
