package ru.ba6kir.tradelab.fundamentals.infrastructure.tinvest;

import java.time.*;
import java.util.*;
import com.google.protobuf.Timestamp;
import ru.tinkoff.piapi.contract.v1.*;
import ru.ba6kir.tradelab.market.application.*;
import ru.ba6kir.tradelab.market.infrastructure.tinvest.TInvestStockInstrumentProvider;
import ru.ba6kir.tradelab.fundamentals.application.port.*;
import ru.ba6kir.tradelab.fundamentals.application.service.FundamentalsException;
import ru.ba6kir.tradelab.fundamentals.domain.model.*;

public class TInvestFundamentalsAdapter implements FundamentalsProvider,AssetIdentityResolver {
    public static final String SOURCE="T_INVEST_PRODUCTION";
    private final TInvestStockInstrumentProvider client;
    private final Clock clock;
    public TInvestFundamentalsAdapter(TInvestStockInstrumentProvider client,Clock clock) { this.client=client; this.clock=clock; }
    @Override public Optional<AssetIdentity> resolve(String instrumentUid) {
        instrumentUid=InstrumentService.validateUid(instrumentUid);
        final String requested=instrumentUid;
        try {
            var share=client.call(s -> s.shareBy(InstrumentRequest.newBuilder().setIdType(InstrumentIdType.INSTRUMENT_ID_TYPE_UID).setId(requested).build())).getInstrument();
            if (!requested.equals(share.getUid()) || share.getAssetUid().isBlank()) return Optional.empty();
            String assetUid=InstrumentService.validateUid(share.getAssetUid());
            var asset=client.call(s -> s.getAssetBy(AssetRequest.newBuilder().setId(assetUid).build())).getAsset();
            if (!assetUid.equals(asset.getUid()) || asset.hasDeletedAt() || asset.getType()!=AssetType.ASSET_TYPE_SECURITY
                    || !asset.hasSecurity() || !asset.getSecurity().hasShare()
                    || asset.getSecurity().getInstrumentKind()!=InstrumentType.INSTRUMENT_TYPE_SHARE
                    || asset.getInstrumentsList().stream().noneMatch(i -> requested.equals(i.getUid()))) return Optional.empty();
            return Optional.of(new AssetIdentity(assetUid,optional(asset.getName()),asset.hasBrand()?optional(asset.getBrand().getCompany()):null,
                    asset.hasBrand()?optional(asset.getBrand().getUid()):null,requested,SOURCE,clock.instant()));
        } catch (MarketException e) {
            if (e.reason()==MarketException.Reason.NOT_FOUND) return Optional.empty();
            throw e;
        }
    }
    @Override public Optional<FundamentalData> fetch(String assetUid) {
        final String requested=InstrumentService.validateUid(assetUid);
        var response=client.call(s -> s.getAssetFundamentals(GetAssetFundamentalsRequest.newBuilder().addAssets(requested).build()));
        if (response.getFundamentalsCount()==0) return Optional.empty();
        if (response.getFundamentalsCount()!=1 || !requested.equals(response.getFundamentals(0).getAssetUid()))
            throw new FundamentalsException(FundamentalsException.Reason.INVALID_DATA);
        var data=response.getFundamentals(0);
        var values=new EnumMap<MetricCode,FundamentalData.RawNumber>(MetricCode.class);
        for (var code:MetricCode.values()) if (code.supported()) {
            var descriptor=data.getDescriptorForType().findFieldByName(code.field());
            if (descriptor==null || descriptor.getJavaType()!=com.google.protobuf.Descriptors.FieldDescriptor.JavaType.DOUBLE)
                throw new FundamentalsException(FundamentalsException.Reason.INVALID_DATA);
            // SDK 1.51 uses implicit-presence scalar doubles. hasField cannot prove a zero was supplied.
            double number=(Double)data.getField(descriptor);
            values.put(code,new FundamentalData.RawNumber(Double.toString(number),descriptor.hasPresence()));
        }
        try {
            return Optional.of(new FundamentalData(requested,optional(data.getCurrency()),
                    data.hasFiscalPeriodStartDate()?instant(data.getFiscalPeriodStartDate()):null,
                    data.hasFiscalPeriodEndDate()?instant(data.getFiscalPeriodEndDate()):null,null,clock.instant(),SOURCE,values));
        } catch (RuntimeException e) { throw new FundamentalsException(FundamentalsException.Reason.INVALID_DATA); }
    }
    @Override public List<FinancialReportEvent> fetchReports(String instrumentUid,Instant from,Instant to) {
        final String requested=InstrumentService.validateUid(instrumentUid);
        var response=client.call(s -> s.getAssetReports(GetAssetReportsRequest.newBuilder().setInstrumentId(requested)
                .setFrom(timestamp(from)).setTo(timestamp(to)).build()));
        try {
            var events=new ArrayList<FinancialReportEvent>();
            for (var event:response.getEventsList()) {
                if (!requested.equals(event.getInstrumentId())) throw new IllegalArgumentException("Mismatched report identity");
                Integer year=event.getPeriodYear()==0?null:event.getPeriodYear();
                Integer number=event.getPeriodNum()==0?null:event.getPeriodNum();
                if (year!=null && (year<1900 || year>3000) || number!=null && (number<1 || number>4))
                    throw new IllegalArgumentException("Invalid reporting period");
                events.add(new FinancialReportEvent(requested,event.hasReportDate()?instant(event.getReportDate()):null,
                        year,number,event.getPeriodType().name(),event.hasCreatedAt()?instant(event.getCreatedAt()):null,SOURCE,clock.instant()));
            }
            return List.copyOf(events);
        } catch (RuntimeException e) { throw new FundamentalsException(FundamentalsException.Reason.INVALID_DATA); }
    }
    private static String optional(String value) { return value.isBlank()?null:value; }
    private static Instant instant(Timestamp value) {
        if (value.getNanos()<0 || value.getNanos()>999_999_999 || value.getSeconds()<=0)
            throw new IllegalArgumentException("Invalid provider timestamp");
        return Instant.ofEpochSecond(value.getSeconds(),value.getNanos());
    }
    private static Timestamp timestamp(Instant value) {
        return Timestamp.newBuilder().setSeconds(value.getEpochSecond()).setNanos(value.getNano()).build();
    }
}
