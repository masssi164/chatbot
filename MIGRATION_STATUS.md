# Migration Status: JPA → R2DBC

## ✅ COMPLETED

### 1. McpServer Entity - MIGRIERT
- ✅ `jakarta.persistence.*` → `org.springframework.data.*`
- ✅ `@Entity` → `@Table`
- ✅ `@GeneratedValue` removed (R2DBC managed)
- ✅ Enum → String mit Helper-Methoden (getStatusEnum(), setStatusEnum())
- ✅ `@PrePersist/@PreUpdate` removed - muss im Service gemacht werden
- ✅ `@Version` removed - manuelles Versioning nötig

### 2. McpServerRepository - MIGRIERT
- ✅ `JpaRepository` → `ReactiveCrudRepository`
- ✅ Return types: `Optional<T>` → `Mono<T>`, `List<T>` → `Flux<T>`
- ✅ New method: `findAllByOrderByNameAsc()` für sortierte Liste

---

## ⏳ IN PROGRESS - Next Steps

### 3. McpServerService - NEEDS MIGRATION
**Status:** Kompiliert NICHT
**Probleme:**
- Import `org.springframework.orm.ObjectOptimisticLockingFailureException` (JPA-only)
- Import `app.chatbot.utils.GenericMapper` (fehlt, muss wiederhergestellt werden)
- Alle Methoden müssen `Mono<T>` / `Flux<T>` zurückgeben
- `@Transactional` entfernen
- Manual timestamp + version management

**Dateien betroffen:**
- `McpServerService.java`
- `McpServerController.java`
- `McpServerStatusStreamController.java`
- `McpClientService.java`
- `McpConnectionService.java`
- `McpCapabilitiesScheduler.java`

### 4. Supporting Classes - NEED RESTORATION
**GenericMapper** - wurde gelöscht, muss wiederhergestellt werden:
```bash
git restore chatbot-backend/src/main/java/app/chatbot/utils/GenericMapper.java
```

### 5. McpSessionRegistry - NEEDS UPDATE
**Status:** Kompiliert, aber nutzt noch blocking Repository calls
**Änderungen nötig:**
```java
// VORHER:
McpServer server = serverRepository.findByServerId(serverId)
    .orElseThrow(() -> new NotFoundException("Server not found"));

// NACHHER:
return serverRepository.findByServerId(serverId)
    .switchIfEmpty(Mono.error(new NotFoundException("Server not found")))
    .flatMap(server -> initializeSession(server));
```

---

## 📋 TODO Liste (Priorität)

1. **HIGH**: `GenericMapper` wiederherstellen
2. **HIGH**: `McpServerService` → Reactive (alle Methoden Mono/Flux)
3. **HIGH**: `McpServerController` → Reactive endpoints
4. **MEDIUM**: `McpSessionRegistry` → Repository calls reactive machen
5. **MEDIUM**: `McpClientService` → Reactive
6. **MEDIUM**: `McpConnectionService` → Reactive  
7. **MEDIUM**: `McpCapabilitiesScheduler` → Reactive scheduling
8. **LOW**: `McpToolContextBuilder` checken
9. **LOW**: DTOs checken (sollten OK sein)
10. **TEST**: Komplett Backend kompilieren
11. **TEST**: Tests anpassen (@DataR2dbcTest statt @DataJpaTest)

---

## 🔍 KEY INSIGHTS

### MCP SDK ist bereits reactive!
```java
// McpAsyncClient.initialize() gibt Mono zurück
Mono<InitializeResult> result = client.initialize();

// McpAsyncClient.listTools() gibt Mono zurück
Mono<ListToolsResult> tools = client.listTools();
```

### R2DBC Pattern
```java
// Save
Mono<McpServer> saved = repository.save(server);

// Find
Mono<McpServer> found = repository.findByServerId("id");

// Find all
Flux<McpServer> all = repository.findAll();

// Chain operations
return repository.findByServerId(id)
    .flatMap(server -> {
        server.setStatus("CONNECTED");
        return repository.save(server);
    })
    .map(this::toDto);
```

### Timestamp Management
```java
// JPA hatte @PrePersist
@PrePersist
void onCreate() {
    lastUpdated = Instant.now();
}

// R2DBC: Im Service setzen
return Mono.just(request)
    .map(req -> McpServer.builder()
        .name(req.getName())
        .lastUpdated(Instant.now()) // ⚠️ Manually!
        .build())
    .flatMap(repository::save);
```

---

## ⚠️ WICHTIGE ENTSCHEIDUNGEN

### 1. Optimistic Locking
**Problem:** R2DBC hat kein `@Version` 
**Lösung:** Manual versioning in Service:
```java
public Mono<McpServer> updateStatus(String id, String newStatus) {
    return repository.findByServerId(id)
        .flatMap(server -> {
            Long currentVersion = server.getVersion();
            server.setStatus(newStatus);
            server.setVersion(currentVersion + 1);
            return repository.save(server);
        });
}
```

### 2. Transaction Management
**Problem:** `@Transactional` in R2DBC funktioniert anders
**Lösung:** `@Transactional` entfernen, bei Bedarf `TransactionalOperator` nutzen

### 3. Enum Handling
**Entscheidung:** Enums als String speichern + Helper-Methoden
```java
// String in DB
private String status;

// Helper für Typ-Safety
public McpServerStatus getStatusEnum() {
    return McpServerStatus.valueOf(status);
}
```

---

## 📊 FORTSCHRITT

```
[████████░░░░░░░░░░] 40% Complete

Completed:
- McpServer Entity ✅
- McpServerRepository ✅

In Progress:
- McpServerService ⏳

Pending:
- McpServerController
- McpSessionRegistry
- McpClientService  
- McpConnectionService
- McpCapabilitiesScheduler
- Tests
```

---

## 🚀 NEXT ACTION

```bash
# 1. GenericMapper wiederherstellen
git restore chatbot-backend/src/main/java/app/chatbot/utils/GenericMapper.java

# 2. McpServerService starten (größte Datei, viele Änderungen)
# - Alle blocking calls → reactive
# - Repository calls wrappen in Mono/Flux
# - @Transactional entfernen
```

**Erwartete Arbeit:** 2-3 Stunden für komplette Service Layer Migration
**Risiko:** MEDIUM (MCP SDK ist reactive, passt gut!)
