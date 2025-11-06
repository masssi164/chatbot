# 🚀 Chatbot Deployment Guide

## Multi-Profile Container Setup mit Gradle Wrapper

### 📋 Verfügbare Profile

#### **Development Profile (`dev`)**
- ✅ **H2 In-Memory Datenbank** - Keine externe DB nötig
- ✅ **Erweiterte Logs** - Debug-Informationen aktiv
- ✅ **Schnelle Startup-Zeit** - Optimiert für Entwicklung
- ✅ **Lokale API URLs** - Standardmäßig localhost

#### **Production Profile (`prod`)**
- ✅ **PostgreSQL Datenbank** - Persistente Daten
- ✅ **Optimierte Performance** - Connection Pooling & JVM Tuning
- ✅ **Sicherheit** - Reduzierte Log-Ausgaben
- ✅ **Environment Variables** - Konfigurierbar über .env

---

## 🎯 Deployment Kommandos

### **Hauptkommandos (Empfohlen)**

```bash
# 🏭 PRODUCTION: Alles bauen und starten (PostgreSQL)
./gradlew composeUp

# 🔧 DEVELOPMENT: Development-Modus starten (H2)
./gradlew developmentUp  

# ⚠️ Nur externe Services (für lokale JVM-Entwicklung)
./gradlew externalServicesUp

# 🛑 Alle Services stoppen
./gradlew composeDown
```

### **Build & Maintenance**

```bash
# 🏗️ Alle Docker Images neu bauen
./gradlew buildAllImages

# 🔄 Clean Rebuild (alles neu)
./gradlew rebuild

# 🏥 Health Check aller Services
./gradlew healthCheck

# 📋 Logs aller Services anzeigen
./gradlew logs
```

---

## 🔧 Profile-Konfiguration

### **Development Setup**
```bash
# 1. Externe Services starten
./gradlew externalServicesUp

# 2. Backend lokal entwickeln (IDE)
cd chatbot-backend
./gradlew bootRun --args='--spring.profiles.active=dev'

# 3. Frontend lokal entwickeln
cd chatbot
npm install
npm run dev
```

### **Production Setup**
```bash
# 1. Environment konfigurieren (.env)
# 2. Alles starten
./gradlew composeUp

# Services sind verfügbar:
# - Frontend: http://localhost:3000
# - Backend: http://localhost:8080
# - n8n: http://localhost:5678
```

---

## 🗄️ Datenbank Konfiguration

### **Development (H2)**
- **Typ**: In-Memory H2 Database
- **URL**: `r2dbc:h2:mem:///chatbotdb`
- **Vorteile**: Keine Setup erforderlich, schnell
- **Nachteile**: Daten gehen bei Neustart verloren

### **Production (PostgreSQL)**
- **Typ**: PostgreSQL 16 mit R2DBC
- **Host**: postgres:5432
- **Database**: chatbot_db
- **Migrationen**: Flyway (JDBC) + R2DBC (Runtime)
- **Connection Pool**: 10-50 Connections

---

## 🌍 Environment Variables

### **Wichtige Variablen (.env)**

```bash
# Profil auswählen
SPRING_PROFILES_ACTIVE=prod  # oder 'dev'

# Datenbank (nur Production)
DB_HOST=postgres
DB_NAME=chatbot_db
DB_USER=n8n
DB_PASSWORD=changeme

# Security
MCP_ENCRYPTION_KEY=your-32-character-secret-key-here

# API Endpoints
OPENAI_BASE_URL=https://api.openai.com/v1
VITE_API_BASE_URL=http://localhost:8080
```

---

## 🏗️ Architektur

```
📁 Root Project (Gradle Wrapper)
├── 🐳 docker-compose.yml         # Production Setup
├── 🐳 docker-compose.dev.yml     # Development Overrides
├── ⚙️ build.gradle               # Zentrale Build-Logic
├── 📁 chatbot/                   # React Frontend
│   └── 🐳 Dockerfile + 📦 npm
├── 📁 chatbot-backend/           # Spring Boot Backend
│   └── 🐳 Dockerfile + ☕ Gradle
└── 📁 scripts/                   # Setup Scripts
    └── create-multiple-postgresql-databases.sh
```

### **Service Dependencies**

```
🏭 Production Flow:
postgres → n8n → chatbot-backend → chatbot-frontend

🔧 Development Flow:  
n8n → chatbot-backend (H2) → chatbot-frontend
```

---

## 🚨 Troubleshooting

### **Häufige Probleme**

1. **Port bereits belegt**
   ```bash
   # Ports prüfen
   ./gradlew healthCheck
   docker-compose ps
   ```

2. **Database Connection Fehler**
   ```bash
   # PostgreSQL Logs prüfen
   docker-compose logs postgres
   
   # Backend Logs prüfen
   docker-compose logs chatbot-backend
   ```

3. **Build Fehler**
   ```bash
   # Clean Rebuild
   ./gradlew clean rebuild
   ```

4. **Profile-Probleme**
   ```bash
   # Profil explizit setzen
   SPRING_PROFILES_ACTIVE=dev ./gradlew developmentUp
   ```

### **Nützliche Debug-Kommandos**

```bash
# Container Status
docker-compose ps

# Alle Container Logs
./gradlew logs

# Spezifische Service Logs  
docker-compose logs -f chatbot-backend

# In Container einsteigen
docker-compose exec chatbot-backend bash
docker-compose exec postgres psql -U n8n -d chatbot_db
```

---

## ✅ Quick Start Checkliste

1. **✅ Environment Setup**
   ```bash
   cp .env.example .env  # Falls vorhanden
   # .env nach Bedarf anpassen
   ```

2. **✅ Development Starten**
   ```bash
   ./gradlew developmentUp
   ```

3. **✅ Production Starten**  
   ```bash
   ./gradlew composeUp
   ```

4. **✅ Zugriff testen**
   - Frontend: http://localhost:3000
   - Backend Health: http://localhost:8080/actuator/health
   - n8n: http://localhost:5678

---

## 🎯 Fazit

**Single Command Deployment erreicht!** 🎉

- **`./gradlew composeUp`** → Alles läuft (Production)
- **`./gradlew developmentUp`** → Entwicklung ready (H2)  
- **Multi-Profile Support** → Dev/Prod getrennt
- **R2DBC + PostgreSQL** → Production-ready persistence
- **Zentrale Gradle Tasks** → Konsistentes Management

**Happy Coding! 🚀**