package app.chatbot.mcp;

import app.chatbot.mcp.dto.McpServerDto;
import app.chatbot.mcp.dto.McpServerRequest;
import app.chatbot.utils.GenericMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@RequiredArgsConstructor
public class McpServerService {

    private final McpServerRepository repository;
    private final GenericMapper mapper;

    @Transactional(readOnly = true)
    public List<McpServerDto> listServers() {
        return repository.findAll().stream()
                .sorted((a, b) -> b.getLastUpdated().compareTo(a.getLastUpdated()))
                .map(server -> mapper.map(server, McpServerDto.class))
                .toList();
    }

    @Transactional(readOnly = true)
    public McpServerDto getServer(String serverId) {
        return repository.findByServerId(serverId)
                .map(server -> mapper.map(server, McpServerDto.class))
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "MCP server not found"));
    }

    @Transactional
    public McpServerDto createOrUpdate(McpServerRequest request) {
        String requestedId = StringUtils.hasText(request.serverId()) ? request.serverId().trim() : null;

        McpServer server = requestedId != null
                ? repository.findByServerId(requestedId).orElse(null)
                : null;

        if (server == null) {
            server = new McpServer();
            server.setServerId(requestedId != null ? requestedId : generateServerId());
        }

        applyUpdates(server, request);
        McpServer saved = repository.save(server);
        return mapper.map(saved, McpServerDto.class);
    }

    @Transactional
    public McpServerDto update(String serverId, McpServerRequest request) {
        McpServer server = repository.findByServerId(serverId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "MCP server not found"));

        applyUpdates(server, request);
        McpServer saved = repository.save(server);
        return mapper.map(saved, McpServerDto.class);
    }

    @Transactional
    public void delete(String serverId) {
        repository.deleteByServerId(serverId);
    }

    private void applyUpdates(McpServer server, McpServerRequest request) {
        server.setName(request.name().trim());
        server.setBaseUrl(request.baseUrl().trim());

        String apiKey = request.apiKey();
        server.setApiKey(StringUtils.hasText(apiKey) ? apiKey.trim() : null);

        if (request.status() != null) {
            server.setStatus(request.status());
        }
        server.setLastUpdated(Instant.now());
    }

    private String generateServerId() {
        return "mcp-" + UUID.randomUUID();
    }
}
