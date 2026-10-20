package ru.ba6kir.tradelab.fundamentals.domain.validation;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import ru.ba6kir.tradelab.fundamentals.domain.model.*;

public class FundamentalNormalizer {
    public FundamentalSnapshot normalize(FundamentalData data) {
        Objects.requireNonNull(data.fetchedAt());
        if (data.periodStart()!=null && data.periodEnd()!=null && data.periodStart().isAfter(data.periodEnd()))
            throw new IllegalArgumentException("Invalid provider fiscal period");
        if (data.periodEnd()!=null && data.periodEnd().isAfter(data.fetchedAt().plusSeconds(60)))
            throw new IllegalArgumentException("Future fiscal period");
        var metrics=new ArrayList<MetricValue>();
        var raw=new TreeMap<String,String>();
        raw.put("asset_uid",data.assetUid()); raw.put("source",data.source());
        raw.put("currency",text(data.currency())); raw.put("fiscal_period_start_date",text(data.periodStart()));
        raw.put("fiscal_period_end_date",text(data.periodEnd())); raw.put("provider_as_of",text(data.providerAsOf()));
        for (var code:MetricCode.values()) {
            var input=data.values().get(code);
            if (code.supported()) raw.put(code.field(),input==null ? "<absent>" : input.text()+"|presenceKnown="+input.presenceKnown());
            metrics.add(metric(code,input,data.currency()));
        }
        long confident=metrics.stream().filter(m -> m.quality()==QualityStatus.VALID).count();
        String completeness=confident==0 ? "NO_CONFIDENT_VALUES" : metrics.stream().filter(m -> m.code().supported())
                .allMatch(m -> m.quality()==QualityStatus.VALID) ? "COMPLETE" : "PARTIAL";
        return new FundamentalSnapshot(null,data.assetUid(),data.source(),data.fetchedAt(),data.fetchedAt(),data.providerAsOf(),
                data.periodStart(),data.periodEnd(),hash(raw),completeness,metrics,raw);
    }
    private MetricValue metric(MetricCode code,FundamentalData.RawNumber input,String currency) {
        if (!code.supported()) return value(code,null,null,QualityStatus.UNSUPPORTED,"NO_PROVIDER_FIELD",null);
        if (input==null) return value(code,null,currency,QualityStatus.MISSING,"FIELD_ABSENT",null);
        BigDecimal number;
        try {
            double source=Double.parseDouble(input.text());
            if (!Double.isFinite(source)) return value(code,null,currency,QualityStatus.SUSPECT,"NON_FINITE",input.text());
            number=BigDecimal.valueOf(source);
        } catch (RuntimeException ignored) { return value(code,null,currency,QualityStatus.SUSPECT,"INVALID_NUMBER",input.text()); }
        var reasons=new ArrayList<String>();
        if (number.signum()==0 && !input.presenceKnown()) reasons.add("ZERO_WITHOUT_PRESENCE");
        boolean monetary=code.unit()==MetricCode.Unit.MONEY || code.unit()==MetricCode.Unit.MONEY_PER_SHARE;
        if (monetary && (currency==null || !currency.matches("[A-Za-z]{3}"))) reasons.add("CURRENCY_UNKNOWN");
        if (code.unit()==MetricCode.Unit.UNVERIFIED_RATE) reasons.add("RATE_SCALE_UNDOCUMENTED");
        if (number.signum()<0 && (code==MetricCode.MARKET_CAPITALIZATION || code==MetricCode.TOTAL_DEBT
                || code==MetricCode.REVENUE || code==MetricCode.SHARES_OUTSTANDING || code.unit()==MetricCode.Unit.RATIO))
            reasons.add("NEGATIVE_CONTEXT_REQUIRED");
        if (number.signum()<0 && code.unit()==MetricCode.Unit.MULTIPLE) reasons.add("NEGATIVE_MULTIPLE_CONTEXT_REQUIRED");
        if (code==MetricCode.SHARES_OUTSTANDING && number.stripTrailingZeros().scale()>0) reasons.add("FRACTIONAL_SHARE_COUNT");
        return value(code,number,currency,reasons.isEmpty()?QualityStatus.VALID:QualityStatus.SUSPECT,String.join(";",reasons),input.text());
    }
    private MetricValue value(MetricCode code,BigDecimal number,String currency,QualityStatus quality,String reason,String raw) {
        boolean money=code.unit()==MetricCode.Unit.MONEY || code.unit()==MetricCode.Unit.MONEY_PER_SHARE;
        return new MetricValue(code,number,code.unit(),money?currency:null,code.period(),quality,reason,raw);
    }
    private static String text(Object value) { return value==null ? "<unknown>" : value.toString(); }
    public static String hash(Map<String,String> data) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            new TreeMap<>(data).forEach((key,value) -> {
                // Length prefixes avoid delimiter collisions in provider text.
                for (String part:List.of(key,value)) digest.update((part.length()+":"+part).getBytes(StandardCharsets.UTF_8));
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
