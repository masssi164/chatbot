package app.chatbot.mcp;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class McpEndpointResolverTest {

    @Test
    void resolvesRootUrlWithTransportDefaults() {
        McpServer server = new McpServer();
        server.setBaseUrl("http://localhost:5678");
        server.setTransport(McpTransport.SSE);

        List<McpEndpointResolver.Endpoint> endpoints = McpEndpointResolver.resolveCandidates(server, McpTransport.SSE);

        assertThat(endpoints).hasSizeGreaterThanOrEqualTo(2);
        // First: Transport-specific default (/sse)
        assertThat(endpoints.get(0).fullUrl()).isEqualTo("http://localhost:5678/sse");
        assertThat(endpoints.get(0).relativePath()).isEqualTo("/sse");
        // Should have fallback options
        assertThat(endpoints).anyMatch(e -> e.relativePath().equals("/"));
    }

    @Test
    void preservesCompletePathExactlyAsFirst() {
        McpServer server = new McpServer();
        server.setBaseUrl("http://localhost:5678/api/v1");
        server.setTransport(McpTransport.STREAMABLE_HTTP);

        List<McpEndpointResolver.Endpoint> endpoints =
                McpEndpointResolver.resolveCandidates(server, McpTransport.STREAMABLE_HTTP);

        // Microsoft Best Practice: Complete paths should be tried FIRST exactly as provided
        assertThat(endpoints).isNotEmpty();
        McpEndpointResolver.Endpoint first = endpoints.get(0);
        assertThat(first.fullUrl()).isEqualTo("http://localhost:5678/api/v1");
        assertThat(first.relativePath()).isEqualTo("/api/v1");
        assertThat(first.fullUrl()).doesNotEndWith("/");
        
        // May have fallbacks for non-UUID paths
        assertThat(endpoints.size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void preservesUuidPathExactly() {
        // BUG FIX: UUID paths (session IDs) must be used EXACTLY as provided - NO FALLBACKS
        McpServer server = new McpServer();
        server.setBaseUrl("http://localhost:5678/mcp/2714421f-0865-468b-b938-0d592153a235");
        server.setTransport(McpTransport.SSE);

        List<McpEndpointResolver.Endpoint> endpoints =
                McpEndpointResolver.resolveCandidates(server, McpTransport.SSE);

        // Session URLs should have NO fallbacks - only the exact URL
        assertThat(endpoints).hasSize(1);
        McpEndpointResolver.Endpoint endpoint = endpoints.get(0);
        assertThat(endpoint.fullUrl())
                .isEqualTo("http://localhost:5678/mcp/2714421f-0865-468b-b938-0d592153a235");
        assertThat(endpoint.fullUrl()).doesNotEndWith("/");
    }

    @Test
    void preservesQueryParameters() {
        McpServer server = new McpServer();
        server.setBaseUrl("http://localhost:5678/mcp/session?token=abc123");
        server.setTransport(McpTransport.SSE);

        List<McpEndpointResolver.Endpoint> endpoints =
                McpEndpointResolver.resolveCandidates(server, McpTransport.SSE);

        assertThat(endpoints).isNotEmpty();
        // Query params should be preserved on the first candidate
        assertThat(endpoints.get(0).fullUrl())
                .isEqualTo("http://localhost:5678/mcp/session?token=abc123");
    }

    @Test
    void usesStreamableHttpDefaultForRootUrl() {
        McpServer server = new McpServer();
        server.setBaseUrl("http://localhost:5678");
        server.setTransport(McpTransport.STREAMABLE_HTTP);

        List<McpEndpointResolver.Endpoint> endpoints =
                McpEndpointResolver.resolveCandidates(server, McpTransport.STREAMABLE_HTTP);

        assertThat(endpoints).hasSize(2);
        // First: /mcp for STREAMABLE_HTTP
        assertThat(endpoints.get(0).fullUrl()).isEqualTo("http://localhost:5678/mcp");
        assertThat(endpoints.get(0).relativePath()).isEqualTo("/mcp");
        // Second: Fallback root
        assertThat(endpoints.get(1).fullUrl()).isEqualTo("http://localhost:5678");
    }
}

