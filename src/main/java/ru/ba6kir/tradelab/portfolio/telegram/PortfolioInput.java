package ru.ba6kir.tradelab.portfolio.telegram;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class PortfolioInput {
    private PortfolioInput(){}
    public static BigDecimal decimal(String value,boolean signed){
        if(value==null || !value.matches((signed?"-?":"")+"[0-9]{1,20}([.,][0-9]{1,9})?"))throw new IllegalArgumentException("INVALID_DECIMAL");return new BigDecimal(value.replace(',','.'));
    }
    public static Instant time(String value){try{return value.length()==10?LocalDate.parse(value).atStartOfDay(ZoneId.of("Europe/Moscow")).toInstant():OffsetDateTime.parse(value).toInstant();}catch(RuntimeException e){throw new IllegalArgumentException("INVALID_DATE_USE_ISO_OFFSET");}}
    public static UUID uuid(String value){try{if(!value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))throw new IllegalArgumentException();return UUID.fromString(value);}catch(RuntimeException e){throw new IllegalArgumentException("INVALID_LOCAL_IDENTIFIER");}}
    public static String name(String value){if(value==null || value.isBlank() || value.length()>120 || value.matches(".*[\\p{Cntrl}\\p{Cf}].*"))throw new IllegalArgumentException("INVALID_NAME");return value;}
}
