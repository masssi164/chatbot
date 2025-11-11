package app.chatbot.mcp;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.chatbot.mcp.dto.McpServerDto;
import app.chatbot.mcp.dto.McpServerRequest;
import jakarta.validation.Valid;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/mcp-servers")
@Validated
public class McpServerController {

    private final LiteLlmMcpService mcpService;

    public McpServerController(LiteLlmMcpService mcpService) {
        this.mcpService = mcpService;
    }

    @GetMapping
    public Flux<McpServerDto> listServers() {
        return mcpService.listServers();
    }

    @GetMapping("/{serverId}")
    public Mono<McpServerDto> getServer(@PathVariable String serverId) {
        return mcpService.getServer(serverId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<McpServerDto> upsertServer(@Valid @RequestBody McpServerRequest request) {
        return mcpService.upsertServer(request);
    }

    @PutMapping("/{serverId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<McpServerDto> updateServer(@PathVariable String serverId,
                                           @Valid @RequestBody McpServerRequest request) {
        McpServerRequest merged = new McpServerRequest(
                serverId,
                request.name(),
                request.baseUrl(),
                request.transport(),
                request.authType(),
                request.authValue(),
                request.staticHeaders(),
                request.extraHeaders(),
                request.accessGroups(),
                request.requireApproval()
        );
        return mcpService.upsertServer(merged);
    }

    @DeleteMapping("/{serverId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable String serverId) {
        return mcpService.deleteServer(serverId);
    }
}
