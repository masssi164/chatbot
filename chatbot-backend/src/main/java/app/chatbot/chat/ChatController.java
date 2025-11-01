package app.chatbot.chat;

import app.chatbot.chat.dto.ChatDto;
import app.chatbot.chat.MessageRole;
import app.chatbot.chat.dto.ChatMessageDto;
import app.chatbot.chat.dto.ChatMessageRequest;
import app.chatbot.chat.dto.ChatSummaryDto;
import app.chatbot.chat.dto.ChatCompletionRequest;
import app.chatbot.chat.dto.CreateChatRequest;
import app.chatbot.chat.dto.UpdateChatRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/chats")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final ChatService chatService;
    private final ChatConversationService chatConversationService;

    @GetMapping
    public List<ChatSummaryDto> listChats() {
        log.debug("Listing chats");
        return chatService.listChats();
    }

    @GetMapping("/{chatId}")
    public ChatDto getChat(@PathVariable("chatId") String chatId) {
        log.debug("Fetching chat {}", chatId);
        return chatService.getChat(chatId);
    }

    @PostMapping
    public ChatDto saveChat(@Valid @RequestBody CreateChatRequest request) {
        log.debug("Saving chat (chatId={})", request.chatId());
        ChatDto chat = chatService.saveChat(request);

        if (StringUtils.hasText(request.model()) && request.messages() != null && !request.messages().isEmpty()) {
            ChatMessageRequest lastMessage = request.messages().get(request.messages().size() - 1);
            if (lastMessage != null && lastMessage.role() == MessageRole.USER) {
                try {
                    chatConversationService.generateAssistantMessage(
                            chat.chatId(),
                            request.model(),
                            request.systemPrompt(),
                            request.parameters()
                    );
                } catch (ResponseStatusException exception) {
                    log.warn("Assistant generation failed for new chat {}", chat.chatId(), exception);
                }
                chat = chatService.getChat(chat.chatId());
            }
        }

        return chat;
    }

    @PostMapping("/{chatId}/messages")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ChatDto addMessage(@PathVariable("chatId") String chatId,
                              @Valid @RequestBody ChatCompletionRequest request) {
        if (request.message().role() != MessageRole.USER) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Only user messages can initiate responses");
        }

        log.debug("Adding message (chatId={}, messageId={})", chatId, request.message().messageId());
        chatService.addMessage(chatId, request.message());
        try {
            chatConversationService.generateAssistantMessage(
                    chatId,
                    request.model(),
                    request.systemPrompt(),
                    request.parameters()
            );
        } catch (ResponseStatusException exception) {
            log.warn("Assistant generation failed for chat {}", chatId, exception);
        }
        return chatService.getChat(chatId);
    }

    @PutMapping("/{chatId}")
    public ChatDto updateChat(@PathVariable("chatId") String chatId, @RequestBody UpdateChatRequest request) {
        log.debug("Updating chat {}", chatId);
        return chatService.updateChat(chatId, request);
    }

    @DeleteMapping("/{chatId}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void deleteChat(@PathVariable("chatId") String chatId) {
        log.debug("Deleting chat {}", chatId);
        chatService.deleteChat(chatId);
    }
}
