package app.chatbot.n8n.dto;

public record N8nConnectionStatusResponse(
        boolean connected,
        String message
) {
}
