package ru.ba6kir.tradelab.telegram.adapter;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import ru.ba6kir.tradelab.telegram.dispatcher.TelegramCommandDispatcher;

import static org.mockito.Mockito.*;
import static ru.ba6kir.tradelab.telegram.TelegramTestData.update;

class TelegramUpdateConsumerTest {
    @Test
    void oneMalformedUpdateDoesNotStopSubsequentUpdates() {
        var dispatcher = mock(TelegramCommandDispatcher.class);
        var malformed = update("/status");
        var valid = update("/ping");
        doThrow(new IllegalArgumentException("sensitive diagnostics")).when(dispatcher).dispatch(malformed);
        var consumer = new TelegramUpdateConsumer(dispatcher);

        consumer.consume(null);
        consumer.consume(Arrays.asList(null, malformed, valid));

        verify(dispatcher).dispatch(null);
        verify(dispatcher).dispatch(malformed);
        verify(dispatcher).dispatch(valid);
    }
}
