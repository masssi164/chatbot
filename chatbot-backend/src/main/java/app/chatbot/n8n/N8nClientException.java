package app.chatbot.n8n;

public class N8nClientException extends RuntimeException {

    public N8nClientException(String message, Throwable cause) {
        super(message, cause);
    }

    public N8nClientException(String message) {
        super(message);
    }
}
