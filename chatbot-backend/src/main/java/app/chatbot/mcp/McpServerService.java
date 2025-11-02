package app.chatbot.mcp;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import app.chatbot.mcp.dto.McpConnectionStatusDto;
import app.chatbot.mcp.dto.McpServerDto;
import app.chatbot.mcp.dto.McpServerRequest;
import app.chatbot.security.EncryptionException;
import app.chatbot.security.SecretEncryptor;
import app.chatbot.utils.GenericMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class McpServerService {

    private final McpServerRepository repository;
    private final GenericMapper mapper;
    private final SecretEncryptor secretEncryptor;
    private final McpConnectionService connectionService;
    private final McpClientService mcpClientService;

    @Transactional(readOnly = true)
    public List<McpServerDto> listServers() {
        return repository.findAll().stream()
                .sorted((a, b) -> b.getLastUpdated().compareTo(a.getLastUpdated()))
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public McpServerDto getServer(String serverId) {
        return repository.findByServerId(serverId)
                .map(this::toDto)
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
        mcpClientService.closeConnection(saved.getServerId());
        return toDto(saved);
    }

    @Transactional
    public McpServerDto update(String serverId, McpServerRequest request) {
        McpServer server = repository.findByServerId(serverId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "MCP server not found"));

        applyUpdates(server, request);
        McpServer saved = repository.save(server);
        mcpClientService.closeConnection(saved.getServerId());
        return toDto(saved);
    }

    @Transactional
    public void deleteServer(String serverId) {
        McpServer server = repository.findByServerId(serverId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "MCP server not found"));
        mcpClientService.closeConnection(server.getServerId());
        repository.delete(server);
    }

    @Transactional
    public void delete(String serverId) {
        mcpClientService.closeConnection(serverId);
        repository.deleteByServerId(serverId);
    }

    private void applyUpdates(McpServer server, McpServerRequest request) {
        server.setName(request.name().trim());
        server.setBaseUrl(request.baseUrl().trim());

        // Encrypt API key before storing
        String apiKey = request.apiKey();
        if (StringUtils.hasText(apiKey)) {
            try {
                String encrypted = secretEncryptor.encrypt(apiKey.trim());
                server.setApiKey(encrypted);
            } catch (EncryptionException ex) {
                log.error("Failed to encrypt API key for MCP server {}", server.getServerId(), ex);
                throw new IllegalStateException("Failed to encrypt API key", ex);
            }
        } else {
            server.setApiKey(null);
        }

        if (request.status() != null) {
            server.setStatus(request.status());
        }
        
        if (request.transport() != null) {
            server.setTransport(request.transport());
        }
        
        server.setLastUpdated(Instant.now());
    }

    private McpServerDto toDto(McpServer server) {
        // Check if API key exists (don't decrypt for DTO)
        boolean hasApiKey = StringUtils.hasText(server.getApiKey());
        
        return new McpServerDto(
                server.getServerId(),
                server.getName(),
                server.getBaseUrl(),
                hasApiKey,
                server.getStatus(),
                server.getTransport(),
                server.getLastUpdated()
        );
    }

    @Transactional
    public McpConnectionStatusDto verifyConnection(String serverId) {
        McpServer server = repository.findByServerId(serverId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "MCP server not found"));

        // Decrypt API key for connection test
        String decryptedApiKey = null;
        if (StringUtils.hasText(server.getApiKey())) {
            try {
                decryptedApiKey = secretEncryptor.decrypt(server.getApiKey());
            } catch (EncryptionException ex) {
                log.error("Failed to decrypt API key for MCP server {}", serverId, ex);
                server.setStatus(McpServerStatus.ERROR);
                server.setLastUpdated(Instant.now());
                repository.save(server);
                return new McpConnectionStatusDto(
                        McpServerStatus.ERROR,
                        0,
                        "Failed to decrypt API key: " + ex.getMessage()
                );
            }
        }

        // Attempt connection
        try {
            McpConnectionService.ConnectionResult result = connectionService.testConnection(
                    server.getBaseUrl(),
                    server.getTransport(),
                    decryptedApiKey
            );

            server.setStatus(result.success() ? McpServerStatus.CONNECTED : McpServerStatus.ERROR);
            server.setLastUpdated(Instant.now());
            repository.save(server);

            return new McpConnectionStatusDto(
                    server.getStatus(),
                    result.toolCount(),
                    result.message()
            );
        } catch (Exception ex) {
            log.error("Connection test failed for MCP server {}", serverId, ex);
            server.setStatus(McpServerStatus.ERROR);
            server.setLastUpdated(Instant.now());
            repository.save(server);

            return new McpConnectionStatusDto(
                    McpServerStatus.ERROR,
                    0,
                    "Connection failed: " + ex.getMessage()
            );
        }
    }

    private String generateServerId() {
        return "mcp-" + UUID.randomUUID();
    }
}
