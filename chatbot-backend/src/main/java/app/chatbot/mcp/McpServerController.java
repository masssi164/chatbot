package app.chatbot.mcp;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.chatbot.mcp.dto.McpConnectionStatusDto;
import app.chatbot.mcp.dto.McpServerDto;
import app.chatbot.mcp.dto.McpServerRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/mcp-servers")
@RequiredArgsConstructor
@Slf4j
public class McpServerController {

    private final McpServerService service;
    private final McpConnectionService connectionService;

    @GetMapping
    public List<McpServerDto> listServers() {
        log.debug("Listing MCP servers");
        return service.listServers();
    }

    @GetMapping("/{serverId}")
    public McpServerDto getServer(@PathVariable("serverId") String serverId) {
        log.debug("Fetching MCP server {}", serverId);
        return service.getServer(serverId);
    }

    @PostMapping
    public McpServerDto createOrUpdate(@Valid @RequestBody McpServerRequest request) {
        log.debug("Creating or updating MCP server {}", request.serverId());
        return service.createOrUpdate(request);
    }

    @PutMapping("/{serverId}")
    public McpServerDto update(@PathVariable("serverId") String serverId,
                               @Valid @RequestBody McpServerRequest request) {
        log.debug("Updating MCP server {}", serverId);
        return service.update(serverId, request);
    }

    @DeleteMapping("/{serverId}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("serverId") String serverId) {
        log.debug("Deleting MCP server {}", serverId);
        service.delete(serverId);
    }

    @PostMapping("/{serverId}/verify")
    public McpConnectionStatusDto verifyConnection(@PathVariable("serverId") String serverId) {
        log.debug("Verifying connection to MCP server {}", serverId);
        return service.verifyConnection(serverId);
    }
}
