package ru.ba6kir.tradelab.analysis.domain.metric;

import java.math.*;
import java.time.*;
import java.util.*;
import ru.ba6kir.tradelab.fundamentals.domain.model.QualityStatus;
import ru.ba6kir.tradelab.fundamentals.domain.validation.FundamentalNormalizer;
import static ru.ba6kir.tradelab.analysis.domain.metric.AnalysisMetric.*;
import static ru.ba6kir.tradelab.analysis.domain.metric.FinancialObservation.*;

public class DerivedMetricCalculator {
    public List<FinancialObservation> calculate(String uid,Long snapshot,List<FinancialObservation> observations,Instant now) {
        var inputs=new EnumMap<AnalysisMetric,FinancialObservation>(AnalysisMetric.class);
        observations.stream().filter(o -> o.origin()==Origin.PROVIDER).forEach(o -> inputs.putIfAbsent(o.metricCode(),o));
        var result=new ArrayList<FinancialObservation>();
        result.add(derive(uid,snapshot,EARNINGS_YIELD,"earnings-yield-v1:100/positive-PE",List.of(PE),inputs,now));
        result.add(derive(uid,snapshot,NET_MARGIN,"net-margin-v1:100*net-income/revenue",List.of(NET_INCOME,REVENUE),inputs,now));
        result.add(derive(uid,snapshot,EBITDA_MARGIN,"ebitda-margin-v1:100*EBITDA/revenue",List.of(EBITDA,REVENUE),inputs,now));
        result.add(derive(uid,snapshot,ROE,"roe-v1:100*net-income/verified-average-equity",List.of(NET_INCOME,AVERAGE_EQUITY),inputs,now));
        result.add(derive(uid,snapshot,ROIC,"roic-v1:100*NOPAT/verified-average-invested-capital",List.of(NOPAT,AVERAGE_INVESTED_CAPITAL),inputs,now));
        var net=derive(uid,snapshot,NET_DEBT,"net-debt-v1:total-debt-cash",List.of(TOTAL_DEBT,CASH),inputs,now);
        result.add(net);
        // Prefer a valid direct net-debt input; otherwise use the separately labelled local calculation.
        if (!inputs.containsKey(NET_DEBT) || !inputs.get(NET_DEBT).validValue()) inputs.put(NET_DEBT,net);
        result.add(derive(uid,snapshot,NET_DEBT_EBITDA,"net-debt-ebitda-v1:net-debt/positive-TTM-EBITDA-at-same-end",List.of(NET_DEBT,EBITDA),inputs,now));
        return List.copyOf(result);
    }
    private FinancialObservation derive(String uid,Long snapshot,AnalysisMetric code,String method,List<AnalysisMetric> codes,
            Map<AnalysisMetric,FinancialObservation> inputs,Instant now) {
        var rows=codes.stream().map(inputs::get).toList(); String reason=null;
        if (rows.stream().anyMatch(Objects::isNull) || rows.stream().anyMatch(o -> o.value()==null)) reason="MISSING_INPUTS";
        else if (rows.stream().anyMatch(o -> !uid.equals(o.assetUid()) || !Objects.equals(snapshot,o.snapshotId()))) reason="IDENTITY_OR_SNAPSHOT_MISMATCH";
        else if (rows.stream().anyMatch(o -> !o.validValue() || o.fetchedAt()==null || o.fetchedAt().isAfter(now)
                || o.reportingEndDate()!=null && o.reportingEndDate().isAfter(now.atOffset(ZoneOffset.UTC).toLocalDate()))) reason="INVALID_OR_STALE_INPUTS";
        else if (code==EARNINGS_YIELD) {
            if (rows.getFirst().unit()!=Unit.MULTIPLE) reason="UNIT_MISMATCH";
            else if (rows.getFirst().value().signum()<=0) reason="NONPOSITIVE_PE";
        } else {
            if (rows.stream().anyMatch(o -> o.unit()!=Unit.MONEY)) reason="UNIT_MISMATCH";
            else if (rows.stream().anyMatch(o -> o.currency()==null) || rows.stream().map(FinancialObservation::currency).distinct().count()!=1) reason="CURRENCY_MISMATCH";
            else if (code==NET_DEBT_EBITDA) {
                if (!rows.getFirst().usable() || !rows.get(1).usable() || rows.getFirst().observationType()!=ObservationType.POINT_IN_TIME
                        || rows.get(1).observationType()!=ObservationType.TTM || !Objects.equals(rows.getFirst().reportingEndDate(),rows.get(1).reportingEndDate()))
                    reason="INCOMPATIBLE_PERIODS";
            } else if (!rows.getFirst().compatible(rows.get(1))) reason="INCOMPATIBLE_PERIODS";
            if (reason==null && code==NET_DEBT && rows.getFirst().observationType()!=ObservationType.POINT_IN_TIME) reason="BALANCE_SHEET_DATE_REQUIRED";
            if (reason==null && code!=NET_DEBT && rows.get(1).value().signum()<=0) reason="NONPOSITIVE_DENOMINATOR";
            if (reason==null && code==NET_DEBT && rows.stream().anyMatch(o -> o.value().signum()<0)) reason="NEGATIVE_DEBT_OR_CASH";
        }
        BigDecimal value=null;
        if (reason==null) {
            value=code==EARNINGS_YIELD ? BigDecimal.valueOf(100).divide(rows.getFirst().value(),12,RoundingMode.HALF_UP)
                    :code==NET_DEBT ? rows.getFirst().value().subtract(rows.get(1).value())
                    :rows.getFirst().value().divide(rows.get(1).value(),12,RoundingMode.HALF_UP).multiply(code==NET_DEBT_EBITDA?BigDecimal.ONE:BigDecimal.valueOf(100));
            if (code!=NET_DEBT) value=value.setScale(6,RoundingMode.HALF_UP);
        }
        var first=rows.stream().filter(Objects::nonNull).findFirst().orElse(null);
        var lineage=new ArrayList<String>();
        for (int i=0;i<codes.size();i++) lineage.add(rows.get(i)==null?"missing:"+codes.get(i):rows.get(i).id());
        String id=FundamentalNormalizer.hash(Map.of("asset",uid,"snapshot",String.valueOf(snapshot),"method",method,"inputs",String.join("|",lineage)));
        return new FinancialObservation("derived:"+id,uid,snapshot,code,value,code.unit(),code==NET_DEBT && first!=null?first.currency():null,
                first==null?ObservationType.UNKNOWN:code==NET_DEBT_EBITDA?ObservationType.TTM:first.observationType(),
                code==NET_DEBT_EBITDA?"TTM":first==null?null:first.reportingPeriod(),
                code==NET_DEBT_EBITDA && rows.get(1)!=null?rows.get(1).periodStart():first==null?null:first.periodStart(),
                first==null?null:first.reportingEndDate(),first==null?null:first.providerAsOfDate(),
                rows.stream().filter(Objects::nonNull).map(FinancialObservation::fetchedAt).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(now),
                "LOCAL_FORMULA",Origin.DERIVED,Set.of(reason==null?QualityStatus.VALID:reason.equals("MISSING_INPUTS")?QualityStatus.MISSING:QualityStatus.SUSPECT),
                reason,method,lineage,null);
    }
}
