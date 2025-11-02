package app.chatbot.chat;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import app.chatbot.chat.dto.ChatMessageDto;
import app.chatbot.chat.dto.CompletionParameters;
import app.chatbot.mcp.McpClientService;
import app.chatbot.mcp.McpServerRepository;
import app.chatbot.mcp.McpToolContextBuilder;
import app.chatbot.openai.OpenAiProxyService;

class ChatConversationServiceTest {

    private ChatRepository chatRepository;
    private ChatService chatService;
    private OpenAiProxyService proxyService;
    private ChatTitleService chatTitleService;
    private McpToolContextBuilder toolContextBuilder;
    private McpClientService mcpClientService;
    private McpServerRepository mcpServerRepository;
    private ChatConversationService conversationService;

    @BeforeEach
    void setUp() {
        chatRepository = mock(ChatRepository.class);
        chatService = mock(ChatService.class);
        proxyService = mock(OpenAiProxyService.class);
        chatTitleService = mock(ChatTitleService.class);
        toolContextBuilder = mock(McpToolContextBuilder.class);
        mcpClientService = mock(McpClientService.class);
        mcpServerRepository = mock(McpServerRepository.class);
        ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
        conversationService = new ChatConversationService(
            chatRepository, 
            chatService, 
            proxyService, 
            chatTitleService, 
            toolContextBuilder, 
            mcpClientService, 
            mcpServerRepository, 
            objectMapper
        );
    }

    @Test
    void generatesAssistantMessageAndPersists() {
        Chat chat = new Chat();
        chat.setChatId("chat-1");
        chat.setCreatedAt(Instant.now());
        chat.setUpdatedAt(Instant.now());
        chat.setTitleModel("title-model");
        ChatMessage userMessage = new ChatMessage();
        userMessage.setMessageId(UUID.randomUUID().toString());
        userMessage.setRole(MessageRole.USER);
        userMessage.setContent("Hello");
        userMessage.setCreatedAt(Instant.now());
        chat.addMessage(userMessage);

        when(chatRepository.findWithMessagesByChatId("chat-1")).thenReturn(Optional.of(chat));
        when(proxyService.createResponse(any(), any()))
                .thenReturn(ResponseEntity.ok("{\"choices\":[{\"message\":{\"content\":\"Howdy!\"}}]}"));
        ChatMessageDto assistantDto = new ChatMessageDto("assistant-1", MessageRole.ASSISTANT, "Howdy!", Instant.now());
        when(chatService.addMessage(eq("chat-1"), any())).thenReturn(assistantDto);

        ChatMessageDto result = conversationService.generateAssistantMessage(
                "chat-1",
                "gpt-model",
                "Be nice",
                null
        );

        assertThat(result.content()).isEqualTo("Howdy!");
        verify(proxyService).createResponse(argThat(payload -> {
            assertThat(payload.get("model").asText()).isEqualTo("gpt-model");
            assertThat(payload.get("input").isArray()).isTrue();
            assertThat(payload.get("input").size()).isGreaterThanOrEqualTo(2);
            assertThat(payload.get("input").get(0).get("role").asText()).isEqualTo("system");
            assertThat(payload.get("input").get(1).get("role").asText()).isEqualTo("user");
            return true;
        }), any());
        verify(chatService).addMessage(eq("chat-1"), any());
        verify(chatTitleService).generateTitleAsync(eq("chat-1"), eq("title-model"), argThat(messages ->
                messages != null && !messages.isEmpty() && messages.get(0).role() == MessageRole.USER));
    }

    @Test
    void throwsWhenChatMissing() {
        when(chatRepository.findWithMessagesByChatId("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> conversationService.generateAssistantMessage("missing", "model", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Chat not found");
        verifyNoInteractions(chatTitleService);
    }

    @Test
    void throwsWhenModelMissing() {
        Chat chat = new Chat();
        chat.setChatId("chat-1");
        chat.setCreatedAt(Instant.now());
        chat.setUpdatedAt(Instant.now());
        when(chatRepository.findWithMessagesByChatId("chat-1")).thenReturn(Optional.of(chat));

        assertThatThrownBy(() -> conversationService.generateAssistantMessage("chat-1", "", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Model must be provided");
        verifyNoInteractions(chatTitleService);
    }

    @Test
    void throwsWhenNoAssistantText() {
        Chat chat = new Chat();
        chat.setChatId("chat-1");
        chat.setCreatedAt(Instant.now());
        chat.setUpdatedAt(Instant.now());
        ChatMessage userMessage = new ChatMessage();
        userMessage.setMessageId(UUID.randomUUID().toString());
        userMessage.setRole(MessageRole.USER);
        userMessage.setContent("Hello");
        userMessage.setCreatedAt(Instant.now());
        chat.addMessage(userMessage);
        when(chatRepository.findWithMessagesByChatId("chat-1")).thenReturn(Optional.of(chat));
        when(proxyService.createResponse(any(), any())).thenReturn(ResponseEntity.ok("{}"));

        assertThatThrownBy(() -> conversationService.generateAssistantMessage("chat-1", "gpt", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No response text");
        verifyNoInteractions(chatTitleService);
    }

    @Test
    void appliesCompletionParameters() {
        Chat chat = new Chat();
        chat.setChatId("chat-2");
        chat.setCreatedAt(Instant.now());
        chat.setUpdatedAt(Instant.now());
        chat.setSystemPrompt("Stored prompt");
        chat.setTitleModel("fallback-model");
        ChatMessage user = new ChatMessage();
        user.setMessageId("user-1");
        user.setRole(MessageRole.USER);
        user.setContent("Hello");
        user.setCreatedAt(Instant.now());
        chat.addMessage(user);

        when(chatRepository.findWithMessagesByChatId("chat-2")).thenReturn(Optional.of(chat));
        when(proxyService.createResponse(any(), any()))
                .thenReturn(ResponseEntity.ok("{\"choices\":[{\"message\":{\"content\":\"Hi\"}}]}"));
        when(chatService.addMessage(eq("chat-2"), any())).thenReturn(
                new ChatMessageDto("assistant", MessageRole.ASSISTANT, "Hi", Instant.now())
        );

        CompletionParameters parameters = new CompletionParameters(0.5, 256, 0.9, 0.1, 0.0);

        conversationService.generateAssistantMessage("chat-2", "gpt", "System", parameters);

        verify(proxyService).createResponse(argThat(payload -> {
            assertThat(payload.get("temperature").asDouble()).isEqualTo(0.5);
            assertThat(payload.get("max_output_tokens").asInt()).isEqualTo(256);
            assertThat(payload.get("top_p").asDouble()).isEqualTo(0.9);
            assertThat(payload.get("presence_penalty").asDouble()).isEqualTo(0.1);
            assertThat(payload.get("frequency_penalty").asDouble()).isEqualTo(0.0);
            return true;
        }), any());
        verify(chatTitleService).generateTitleAsync(eq("chat-2"), eq("fallback-model"), any());
    }

    @Test
    void usesStoredSystemPromptWhenMissingInRequest() {
        Chat chat = new Chat();
        chat.setChatId("chat-3");
        chat.setCreatedAt(Instant.now());
        chat.setUpdatedAt(Instant.now());
        chat.setSystemPrompt("Stored prompt");
        chat.setTitleModel("title-model");
        ChatMessage userMessage = new ChatMessage();
        userMessage.setMessageId(UUID.randomUUID().toString());
        userMessage.setRole(MessageRole.USER);
        userMessage.setContent("Hello");
        userMessage.setCreatedAt(Instant.now());
        chat.addMessage(userMessage);

        when(chatRepository.findWithMessagesByChatId("chat-3")).thenReturn(Optional.of(chat));
        when(proxyService.createResponse(any(), any()))
                .thenReturn(ResponseEntity.ok("{\"choices\":[{\"message\":{\"content\":\"Howdy!\"}}]}"));
        ChatMessageDto assistantDto = new ChatMessageDto("assistant-1", MessageRole.ASSISTANT, "Howdy!", Instant.now());
        when(chatService.addMessage(eq("chat-3"), any())).thenReturn(assistantDto);

        conversationService.generateAssistantMessage("chat-3", "gpt", null, null);

        verify(proxyService).createResponse(argThat(payload -> {
            assertThat(payload.get("input").get(0).get("content").asText()).isEqualTo("Stored prompt");
            return true;
        }), any());
        verify(chatTitleService).generateTitleAsync(eq("chat-3"), eq("title-model"), any());
    }
}
