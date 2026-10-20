package ru.ba6kir.tradelab.market;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.ba6kir.tradelab.market.application.*;
import ru.ba6kir.tradelab.market.domain.StockInstrument;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ru.ba6kir.tradelab.market.InstrumentServiceTest.*;

@SpringBootTest(properties = {"tradelab.telegram.enabled=false", "tradelab.invest.enabled=false", "tradelab.invest.token="})
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class InstrumentPersistenceIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-bookworm");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("tradelab.database.host", POSTGRES::getHost);
        r.add("tradelab.database.port", POSTGRES::getFirstMappedPort);
        r.add("tradelab.database.name", POSTGRES::getDatabaseName);
        r.add("tradelab.database.username", POSTGRES::getUsername);
        r.add("tradelab.database.password", POSTGRES::getPassword);
    }
    @Autowired InstrumentStore store;
    @Autowired JdbcTemplate jdbc;

    @Test void repeatedSynchronizationUpdatesSameUidAndPreservesOptionalNullsAndUtc() {
        var provider = mock(StockInstrumentProvider.class);
        var service = new InstrumentService(provider, store, java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC), Duration.ofHours(24), true);
        var first = stock(UID, "old", NOW.minusSeconds(60));
        var updated = stock(UID, "new", NOW);
        when(provider.findByUid(UID)).thenReturn(Optional.of(first), Optional.of(updated), Optional.of(updated));
        service.refresh(UID);
        service.refresh(UID);
        service.refresh(UID);
        assertThat(store.findByUid(UID)).contains(updated);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM instruments WHERE uid = ?", Long.class, UID)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_name='instruments' AND column_name='updated_at'",
                String.class)).isEqualTo("timestamp with time zone");
        assertThat(jdbc.queryForObject("SHOW TIME ZONE", String.class)).isEqualTo("UTC");
        store.upsert(first); // A late stale response cannot overwrite a newer refresh.
        assertThat(store.findByUid(UID)).contains(updated);
    }

    @Test void tickerAndClassCodeDoNotFormUniqueIdentity() {
        store.upsert(stock(UID, "one", NOW));
        var second = stock("22222222-2222-2222-2222-222222222222", "two", NOW);
        store.upsert(second);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM instruments WHERE ticker = 'SBER'", Long.class)).isEqualTo(2L);
        assertThat(store.findByUid(second.uid())).contains(second);
        assertThat(store.findByUid("33333333-3333-3333-3333-333333333333")).isEmpty();
    }
}
