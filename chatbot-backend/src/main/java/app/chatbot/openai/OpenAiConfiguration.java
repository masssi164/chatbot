package app.chatbot.openai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableConfigurationProperties(OpenAiProperties.class)
public class OpenAiConfiguration {

    @Bean
    public RestTemplate openAiRestTemplate(OpenAiProperties properties,
                                           RestTemplateBuilder builder) {
        return builder
                .rootUri(properties.baseUrl())
                .requestFactory(() -> {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout((int) properties.connectTimeout().toMillis());
                    factory.setReadTimeout((int) properties.readTimeout().toMillis());
                    return factory;
                })
                .build();
    }

}
