package app.chatbot.security;

import org.springframework.lang.Nullable;

/**
 * Simple interface for encrypting and decrypting sensitive string values.
 */
public interface SecretEncryptor {

    /**
     * Encrypt the provided plain text.
     *
     * @param plainText the text to encrypt
     * @return the encrypted representation or {@code null} if the input is blank
     */
    @Nullable
    String encrypt(@Nullable String plainText);

    /**
     * Decrypt the provided cipher text.
     *
     * @param cipherText the encrypted value
     * @return the decrypted representation or {@code null} if the input is blank
     */
    @Nullable
    String decrypt(@Nullable String cipherText);
}
