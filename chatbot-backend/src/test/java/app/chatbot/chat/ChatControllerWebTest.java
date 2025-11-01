package app.chatbot.chat;

import app.chatbot.chat.dto.ChatDto;
import app.chatbot.chat.dto.ChatSummaryDto;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
class ChatControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatService chatService;

    @MockBean
    private ChatConversationService chatConversationService;

    @Test
    void resolvesChatIdPathVariable() throws Exception {
        ChatDto dto = new ChatDto(
                "chat-123",
                "Demo chat",
                "You are helpful",
                "title-model",
                Instant.now(),
                Instant.now(),
                List.of()
        );
        when(chatService.getChat("chat-123")).thenReturn(dto);

        mockMvc.perform(get("/api/chats/{chatId}", "chat-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatId").value("chat-123"));

        verify(chatService).getChat("chat-123");
    }

    @Test
    void triggersAssistantGenerationOnCreate() throws Exception {
        ChatDto dto = new ChatDto(
                "chat-1",
                null,
                "You are helpful",
                "title-gpt",
                Instant.now(),
                Instant.now(),
                List.of()
        );
        when(chatService.saveChat(Mockito.any())).thenReturn(dto);
        when(chatService.getChat("chat-1")).thenReturn(dto);

        String payload = """
                {
                  "chatId": null,
                  "title": null,
                  "model": "gpt",
                  "systemPrompt": "You are helpful",
                  "titleModel": "title-gpt",
                  "parameters": {
                    "temperature": 0.4,
                    "maxTokens": 512
                  },
                  "messages": [
                    {
                      "messageId": "msg-1",
                      "role": "USER",
                      "content": "Hi",
                      "createdAt": "2024-03-03T10:00:00Z"
                    }
                  ]
                }
                """;

        mockMvc.perform(
                post("/api/chats")
                        .contentType("application/json")
                        .content(payload)
        ).andExpect(status().isOk());

        verify(chatConversationService).generateAssistantMessage(
                Mockito.eq("chat-1"),
                Mockito.eq("gpt"),
                Mockito.eq("You are helpful"),
                Mockito.argThat(params -> params != null
                        && params.temperature() != null
                        && params.temperature().equals(0.4)
                        && params.maxTokens() != null
                        && params.maxTokens().equals(512))
        );
    }

    @Test
    void sendMessageEndpointReturnsChat() throws Exception {
        ChatDto chat = new ChatDto(
                "chat-1",
                null,
                "You are helpful",
                "title-gpt",
                Instant.now(),
                Instant.now(),
                List.of()
        );
        when(chatService.getChat("chat-1")).thenReturn(chat);

        String payload = """
                {
                  "model": "gpt",
                  "systemPrompt": "You are helpful",
                  "parameters": {
                    "topP": 0.9
                  },
                  "message": {
                    "messageId": "msg-1",
                    "role": "USER",
                    "content": "Hi",
                    "createdAt": "2024-03-03T10:00:00Z"
                  }
                }
                """;

        mockMvc.perform(
                post("/api/chats/{chatId}/messages", "chat-1")
                        .contentType("application/json")
                        .content(payload)
        ).andExpect(status().isCreated());

        verify(chatService).addMessage(Mockito.eq("chat-1"), Mockito.any());
        verify(chatConversationService).generateAssistantMessage(
                Mockito.eq("chat-1"),
                Mockito.eq("gpt"),
                Mockito.eq("You are helpful"),
                Mockito.argThat(params -> params != null
                        && params.topP() != null
                        && params.topP().equals(0.9))
        );
    }

    @Test
    void listChatsReturnsSummaries() throws Exception {
        List<ChatSummaryDto> summaries = List.of(
                new ChatSummaryDto("chat-1", "Title", Instant.now(), Instant.now(), 3)
        );
        when(chatService.listChats()).thenReturn(summaries);

        mockMvc.perform(get("/api/chats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].chatId").value("chat-1"));

        verify(chatService).listChats();
    }

    @Test
    void updateChatDelegatesToService() throws Exception {
        ChatDto updated = new ChatDto(
                "chat-1",
                "Renamed",
                "Updated system",
                "title-gpt",
                Instant.now(),
                Instant.now(),
                List.of()
        );
        when(chatService.updateChat(Mockito.eq("chat-1"), Mockito.any())).thenReturn(updated);

        mockMvc.perform(
                put("/api/chats/{chatId}", "chat-1")
                        .contentType("application/json")
                        .content("""
                                {
                                  "title": "Renamed",
                                  "systemPrompt": "  Updated system  ",
                                  "titleModel": "  title-gpt "
                                }
                                """)
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Renamed"))
                .andExpect(jsonPath("$.systemPrompt").value("Updated system"))
                .andExpect(jsonPath("$.titleModel").value("title-gpt"));

        verify(chatService).updateChat(Mockito.eq("chat-1"), Mockito.argThat(request ->
                "Renamed".equals(request.title())
                        && "  Updated system  ".equals(request.systemPrompt())
                        && "  title-gpt ".equals(request.titleModel())
        ));
    }

    @Test
    void deleteChatReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/chats/{chatId}", "chat-1"))
                .andExpect(status().isNoContent());

        verify(chatService).deleteChat("chat-1");
    }

    @Test
    void addMessageRejectsNonUserRole() throws Exception {
        String payload = """
                {
                  "model": "gpt",
                  "systemPrompt": "",
                  "message": {
                    "messageId": "msg-1",
                    "role": "ASSISTANT",
                    "content": "Hi",
                    "createdAt": "2024-03-03T10:00:00Z"
                  }
                }
                """;

        mockMvc.perform(
                post("/api/chats/{chatId}/messages", "chat-1")
                        .contentType("application/json")
                        .content(payload)
        ).andExpect(status().isBadRequest());

        verify(chatService, never()).addMessage(Mockito.anyString(), Mockito.any());
        verify(chatConversationService, never()).generateAssistantMessage(
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()
        );
    }
}
