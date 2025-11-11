package app.chatbot.mcp;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.chatbot.mcp.dto.McpCapabilitiesResponse;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/mcp")
public class McpToolController {

    private final LiteLlmMcpService mcpService;

    public McpToolController(LiteLlmMcpService mcpService) {
        this.mcpService = mcpService;
    }

    @GetMapping("/servers/{serverId}/capabilities")
    public Mono<McpCapabilitiesResponse> getCapabilities(@PathVariable String serverId) {
        return mcpService.getCapabilities(serverId);
    }
}
