# Docker Compose Usage Guide

## Overview

The `docker-compose.yml` defines a complete stack with 4 services:

```
┌─────────────────┐
│   Frontend      │  Port 3000 (React + Vite + Nginx)
│  chatbot-frontend│
└────────┬────────┘
         │ depends on
         ▼
┌─────────────────┐
│    Backend      │  Port 8080 (Spring Boot)
│ chatbot-backend │
└────────┬────────┘
         │ depends on
         ▼
┌─────────────────┐     ┌─────────────────┐
│      n8n        │◄────┤   PostgreSQL    │
│  Port 5678      │     │   Port 5432     │
└─────────────────┘     └─────────────────┘
```

## Scenarios

### Scenario 1: Run Everything in Docker (Production-like)

**Use case:** Test the complete stack as it would run in production.

```bash
# Start all services
./gradlew composeUp

# Access applications
# - http://localhost:3000  (Frontend)
# - http://localhost:8080  (Backend API)
# - http://localhost:5678  (n8n)

# View logs
./gradlew composeLogs

# Stop everything
./gradlew composeDown
```

**Pros:**
- ✅ Closest to production
- ✅ Isolated environment
- ✅ No local dependencies needed

**Cons:**
- ❌ Slower rebuild times
- ❌ No hot reload
- ❌ Need to rebuild images after code changes

---

### Scenario 2: Hybrid - Infrastructure in Docker, Apps Local (Recommended for Development)

**Use case:** Fast development with hot reload while using real services.

**Step 1:** Modify `docker-compose.yml` temporarily

Comment out the chatbot services:
```yaml
#  chatbot-backend:
#    build:
#      context: ./chatbot-backend
#    ...
#
#  chatbot-frontend:
#    build:
#      context: ./chatbot
#    ...
```

**Step 2:** Start infrastructure only
```bash
./gradlew composeUp
```

**Step 3:** Run backend locally
```bash
# Terminal 1: Backend with hot reload
./gradlew :chatbot-backend:bootRun
```

**Step 4:** Run frontend locally
```bash
# Terminal 2: Frontend with Vite dev server
cd chatbot
npm run dev
```

**Access applications:**
- http://localhost:5173 (Frontend - Vite dev server)
- http://localhost:8080 (Backend API)
- http://localhost:5678 (n8n)

**Pros:**
- ✅ Fast hot reload for frontend
- ✅ Spring Boot DevTools for backend
- ✅ Real infrastructure (n8n, PostgreSQL)
- ✅ Faster iteration

**Cons:**
- ❌ Need Java 17 and Node.js installed
- ❌ Manual startup of apps

---

### Scenario 3: Local Development, No Docker

**Use case:** Lightweight development without Docker.

**Requirements:**
- n8n running externally or mock MCP server
- PostgreSQL running locally or use H2 in-memory

```bash
# Backend (using H2 in-memory database)
./gradlew :chatbot-backend:bootRun

# Frontend
cd chatbot
npm run dev
```

**Configure backend** to use H2 instead of PostgreSQL:
```properties
# src/main/resources/application-dev.properties
spring.datasource.url=jdbc:h2:mem:chatbotdb
spring.datasource.driver-class-name=org.h2.Driver
```

---

## Common Tasks

### Rebuild After Code Changes (Full Docker)

```bash
# Option 1: Rebuild specific service
docker-compose build chatbot-backend
docker-compose up -d chatbot-backend

# Option 2: Use Gradle
./gradlew :chatbot-backend:buildDockerImage
./gradlew restart

# Option 3: Rebuild everything
./gradlew composeBuild
./gradlew restart
```

### View Logs

```bash
# All services
docker-compose logs -f

# Specific service
docker-compose logs -f chatbot-backend
docker-compose logs -f chatbot-frontend

# Or with Gradle
./gradlew composeLogs
```

### Access Running Container

```bash
# Backend
docker-compose exec chatbot-backend sh

# Frontend
docker-compose exec chatbot-frontend sh

# Database
docker-compose exec postgres psql -U n8n -d n8n
```

### Clean Up

```bash
# Stop and remove containers (keeps volumes)
./gradlew composeDown

# Stop and remove everything including volumes
./gradlew composeDownForced

# Remove images
docker-compose down --rmi all

# Full cleanup
docker-compose down -v --rmi all
docker system prune -a
```

## Environment Configuration

### Development (.env)
```properties
BACKEND_PORT=8080
FRONTEND_PORT=3000
SPRING_PROFILES_ACTIVE=dev
VITE_API_BASE_URL=http://localhost:8080
```

### Production
```properties
BACKEND_PORT=8080
FRONTEND_PORT=80
SPRING_PROFILES_ACTIVE=prod
VITE_API_BASE_URL=https://api.yourdomain.com
```

## Troubleshooting

### Port Already in Use
```bash
# Find process using port 8080
lsof -i :8080
netstat -an | grep 8080

# Kill process
kill -9 <PID>

# Or change port in .env
BACKEND_PORT=8081
```

### Service Not Healthy
```bash
# Check health
docker-compose ps

# View logs
docker-compose logs chatbot-backend

# Check health endpoint manually
curl http://localhost:8080/actuator/health
```

### Build Fails
```bash
# Clean Docker cache
docker builder prune -a

# Rebuild without cache
docker-compose build --no-cache chatbot-backend

# Check Dockerfile
docker build -f chatbot-backend/Dockerfile chatbot-backend
```

### Frontend Can't Connect to Backend
1. Check `VITE_API_BASE_URL` in `.env`
2. Verify backend is running: `curl http://localhost:8080/actuator/health`
3. Check network in docker-compose: all services on same network
4. For local dev, use `http://localhost:8080` not `http://chatbot-backend:8080`

## Best Practices

### For Development
1. Use **Scenario 2** (hybrid) for fastest iteration
2. Keep `.env` file with development settings
3. Use `stopContainers=false` in Gradle to keep containers running

### For Testing
1. Start full stack with `./gradlew composeUp`
2. Run integration tests against real services
3. Clean up with `./gradlew composeDownForced`

### For CI/CD
```bash
# Build and test
./gradlew clean build

# Build Docker images
./gradlew :chatbot-backend:buildDockerImage :chatbot:buildDockerImage

# Start stack and run E2E tests
./gradlew composeUp
./gradlew e2eTest
./gradlew composeDown

# Push images
./gradlew :chatbot-backend:pushDockerImage :chatbot:pushDockerImage
```

## Service URLs Reference

| Service | Docker Compose | Local Development |
|---------|---------------|-------------------|
| Frontend | http://localhost:3000 | http://localhost:5173 |
| Backend | http://localhost:8080 | http://localhost:8080 |
| Backend Health | http://localhost:8080/actuator/health | http://localhost:8080/actuator/health |
| n8n | http://localhost:5678 | http://localhost:5678 |
| PostgreSQL | localhost:5432 | localhost:5432 |
| MCP Server | http://localhost:5678/mcp/2714421f-0865-468b-b938-0d592153a235 | Same |
