# 🔍 Lösungsvergleich: MCP Server Connection Management

**Ziel**: MCP-Server hinzufügen + Tools/Resources abrufen  
**Problem**: Concurrent Updates führen zu Race Conditions und Exceptions  
**Datum**: 2. November 2025

---

## 📊 Lösungskonzepte im Vergleich

### ✅ **Lösung 1: Event-Driven Architecture mit Message Queue** (EMPFOHLEN)

**Prinzip**: Entkopplung von Request-Handling und Connection-Processing

```
Frontend Request → Controller → Message Queue → Single Worker → DB
```

**Implementierung**:
```java
@RestController
public class McpServerController {
    private final ApplicationEventPublisher eventPublisher;
    
    @PutMapping("/{serverId}")
    public McpServerDto update(@PathVariable String serverId, 
                               @RequestBody McpServerRequest request) {
        // 1. Synchrones DB-Update (fast!)
        McpServerDto dto = service.update(serverId, request);
        
        // 2. Event publishen (async, non-blocking)
        eventPublisher.publishEvent(new McpServerUpdatedEvent(serverId));
        
        return dto; // Sofort zurück!
    }
}

@Component
public class McpConnectionEventListener {
    @EventListener
    @Async("mcpServerTaskExecutor")
    @Order(1) // Sequential processing per server
    public void handleServerUpdated(McpServerUpdatedEvent event) {
        // Nur 1 Thread pro Server zur gleichen Zeit
        mcpConnectionService.connectAndSync(event.getServerId());
    }
}
```

**Vorteile**:
- ✅ **Keine Concurrency-Probleme**: Events werden sequenziell verarbeitet
- ✅ **Einfaches Modell**: Keine Locks, keine Retries, keine Deduplication nötig
- ✅ **Testbar**: Events können leicht gemockt werden
- ✅ **Standard Spring Pattern**: `@EventListener` + `@Async`
- ✅ **Erweiterbar**: Weitere Listener können einfach hinzugefügt werden

**Nachteile**:
- ⚠️ Leicht verzögerte Connection (Event-Verarbeitung dauert ~1-10ms)
- ⚠️ Kein direktes Feedback über Connection-Status im Response

**Microsoft Best Practice**:
> "Asynchronous message-based communication... Using a message queue that will be the base for an event-creator component"  
> Quelle: [Asynchronous message-based communication](https://learn.microsoft.com/en-us/dotnet/architecture/microservices/architect-microservice-container-applications/asynchronous-message-based-communication)

**Komplexität**: ⭐⭐ (NIEDRIG)

---

### ✅ **Lösung 2: Idempotent Operations mit Deduplication** (GUT)

**Prinzip**: Operationen so designen, dass mehrfache Ausführung kein Problem ist

```
Frontend Request → Controller → Check if already running → Skip or Execute
```

**Implementierung**:
```java
@RestController
public class McpServerController {
    private final ConcurrentHashMap<String, CompletableFuture<McpServer>> inFlight = new ConcurrentHashMap<>();
    
    @PutMapping("/{serverId}")
    public McpServerDto update(@PathVariable String serverId, 
                               @RequestBody McpServerRequest request) {
        McpServerDto dto = service.update(serverId, request);
        
        // Atomare Deduplication mit computeIfAbsent
        inFlight.computeIfAbsent(serverId, id -> {
            CompletableFuture<McpServer> future = service.connectAndSyncAsync(id);
            future.whenComplete((result, error) -> inFlight.remove(id));
            return future;
        });
        
        return dto;
    }
}

@Service
public class McpConnectionService {
    public void connectAndSync(String serverId) {
        // Idempotente Operation: Kann mehrfach aufgerufen werden
        // 1. Check if already connected → Skip
        // 2. Connect → Idempotent (gleicher State egal wie oft)
        // 3. Fetch capabilities → Idempotent (gleiche Daten)
    }
}
```

**Vorteile**:
- ✅ **Gute Performance**: Nur 1 Connection pro Server
- ✅ **Keine DB-Locks nötig**: Deduplication auf Application-Layer
- ✅ **Microsoft-Empfohlen**: "Designing Azure Functions for identical input"

**Nachteile**:
- ⚠️ Muss korrekt implementiert werden (atomare Operations!)
- ⚠️ In-Memory State (verloren bei Restart)
- ⚠️ Funktioniert nur in Single-Instance Deployments

**Microsoft Best Practice**:
> "An idempotent operation is one that has no extra effect if it's called more than once with the same input parameters"  
> Quelle: [Designing Azure Functions for identical input](https://learn.microsoft.com/en-us/azure/azure-functions/functions-idempotent)

**Komplexität**: ⭐⭐⭐ (MITTEL)

---

### ⚠️ **Lösung 3: Pessimistic Locking** (FUNKTIONIERT, ABER NICHT OPTIMAL)

**Prinzip**: Database Lock verhindert parallele Updates

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<McpServer> findWithLockByServerId(String serverId);
```

**Vorteile**:
- ✅ **Garantiert keine Conflicts**: DB übernimmt Synchronisierung
- ✅ **Einfache Implementierung**: Nur `@Lock` Annotation nötig

**Nachteile**:
- ❌ **Lange Lock-Dauer**: 15 Sekunden während MCP-Connection!
- ❌ **Blockiert andere Requests**: Alle Updates warten
- ❌ **Skaliert schlecht**: Deadlock-Gefahr bei vielen Servern
- ❌ **Microsoft rät ab**: "Pessimistic concurrency is rarely used... if not properly relinquished, can prevent other users from updating data"

**Microsoft Best Practice**:
> "Pessimistic concurrency is rarely used because such locks, if not properly relinquished, can prevent other users from updating data"  
> Quelle: [Implementing Optimistic Concurrency](https://learn.microsoft.com/en-us/aspnet/web-forms/overview/data-access/editing-inserting-and-deleting-data/implementing-optimistic-concurrency-vb#introduction)

**Komplexität**: ⭐⭐ (NIEDRIG, aber PROBLEMATISCH)

---

### ⚠️ **Lösung 4: Optimistic Locking mit Retry** (CURRENT STATE, SUBOPTIMAL)

**Prinzip**: Version-basierte Concurrency Control + Retry bei Conflict

```java
@Version
private Long version;

while (attempt < maxRetries) {
    try {
        server = repository.findByServerId(serverId);
        server.setStatus(newStatus);
        repository.save(server); // Throws Exception if version mismatch
        break;
    } catch (OptimisticLockingFailureException ex) {
        Thread.sleep(backoff);
        attempt++;
    }
}
```

**Vorteile**:
- ✅ **Keine Locks**: Höhere Concurrency als Pessimistic
- ✅ **Standard JPA Feature**: `@Version` Annotation

**Nachteile**:
- ❌ **Exceptions bei hoher Last**: Jeder 2. Request schlägt fehl
- ❌ **Retry-Overhead**: 3x Query + Backoff = langsam
- ❌ **Komplexe Error-Handling**: Retry-Logik überall nötig
- ❌ **Nicht für async Tasks**: "@Async" + Retry = schwierig

**Microsoft Best Practice**:
> "Optimistic concurrency is generally used in environments with a **low contention** for data"  
> Unser Fall: **HIGH CONTENTION** (viele Updates pro Sekunde)  
> Quelle: [Optimistic Concurrency](https://learn.microsoft.com/en-us/sql/connect/ado-net/optimistic-concurrency)

**Komplexität**: ⭐⭐⭐⭐ (HOCH)

---

## 🎯 Empfehlung: Hybrid-Ansatz

**Kombination aus Lösung 1 + 2:**

### 1. Event-Driven für Connection Management
```java
@PutMapping("/{serverId}")
public McpServerDto update(@PathVariable String serverId, 
                           @RequestBody McpServerRequest request) {
    // Synchrones DB-Update (Optimistic Locking OK hier, weil fast)
    McpServerDto dto = service.update(serverId, request);
    
    // Event publishen → Async Connection (sequenziell pro Server)
    eventPublisher.publishEvent(new McpServerUpdatedEvent(serverId));
    
    return dto;
}
```

### 2. Idempotent Connection Service
```java
@EventListener
@Async
public void handleServerUpdated(McpServerUpdatedEvent event) {
    String serverId = event.getServerId();
    
    // Idempotent: Check if already connected
    McpServer server = repository.findByServerId(serverId);
    if (server.getStatus() == McpServerStatus.CONNECTED) {
        log.debug("Server {} already connected, skipping", serverId);
        return;
    }
    
    // Connect (idempotent operation)
    mcpConnectionService.connectAndSync(serverId);
}
```

### 3. Optimistic Locking NUR für schnelle DB-Operations
```java
@Transactional
public McpServerDto update(String serverId, McpServerRequest request) {
    // Optimistic Locking OK: Operation dauert <100ms
    McpServer server = repository.findByServerId(serverId)
        .orElseThrow();
    
    applyUpdates(server, request);
    return toDto(repository.save(server));
    
    // Kein Retry nötig: Event-Listener ist idempotent
}
```

---

## 📈 Vergleichstabelle

| Kriterium | Event-Driven | Idempotent + Dedup | Pessimistic Lock | Optimistic + Retry |
|-----------|--------------|-------------------|------------------|-------------------|
| **Komplexität** | ⭐⭐ Niedrig | ⭐⭐⭐ Mittel | ⭐⭐ Niedrig | ⭐⭐⭐⭐ Hoch |
| **Performance** | ⭐⭐⭐⭐⭐ Sehr gut | ⭐⭐⭐⭐ Gut | ⭐⭐ Schlecht | ⭐⭐⭐ Mittel |
| **Skalierbarkeit** | ⭐⭐⭐⭐⭐ Exzellent | ⭐⭐⭐ Gut (Single-Instance) | ⭐⭐ Schlecht | ⭐⭐⭐ Mittel |
| **Wartbarkeit** | ⭐⭐⭐⭐⭐ Sehr gut | ⭐⭐⭐⭐ Gut | ⭐⭐⭐ Mittel | ⭐⭐ Schlecht |
| **Testbarkeit** | ⭐⭐⭐⭐⭐ Exzellent | ⭐⭐⭐⭐ Gut | ⭐⭐⭐ Mittel | ⭐⭐ Schwierig |
| **Microsoft Best Practice** | ✅ JA | ✅ JA | ❌ Abgeraten | ⚠️ Nur bei LOW CONTENTION |

---

## 🚀 Umsetzungsplan: Event-Driven Approach

### Phase 1: Event Infrastructure (1 Stunde)
```java
// 1. Event Class
public record McpServerUpdatedEvent(String serverId, Instant timestamp) {}

// 2. Event Listener
@Component
public class McpConnectionEventListener {
    @EventListener
    @Async("mcpServerTaskExecutor")
    public void handleServerUpdated(McpServerUpdatedEvent event) {
        mcpConnectionService.connectAndSync(event.serverId());
    }
}

// 3. Controller Update
@PutMapping("/{serverId}")
public McpServerDto update(@PathVariable String serverId, 
                           @RequestBody McpServerRequest request) {
    McpServerDto dto = service.update(serverId, request);
    eventPublisher.publishEvent(new McpServerUpdatedEvent(serverId, Instant.now()));
    return dto;
}
```

### Phase 2: Idempotent Service (30 Minuten)
```java
public void connectAndSync(String serverId) {
    McpServer server = repository.findByServerId(serverId).orElseThrow();
    
    // Idempotent check
    if (server.getStatus() == McpServerStatus.CONNECTED 
        && server.getLastSyncedAt() != null
        && Duration.between(server.getLastSyncedAt(), Instant.now()).toMinutes() < 5) {
        log.debug("Server {} recently synced, skipping", serverId);
        return;
    }
    
    // Connect (idempotent)
    try {
        updateStatus(serverId, McpServerStatus.CONNECTING);
        McpAsyncClient client = sessionRegistry.getOrCreateSession(serverId).block();
        fetchAndCacheCapabilities(serverId, client);
        updateStatus(serverId, McpServerStatus.CONNECTED);
    } catch (Exception ex) {
        updateStatus(serverId, McpServerStatus.ERROR);
    }
}
```

### Phase 3: Cleanup (15 Minuten)
- ❌ Entferne Pessimistic Locking
- ❌ Entferne Retry-Logik
- ❌ Entferne Deduplication Map (nicht mehr nötig)
- ✅ Behalte Optimistic Locking nur für schnelle DB-Ops

---

## 🎓 Fazit

**Aktuelle Lösung** (Pessimistic + Retry + Deduplication):
- ❌ Zu komplex (3 verschiedene Mechanismen)
- ❌ Performance-Probleme (15s Locks)
- ❌ Schwer testbar
- ❌ Nicht Microsoft Best Practice

**Empfohlene Lösung** (Event-Driven + Idempotent):
- ✅ **Einfacher**: Nur 2 Konzepte (Events + Idempotenz)
- ✅ **Performanter**: Keine Locks, keine Waits
- ✅ **Skalierbarer**: Funktioniert mit Load Balancern
- ✅ **Standard Pattern**: Spring @EventListener
- ✅ **Microsoft-konform**: Asynchronous message-based communication

**Migration**: 
- **Aufwand**: ~2 Stunden
- **Risk**: Niedrig (kann parallel getestet werden)
- **Benefit**: Weniger Code, bessere Performance, einfacher zu verstehen

---

## 📚 Referenzen

1. [Asynchronous message-based communication (Microsoft)](https://learn.microsoft.com/en-us/dotnet/architecture/microservices/architect-microservice-container-applications/asynchronous-message-based-communication)
2. [Designing Azure Functions for identical input (Microsoft)](https://learn.microsoft.com/en-us/azure/azure-functions/functions-idempotent)
3. [Optimistic Concurrency (Microsoft)](https://learn.microsoft.com/en-us/sql/connect/ado-net/optimistic-concurrency)
4. [Pessimistic Locking Disadvantages (Microsoft)](https://learn.microsoft.com/en-us/aspnet/web-forms/overview/data-access/editing-inserting-and-deleting-data/implementing-optimistic-concurrency-vb)
5. [Event-Driven Architecture Best Practices](https://learn.microsoft.com/en-us/azure/architecture/guide/architecture-styles/event-driven)

