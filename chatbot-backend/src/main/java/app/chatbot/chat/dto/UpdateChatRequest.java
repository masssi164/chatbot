package app.chatbot.chat.dto;

public record UpdateChatRequest(String title, String systemPrompt, String titleModel) {
}
