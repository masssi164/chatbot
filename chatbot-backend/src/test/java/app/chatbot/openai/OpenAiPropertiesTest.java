package app.chatbot.openai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiPropertiesTest {

    @Test
    void defaultsToLocalhostWhenBaseUrlMissing() {
        OpenAiProperties properties = new OpenAiProperties(null, null, null, null, null);

        assertThat(properties.baseUrl()).isEqualTo(OpenAiProperties.DEFAULT_BASE_URL);
        assertThat(properties.apiKey()).isNull();
        assertThat(properties.defaultTitleModel()).isEqualTo(OpenAiProperties.DEFAULT_TITLE_MODEL);
        assertThat(properties.connectTimeout()).isEqualTo(OpenAiProperties.DEFAULT_CONNECT_TIMEOUT);
        assertThat(properties.readTimeout()).isEqualTo(OpenAiProperties.DEFAULT_READ_TIMEOUT);
    }

    @Test
    void trimsTrailingSlashesAndWhitespace() {
        OpenAiProperties properties = new OpenAiProperties(
                "  http://localhost:1234/v1////  ",
                "  test-key  ",
                "  gpt-4.1 ",
                java.time.Duration.ofSeconds(3),
                java.time.Duration.ofSeconds(90)
        );

        assertThat(properties.baseUrl()).isEqualTo("http://localhost:1234/v1");
        assertThat(properties.apiKey()).isEqualTo("test-key");
        assertThat(properties.defaultTitleModel()).isEqualTo("gpt-4.1");
        assertThat(properties.connectTimeout()).isEqualTo(java.time.Duration.ofSeconds(3));
        assertThat(properties.readTimeout()).isEqualTo(java.time.Duration.ofSeconds(90));
    }

    @Test
    void keepsCustomBaseUrlWithoutVersionSuffixIntact() {
        OpenAiProperties properties = new OpenAiProperties(
                "https://api.example.com",
                null,
                "",
                java.time.Duration.ofSeconds(1),
                java.time.Duration.ofSeconds(30)
        );

        assertThat(properties.baseUrl()).isEqualTo("https://api.example.com");
        assertThat(properties.defaultTitleModel()).isEqualTo(OpenAiProperties.DEFAULT_TITLE_MODEL);
        assertThat(properties.connectTimeout()).isEqualTo(java.time.Duration.ofSeconds(1));
        assertThat(properties.readTimeout()).isEqualTo(java.time.Duration.ofSeconds(30));
    }
}
