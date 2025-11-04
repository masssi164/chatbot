# Migration Plan: JPA → R2DBC für MCP Server Management

## Ziel
MCP Server Management von **JPA (blocking)** auf **R2DBC (reactive)** migrieren für bessere SSE/Streaming Performance.

## Status
- ✅ MCP SDK v0.15.0 ist bereits reactive (Project Reactor)
- ✅ Conversation/Message Entities bereits R2DBC
- ⏳ McpServer Entity noch JPA
- ⏳ McpServerRepository noch JpaRepository

---

## Phase 1: Entity Migration

### 1.1 McpServer Entity (JPA → R2DBC)

**VORHER (JPA):**
```java
import jakarta.persistence.*;

@Entity
@Table(name = "mcp_servers")
public class McpServer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "server_id", nullable = false, unique = true)
    private String serverId;
    
    @Version
    private Long version; // Optimistic locking
    
    @PrePersist
    protected void onCreate() {
        lastUpdated = Instant.now();
    }
}
```

**NACHHER (R2DBC):**
```java
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.relational.core.mapping.Column;

@Table("mcp_servers")
@Data
@Builder
public class McpServer {
    @Id
    private Long id;
    
    @Column("server_id")
    private String serverId;
    
    // ⚠️ R2DBC unterstützt KEIN @Version - manuelle Versioning-Logik nötig
    private Long version;
    
    // ⚠️ R2DBC hat KEINE @PrePersist - im Service setzen
    @Column("last_updated")
    private Instant lastUpdated;
    
    // Enums als String speichern
    private String status; // McpServerStatus.name()
    private String transport; // McpTransport.name()
}
```

**Änderungen:**
- `jakarta.persistence.*` → `org.springframework.data.*`
- `@Entity` → `@Table`
- `@GeneratedValue` entfernen (R2DBC managed AUTO_INCREMENT)
- `@PrePersist` Logic → Service Layer
- `@Version` entfernen, manuelles Versioning
- Enums → String (oder custom converter)

---

## Phase 2: Repository Migration

### 2.1 McpServerRepository

**VORHER (JPA):**
```java
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpServerRepository extends JpaRepository<McpServer, Long> {
    Optional<McpServer> findByServerId(String serverId);
    boolean existsByServerId(String serverId);
    void deleteByServerId(String serverId);
}
```

**NACHHER (R2DBC):**
```java
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

public interface McpServerRepository extends ReactiveCrudRepository<McpServer, Long> {
    Mono<McpServer> findByServerId(String serverId);
    Mono<Boolean> existsByServerId(String serverId);
    Mono<Void> deleteByServerId(String serverId);
    
    // Alle Server ordered by name
    Flux<McpServer> findAllByOrderByNameAsc();
}
```

**Änderungen:**
- `JpaRepository` → `ReactiveCrudRepository`
- Return types: `Optional<T>` → `Mono<T>`, `List<T>` → `Flux<T>`
- Void methods → `Mono<Void>`

---

## Phase 3: Service Layer Migration

### 3.1 McpServerService

**VORHER (Blocking JPA):**
```java
@Service
@Transactional
public class McpServerService {
    private final McpServerRepository repository;
    
    public McpServerDto registerServer(McpServerRequest request) {
        McpServer server = new McpServer();
        server.setServerId(UUID.randomUUID().toString());
        server.setName(request.getName());
        server.setStatus(McpServerStatus.IDLE);
        
        McpServer saved = repository.save(server);
        return toDto(saved);
    }
    
    public List<McpServerDto> getAllServers() {
        return repository.findAll().stream()
            .map(this::toDto)
            .collect(Collectors.toList());
    }
}
```

**NACHHER (Reactive R2DBC):**
```java
@Service
public class McpServerService {
    private final McpServerRepository repository;
    
    public Mono<McpServerDto> registerServer(McpServerRequest request) {
        return Mono.just(request)
            .map(req -> McpServer.builder()
                .serverId(UUID.randomUUID().toString())
                .name(req.getName())
                .status(McpServerStatus.IDLE.name())
                .lastUpdated(Instant.now()) // ⚠️ Manually set
                .build())
            .flatMap(repository::save)
            .map(this::toDto);
    }
    
    public Flux<McpServerDto> getAllServers() {
        return repository.findAll()
            .map(this::toDto);
    }
    
    // Optimistic locking manually
    public Mono<McpServer> updateServerStatus(String serverId, McpServerStatus newStatus) {
        return repository.findByServerId(serverId)
            .flatMap(server -> {
                Long currentVersion = server.getVersion();
                server.setStatus(newStatus.name());
                server.setVersion(currentVersion + 1);
                server.setLastUpdated(Instant.now());
                return repository.save(server);
            })
            .switchIfEmpty(Mono.error(new NotFoundException("Server not found")));
    }
}
```

**Änderungen:**
- `@Transactional` entfernen (R2DBC managed transactions differently)
- Return types: `T` → `Mono<T>`, `List<T>` → `Flux<T>`
- Stream operations → Reactor operators (`map`, `flatMap`, `filter`)
- Manual timestamp management
- Manual versioning for optimistic locking

---

## Phase 4: Controller Migration

### 4.1 McpServerController

**VORHER (Blocking):**
```java
@RestController
@RequestMapping("/api/mcp/servers")
public class McpServerController {
    private final McpServerService service;
    
    @PostMapping
    public ResponseEntity<McpServerDto> registerServer(@RequestBody McpServerRequest request) {
        McpServerDto dto = service.registerServer(request);
        return ResponseEntity.ok(dto);
    }
    
    @GetMapping
    public List<McpServerDto> listServers() {
        return service.getAllServers();
    }
}
```

**NACHHER (Reactive):**
```java
@RestController
@RequestMapping("/api/mcp/servers")
public class McpServerController {
    private final McpServerService service;
    
    @PostMapping
    public Mono<ResponseEntity<McpServerDto>> registerServer(@RequestBody McpServerRequest request) {
        return service.registerServer(request)
            .map(ResponseEntity::ok);
    }
    
    @GetMapping
    public Flux<McpServerDto> listServers() {
        return service.getAllServers();
    }
}
```

**Änderungen:**
- Return types wrappen in `Mono<>` / `Flux<>`
- WebFlux serialisiert automatisch reactive types

---

## Phase 5: MCP Client Integration (McpSessionRegistry)

**WICHTIG**: `McpAsyncClient` ist BEREITS reactive!

```java
@Component
public class McpSessionRegistry {
    private final McpServerRepository serverRepository; // Now reactive!
    
    public Mono<McpAsyncClient> getOrCreateSession(String serverId) {
        return serverRepository.findByServerId(serverId)
            .switchIfEmpty(Mono.error(new NotFoundException("Server not found")))
            .flatMap(server -> {
                // Check if session exists in cache
                SessionHolder holder = sessions.get(serverId);
                if (holder != null && holder.client != null) {
                    return Mono.just(holder.client);
                }
                
                // Create new session
                return initializeSession(server);
            });
    }
    
    private Mono<McpAsyncClient> initializeSession(McpServer server) {
        McpClientTransport transport = createTransport(server);
        
        return Mono.fromCallable(() -> 
            McpClient.async(transport).build()
        )
        .flatMap(client -> client.initialize()
            .thenReturn(client)
        );
    }
}
```

**Änderungen:**
- Repository calls sind jetzt Mono/Flux
- `McpAsyncClient.initialize()` gibt `Mono<InitializeResult>` zurück
- Perfect reactive chain!

---

## Phase 6: Database Schema

**KEINE Änderungen nötig!** Flyway migrations V2 und V3 sind kompatibel.

```sql
-- V2__add_mcp_transport.sql
ALTER TABLE mcp_servers ADD COLUMN transport VARCHAR(20) NOT NULL DEFAULT 'STREAMABLE_HTTP';

-- V3__add_mcp_capabilities_cache.sql
ALTER TABLE mcp_servers ADD COLUMN tools_cache TEXT;
ALTER TABLE mcp_servers ADD COLUMN resources_cache TEXT;
```

R2DBC nutzt dieselbe H2 Datenbank, nur mit reactive driver.

---

## Phase 7: Testing

### Unit Tests mit R2DBC
```java
@DataR2dbcTest
class McpServerRepositoryTest {
    @Autowired
    private McpServerRepository repository;
    
    @Test
    void testFindByServerId() {
        String serverId = "test-123";
        McpServer server = McpServer.builder()
            .serverId(serverId)
            .name("Test Server")
            .build();
        
        StepVerifier.create(
            repository.save(server)
                .then(repository.findByServerId(serverId))
        )
        .assertNext(found -> assertThat(found.getName()).isEqualTo("Test Server"))
        .verifyComplete();
    }
}
```

---

## Checkliste

### Entities
- [ ] McpServer: JPA → R2DBC annotations
- [ ] Enums → String mapping
- [ ] @PrePersist logic → Service

### Repositories
- [ ] McpServerRepository extends ReactiveCrudRepository
- [ ] Return types: Mono/Flux

### Services
- [ ] McpServerService: alle Methoden → Mono/Flux
- [ ] @Transactional entfernen
- [ ] Manual versioning
- [ ] Timestamp management

### Controllers
- [ ] McpServerController: Reactive return types
- [ ] McpToolController: Reactive
- [ ] McpServerStatusStreamController: SSE mit Flux

### Session Management
- [ ] McpSessionRegistry: Repository calls reactive
- [ ] McpAsyncClient integration (bereits reactive)

### Utilities
- [ ] GenericMapper: Mono/Flux support
- [ ] SecretEncryptor: stays synchronous (crypto blocking)

### Tests
- [ ] @DataR2dbcTest statt @DataJpaTest
- [ ] StepVerifier für reactive tests

---

## Nächste Schritte

1. **McpServer Entity migrieren** (höchste Priorität)
2. **McpServerRepository refactor**
3. **McpServerService reactive machen**
4. **Controller anpassen**
5. **Tests anpassen**
6. **Integration testen**
