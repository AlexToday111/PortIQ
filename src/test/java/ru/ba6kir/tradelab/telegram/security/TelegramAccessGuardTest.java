package ru.ba6kir.tradelab.telegram.security;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import ru.ba6kir.tradelab.telegram.config.TelegramProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static ru.ba6kir.tradelab.telegram.TelegramTestData.*;

class TelegramAccessGuardTest {
    private final TelegramAccessGuard guard = new TelegramAccessGuard(properties());

    @Test
    void allowsOwnerInMatchingPrivateChatWith64BitId() {
        assertThat(guard.isAllowed(update("/ping"))).isTrue();
    }

    @Test
    void neverAuthorizesWhenDisabled() {
        var disabled = new TelegramAccessGuard(new TelegramProperties(false, null, OWNER));
        assertThat(disabled.isAllowed(update("/ping"))).isFalse();
    }

    @ParameterizedTest
    @MethodSource("rejectedUpdates")
    void rejectsMissingOrUntrustedIdentity(Update update) {
        assertThat(guard.isAllowed(update)).isFalse();
    }

    static Stream<Update> rejectedUpdates() {
        var otherUser = update("/status");
        otherUser.getMessage().getFrom().setId(123L);
        var noSender = update("/status");
        noSender.getMessage().setFrom(null);
        var noSenderId = update("/status");
        var senderWithoutId = mock(User.class);
        when(senderWithoutId.getIsBot()).thenReturn(false);
        noSenderId.getMessage().setFrom(senderWithoutId);
        var noChat = update("/status");
        noChat.getMessage().setChat(null);
        var noChatId = update("/status");
        var chatWithoutId = mock(Chat.class);
        when(chatWithoutId.getType()).thenReturn("private");
        noChatId.getMessage().setChat(chatWithoutId);
        var noChatType = update("/status");
        var chatWithoutType = mock(Chat.class);
        when(chatWithoutType.getId()).thenReturn(OWNER);
        noChatType.getMessage().setChat(chatWithoutType);
        var differentChat = update("/status");
        differentChat.getMessage().getChat().setId(123L);
        var botSender = update("/status");
        botSender.getMessage().getFrom().setIsBot(true);
        var unknownSenderType = update("/status");
        var senderWithoutType = mock(User.class);
        when(senderWithoutType.getId()).thenReturn(OWNER);
        when(senderWithoutType.getIsBot()).thenReturn(null);
        unknownSenderType.getMessage().setFrom(senderWithoutType);
        var senderChat = update("/status");
        senderChat.getMessage().setSenderChat(Chat.builder().id(OWNER).type("private").build());
        var group = update("/status");
        group.getMessage().getChat().setType("group");
        var supergroup = update("/status");
        supergroup.getMessage().getChat().setType("supergroup");
        var channel = update("/status");
        channel.getMessage().getChat().setType("channel");
        var edited = new Update();
        edited.setEditedMessage(update("/status").getMessage());
        return Stream.of(null, new Update(), otherUser, noSender, noSenderId, noChat,
                noChatId, noChatType, differentChat, botSender, unknownSenderType, senderChat,
                group, supergroup, channel, edited);
    }
}
