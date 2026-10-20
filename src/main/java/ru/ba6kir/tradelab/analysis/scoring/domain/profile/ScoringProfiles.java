package ru.ba6kir.tradelab.analysis.scoring.domain.profile;

import java.math.BigDecimal;
import java.util.*;
import ru.ba6kir.tradelab.analysis.domain.metric.*;
import ru.ba6kir.tradelab.analysis.domain.sector.SectorClassification;
import ru.ba6kir.tradelab.analysis.scoring.domain.model.ScoringResult.Pillar;
import ru.ba6kir.tradelab.analysis.scoring.domain.rule.MetricScoringRule;
import static ru.ba6kir.tradelab.analysis.domain.metric.AnalysisMetric.*;
import static ru.ba6kir.tradelab.analysis.scoring.domain.rule.MetricScoringRule.*;

public final class ScoringProfiles {
    private ScoringProfiles() {}
    public static String resolve(SectorClassification c) {
        if (c==null || c.sector()==SectorClassification.Sector.UNKNOWN || c.businessType()==SectorClassification.BusinessType.UNKNOWN
                || c.businessType()==SectorClassification.BusinessType.NON_BANK_FINANCIAL) return "UNSUPPORTED";
        if (c.businessType()==SectorClassification.BusinessType.BANK) return c.sector()==SectorClassification.Sector.FINANCIALS?"BANK":"UNSUPPORTED";
        return switch(c.sector()) { case OIL_AND_GAS -> "OIL_AND_GAS"; case METALS_AND_MINING -> "METALS_AND_MINING";
            case FINANCIALS,UNKNOWN -> "UNSUPPORTED"; default -> "GENERAL_NON_FINANCIAL"; };
    }
    public static Map<String,ScoringModel.Profile> defaults() {
        var result=new TreeMap<String,ScoringModel.Profile>();
        for (String id:List.of("BANK","OIL_AND_GAS","METALS_AND_MINING","GENERAL_NON_FINANCIAL")) {
            boolean bank=id.equals("BANK"),mining=id.equals("METALS_AND_MINING"),general=id.equals("GENERAL_NON_FINANCIAL");
            var rules=new ArrayList<MetricScoringRule>();
            rules.add(rule(id,Pillar.Q,bank || general?ROE:ROIC,"0.6",Direction.HEALTHY_INTERVAL,
                    bank || general?"-30:0,0:10,10:60,20:85,30:75,50:20,100:0":"-20:0,0:15,8:55,15:85,25:75,50:20,100:0",null));
            rules.add(rule(id,Pillar.Q,bank?ROA:NET_MARGIN,"0.4",Direction.HEALTHY_INTERVAL,bank?"-10:0,0:10,1:60,2:85,4:65,10:0":
                    mining?"-30:0,0:10,5:45,12:75,20:85,50:50,100:10":"-30:0,0:10,5:45,15:75,30:85,60:50,100:10",null));
            rules.add(rule(id,Pillar.F,bank?CAPITAL_ADEQUACY:NET_DEBT_EBITDA,"0.7",bank?Direction.HEALTHY_INTERVAL:Direction.HEALTHY_INTERVAL,
                    bank?"0:0,8:20,12:60,16:85,22:90,40:70":"-5:65,-1:80,0:80,1:85,2:70,4:30,6:0",bank?"verified:total-regulatory-capital-to-rwa-percent":null));
            rules.add(rule(id,Pillar.F,bank?ASSET_QUALITY:CURRENT_RATIO,"0.3",bank?Direction.LOWER:Direction.HEALTHY_INTERVAL,
                    bank?"0:90,1:85,3:65,5:35,10:0":"0:0,0.5:20,1:55,1.5:80,2.5:85,4:60,10:20",bank?"verified:npl-to-gross-loans-percent":null));
            rules.add(rule(id,Pillar.V,bank?PB:general?PE:EV_EBITDA,"0.6",Direction.HEALTHY_INTERVAL,bank?"0:0,0.3:0,0.7:70,1.2:85,2:60,4:10,8:0":
                    general?"0:0,2:0,6:75,12:80,20:50,40:10,80:0":mining?"0:0,1:0,4:70,7:80,12:35,20:0":"0:0,1:0,4:80,7:70,12:35,20:0",null));
            rules.add(rule(id,Pillar.V,general?PS:PE,"0.4",Direction.HEALTHY_INTERVAL,general?"0:0,0.1:0,0.7:80,1.5:75,3:40,6:0":"0:0,2:0,6:75,12:80,20:50,40:10,80:0",null));
            rules.add(rule(id,Pillar.D,DIVIDEND_YIELD,"0.5",Direction.HEALTHY_INTERVAL,"0:20,2:50,5:80,8:70,12:30,20:0",null));
            rules.add(rule(id,Pillar.D,DIVIDEND_HISTORY,"0.5",Direction.HIGHER,"0:0,1:20,3:60,5:80,10:90",null));
            result.put(id,new ScoringModel.Profile(id,rules,Set.of(NET_INCOME),bank?Set.of(EARNINGS_GROWTH):Set.of(FREE_CASH_FLOW,EARNINGS_GROWTH)));
        }
        return Map.copyOf(result);
    }
    private static MetricScoringRule rule(String profile,Pillar p,AnalysisMetric metric,String weight,Direction direction,String curve,String definition) {
        var points=Arrays.stream(curve.split(",")).map(s -> s.split(":")).map(a -> new Point(new BigDecimal(a[0]),new BigDecimal(a[1]))).toList();
        return new MetricScoringRule(profile+":"+p+":"+metric+":v1",p,metric,metric.unit(),p==Pillar.D?FinancialObservation.Origin.DERIVED:FinancialObservation.Origin.PROVIDER,
                direction,true,new BigDecimal(weight),points,definition,metric==DIVIDEND_HISTORY?Transformation.DISTINCT_RECORDED_YEARS:Transformation.IDENTITY);
    }
}
