package app.chatbot.security;

/**
 * Signals that an encryption or decryption operation failed.
 */
public class EncryptionException extends RuntimeException {

    public EncryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
