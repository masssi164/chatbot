package app.chatbot.conversation;

/**
 * Conversation lifecycle status tracking for OpenAI Responses API.
 * 
 * Status transitions:
 * CREATED → STREAMING → (COMPLETED | INCOMPLETE | FAILED)
 */
public enum ConversationStatus {
    /**
     * Conversation created, no response started yet.
     */
    CREATED,
    
    /**
     * Response streaming in progress (after response.created event).
     */
    STREAMING,
    
    /**
     * Response completed successfully (response.completed event).
     */
    COMPLETED,
    
    /**
     * Response ended before completion, typically due to token limits (response.incomplete event).
     */
    INCOMPLETE,
    
    /**
     * Response failed due to an error (response.failed or error event).
     */
    FAILED
}
