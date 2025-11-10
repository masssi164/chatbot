package app.chatbot.conversation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import app.chatbot.conversation.dto.AddMessageRequest;
import app.chatbot.conversation.dto.CreateConversationRequest;
import app.chatbot.conversation.dto.NewMessageRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@WebFluxTest(ConversationController.class)
@AutoConfigureWebTestClient
class ConversationControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ConversationRepository conversationRepository;

    @MockitoBean
    private MessageRepository messageRepository;

    @MockitoBean
    private ToolCallRepository toolCallRepository;

    @MockitoBean
    private ConversationService conversationService;

    @Test
    void shouldListConversationsWithCounts() {
        Conversation conversation = Conversation.builder()
                .id(1L)
                .title("Sample")
                .createdAt(Instant.parse("2024-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2024-01-01T01:00:00Z"))
                .build();

        Mockito.when(conversationRepository.findAllByOrderByUpdatedAtDesc())
                .thenReturn(Flux.just(conversation));
        Mockito.when(messageRepository.countByConversationId(1L)).thenReturn(Mono.just(5L));

        webTestClient.get()
                .uri("/api/conversations")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].id").isEqualTo(1)
                .jsonPath("$[0].messageCount").isEqualTo(5);
    }

    @Test
    void shouldReturnConversationDetail() {
        Conversation conversation = Conversation.builder()
                .id(2L)
                .title("Detail")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        Message message = Message.builder()
                .id(11L)
                .conversationId(2L)
                .role(MessageRole.USER)
                .content("Hi")
                .createdAt(Instant.now())
                .build();

        ToolCall toolCall = ToolCall.builder()
                .id(21L)
                .conversationId(2L)
                .type(ToolCallType.MCP)
                .status(ToolCallStatus.COMPLETED)
                .createdAt(Instant.now())
                .build();

        Mockito.when(conversationRepository.findById(2L)).thenReturn(Mono.just(conversation));
        Mockito.when(messageRepository.findByConversationIdOrderByCreatedAtAsc(2L)).thenReturn(Flux.just(message));
        Mockito.when(toolCallRepository.findByConversationIdOrderByCreatedAtAsc(2L)).thenReturn(Flux.just(toolCall));

        webTestClient.get()
                .uri("/api/conversations/2")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.messages[0].content").isEqualTo("Hi")
                .jsonPath("$.toolCalls[0].type").isEqualTo("MCP");
    }

    @Test
    void shouldCreateConversationAndSeedMessages() {
        Conversation created = Conversation.builder()
                .id(3L)
                .title("Created")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        Message seeded = Message.builder()
                .id(31L)
                .conversationId(3L)
                .role(MessageRole.USER)
                .content("Seed")
                .createdAt(Instant.now())
                .build();

        Mockito.when(conversationService.ensureConversation(null, "Created")).thenReturn(Mono.just(created));
        Mockito.when(conversationService.appendMessage(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(seeded));
        Mockito.when(conversationRepository.findById(3L)).thenReturn(Mono.just(created));
        Mockito.when(messageRepository.findByConversationIdOrderByCreatedAtAsc(3L))
                .thenReturn(Flux.just(seeded));
        Mockito.when(toolCallRepository.findByConversationIdOrderByCreatedAtAsc(3L))
                .thenReturn(Flux.empty());

        CreateConversationRequest request = new CreateConversationRequest(
                "Created",
                List.of(new NewMessageRequest(MessageRole.USER, "Seed", null, null, null, null))
        );

        webTestClient.post()
                .uri("/api/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.messages[0].content").isEqualTo("Seed");

        verify(conversationService, times(1)).ensureConversation(null, "Created");
        verify(conversationService, times(1)).appendMessage(any(), any(), any(), any(), any(), any());
    }

    @Test
    void addMessageReturns404WhenConversationMissing() {
        Mockito.when(conversationRepository.findById(99L)).thenReturn(Mono.empty());

        AddMessageRequest request = new AddMessageRequest(MessageRole.USER, "Hi", null, null, null);

        webTestClient.post()
                .uri("/api/conversations/99/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void addMessageAppendsWhenConversationExists() {
        Conversation conversation = Conversation.builder().id(4L).build();
        Message appended = Message.builder().id(41L).conversationId(4L).content("Hi").role(MessageRole.USER).build();

        Mockito.when(conversationRepository.findById(4L)).thenReturn(Mono.just(conversation));
        Mockito.when(conversationService.appendMessage(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(appended));

        AddMessageRequest request = new AddMessageRequest(MessageRole.USER, "Hi", null, null, null);

        webTestClient.post()
                .uri("/api/conversations/4/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content").isEqualTo("Hi");

        verify(conversationService).appendMessage(4L, MessageRole.USER, "Hi", null, null, null);
    }
}
