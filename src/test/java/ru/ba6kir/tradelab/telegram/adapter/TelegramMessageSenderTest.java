package ru.ba6kir.tradelab.telegram.adapter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static ru.ba6kir.tradelab.telegram.TelegramTestData.OWNER;

@ExtendWith(OutputCaptureExtension.class)
class TelegramMessageSenderTest {
    private final TelegramClient client = mock(TelegramClient.class);
    private final TelegramMessageSender sender = new TelegramMessageSender(client, OWNER);

    @Test
    void usesPlainTextAndSplitsWithoutBreakingSurrogatePairs() throws Exception {
        String text = "a".repeat(4095) + "😀" + "б".repeat(4096);
        assertThat(sender.send(OWNER, text)).isTrue();

        var captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client, times(3)).execute(captor.capture());
        var messages = captor.getAllValues();
        assertThat(messages).allSatisfy(message -> {
            assertThat(message.getChatId()).isEqualTo(Long.toString(OWNER));
            assertThat(message.getParseMode()).isNull();
            assertThat(message.getText().length()).isBetween(1, 4096);
            assertThat(Character.isHighSurrogate(message.getText().charAt(message.getText().length() - 1))).isFalse();
            assertThat(Character.isLowSurrogate(message.getText().charAt(0))).isFalse();
        });
        assertThat(String.join("", messages.stream().map(SendMessage::getText).toList())).isEqualTo(text);
    }

    @Test
    void refusesNonOwnerAndEmptyMessages() {
        assertThat(sender.send(123, "private response")).isFalse();
        assertThat(sender.send(OWNER, null)).isFalse();
        assertThat(sender.send(OWNER, " ")).isFalse();
        verifyNoInteractions(client);
    }

    @Test
    void networkFailuresAreContainedWithoutLeakingTokenOrRetrying(CapturedOutput output) throws Exception {
        when(client.execute(any(SendMessage.class)))
                .thenThrow(new TelegramApiException("https://api.telegram.org/botDO_NOT_LOG_THIS_TOKEN/sendMessage"));

        assertThat(sender.send(OWNER, "a".repeat(5000))).isFalse();

        verify(client).execute(any(SendMessage.class));
        assertThat(output).doesNotContain("DO_NOT_LOG_THIS_TOKEN", "api.telegram.org");
    }
}
