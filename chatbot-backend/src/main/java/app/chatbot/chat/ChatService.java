package app.chatbot.chat;

import app.chatbot.chat.dto.ChatDto;
import app.chatbot.chat.dto.ChatMessageDto;
import app.chatbot.chat.dto.ChatMessageRequest;
import app.chatbot.chat.dto.ChatSummaryDto;
import app.chatbot.chat.dto.CreateChatRequest;
import app.chatbot.chat.dto.UpdateChatRequest;
import app.chatbot.utils.GenericMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatRepository chatRepository;
    private final GenericMapper mapper;
    private final ChatTitleService chatTitleService;

    @Transactional(readOnly = true)
    public List<ChatSummaryDto> listChats() {
        return chatRepository.findAll().stream()
                .sorted(Comparator.comparing(Chat::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(chat -> new ChatSummaryDto(
                        chat.getChatId(),
                        chat.getTitle(),
                        chat.getCreatedAt(),
                        chat.getUpdatedAt(),
                        chat.getMessages().size()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public ChatDto getChat(String chatId) {
        Chat chat = chatRepository.findWithMessagesByChatId(chatId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Chat not found"));
        return toDto(chat);
    }

    @Transactional
    public ChatDto saveChat(CreateChatRequest request) {
        String incomingId = StringUtils.hasText(request.chatId()) ? request.chatId().trim() : null;
        Chat chat = resolveOrCreate(incomingId);

        String trimmedTitle = request.title() != null ? request.title().trim() : null;
        boolean titleProvided = StringUtils.hasText(trimmedTitle);
        chat.setTitle(titleProvided ? trimmedTitle : null);

        String sanitizedSystemPrompt = StringUtils.hasText(request.systemPrompt())
                ? request.systemPrompt().trim()
                : null;
        chat.setSystemPrompt(sanitizedSystemPrompt);

        String sanitizedTitleModel = StringUtils.hasText(request.titleModel())
                ? request.titleModel().trim()
                : null;
        chat.setTitleModel(sanitizedTitleModel);

        List<ChatMessageRequest> messages = request.messages();
        if (messages != null) {
            chat.clearMessages();
            for (ChatMessageRequest messageRequest : messages) {
                chat.addMessage(toEntity(messageRequest));
            }
            messages.stream()
                    .map(ChatMessageRequest::createdAt)
                    .filter(Objects::nonNull)
                    .min(Instant::compareTo)
                    .ifPresent(chat::setCreatedAt);
        }

        Chat saved = chatRepository.save(chat);

        if (!titleProvided && messages != null && !messages.isEmpty()) {
            String preferredModel = StringUtils.hasText(request.titleModel())
                    ? request.titleModel().trim()
                    : null;
            chatTitleService.generateTitleAsync(saved.getChatId(), preferredModel, messages);
        }
        return toDto(saved);
    }

    @Transactional
    public ChatMessageDto addMessage(String chatId, ChatMessageRequest request) {
        Chat chat = chatRepository.findWithMessagesByChatId(chatId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Chat not found"));

        boolean duplicate = chat.getMessages().stream()
                .anyMatch(message -> message.getMessageId().equals(request.messageId()));

        if (duplicate) {
            throw new ResponseStatusException(CONFLICT, "Message already exists");
        }

        ChatMessage message = toEntity(request);
        if (message.getCreatedAt() == null) {
            message.setCreatedAt(Instant.now());
        }
        chat.addMessage(message);
        chatRepository.save(chat);
        return mapper.map(message, ChatMessageDto.class);
    }

    @Transactional
    public ChatDto updateChat(String chatId, UpdateChatRequest request) {
        Chat chat = chatRepository.findByChatId(chatId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Chat not found"));

        if (request.title() != null) {
            String trimmedTitle = request.title().trim();
            chat.setTitle(StringUtils.hasText(trimmedTitle) ? trimmedTitle : null);
        }

        if (request.systemPrompt() != null) {
            String trimmedPrompt = request.systemPrompt().trim();
            chat.setSystemPrompt(StringUtils.hasText(trimmedPrompt) ? trimmedPrompt : null);
        }

        if (request.titleModel() != null) {
            String trimmedTitleModel = request.titleModel().trim();
            chat.setTitleModel(StringUtils.hasText(trimmedTitleModel) ? trimmedTitleModel : null);
        }

        Chat saved = chatRepository.save(chat);
        return toDto(saved);
    }

    @Transactional
    public void deleteChat(String chatId) {
        chatRepository.deleteByChatId(chatId);
    }

    private ChatDto toDto(Chat chat) {
        List<ChatMessageDto> messages = mapper.mapList(chat.getMessages(), ChatMessageDto.class);
        return new ChatDto(
                chat.getChatId(),
                chat.getTitle(),
                chat.getSystemPrompt(),
                chat.getTitleModel(),
                chat.getCreatedAt(),
                chat.getUpdatedAt(),
                messages
        );
    }

    private ChatMessage toEntity(ChatMessageRequest request) {
        ChatMessage message = mapper.map(request, ChatMessage.class);
        if (message != null && message.getCreatedAt() == null) {
            message.setCreatedAt(Instant.now());
        }
        return message;
    }

    private Chat resolveOrCreate(String chatId) {
        if (StringUtils.hasText(chatId)) {
            return chatRepository.findWithMessagesByChatId(chatId)
                    .orElseGet(() -> {
                        Chat newChat = new Chat();
                        newChat.setChatId(chatId);
                        return newChat;
                    });
        }
        Chat chat = new Chat();
        chat.setChatId(generateChatId());
        return chat;
    }

    private String generateChatId() {
        return UUID.randomUUID().toString();
    }
}
