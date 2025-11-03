package app.chatbot.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import app.chatbot.mcp.config.McpProperties;
import app.chatbot.security.SecretEncryptor;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.WebFluxSseClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import io.netty.handler.timeout.IdleStateHandler;

/**
 * Zentrales Session-Management für MCP-Client-Verbindungen.
 * Verwaltet den Lifecycle aller McpAsyncClient-Instanzen mit Thread-Safety und graceful shutdown.
 * 
 * <p>Features:
 * <ul>
 *   <li>Lazy Initialization: Clients werden erst bei Bedarf erstellt</li>
 *   <li>Session Caching: Einmal initialisierte Clients werden wiederverwendet</li>
 *   <li>State Tracking: INITIALIZING → ACTIVE → ERROR/CLOSED</li>
 *   <li>Idle Timeout: Sessions > 30 Min ohne Zugriff werden geschlossen</li>
 *   <li>Graceful Shutdown: Alle Sessions werden beim App-Stop geschlossen</li>
 *   <li>Error Recovery: Bei Fehler wird Session aus Cache entfernt</li>
 * </ul>
 */
@Component
@Slf4j
public class McpSessionRegistry implements ApplicationListener<ContextClosedEvent> {

    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
    
    private final ConcurrentHashMap<String, SessionHolder> sessions = new ConcurrentHashMap<>();
    private final McpServerRepository serverRepository;
    private final McpProperties properties;
    private final SecretEncryptor encryptor;

    public McpSessionRegistry(McpServerRepository serverRepository,
                             McpProperties properties,
                             SecretEncryptor encryptor) {
        this.serverRepository = serverRepository;
        this.properties = properties;
        this.encryptor = encryptor;
    }

    /**
     * Holt oder erstellt eine MCP-Session für den gegebenen Server.
     * Thread-safe durch ConcurrentHashMap.computeIfAbsent().
     * 
     * @param serverId Die Server-ID
     * @return Mono mit initialisiertem McpAsyncClient
     */
    public Mono<McpAsyncClient> getOrCreateSession(String serverId) {
        System.err.println("🔵 getOrCreateSession called for serverId: " + serverId);
        Mono<McpAsyncClient> result = Mono.defer(() -> {
            System.err.println("🟢 Mono.defer() lambda executing for serverId: " + serverId);
            System.err.println("🔵 Inside Mono.defer for serverId: " + serverId);
            
            // Track if we created a new SessionHolder
            final boolean[] isNewHolder = {false};
            
            SessionHolder holder = sessions.computeIfAbsent(serverId, key -> {
                System.err.println("🟢 Creating new SessionHolder for serverId: " + serverId);
                log.debug("Creating new MCP session for server: {}", serverId);
                isNewHolder[0] = true;
                return new SessionHolder(serverId);
            });

            // Update last accessed timestamp
            holder.lastAccessedAt = Instant.now();

            // Check current state
            SessionState currentState = holder.state.get();
            
            if (currentState == SessionState.ACTIVE && holder.client != null) {
                log.trace("Reusing existing MCP session for server: {}", serverId);
                return Mono.just(holder.client);
            }

            // If we just created this holder, initialize it immediately (don't check INITIALIZING state)
            if (isNewHolder[0]) {
                System.err.println("🎯 New SessionHolder created, initializing immediately for serverId: " + serverId);
                return initializeSession(holder);
            }

            if (currentState == SessionState.INITIALIZING) {
                // Another thread is initializing, wait briefly and retry
                log.debug("Session {} is being initialized by another thread, waiting...", serverId);
                return Mono.delay(Duration.ofMillis(50))
                    .then(Mono.defer(() -> getOrCreateSession(serverId)));
            }

            // State is ERROR or CLOSED → remove and recreate
            if (currentState == SessionState.ERROR || currentState == SessionState.CLOSED) {
                log.info("Session {} is in state {}, recreating...", serverId, currentState);
                sessions.remove(serverId);
                return getOrCreateSession(serverId); // Recursive call to recreate
            }

            // Initialize new session
            return initializeSession(holder);
        });
        System.err.println("🟡 Returning Mono for serverId: " + serverId);
        return result;
    }

    /**
     * Initialisiert eine neue MCP-Session.
     * 
     * @param holder Der SessionHolder
     * @return Mono mit initialisiertem Client
     */
    private Mono<McpAsyncClient> initializeSession(SessionHolder holder) {
        // State is already INITIALIZING (set in SessionHolder constructor)
        // No need to check/set again
        
        return Mono.fromSupplier(() -> serverRepository.findByServerId(holder.serverId))
            .flatMap(serverOpt -> serverOpt
                .map(Mono::just)
                .orElseGet(() -> Mono.error(new McpClientException(
                    "MCP server not found: " + holder.serverId))))
            .flatMap(server -> {
                try {
                    System.err.println("🚨 initializeSession: serverId=" + server.getServerId() + 
                        ", baseUrl=" + server.getBaseUrl() + ", transport=" + server.getTransport());
                    
                    String decryptedApiKey = decryptApiKey(server);
                    
                    // For SSE: Use baseUrl directly from server without endpoint resolution
                    // This ensures we connect to the exact URL provided by the user
                    String targetUrl = server.getBaseUrl();
                    log.info("🔗 MCP Connection Attempt - Server: {}, Transport: {}, Target URL: '{}'", 
                        server.getServerId(), server.getTransport(), targetUrl);
                    
                    McpClientTransport transport = createTransport(targetUrl, decryptedApiKey, 
                        server.getTransport());
                    
                    System.err.println("🎉 Transport created successfully!");

                    McpAsyncClient client = McpClient
                        .async(transport)
                        .clientInfo(new McpSchema.Implementation(
                            properties.clientName(), 
                            properties.clientVersion()))
                        .capabilities(McpSchema.ClientCapabilities.builder().build())
                        .requestTimeout(properties.requestTimeout())
                        .initializationTimeout(properties.initializationTimeout())
                        .build();

                    log.info("🚀 Initializing MCP async client for server {} using {} transport",
                        server.getServerId(), server.getTransport());

                    return client.initialize()
                        .doOnSuccess(result -> {
                            holder.client = client;
                            holder.state.set(SessionState.ACTIVE);
                            log.info("MCP session established for server {}", holder.serverId);
                        })
                        .onErrorResume(error -> {
                            holder.state.set(SessionState.ERROR);
                            sessions.remove(holder.serverId);
                            log.error("Failed to initialize MCP session for server {}", 
                                holder.serverId, error);
                            return Mono.error(new McpClientException(
                                "MCP session initialization failed: " + error.getMessage(), error));
                        })
                        .thenReturn(client);

                } catch (Exception ex) {
                    holder.state.set(SessionState.ERROR);
                    sessions.remove(holder.serverId);
                    log.error("Failed to create MCP transport for server {}", holder.serverId, ex);
                    return Mono.error(new McpClientException(
                        "Failed to create MCP client: " + ex.getMessage(), ex));
                }
            })
            .timeout(properties.initializationTimeout())
            .doOnError(error -> {
                holder.state.set(SessionState.ERROR);
                sessions.remove(holder.serverId);
                log.error("MCP session initialization timeout for server {}", holder.serverId);
            });
    }

    /**
     * Schließt eine MCP-Session und entfernt sie aus dem Cache.
     * 
     * @param serverId Die Server-ID
     * @return Mono das completet wenn Session geschlossen ist
     */
    public Mono<Void> closeSession(String serverId) {
        return Mono.defer(() -> {
            SessionHolder holder = sessions.remove(serverId);
            
            if (holder == null || holder.client == null) {
                log.debug("No active session found for server: {}", serverId);
                return Mono.empty();
            }

            holder.state.set(SessionState.CLOSED);
            log.info("Closing MCP session for server: {}", serverId);

            return holder.client.closeGracefully()
                .timeout(Duration.ofSeconds(5))
                .doOnSuccess(v -> log.info("MCP session closed for server: {}", serverId))
                .doOnError(error -> log.warn("Error closing MCP session for server {}: {}", 
                    serverId, error.getMessage()))
                .onErrorResume(error -> Mono.empty()); // Ignore close errors
        });
    }

    /**
     * Entschlüsselt den API-Key eines Servers.
     * 
     * @param server Der MCP-Server
     * @return Entschlüsselter API-Key oder null
     */
    private String decryptApiKey(McpServer server) {
        if (!StringUtils.hasText(server.getApiKey())) {
            return null;
        }
        try {
            return encryptor.decrypt(server.getApiKey());
        } catch (Exception ex) {
            log.warn("Failed to decrypt API key for server {}", server.getServerId(), ex);
            return null;
        }
    }

    /**
     * Erstellt einen Transport für die gegebene URL.
     * 
     * @param targetUrl Die Ziel-URL (exakt wie vom User eingegeben)
     * @param apiKey Der API-Key (optional)
     * @param transport Der Transport-Typ
     * @return Konfigurierter McpClientTransport
     */
    private McpClientTransport createTransport(String targetUrl,
                                               String apiKey,
                                               McpTransport transport) {
        System.err.println("🏗️ createTransport called: URL=" + targetUrl + ", transport=" + transport);
        
        HttpClient.Builder clientBuilder = HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout());
        
        var requestBuilder = java.net.http.HttpRequest.newBuilder();
        if (StringUtils.hasText(apiKey)) {
            requestBuilder.header("Authorization", "Bearer " + apiKey.trim());
        }

        if (transport == McpTransport.STREAMABLE_HTTP) {
            // For Streamable HTTP: Parse URL into baseUri + endpoint
            URI uri = URI.create(targetUrl);
            String baseUri = buildBaseUri(uri);
            String path = uri.getRawPath();
            if (!StringUtils.hasText(path)) {
                path = "/mcp";
            }
            
            return HttpClientStreamableHttpTransport
                .builder(baseUri)
                .clientBuilder(clientBuilder)
                .requestBuilder(requestBuilder)
                .endpoint(path)
                .connectTimeout(properties.connectTimeout())
                .build();
        }

        // For SSE: Use the EXACT URL provided by the user
        // SSE EventStream requires the complete URL without modification
        log.info("🔌 Creating SSE transport - Full URL: '{}', Connect Timeout: {}", 
            targetUrl, properties.connectTimeout());
        
        // WICHTIG: Der Builder erwartet baseUri (z.B. "http://localhost:5678")
        // und sseEndpoint gibt den Pfad an (z.B. "/mcp" oder "/mcp/uuid")
        // ABER: Wenn wir targetUrl als baseUri übergeben und dann "/" als endpoint,
        // wird die URL falsch konstruiert!
        
        // Lösung: Parse targetUrl in baseUri + path
        URI uri = URI.create(targetUrl);
        String baseUri = buildBaseUri(uri);
        String path = uri.getRawPath();
        if (!StringUtils.hasText(path)) {
            path = "/";
        }

        log.info("🔌 SSE Transport Configuration - Base URI: '{}', SSE Endpoint: '{}'", 
            baseUri, path);

        WebClient.Builder webClientBuilder = WebClient.builder()
            .baseUrl(baseUri)
            .defaultHeader(HttpHeaders.ACCEPT, "application/json, text/event-stream");

        if (StringUtils.hasText(apiKey)) {
            webClientBuilder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim());
        }

        reactor.netty.http.client.HttpClient reactorClient = reactor.netty.http.client.HttpClient.create()
            .responseTimeout(Duration.ZERO) // SSE streams stay open indefinitely; disable read timeout
            .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.connectTimeout().toMillis())
            .doOnConnected(conn -> {
                if (conn.channel().pipeline().get(IdleStateHandler.class) != null) {
                    conn.channel().pipeline().remove(IdleStateHandler.class);
                }
            });

        webClientBuilder.clientConnector(new ReactorClientHttpConnector(reactorClient));

        WebFluxSseClientTransport sseTransport = new WebFluxSseClientTransport(
            webClientBuilder,
            McpJsonMapper.getDefault(),
            path
        );

        log.info("✅ SSE transport created successfully - will connect to: {}{}", baseUri, path);
        return sseTransport;
    }
    
    /**
     * Baut die Basis-URI aus einem URI-Objekt.
     */
    private String buildBaseUri(URI uri) {
        StringBuilder builder = new StringBuilder()
                .append(uri.getScheme())
                .append("://")
                .append(uri.getHost());

        if (uri.getPort() != -1) {
            builder.append(':').append(uri.getPort());
        }
        return builder.toString();
    }

    /**
     * Scheduled Task zum Schließen von idle Sessions.
     * Läuft alle 10 Minuten, schließt Sessions ohne Aktivität > 30 Minuten.
     * 
     * Best Practice aus MCP SDK: Idle connections sollten geschlossen werden.
     */
    @Scheduled(fixedDelay = 600000) // 10 Minuten
    public void cleanupIdleSessions() {
        Instant cutoff = Instant.now().minus(IDLE_TIMEOUT);
        
        sessions.entrySet().stream()
            .filter(entry -> {
                SessionHolder holder = entry.getValue();
                return holder.lastAccessedAt.isBefore(cutoff) 
                    && holder.state.get() == SessionState.ACTIVE;
            })
            .forEach(entry -> {
                String serverId = entry.getKey();
                SessionHolder holder = entry.getValue();
                
                log.info("Closing idle MCP session for server {} (last accessed: {})", 
                        serverId, holder.lastAccessedAt);
                
                closeSession(serverId).subscribe(
                    unused -> log.debug("Idle session {} closed successfully", serverId),
                    error -> log.error("Error closing idle session {}", serverId, error)
                );
            });
    }

    /**
     * ApplicationListener Callback für graceful shutdown.
     * Schließt alle aktiven Sessions beim Herunterfahren der Anwendung.
     * 
     * @param event Das ContextClosedEvent
     */
    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        log.info("Application shutdown initiated, closing {} MCP sessions...", sessions.size());

        Flux.fromIterable(sessions.keySet())
            .flatMap(this::closeSession)
            .blockLast(Duration.ofSeconds(30)); // Wait max 30 seconds for all sessions to close

        log.info("All MCP sessions closed");
    }

    /**
     * Session State Enum.
     */
    private enum SessionState {
        /** Session wird gerade initialisiert */
        INITIALIZING,
        /** Session ist aktiv und bereit */
        ACTIVE,
        /** Session ist fehlgeschlagen */
        ERROR,
        /** Session ist geschlossen */
        CLOSED
    }

    /**
     * Interne Klasse zum Halten einer MCP-Session mit Metadaten.
     */
    private static class SessionHolder {
        final String serverId;
        final AtomicReference<SessionState> state;
        final Instant createdAt;
        volatile Instant lastAccessedAt;
        volatile McpAsyncClient client;

        SessionHolder(String serverId) {
            this.serverId = serverId;
            this.state = new AtomicReference<>(SessionState.INITIALIZING);
            this.createdAt = Instant.now();
            this.lastAccessedAt = Instant.now();
        }
    }
}
