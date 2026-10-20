package ru.ba6kir.tradelab.dividends.infrastructure.tinvest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import com.google.protobuf.Timestamp;
import ru.tinkoff.piapi.contract.v1.*;
import ru.ba6kir.tradelab.market.application.InstrumentService;
import ru.ba6kir.tradelab.market.infrastructure.tinvest.*;
import ru.ba6kir.tradelab.dividends.application.port.DividendProvider;
import ru.ba6kir.tradelab.dividends.domain.model.DividendEvent;

public class TInvestDividendAdapter implements DividendProvider {
    private final TInvestStockInstrumentProvider provider;
    private final Clock clock;
    public TInvestDividendAdapter(TInvestStockInstrumentProvider provider,Clock clock) { this.provider=provider; this.clock=clock; }
    @Override public List<DividendEvent> fetchDividends(String uid,Instant from,Instant to) {
        InstrumentService.validateUid(uid);
        if (from==null || to==null || !from.isBefore(to) || Duration.between(from,to).toDays()>2200)
            throw new IllegalArgumentException("Invalid dividend request range");
        var request=GetDividendsRequest.newBuilder().setInstrumentId(uid).setFrom(stamp(from)).setTo(stamp(to)).build();
        var response=provider.call(stub -> stub.getDividends(request));
        var retrieved=clock.instant();
        return response.getDividendsList().stream().map(d -> normalize(uid,d,retrieved)).toList();
    }
    public static DividendEvent normalize(String uid,Dividend d,Instant retrieved) {
        var issues=new TreeSet<String>();
        BigDecimal amount=d.hasDividendNet()?money(d.getDividendNet(),issues,"AMOUNT"):null;
        if (amount==null) issues.add("MISSING_AMOUNT");
        else if (amount.signum()<=0) issues.add("SUSPECT_NONPOSITIVE_AMOUNT");
        String currency=d.hasDividendNet()?currency(d.getDividendNet().getCurrency()):null;
        if (currency==null) issues.add("MISSING_CURRENCY");
        var declared=date(d.hasDeclaredDate()?d.getDeclaredDate():null,issues);
        var lastBuy=date(d.hasLastBuyDate()?d.getLastBuyDate():null,issues);
        var record=date(d.hasRecordDate()?d.getRecordDate():null,issues);
        var payment=date(d.hasPaymentDate()?d.getPaymentDate():null,issues);
        if (record==null) issues.add("MISSING_RECORD_DATE");
        if (payment==null) issues.add("MISSING_PAYMENT_DATE");
        if (record!=null && ((lastBuy!=null && lastBuy.isAfter(record)) || (payment!=null && payment.isBefore(record))
                || (declared!=null && declared.isAfter(record)))) issues.add("SUSPECT_DATE_ORDER");
        var created=instant(d.hasCreatedAt()?d.getCreatedAt():null,issues);
        if (created==null) issues.add("MISSING_IDENTITY_EVIDENCE");
        else if (created.isAfter(retrieved.plusSeconds(60))) issues.add("SUSPECT_CREATION_TIME");
        BigDecimal price=d.hasClosePrice()?money(d.getClosePrice(),issues,"REFERENCE_PRICE"):null;
        String priceCurrency=d.hasClosePrice()?currency(d.getClosePrice().getCurrency()):null;
        if (price!=null && price.signum()<=0) issues.add("SUSPECT_REFERENCE_PRICE");
        if (priceCurrency!=null && currency!=null && !priceCurrency.equals(currency)) issues.add("REFERENCE_CURRENCY_MISMATCH");
        BigDecimal yield=null;
        if (d.hasYieldValue()) {
            try { yield=QuotationDecimals.from(d.getYieldValue()); }
            catch (IllegalArgumentException e) { issues.add("SUSPECT_PROVIDER_YIELD"); }
        }
        String raw=Base64.getEncoder().encodeToString(d.toByteArray());
        // Creation time is evidence for a collision group, NOT an authoritative event ID.
        String anchor=created==null?"dates|"+record+"|"+declared+"|"+lastBuy+"|"+payment:"created|"+created;
        return new DividendEvent(uid,amount,currency,declared,lastBuy,record,payment,blank(d.getDividendType()),blank(d.getRegularity()),
                yield,price,priceCurrency,created,retrieved,TInvestStockInstrumentProvider.SOURCE,hash(raw),hash(anchor),issues,raw);
    }
    private static BigDecimal money(MoneyValue value,Set<String> issues,String field) {
        try { return QuotationDecimals.from(value); }
        catch (IllegalArgumentException e) { issues.add("SUSPECT_"+field); return null; }
    }
    private static String blank(String s) { return s.isBlank()?null:s; }
    private static String currency(String s) { return s.matches("[A-Za-z]{3}")?s.toUpperCase(Locale.ROOT):null; }
    private static LocalDate date(Timestamp t,Set<String> issues) {
        var instant=instant(t,issues); return instant==null?null:instant.atOffset(ZoneOffset.UTC).toLocalDate();
    }
    private static Instant instant(Timestamp t,Set<String> issues) {
        if (t==null) return null;
        if (t.getSeconds()<=0 || t.getNanos()<0 || t.getNanos()>999999999 || t.getSeconds()>253402300799L) {
            issues.add("SUSPECT_TIMESTAMP"); return null;
        }
        return Instant.ofEpochSecond(t.getSeconds(),t.getNanos());
    }
    private static Timestamp stamp(Instant t) { return Timestamp.newBuilder().setSeconds(t.getEpochSecond()).setNanos(t.getNano()).build(); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
