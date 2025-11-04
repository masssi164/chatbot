package app.chatbot.conversation.dto;

import java.util.List;

public record CreateConversationRequest(
        String title,
        List<NewMessageRequest> messages
) {}
