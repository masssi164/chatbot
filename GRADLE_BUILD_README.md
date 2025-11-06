# Chatbot Monorepo - Gradle Multi-Project Build

This is a Gradle multi-project build that manages the chatbot frontend (Vite/React), backend (Spring Boot), and Docker Compose infrastructure.

## Project Structure

```
chatbot/
├── build.gradle                 # Root project with Docker Compose plugin
├── settings.gradle              # Multi-project configuration
├── docker-compose.yml          # n8n + PostgreSQL services
├── chatbot/                    # Frontend (Vite + React + TypeScript)
│   ├── build.gradle           # Node/NPM + Docker tasks
│   ├── Dockerfile             # Multi-stage build (Node + Nginx)
│   ├── package.json
│   └── src/
└── chatbot-backend/           # Backend (Spring Boot + Java 17)
    ├── build.gradle          # Spring Boot + Docker tasks
    ├── Dockerfile            # Multi-stage build (JDK + JRE)
    └── src/
```

## Prerequisites

- Java 17+
- Node.js 20+
- Docker & Docker Compose
- Gradle 8.14+ (or use `./gradlew`)

## Available Gradle Tasks

### Root Project Tasks

#### Docker Compose Management
```bash
# Start docker-compose services (n8n + postgres)
./gradlew composeUp

# Stop docker-compose services (keeps containers running by default)
./gradlew composeDown

# Force stop and remove containers
./gradlew composeDownForced

# View logs from all containers
./gradlew composeLogs

# Rebuild images
./gradlew composeBuild

# Pull latest images
./gradlew composePull

# Convenience aliases
./gradlew up          # = composeUp
./gradlew down        # = composeDown
./gradlew restart     # = composeDown + composeUp
```

#### Application Lifecycle
```bash
# Start everything (docker-compose + build all projects)
./gradlew startAll

# Stop everything
./gradlew stopAll

# Build all subprojects
./gradlew build

# Clean all subprojects
./gradlew clean
```

### Frontend Tasks (chatbot)

```bash
# Install npm dependencies
./gradlew :chatbot:npmInstall

# Build React app
./gradlew :chatbot:npmBuild

# Run tests
./gradlew :chatbot:npmTest

# Clean build artifacts
./gradlew :chatbot:npmClean

# Build Docker image
./gradlew :chatbot:buildDockerImage

# Push Docker image to registry
./gradlew :chatbot:pushDockerImage

# Remove local Docker image
./gradlew :chatbot:removeDockerImage
```

### Backend Tasks (chatbot-backend)

```bash
# Build Spring Boot application
./gradlew :chatbot-backend:build

# Run Spring Boot application
./gradlew :chatbot-backend:bootRun

# Build fat JAR
./gradlew :chatbot-backend:bootJar

# Run tests
./gradlew :chatbot-backend:test

# Run tests with coverage
./gradlew :chatbot-backend:jacocoTestReport

# Build Docker image (custom Dockerfile)
./gradlew :chatbot-backend:buildDockerImage

# Build Docker image (Spring Boot Cloud Native Buildpacks)
./gradlew :chatbot-backend:bootBuildImage

# Push Docker image to registry
./gradlew :chatbot-backend:pushDockerImage

# Remove local Docker image
./gradlew :chatbot-backend:removeDockerImage
```

## Quick Start

### 1. Start All Services with Docker Compose
```bash
# Start all services (n8n, PostgreSQL, Backend, Frontend)
./gradlew composeUp

# This will:
# - Start PostgreSQL on port 5432
# - Start n8n on port 5678
# - Build and start Backend on port 8080
# - Build and start Frontend on port 3000
# - Wait for all services to be healthy
```

**Services will be available at:**
- Frontend: http://localhost:3000
- Backend API: http://localhost:8080
- Backend Health: http://localhost:8080/actuator/health
- n8n: http://localhost:5678
- PostgreSQL: localhost:5432

### 2. Development Mode (without Docker)

If you want to develop locally without Docker:

**Start Infrastructure Only:**
```bash
# Temporarily disable frontend/backend in docker-compose.yml
# Then start just n8n and postgres
./gradlew composeUp
```

**Run Backend Locally:**
```bash
./gradlew :chatbot-backend:bootRun
```

**Run Frontend Locally:**
```bash
cd chatbot
npm run dev
```

### 3. Stop All Services
```bash
./gradlew composeDown

# Or force stop and remove containers
./gradlew composeDownForced
```

## Docker Compose Configuration

The `docker-compose.yml` now includes all four services:

### Services

1. **postgres** (PostgreSQL 16)
   - Port: 5432
   - Database: n8n
   - Health check: pg_isready

2. **n8n** (Workflow Automation)
   - Port: 5678
   - Depends on: postgres
   - Volumes: n8n_data

3. **chatbot-backend** (Spring Boot)
   - Port: 8080
   - Built from: `./chatbot-backend/Dockerfile`
   - Health check: `/actuator/health`
   - Depends on: n8n, postgres

4. **chatbot-frontend** (React + Vite + Nginx)
   - Port: 3000 (mapped to 80 inside container)
   - Built from: `./chatbot/Dockerfile`
   - Health check: HTTP GET on `/`
   - Depends on: chatbot-backend

### Build Context

Docker Compose will automatically build images on first run:
```bash
# Build all images
./gradlew composeBuild

# Or start (builds if needed)
./gradlew composeUp
```

### Environment Variables

All services read from `.env` file:

```properties
# n8n Configuration
N8N_HOST=localhost
N8N_PORT=5678
N8N_PROTOCOL=http

# PostgreSQL
POSTGRES_USER=n8n
POSTGRES_PASSWORD=changeme
POSTGRES_DB=n8n

# Backend
BACKEND_PORT=8080
SPRING_PROFILES_ACTIVE=prod

# Frontend
FRONTEND_PORT=3000
VITE_API_BASE_URL=http://localhost:8080
VITE_N8N_BASE_URL=http://localhost:5678
```

### Health Checks

All services have health checks configured:
- **postgres**: `pg_isready` every 5s
- **n8n**: Depends on postgres being healthy
- **chatbot-backend**: `/actuator/health` every 30s (40s start period)
- **chatbot-frontend**: HTTP GET on `/` every 30s

The `composeUp` task waits for all services to be healthy before completing.

## Docker Images

### Backend Image (`chatbot-backend/Dockerfile`)
- **Base**: Eclipse Temurin 17 JDK (Alpine)
- **Runtime**: Eclipse Temurin 17 JRE (Alpine)
- **Port**: 8080
- **Health Check**: `/actuator/health` endpoint
- **User**: Non-root (`spring:spring`)

### Frontend Image (`chatbot/Dockerfile`)
- **Build Stage**: Node 20 Alpine
- **Runtime**: Nginx Alpine
- **Port**: 80
- **Health Check**: HTTP GET on `/`
- **Serves**: `/usr/share/nginx/html`

## Gradle Plugins Used

1. **com.avast.gradle.docker-compose** (0.17.19)
   - Manages docker-compose lifecycle
   - Waits for healthy containers
   - Exposes service info

2. **com.bmuschko.docker-remote-api** (9.4.0)
   - Builds Docker images
   - Pushes to registries
   - Removes images

3. **org.springframework.boot** (3.4.0)
   - Spring Boot application
   - `bootBuildImage` with Cloud Native Buildpacks

## Development Workflow

### Full Stack Development with Docker

```bash
# 1. Start all services (builds images on first run)
./gradlew composeUp

# 2. Access services
# - Frontend: http://localhost:3000
# - Backend: http://localhost:8080
# - n8n: http://localhost:5678

# 3. View logs
./gradlew composeLogs

# 4. Make code changes and rebuild
# Backend changes:
./gradlew :chatbot-backend:build
./gradlew composeBuild  # Rebuild backend image
./gradlew restart       # Restart services

# Frontend changes:
./gradlew :chatbot:npmBuild
./gradlew composeBuild  # Rebuild frontend image
./gradlew restart       # Restart services

# 5. Stop when done
./gradlew composeDown
```

### Local Development (Hybrid Mode)

Run infrastructure in Docker, but develop locally:

```bash
# 1. Start only infrastructure
# Temporarily comment out chatbot-backend and chatbot-frontend in docker-compose.yml

./gradlew composeUp

# 2. Run backend locally (with hot reload)
./gradlew :chatbot-backend:bootRun

# 3. Run frontend locally (with hot reload)
cd chatbot
npm run dev

# 4. Access services
# - Frontend: http://localhost:5173 (Vite dev server)
# - Backend: http://localhost:8080
# - n8n: http://localhost:5678
```

### Testing Workflow

```bash
# 1. Start infrastructure
./gradlew composeUp

# 2. Test backend
./gradlew :chatbot-backend:test

# 3. Test frontend
./gradlew :chatbot:npmTest

# 4. Build everything
./gradlew build

# 5. Integration tests (if any)
./gradlew integrationTest

# 6. Stop infrastructure
./gradlew composeDown
```

### CI/CD Pipeline Example

```bash
# Clean build with tests
./gradlew clean build

# Build Docker images
./gradlew :chatbot-backend:buildDockerImage :chatbot:buildDockerImage

# Push to registry (configure credentials first)
./gradlew :chatbot-backend:pushDockerImage :chatbot:pushDockerImage
```

## Troubleshooting

### Port Conflicts
If ports 5432 or 5678 are already in use:
```bash
# Stop conflicting containers
docker ps
docker stop <container-id>

# Or change ports in .env file
N8N_PORT=5679
```

### Docker Compose Not Stopping
By default, `stopContainers=false` keeps containers running. To force cleanup:
```bash
./gradlew composeDownForced
```

### Build Cache Issues
```bash
# Clean and rebuild
./gradlew clean build --no-build-cache

# Or clean Docker
docker system prune -a
```

### Docker Build Fails
```bash
# Check Docker is running
docker info

# Verify Dockerfile syntax
docker build -f chatbot-backend/Dockerfile chatbot-backend
```

## Project Configuration

### Multi-Project Settings (`settings.gradle`)
```gradle
rootProject.name = 'chatbot-monorepo'
include 'chatbot', 'chatbot-backend'
```

### Root Build (`build.gradle`)
- Docker Compose plugin configuration
- Subproject repositories
- Composite lifecycle tasks

### Subproject Builds
- `chatbot/build.gradle`: Node/NPM + Docker
- `chatbot-backend/build.gradle`: Spring Boot + Jacoco + Docker

## MCP Server Configuration

The n8n MCP server is accessible at:
```
http://localhost:5678/mcp/2714421f-0865-468b-b938-0d592153a235
```

Configure in `chatbot-backend/src/main/resources/application.properties` if needed.

## License

[Add license information]

## Contributing

[Add contribution guidelines]
