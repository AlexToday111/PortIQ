package ru.ba6kir.tradelab.analysis.scoring.domain.risk;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import ru.ba6kir.tradelab.analysis.domain.metric.*;
import ru.ba6kir.tradelab.analysis.scoring.domain.model.*;
import ru.ba6kir.tradelab.analysis.scoring.domain.profile.ScoringModel;
import ru.ba6kir.tradelab.analysis.scoring.domain.rule.ScoringEligibilityValidator;
import static ru.ba6kir.tradelab.analysis.scoring.domain.model.ScoringResult.*;

public class FundamentalRiskEvaluator {
    public List<RiskFlag> evaluate(ScoringInput input,ScoringModel model,List<String> reasons,Instant now) {
        var flags=new ArrayList<RiskFlag>();
        for (var o:input.observations()) {
            if (o.value()==null) continue;
            if (o.metricCode()==AnalysisMetric.NET_INCOME && o.value().signum()<0) flags.add(flag("NEGATIVE_NET_INCOME",Severity.CRITICAL,o,"value < 0","Recorded loss; low valuation multiples do not override this condition"));
            if (!ru.ba6kir.tradelab.analysis.scoring.domain.profile.ScoringProfiles.resolve(input.classification()).equals("BANK")
                    && o.metricCode()==AnalysisMetric.FREE_CASH_FLOW && o.value().signum()<0) flags.add(flag("NEGATIVE_FCF",Severity.WARNING,o,"value < 0","Recorded negative free cash flow; not replaced by net income"));
            if (!ru.ba6kir.tradelab.analysis.scoring.domain.profile.ScoringProfiles.resolve(input.classification()).equals("BANK")
                    && o.validValue() && o.metricCode()==AnalysisMetric.NET_DEBT_EBITDA && o.value().compareTo(model.highLeverage())>=0)
                flags.add(flag("HIGH_INDUSTRIAL_LEVERAGE",Severity.CRITICAL,o,"value >= "+model.highLeverage(),"Experimental profile leverage threshold; not a broker restriction"));
            if (o.metricCode()==AnalysisMetric.DIVIDEND_YIELD && o.unit()==AnalysisMetric.Unit.PERCENT && o.value().compareTo(model.extremeYield())>=0)
                flags.add(flag("EXTREME_DIVIDEND_YIELD",Severity.WARNING,o,"percent >= "+model.extremeYield(),"Extreme observed yield needs verification and cannot improve the dividend score"));
            var profile=model.profiles().get(ru.ba6kir.tradelab.analysis.scoring.domain.profile.ScoringProfiles.resolve(input.classification()));
            var pe=profile==null?null:profile.rules().stream().filter(r -> r.metric()==AnalysisMetric.PE).findFirst().orElse(null);
            if (o.metricCode()==AnalysisMetric.PE && (o.value().signum()<=0 || pe!=null && o.value().compareTo(pe.points().getLast().value())>=0))
                flags.add(flag("EXTREME_OR_UNDEFINED_PE",Severity.WARNING,o,"P/E <= 0 or at/above configured upper endpoint "+(pe==null?"unavailable":pe.points().getLast().value()),"Valuation multiple is undefined or extreme; not evidence of cheapness"));
        }
        if (new ScoringEligibilityValidator().dividendReason(input,model,now)!=null)
            flags.add(new RiskFlag("UNRELIABLE_DIVIDEND_EVIDENCE",Severity.WARNING,AnalysisMetric.DIVIDEND_HISTORY,
                    input.dividends()==null?List.of():input.dividends().eventHashes(),null,"insufficient reliable recorded history/coverage",
                    "Missing dividend records are not confirmed zero dividends",input.dividends()==null?null:input.dividends().fetchedAt()));
        if (reasons.stream().anyMatch(r -> r.startsWith("STALE"))) flags.add(new RiskFlag("OUTDATED_FINANCIAL_EVIDENCE",Severity.WARNING,null,List.of(),null,
                "observation or financial-period age exceeds configured limit","Source timestamps are retained; retrieval is not publication",input.metadataFetchedAt()));
        if (!reasons.isEmpty()) flags.add(new RiskFlag("INSUFFICIENT_FINANCIAL_COVERAGE",Severity.WARNING,null,List.of(),null,
                String.join(",",reasons),"An incomplete assessment is not assigned an arbitrary overall score",input.metadataFetchedAt()));
        if (Boolean.FALSE.equals(input.apiTradeAvailable()) || input.tradingStatus()!=null && !Set.of("NORMAL_TRADING","SECURITY_TRADING_STATUS_NORMAL_TRADING").contains(input.tradingStatus()))
            flags.add(new RiskFlag("METADATA_TRADING_AVAILABILITY",Severity.INFO,null,List.of(),null,"API trade flag false or metadata status not NORMAL_TRADING",
                    "Instrument metadata may be old; this is not a verified current account restriction",input.metadataFetchedAt()));
        if (input.classification()!=null && input.classification().sector()==ru.ba6kir.tradelab.analysis.domain.sector.SectorClassification.Sector.METALS_AND_MINING)
            flags.add(new RiskFlag("CYCLICAL_VALUATION_CONTEXT",Severity.INFO,null,List.of(),null,"METALS_AND_MINING profile",
                    "Cyclical earnings may distort point-in-time multiples; no cycle adjustment or forecast was invented",input.metadataFetchedAt()));
        return List.copyOf(flags);
    }
    private RiskFlag flag(String rule,Severity severity,FinancialObservation o,String condition,String explanation) {
        return new RiskFlag(rule,severity,o.metricCode(),o.id()==null?List.of():List.of(o.id()),o.value(),condition,explanation+"; source quality="+new TreeSet<>(o.qualityStatus()),o.fetchedAt());
    }
}
