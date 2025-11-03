package app.chatbot.chat;

import app.chatbot.chat.dto.ChatMessageRequest;
import app.chatbot.chat.dto.CreateChatRequest;
import app.chatbot.chat.dto.ToolCallInfo;
import app.chatbot.chat.dto.UpdateChatRequest;
import app.chatbot.utils.GenericMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    private ChatRepository chatRepository;
    private ChatTitleService chatTitleService;
    private ChatService chatService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        chatRepository = mock(ChatRepository.class);
        chatTitleService = mock(ChatTitleService.class);
        objectMapper = Jackson2ObjectMapperBuilder.json().build();
        GenericMapper mapper = new GenericMapper(objectMapper);
        chatService = new ChatService(chatRepository, mapper, chatTitleService, objectMapper);

        when(chatRepository.save(any(Chat.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void saveChatTriggersTitleGenerationWhenMissingTitle() {
        CreateChatRequest request = new CreateChatRequest(
                null,
                null,
                "gpt-model",
                "You are helpful",
                "  title-model  ",
                null,
                List.of(new ChatMessageRequest("msg-1", MessageRole.USER, "Hello there", Instant.now(), null))
        );

        var result = chatService.saveChat(request);

        assertThat(result.title()).isNull();
        assertThat(result.systemPrompt()).isEqualTo("You are helpful");
        assertThat(result.titleModel()).isEqualTo("title-model");

        verify(chatTitleService).generateTitleAsync(
                eq(result.chatId()),
                eq("title-model"),
                argThat(messages -> {
                    assertThat(messages).hasSize(1);
                    assertThat(messages.get(0).content()).isEqualTo("Hello there");
                    return true;
                })
        );
    }

    @Test
    void saveChatDoesNotTriggerTitleGenerationWhenTitleProvided() {
        CreateChatRequest request = new CreateChatRequest(
                null,
                "Manual title",
                "gpt-model",
                "You are helpful",
                null,
                null,
                List.of(new ChatMessageRequest("msg-1", MessageRole.USER, "Hello there", Instant.now(), null))
        );

        var result = chatService.saveChat(request);

        assertThat(result.title()).isEqualTo("Manual title");
        assertThat(result.systemPrompt()).isEqualTo("You are helpful");
        assertThat(result.titleModel()).isNull();
        verify(chatTitleService, never()).generateTitleAsync(any(), any(), any());
    }

    @Test
    void listChatsReturnsSummariesSortedByUpdatedAt() {
        Chat older = new Chat();
        older.setChatId("old");
        older.setCreatedAt(Instant.parse("2024-01-01T00:00:00Z"));
        older.addMessage(createMessage("old-msg"));
        older.setUpdatedAt(Instant.parse("2024-01-02T00:00:00Z"));

        Chat newer = new Chat();
        newer.setChatId("new");
        newer.setCreatedAt(Instant.parse("2024-02-01T00:00:00Z"));
        newer.addMessage(createMessage("new-msg"));
        newer.setUpdatedAt(Instant.parse("2024-02-02T00:00:00Z"));

        when(chatRepository.findAll()).thenReturn(List.of(older, newer));

        var summaries = chatService.listChats();

        assertThat(summaries).hasSize(2);
        assertThat(summaries.get(0).chatId()).isEqualTo("new");
        assertThat(summaries.get(1).chatId()).isEqualTo("old");
    }

    @Test
    void getChatReturnsDtoWithMessages() {
        Chat chat = new Chat();
        chat.setChatId("chat-123");
        chat.setCreatedAt(Instant.parse("2024-03-01T00:00:00Z"));
        chat.setUpdatedAt(Instant.parse("2024-03-01T01:00:00Z"));
        ChatMessage message = createMessage("msg-1");
        message.setContent("Hello");
        message.setRole(MessageRole.USER);
        message.setCreatedAt(Instant.parse("2024-03-01T00:05:00Z"));
        chat.addMessage(message);
        chat.setSystemPrompt("Stored prompt");
        chat.setTitleModel("title-model");
        chat.setUpdatedAt(Instant.parse("2024-03-01T01:00:00Z"));

        when(chatRepository.findWithMessagesByChatId("chat-123")).thenReturn(Optional.of(chat));

        var dto = chatService.getChat("chat-123");

        assertThat(dto.chatId()).isEqualTo("chat-123");
        assertThat(dto.messages()).hasSize(1);
        assertThat(dto.messages().get(0).content()).isEqualTo("Hello");
        assertThat(dto.systemPrompt()).isEqualTo("Stored prompt");
        assertThat(dto.titleModel()).isEqualTo("title-model");
    }

    @Test
    void getChatIncludesToolCallMetadata() throws Exception {
        Chat chat = new Chat();
        chat.setChatId("chat-tools");
        ChatMessage message = createMessage("msg-tools");
        message.setRole(MessageRole.ASSISTANT);
        ToolCallInfo call = new ToolCallInfo("search", "server-1", "{}", "result", true);
        message.setMetadata(objectMapper.writeValueAsString(List.of(call)));
        chat.addMessage(message);

        when(chatRepository.findWithMessagesByChatId("chat-tools")).thenReturn(Optional.of(chat));

        var dto = chatService.getChat("chat-tools");

        assertThat(dto.messages()).hasSize(1);
        assertThat(dto.messages().get(0).toolCalls()).isNotNull();
        assertThat(dto.messages().get(0).toolCalls()).containsExactly(call);
    }

    @Test
    void getChatThrowsWhenMissing() {
        when(chatRepository.findWithMessagesByChatId("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.getChat("missing"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Chat not found");
    }

    @Test
    void addMessageAppendsAndPersists() {
        Chat chat = new Chat();
        chat.setChatId("chat-1");
        chat.setCreatedAt(Instant.now());
        chat.setUpdatedAt(Instant.now());
        ChatMessage existing = createMessage("existing");
        existing.setContent("Hi");
        existing.setRole(MessageRole.USER);
        existing.setCreatedAt(Instant.now());
        chat.addMessage(existing);

        when(chatRepository.findWithMessagesByChatId("chat-1")).thenReturn(Optional.of(chat));

        ChatMessageRequest request = new ChatMessageRequest("new-msg", MessageRole.ASSISTANT, "Hello", null, null);

        chatService.addMessage("chat-1", request);

        verify(chatRepository).save(argThat(saved ->
                saved.getMessages().stream().anyMatch(m -> m.getMessageId().equals("new-msg"))
        ));
    }

    @Test
    void addMessageRejectsDuplicates() {
        Chat chat = new Chat();
        chat.setChatId("chat-1");
        chat.setCreatedAt(Instant.now());
        chat.setUpdatedAt(Instant.now());
        ChatMessage existing = createMessage("dup");
        existing.setRole(MessageRole.USER);
        existing.setContent("Hi");
        existing.setCreatedAt(Instant.now());
        chat.addMessage(existing);

        when(chatRepository.findWithMessagesByChatId("chat-1")).thenReturn(Optional.of(chat));

        ChatMessageRequest duplicate = new ChatMessageRequest("dup", MessageRole.USER, "Another", null, null);

        assertThatThrownBy(() -> chatService.addMessage("chat-1", duplicate))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Message already exists");
    }

    @Test
    void updateChatTrimsTitle() {
        Chat chat = new Chat();
        chat.setChatId("chat-2");
        chat.setTitle("Existing");
        when(chatRepository.findByChatId("chat-2")).thenReturn(Optional.of(chat));
        when(chatRepository.save(any(Chat.class))).thenAnswer(inv -> inv.getArgument(0));

        chat.setSystemPrompt("Existing prompt");
        chat.setTitleModel("existing-model");

        var dto = chatService.updateChat(
                "chat-2",
                new UpdateChatRequest("  Updated Title  ", "  Updated Prompt  ", "  new-model  ")
        );

        assertThat(dto.title()).isEqualTo("Updated Title");
        assertThat(dto.systemPrompt()).isEqualTo("Updated Prompt");
        assertThat(dto.titleModel()).isEqualTo("new-model");
    }

    @Test
    void updateChatClearsPromptAndTitleModelWhenBlank() {
        Chat chat = new Chat();
        chat.setChatId("chat-3");
        chat.setSystemPrompt("Existing prompt");
        chat.setTitleModel("existing-model");
        when(chatRepository.findByChatId("chat-3")).thenReturn(Optional.of(chat));

        var result = chatService.updateChat(
                "chat-3",
                new UpdateChatRequest(null, "   ", "   ")
        );

        verify(chatRepository).save(argThat(saved -> saved.getSystemPrompt() == null && saved.getTitleModel() == null));
        assertThat(chat.getSystemPrompt()).isNull();
        assertThat(chat.getTitleModel()).isNull();
        assertThat(result.systemPrompt()).isNull();
        assertThat(result.titleModel()).isNull();
    }

    @Test
    void deleteChatDelegatesToRepository() {
        chatService.deleteChat("chat-3");
        verify(chatRepository).deleteByChatId("chat-3");
    }

    private ChatMessage createMessage(String id) {
        ChatMessage message = new ChatMessage();
        message.setMessageId(id);
        message.setRole(MessageRole.USER);
        message.setContent("content");
        message.setCreatedAt(Instant.now());
        return message;
    }
}
