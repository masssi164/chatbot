package app.chatbot.chat;

import app.chatbot.chat.dto.ChatMessageRequest;
import app.chatbot.openai.OpenAiProperties;
import app.chatbot.openai.OpenAiProxyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatTitleServiceTest {

    private ChatRepository chatRepository;
    private OpenAiProxyService proxyService;
    private ChatTitleService chatTitleService;

    @BeforeEach
    void setUp() {
        chatRepository = mock(ChatRepository.class);
        proxyService = mock(OpenAiProxyService.class);
        OpenAiProperties properties = new OpenAiProperties(
                "http://localhost:1234/v1",
                null,
                "fallback-model",
                java.time.Duration.ofSeconds(5),
                java.time.Duration.ofSeconds(120)
        );
        ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
        chatTitleService = new ChatTitleService(chatRepository, proxyService, objectMapper, properties);
    }

    @Test
    void generatesAndPersistsTitle() {
        Chat chat = new Chat();
        chat.setChatId("chat-1");
        when(chatRepository.findByChatId("chat-1")).thenReturn(Optional.of(chat));
        when(proxyService.createResponse(any(), any()))
                .thenReturn(ResponseEntity.ok("{\"output_text\":\"Amazing Title\"}"));

        List<ChatMessageRequest> messages = List.of(
                new ChatMessageRequest("msg-1", MessageRole.USER, "Hello", Instant.now(), null)
        );

        chatTitleService.generateTitle("chat-1", "preferred", messages);

        verify(chatRepository).save(argThat(saved -> {
            assertThat(saved.getTitle()).isEqualTo("Amazing Title");
            return true;
        }));
        verify(proxyService).createResponse(any(), any());
    }

    @Test
    void sanitizesQuotedTitle() {
        Chat chat = new Chat();
        chat.setChatId("chat-2");
        when(chatRepository.findByChatId("chat-2")).thenReturn(Optional.of(chat));
        when(proxyService.createResponse(any(), any()))
                .thenReturn(ResponseEntity.ok("{\"output_text\":\"\\\"Quoted\\\"\"}"));

        chatTitleService.generateTitle("chat-2", null, List.of(
                new ChatMessageRequest("msg-1", MessageRole.USER, "Hello", Instant.now(), null)
        ));

        verify(chatRepository).save(argThat(saved -> {
            assertThat(saved.getTitle()).isEqualTo("Quoted");
            return true;
        }));
    }

    @Test
    void truncatesLongTitle() {
        Chat chat = new Chat();
        chat.setChatId("chat-3");
        when(chatRepository.findByChatId("chat-3")).thenReturn(Optional.of(chat));
        String longTitle = "This title is intentionally extended so that it exceeds the maximum allowed character length for titles";
        when(proxyService.createResponse(any(), any()))
                .thenReturn(ResponseEntity.ok("{\"output_text\":\"" + longTitle + "\"}"));

        chatTitleService.generateTitle("chat-3", null, List.of(
                new ChatMessageRequest("msg-1", MessageRole.USER, "Hello", Instant.now(), null)
        ));

        verify(chatRepository).save(argThat(saved -> {
            assertThat(saved.getTitle()).endsWith("...");
            assertThat(saved.getTitle().length()).isLessThanOrEqualTo(80);
            return true;
        }));
    }

    @Test
    void extractsTitleFromChoicesArray() {
        Chat chat = new Chat();
        chat.setChatId("chat-4");
        when(chatRepository.findByChatId("chat-4")).thenReturn(Optional.of(chat));
        when(proxyService.createResponse(any(), any())).thenReturn(ResponseEntity.ok("""
                {
                  "choices": [
                    {"message": {"role": "assistant", "content": "Primary suggestion"}},
                    {"message": {"role": "assistant", "content": "Secondary"}}
                  ]
                }
                """));

        chatTitleService.generateTitle("chat-4", null, List.of(
                new ChatMessageRequest("msg-1", MessageRole.USER, "Hello", Instant.now(), null)
        ));

        verify(chatRepository).save(argThat(saved -> {
            assertThat(saved.getTitle()).isEqualTo("Primary suggestion");
            return true;
        }));
    }

    @Test
    void skipsWhenNoUserMessage() {
        chatTitleService.generateTitle("chat-5", null, List.of(
                new ChatMessageRequest("msg-1", MessageRole.ASSISTANT, "Hi", Instant.now(), null)
        ));

        verify(proxyService, never()).createResponse(any(), any());
        verify(chatRepository, never()).save(any());
    }

    @Test
    void skipsWhenResponseNotSuccessful() {
        Chat chat = new Chat();
        chat.setChatId("chat-6");
        when(chatRepository.findByChatId("chat-6")).thenReturn(Optional.of(chat));
        when(proxyService.createResponse(any(), any()))
                .thenReturn(ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("{}"));

        chatTitleService.generateTitle("chat-6", null, List.of(
                new ChatMessageRequest("msg-1", MessageRole.USER, "Hi", Instant.now(), null)
        ));

        verify(proxyService).createResponse(any(), any());
        verify(chatRepository, never()).save(any());
    }

    @Test
    void skipsWhenSanitizedTitleEmpty() {
        Chat chat = new Chat();
        chat.setChatId("chat-7");
        when(chatRepository.findByChatId("chat-7")).thenReturn(Optional.of(chat));
        when(proxyService.createResponse(any(), any()))
                .thenReturn(ResponseEntity.ok("{\"output_text\":\"\"}"));

        chatTitleService.generateTitle("chat-7", null, List.of(
                new ChatMessageRequest("msg-1", MessageRole.USER, "Hi", Instant.now(), null)
        ));

        verify(proxyService).createResponse(any(), any());
        verify(chatRepository, never()).save(any());
    }

    @Test
    void resolvesModelFallback() {
        Chat chat = new Chat();
        chat.setChatId("chat-8");
        when(chatRepository.findByChatId("chat-8")).thenReturn(Optional.of(chat));
        when(proxyService.createResponse(any(), any()))
                .thenReturn(ResponseEntity.ok("{\"output_text\":\"Fallback\"}"));

        chatTitleService.generateTitle("chat-8", "  ", List.of(
                new ChatMessageRequest("msg-1", MessageRole.USER, "Hi", Instant.now(), null)
        ));

        verify(proxyService).createResponse(argThat(payload -> {
            assertThat(payload.get("model").asText()).isEqualTo("fallback-model");
            assertThat(payload.get("input").isArray()).isTrue();
            return true;
        }), any());
    }

    @Test
    void logsResponseErrorWithoutSaving() {
        Chat chat = new Chat();
        chat.setChatId("chat-9");
        when(chatRepository.findByChatId("chat-9")).thenReturn(Optional.of(chat));
        when(proxyService.createResponse(any(), any()))
                .thenReturn(ResponseEntity.ok("{\"output_text\":\"   \"}"));

        chatTitleService.generateTitle("chat-9", "preferred", List.of(
                new ChatMessageRequest("msg-1", MessageRole.USER, "Hello", Instant.now(), null)
        ));

        verify(chatRepository, never()).save(any());
    }
}
