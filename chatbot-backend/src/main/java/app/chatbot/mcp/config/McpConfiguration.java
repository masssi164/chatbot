package app.chatbot.mcp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(McpProperties.class)
public class McpConfiguration {
}
