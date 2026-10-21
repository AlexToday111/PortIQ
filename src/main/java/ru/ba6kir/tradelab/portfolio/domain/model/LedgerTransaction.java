package ru.ba6kir.tradelab.portfolio.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import ru.ba6kir.tradelab.portfolio.domain.model.PortfolioAccount.Source;

/** Amounts are positive magnitudes except an explicit signed CASH_ADJUSTMENT. Quantities are shares. */
public record LedgerTransaction(UUID id,UUID accountId,Type type,String instrumentUid,BigDecimal quantity,BigDecimal price,
        BigDecimal grossAmount,BigDecimal commission,String currency,Instant executedAt,Instant recordedAt,Source source,
        String externalOperationId,String sourceReference,String idempotencyKey,UUID correctionReference,String status) {
    public enum Type { BUY,SELL,DEPOSIT,WITHDRAWAL,DIVIDEND_RECEIVED,BROKER_FEE,TAX_WITHHELD,CASH_ADJUSTMENT,OPENING_POSITION,REVERSAL }
    public LedgerTransaction {
        Objects.requireNonNull(id);Objects.requireNonNull(accountId);Objects.requireNonNull(type);Objects.requireNonNull(source);
        Objects.requireNonNull(executedAt);Objects.requireNonNull(recordedAt);
        if (currency==null || !currency.matches("[A-Z]{3}") || idempotencyKey==null || idempotencyKey.isBlank() || idempotencyKey.length()>240
                || sourceReference==null || sourceReference.isBlank() || sourceReference.length()>2000 || !"CONFIRMED".equals(status)) invalid("INVALID_TRANSACTION_METADATA");
        if (source==Source.MANUAL && !currency.equals("RUB")) invalid("MANUAL_RUB_ONLY");
        for (var value:new BigDecimal[]{quantity,price,grossAmount,commission}) if (value!=null && (value.precision()>38 || value.scale()>18)) invalid("DECIMAL_PRECISION_EXCEEDED");
        if (commission==null || commission.signum()<0) invalid("INVALID_COMMISSION");
        if (instrumentUid!=null && !instrumentUid.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) invalid("INVALID_INSTRUMENT_UID");
        if (type==Type.BUY || type==Type.SELL) {
            if (instrumentUid==null || quantity==null || quantity.signum()<=0 || price==null || price.signum()<=0
                    || grossAmount==null || grossAmount.compareTo(quantity.multiply(price))!=0) invalid("INVALID_TRADE_AMOUNT_OR_QUANTITY");
        } else if (type==Type.OPENING_POSITION) {
            if (instrumentUid==null || quantity==null || quantity.signum()<=0 || price!=null || commission.signum()!=0
                    || grossAmount!=null && grossAmount.signum()<0) invalid("INVALID_OPENING_POSITION");
        } else if (type==Type.REVERSAL) {
            if (correctionReference==null || quantity!=null || price!=null || grossAmount!=null || commission.signum()!=0) invalid("INVALID_REVERSAL");
        } else {
            if (quantity!=null || price!=null || commission.signum()!=0 || grossAmount==null || grossAmount.signum()==0
                    || type!=Type.CASH_ADJUSTMENT && grossAmount.signum()<0) invalid("INVALID_CASH_AMOUNT");
            if (type==Type.DIVIDEND_RECEIVED && instrumentUid==null) invalid("DIVIDEND_INSTRUMENT_REQUIRED");
        }
        if (type!=Type.REVERSAL && correctionReference!=null && source!=Source.T_INVEST) invalid("USE_EXPLICIT_REVERSAL");
    }
    private static void invalid(String reason) { throw new IllegalArgumentException(reason); }
}
