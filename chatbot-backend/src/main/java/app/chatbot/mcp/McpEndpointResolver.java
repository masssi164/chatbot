package app.chatbot.mcp;

import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Determines candidate MCP transport endpoints based on the stored server configuration.
 * <p>
 * Follows Microsoft Azure SSE Best Practices:
 * <ul>
 *   <li>If a complete path is provided (e.g., /mcp/uuid), use it EXACTLY as-is</li>
 *   <li>If only a root URL is provided, try transport-specific defaults (/sse or /mcp)</li>
 *   <li>Never add trailing slashes to user-provided paths with content</li>
 * </ul>
 *
 * @see <a href="https://learn.microsoft.com/en-us/azure/application-gateway/use-server-sent-events">Azure SSE Guidelines</a>
 */
final class McpEndpointResolver {

    private McpEndpointResolver() {
    }

    /**
     * Resolves candidate endpoints for MCP server connection.
     * <p>
     * <strong>Microsoft Best Practice:</strong> URLs with complete paths (like session UUIDs)
     * should be used exactly as provided without modification.
     * <p>
     * <strong>Examples:</strong>
     * <pre>
     * http://localhost:5678/mcp/uuid → [http://localhost:5678/mcp/uuid]
     * http://localhost:5678/api      → [http://localhost:5678/api]
     * http://localhost:5678           → [http://localhost:5678/sse, http://localhost:5678/mcp, http://localhost:5678]
     * </pre>
     *
     * @param server The MCP server with base URL
     * @param transport The transport type (SSE or STREAMABLE_HTTP)
     * @return List of endpoint candidates, ordered by preference
     */
    static List<Endpoint> resolveCandidates(McpServer server, McpTransport transport) {
        String raw = server.getBaseUrl();
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("MCP baseUrl must not be blank");
        }

        URI uri = URI.create(raw.trim());
        if (!StringUtils.hasText(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
            throw new IllegalArgumentException("MCP baseUrl must include scheme and host");
        }

        String baseUri = buildBaseUri(uri);
        String normalizedPath = normalizePath(uri.getRawPath());

        List<Endpoint> endpoints = new ArrayList<>();

        // ✅ Microsoft Best Practice: If user provides a complete path, try it FIRST exactly as-is
        if (StringUtils.hasText(normalizedPath) && !"/".equals(normalizedPath)) {
            String fullUrl = baseUri + normalizedPath;
            if (StringUtils.hasText(uri.getRawQuery())) {
                fullUrl += "?" + uri.getRawQuery();
            }
            endpoints.add(new Endpoint(baseUri, normalizedPath, fullUrl));
            
            // Don't try fallbacks for paths with session IDs or UUIDs
            if (normalizedPath.matches(".*[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}.*")) {
                return endpoints; // Session URL - use ONLY as provided
            }
        }

        // ✅ For root URLs or as fallback: Try known MCP endpoint conventions
        String transportDefault = defaultPath(transport);
        
        // Try transport-specific path (/sse or /mcp)
        if (!endpoints.stream().anyMatch(e -> e.relativePath().equals(transportDefault))) {
            endpoints.add(new Endpoint(baseUri, transportDefault, baseUri + transportDefault));
        }

        // Fallback: Root path
        if (!endpoints.stream().anyMatch(e -> e.relativePath().equals("/"))) {
            endpoints.add(new Endpoint(baseUri, "/", baseUri));
        }

        return endpoints;
    }

    private static String buildBaseUri(URI uri) {
        StringBuilder builder = new StringBuilder()
                .append(uri.getScheme())
                .append("://")
                .append(uri.getHost());

        if (uri.getPort() != -1) {
            builder.append(':').append(uri.getPort());
        }
        return builder.toString();
    }

    private static String normalizePath(String rawPath) {
        if (!StringUtils.hasText(rawPath)) {
            return "/";
        }
        String path = rawPath.trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        while (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static String defaultPath(McpTransport transport) {
        return transport == McpTransport.SSE ? "/sse" : "/mcp";
    }

    record Endpoint(String baseUri, String relativePath, String fullUrl) {
    }
}
