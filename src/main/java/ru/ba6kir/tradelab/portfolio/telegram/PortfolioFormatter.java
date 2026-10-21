package ru.ba6kir.tradelab.portfolio.telegram;

import java.math.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import ru.ba6kir.tradelab.portfolio.application.port.PortfolioStore;
import ru.ba6kir.tradelab.portfolio.application.service.PortfolioService;
import ru.ba6kir.tradelab.portfolio.domain.model.*;
import ru.ba6kir.tradelab.portfolio.domain.reconciliation.ReconciliationResult;
import ru.ba6kir.tradelab.portfolio.domain.valuation.PortfolioValuation;

public class PortfolioFormatter {
    public static String number(BigDecimal n){return n==null?"неизвестно":n.setScale(Math.min(Math.max(n.scale(),0),6),RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString().replace('.',',');}
    public static String safe(Object value){if(value==null)return "нет данных";String s=value.toString().replaceAll("[\\p{Cntrl}\\p{Cf}]"," ");return s.length()>300?s.substring(0,300)+"… [сокращено]":s;}
    public static String time(Instant t){return t==null?"нет данных":DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss VV").withZone(ZoneId.of("Europe/Moscow")).format(t);}
    public static String type(LedgerTransaction.Type type){return switch(type){case BUY->"Покупка";case SELL->"Продажа";case DEPOSIT->"Пополнение";case WITHDRAWAL->"Вывод";case DIVIDEND_RECEIVED->"Поступление дивиденда";case BROKER_FEE->"Комиссия";case TAX_WITHHELD->"Удержанный налог";case CASH_ADJUSTMENT->"Явная корректировка денег";case OPENING_POSITION->"Начальная позиция";case REVERSAL->"Сторно";};}
    public String confirmation(PortfolioStore.Pending p){var t=p.transaction();return "Проверьте запись для локального счёта "+t.accountId()+":\n"+transaction(t)+
            "\nВремя исполнения: "+time(t.executedAt())+". Без указанной даты используется момент подготовки записи.\nКоличество указывается в акциях, не в лотах. Это запись учёта, а не заявка брокеру.\n"
            +(t.type()==LedgerTransaction.Type.DIVIDEND_RECEIVED?"Подтвердите только фактическое поступление денег; объявление дивиденда не подтверждает получение.\n":"")
            +"Подтверждение действительно до "+time(p.expiresAt())+".\n/confirm "+p.token()+(p.confirmedTransactionId()==null?"":"\nУже записано: "+p.confirmedTransactionId());}
    private String transaction(LedgerTransaction t){return type(t.type())+" ["+t.type()+"]\nUID: "+safe(t.instrumentUid())+"; количество: "+number(t.quantity())+" акций; цена: "+number(t.price())+" "+t.currency()+"/акция\nСумма: "+number(t.grossAmount())+" "+t.currency()+"; комиссия: "+number(t.commission())+" "+t.currency()+"\nДата: "+time(t.executedAt())+"; счёт: "+t.accountId()+"; запись: "+t.id()+(t.correctionReference()==null?"":"; исправляет: "+t.correctionReference());}
    public String transactions(List<LedgerTransaction> rows){var text=new StringBuilder("Последние записи журнала (не заявки брокеру):\n");if(rows.isEmpty())text.append("Операций нет.\n");rows.forEach(t->text.append(transaction(t)).append("\n\n"));return text.toString();}
    public String summary(PortfolioValuation v){var text=new StringBuilder("Портфель: "+safe(v.name())+"\nОценка: "+time(v.valuedAt())+"\nПокрытие: "+(v.complete()?"полное в поддерживаемой области":"ЧАСТИЧНОЕ / есть неизвестные данные"))
            .append("\nОценённые RUB-акции, доступная часть: ").append(number(v.supportedEquityValue())).append(" RUB\nRUB-деньги по журналу, известный фрагмент: ").append(number(v.rubCashSubtotal()))
            .append(" RUB\nПоддерживаемые активы + деньги: ").append(number(v.supportedAssetsPlusCash())).append(" RUB\nРеализованный P&L сделок FIFO: ").append(number(v.realizedPnL()))
            .append(" RUB\nНереализованный P&L: ").append(number(v.unrealizedPnL())).append(" RUB\nЗаписанные поступления дивидендов: ").append(number(v.dividends()))
            .append(" RUB (налоговая gross-база брокерского поступления может быть неизвестна)\nЗаписанные комиссии: ").append(number(v.fees())).append(" RUB; удержания налога: ").append(number(v.taxes())).append(" RUB\n");
        for(var a:v.accounts())text.append("Счёт: ").append(safe(a.name())).append("; деньги ").append(a.cashReliable()?"подтверждены журналом в рамках охвата":"неполная история/сверка").append("; позиций: ").append(a.positions().size()).append("\n");
        warnings(text,v.warnings());return text.append("Цена последней сделки не является исполнимой ценой продажи. P&L по оставшейся стоимости не равен полной доходности портфеля. FIFO — внутренний метод учёта; налоговая отчётность требует отдельной проверки.").toString();}
    public String positions(PortfolioValuation v){var text=new StringBuilder(summary(v)).append("\nПозиции по счетам, стоимость по убыванию:\n");
        v.accounts().stream().flatMap(a->a.positions().stream()).sorted(Comparator.comparing(PortfolioValuation.PositionValue::marketValue,Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(p->p.position().instrumentUid()).thenComparing(PortfolioValuation.PositionValue::accountId)).forEach(p->position(text,p));return text.toString();}
    private void position(StringBuilder text,PortfolioValuation.PositionValue p){text.append("\n").append(safe(p.ticker())).append("; счёт: ").append(safe(p.accountName())).append("\nUID: ").append(p.position().instrumentUid()).append("\nОстаток: ").append(number(p.position().quantity())).append(" акций; средняя стоимость остатка: ").append(number(p.position().averageCost()))
            .append(" ").append(p.position().currency()).append("/акция\nОставшаяся стоимость: ").append(number(p.position().remainingCost())).append(" ").append(p.position().currency())
            .append("; качество стоимости: ").append(p.position().costQuality()).append("\nЦена: ").append(p.quote()==null?"нет данных":number(p.quote().price())+" "+p.quote().currency()).append(" [").append(p.quoteStatus()).append("]\nВремя сделки: ")
            .append(time(p.quote()==null?null:p.quote().quoteTime())).append("; источник: ").append(safe(p.quote()==null?null:p.quote().source())).append("\nРыночная стоимость: ").append(number(p.marketValue())).append(" RUB; нереализованный P&L: ").append(number(p.unrealizedPnL())).append(" RUB; изменение к известной стоимости: ").append(number(p.unrealizedPercent())).append(" %\n");warnings(text,p.warnings());}
    public String detail(PortfolioService.HoldingDetail d){var text=new StringBuilder("Позиция UID: "+d.uid()+"\n");d.positions().forEach(p->position(text,p));
        text.append("\nАгрегат совместимых RUB-позиций по счетам: ").append(number(d.shares())).append(" акций; стоимость: ").append(number(d.remainingCost())).append(" RUB; оценка: ").append(number(d.marketValue())).append(" RUB; P&L: ").append(number(d.unrealizedPnL())).append(" RUB\nЛоты приобретения:\n");
        d.lots().forEach(item->{var l=item.lot();text.append("Счёт: ").append(safe(item.accountName())).append("; покупка/начальная запись: ").append(l.transactionId()).append("; ").append(time(l.acquiredAt())).append("; первоначально: ").append(number(l.initialQuantity())).append("; осталось: ").append(number(l.remainingQuantity())).append(" акций; стоимость остатка: ").append(number(l.remainingCost())).append(" ").append(l.currency()).append("\n");});
        if(d.positions().isEmpty())text.append("В журнале портфеля нет этой позиции.\n");return text.toString();}
    public String accounts(List<PortfolioAccount> accounts){var text=new StringBuilder("Локальные инвестиционные счета:\n");accounts.forEach(a->text.append(safe(a.name())).append("; ").append(a.source()).append("/").append(a.accountType()).append("; брокер: ").append(safe(a.broker())).append("; синхронизация: ").append(a.synchronizationEnabled()?"включена":"выключена").append("; последнее успешное обновление: ").append(time(a.lastSuccessfulSyncAt())).append("\nВыбрать локальный счёт: /accounts select ").append(a.id()).append("\n"));return text.toString();}
    public String reconcile(ReconciliationResult r){var text=new StringBuilder("Сверка по сохранённым наблюдениям брокера: "+time(r.checkedAt())+"\nНаблюдение брокера: "+time(r.brokerCapturedAt())+"\nСчёт: "+r.accountId()+"\n");for(var row:r.rows())text.append("UID/деньги: ").append(safe(row.instrumentUid())).append(" ").append(safe(row.currency())).append("; журнал: ").append(number(row.internalQuantity())).append("; брокер: ").append(number(row.brokerQuantity())).append("; разница: ").append(number(row.difference())).append(" [").append(row.status()).append("]\n");warnings(text,r.warnings());return text.append("Блокировки и время расчётов могут различаться. Сверка не создаёт сделки или денежные корректировки.").toString();}
    private void warnings(StringBuilder text,List<String> warnings){for(var warning:warnings)text.append("Ограничение данных: ").append(safe(warning)).append("\n");}
}
