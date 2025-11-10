# Deployment Guide

This guide covers deploying the chatbot application to production environments.

## Deployment Options

### Option 1: Docker Compose (Recommended for Small-Medium Scale)

**Pros:**
- Simple setup
- All services in one host
- Easy to maintain

**Cons:**
- Single host limitation
- Limited horizontal scaling

### Option 2: Kubernetes (For Large Scale)

**Pros:**
- Horizontal scaling
- High availability
- Resource management

**Cons:**
- More complex setup
- Requires K8s expertise

### Option 3: Cloud Platforms (AWS, GCP, Azure)

**Pros:**
- Managed services
- Auto-scaling
- Global distribution

**Cons:**
- Higher costs
- Vendor lock-in

## Docker Compose Deployment

### Prerequisites

- Docker 20.10+
- Docker Compose 2.0+
- Linux server (Ubuntu 22.04 LTS recommended)
- 4GB RAM minimum (8GB recommended)
- 20GB disk space

### Setup Steps

#### 1. Prepare Server

```bash
# Update system
sudo apt update && sudo apt upgrade -y

# Install Docker
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh

# Add user to docker group
sudo usermod -aG docker $USER
newgrp docker

# Install Docker Compose
sudo apt install docker-compose-plugin
```

#### 2. Clone Repository

```bash
cd /opt
sudo git clone https://github.com/your-org/chatbot.git
cd chatbot
sudo chown -R $USER:$USER .
```

#### 3. Configure Environment

```bash
# Copy example environment file
cp .env.example .env

# Edit configuration
nano .env
```

**Production `.env` Configuration:**

```properties
# Profile
SPRING_PROFILES_ACTIVE=prod

# Database
DB_HOST=postgres
DB_PORT=5432
DB_NAME=chatbot_db
DB_USER=chatbot_user
DB_PASSWORD=<STRONG_PASSWORD_HERE>

# OpenAI / LocalAGI
OPENAI_BASE_URL=http://localagi:8080/v1
OPENAI_API_KEY=sk-local-master
LOCALAI_SEED_MODELS=huggingface://TheBloke/Llama-3.2-1B-Instruct-GGUF/llama-3.2-1b-instruct-q4_k_m.gguf

# Or use the public OpenAI endpoint:
# OPENAI_BASE_URL=https://api.openai.com/v1

# n8n
N8N_HOST=n8n
N8N_PORT=5678
N8N_ENCRYPTION_KEY=<RANDOM_32_CHAR_KEY>

# Security
MCP_ENCRYPTION_KEY=<RANDOM_32_CHAR_KEY>

# Frontend
VITE_API_BASE_URL=https://api.yourdomain.com
VITE_N8N_BASE_URL=https://n8n.yourdomain.com

# Ports
BACKEND_PORT=8080
FRONTEND_PORT=80
```

**Generate Secure Keys:**
```bash
# Generate 32-character keys
openssl rand -hex 32
```

#### 4. Configure Reverse Proxy (Nginx)

Install Nginx on host:

```bash
sudo apt install nginx
```

Create configuration:

```bash
sudo nano /etc/nginx/sites-available/chatbot
```

```nginx
# Frontend
server {
    listen 80;
    server_name yourdomain.com www.yourdomain.com;
    
    location / {
        proxy_pass http://localhost:3000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}

# Backend API
server {
    listen 80;
    server_name api.yourdomain.com;
    
    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        
        # SSE support
        proxy_http_version 1.1;
        proxy_set_header Connection '';
        proxy_buffering off;
        proxy_cache off;
        chunked_transfer_encoding off;
    }
}

# n8n
server {
    listen 80;
    server_name n8n.yourdomain.com;
    
    location / {
        proxy_pass http://localhost:5678;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

Enable site:

```bash
sudo ln -s /etc/nginx/sites-available/chatbot /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl restart nginx
```

#### 5. Setup SSL with Let's Encrypt

```bash
# Install certbot
sudo apt install certbot python3-certbot-nginx

# Obtain certificates
sudo certbot --nginx -d yourdomain.com -d www.yourdomain.com
sudo certbot --nginx -d api.yourdomain.com
sudo certbot --nginx -d n8n.yourdomain.com

# Auto-renewal is configured automatically
```

#### 6. Deploy Application

```bash
# Build images
./gradlew buildAllImages

# Start services
./gradlew up

# Or use docker compose directly
docker compose up -d

# Check status
docker compose ps

# View logs
docker compose logs -f
```

#### 7. Verify Deployment

```bash
# Check health endpoints
curl https://api.yourdomain.com/actuator/health

# Check frontend
curl https://yourdomain.com

# Check all services
docker compose ps
```

### Post-Deployment

#### Setup Monitoring

**Install Prometheus & Grafana** (optional):

```bash
# Add to docker-compose.yml
services:
  prometheus:
    image: prom/prometheus:latest
    volumes:
      - ./config/prometheus.yml:/etc/prometheus/prometheus.yml
      - prometheus_data:/prometheus
    ports:
      - "9090:9090"
      
  grafana:
    image: grafana/grafana:latest
    volumes:
      - grafana_data:/var/lib/grafana
    ports:
      - "3001:3000"
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin
```

#### Setup Backups

```bash
# Create backup script
cat > /opt/chatbot/backup.sh << 'SCRIPT'
#!/bin/bash
BACKUP_DIR="/backup/chatbot"
DATE=$(date +%Y%m%d_%H%M%S)

# Backup database
docker compose exec -T postgres pg_dump -U chatbot_user chatbot_db | gzip > "$BACKUP_DIR/db_$DATE.sql.gz"

# Backup n8n data
docker compose exec -T n8n tar czf - /home/node/.n8n > "$BACKUP_DIR/n8n_$DATE.tar.gz"

# Keep only last 7 days
find "$BACKUP_DIR" -name "*.gz" -mtime +7 -delete
SCRIPT

chmod +x /opt/chatbot/backup.sh

# Add to crontab (daily at 2 AM)
echo "0 2 * * * /opt/chatbot/backup.sh" | crontab -
```

#### Setup Log Rotation

```bash
sudo nano /etc/logrotate.d/chatbot
```

```
/var/log/chatbot/*.log {
    daily
    rotate 7
    compress
    delaycompress
    missingok
    notifempty
    create 0644 root root
}
```

## Kubernetes Deployment

### Prerequisites

- Kubernetes cluster (1.24+)
- kubectl configured
- Helm 3.x (optional)

### Deploy to Kubernetes

#### 1. Create Namespace

```bash
kubectl create namespace chatbot
```

#### 2. Create Secrets

```bash
# Database credentials
kubectl create secret generic chatbot-db \
  --from-literal=username=chatbot_user \
  --from-literal=password=<STRONG_PASSWORD> \
  -n chatbot

# OpenAI API key
kubectl create secret generic openai \
  --from-literal=api-key=sk-<YOUR_KEY> \
  -n chatbot

# MCP encryption key
kubectl create secret generic mcp-encryption \
  --from-literal=key=<32_CHAR_KEY> \
  -n chatbot
```

#### 3. Deploy PostgreSQL

```yaml
# k8s/postgres-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: postgres
  namespace: chatbot
spec:
  replicas: 1
  selector:
    matchLabels:
      app: postgres
  template:
    metadata:
      labels:
        app: postgres
    spec:
      containers:
      - name: postgres
        image: postgres:16-alpine
        env:
        - name: POSTGRES_USER
          valueFrom:
            secretKeyRef:
              name: chatbot-db
              key: username
        - name: POSTGRES_PASSWORD
          valueFrom:
            secretKeyRef:
              name: chatbot-db
              key: password
        - name: POSTGRES_DB
          value: chatbot_db
        ports:
        - containerPort: 5432
        volumeMounts:
        - name: postgres-storage
          mountPath: /var/lib/postgresql/data
      volumes:
      - name: postgres-storage
        persistentVolumeClaim:
          claimName: postgres-pvc
---
apiVersion: v1
kind: Service
metadata:
  name: postgres
  namespace: chatbot
spec:
  selector:
    app: postgres
  ports:
  - port: 5432
    targetPort: 5432
```

#### 4. Deploy Backend

```yaml
# k8s/backend-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: chatbot-backend
  namespace: chatbot
spec:
  replicas: 3
  selector:
    matchLabels:
      app: chatbot-backend
  template:
    metadata:
      labels:
        app: chatbot-backend
    spec:
      containers:
      - name: chatbot-backend
        image: your-registry/chatbot-backend:latest
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: prod
        - name: DB_HOST
          value: postgres
        - name: DB_PORT
          value: "5432"
        - name: DB_NAME
          value: chatbot_db
        - name: DB_USER
          valueFrom:
            secretKeyRef:
              name: chatbot-db
              key: username
        - name: DB_PASSWORD
          valueFrom:
            secretKeyRef:
              name: chatbot-db
              key: password
        - name: OPENAI_API_KEY
          valueFrom:
            secretKeyRef:
              name: openai
              key: api-key
        - name: MCP_ENCRYPTION_KEY
          valueFrom:
            secretKeyRef:
              name: mcp-encryption
              key: key
        ports:
        - containerPort: 8080
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 5
---
apiVersion: v1
kind: Service
metadata:
  name: chatbot-backend
  namespace: chatbot
spec:
  selector:
    app: chatbot-backend
  ports:
  - port: 8080
    targetPort: 8080
```

#### 5. Deploy Frontend

```yaml
# k8s/frontend-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: chatbot-frontend
  namespace: chatbot
spec:
  replicas: 2
  selector:
    matchLabels:
      app: chatbot-frontend
  template:
    metadata:
      labels:
        app: chatbot-frontend
    spec:
      containers:
      - name: chatbot-frontend
        image: your-registry/chatbot-frontend:latest
        ports:
        - containerPort: 80
---
apiVersion: v1
kind: Service
metadata:
  name: chatbot-frontend
  namespace: chatbot
spec:
  selector:
    app: chatbot-frontend
  ports:
  - port: 80
    targetPort: 80
```

#### 6. Create Ingress

```yaml
# k8s/ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: chatbot-ingress
  namespace: chatbot
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-prod
    nginx.ingress.kubernetes.io/proxy-read-timeout: "3600"
    nginx.ingress.kubernetes.io/proxy-send-timeout: "3600"
spec:
  ingressClassName: nginx
  tls:
  - hosts:
    - yourdomain.com
    - api.yourdomain.com
    secretName: chatbot-tls
  rules:
  - host: yourdomain.com
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: chatbot-frontend
            port:
              number: 80
  - host: api.yourdomain.com
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: chatbot-backend
            port:
              number: 8080
```

#### 7. Apply Configuration

```bash
kubectl apply -f k8s/
```

## Cloud Platform Deployment

### AWS (Elastic Beanstalk + RDS)

1. **Create RDS PostgreSQL instance**
2. **Create Elastic Beanstalk environment**
3. **Deploy Docker Compose**
4. **Configure Application Load Balancer**
5. **Setup CloudFront (CDN)**

### GCP (Cloud Run + Cloud SQL)

1. **Create Cloud SQL PostgreSQL instance**
2. **Deploy to Cloud Run**
3. **Configure Cloud Load Balancing**
4. **Setup Cloud CDN**

### Azure (Container Instances + PostgreSQL)

1. **Create Azure Database for PostgreSQL**
2. **Deploy to Container Instances**
3. **Configure Application Gateway**
4. **Setup Azure CDN**

## Performance Tuning

### Backend (Spring Boot)

```properties
# JVM Options
JAVA_OPTS=-Xms512m -Xmx2g -XX:+UseG1GC

# R2DBC Connection Pool
spring.r2dbc.pool.initial-size=10
spring.r2dbc.pool.max-size=50
spring.r2dbc.pool.max-idle-time=30m

# WebFlux
spring.webflux.base-path=/api
```

### Database (PostgreSQL)

```sql
-- Create indexes
CREATE INDEX idx_messages_conversation_id ON messages(conversation_id);
CREATE INDEX idx_tool_calls_conversation_id ON tool_calls(conversation_id);
CREATE INDEX idx_mcp_servers_status ON mcp_servers(status);

-- Analyze tables
ANALYZE conversations;
ANALYZE messages;
ANALYZE tool_calls;
```

### Frontend (Nginx)

```nginx
# Enable gzip compression
gzip on;
gzip_types text/plain text/css application/json application/javascript text/xml application/xml application/xml+rss text/javascript;

# Enable caching
location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg)$ {
    expires 1y;
    add_header Cache-Control "public, immutable";
}
```

## Monitoring

### Health Checks

- Backend: `https://api.yourdomain.com/actuator/health`
- Frontend: `https://yourdomain.com`
- Database: `docker compose exec postgres pg_isready`

### Metrics (Prometheus)

Key metrics to monitor:
- Response times (P50, P95, P99)
- Error rates
- Database connection pool usage
- MCP session count
- Tool execution latency

### Logging

- Backend logs: `/var/log/chatbot/backend.log`
- Frontend logs: Browser console / Nginx access logs
- Database logs: PostgreSQL logs

## Security Checklist

- [ ] Strong passwords for all services
- [ ] SSL/TLS enabled (HTTPS)
- [ ] API keys encrypted in database
- [ ] Firewall configured (only ports 80, 443 open)
- [ ] Regular security updates
- [ ] Backup strategy in place
- [ ] Monitoring and alerting configured
- [ ] CORS properly configured
- [ ] Rate limiting enabled
- [ ] Input validation on all endpoints

## Maintenance

### Update Application

```bash
# Pull latest code
cd /opt/chatbot
git pull

# Rebuild images
./gradlew buildAllImages

# Restart services (zero downtime)
docker compose up -d --no-deps --build chatbot-backend
docker compose up -d --no-deps --build chatbot-frontend
```

### Database Backup

```bash
# Manual backup
docker compose exec postgres pg_dump -U chatbot_user chatbot_db | gzip > backup_$(date +%Y%m%d).sql.gz

# Restore
gunzip < backup_20250106.sql.gz | docker compose exec -T postgres psql -U chatbot_user chatbot_db
```

### Scale Services

```bash
# Scale backend replicas
docker compose up -d --scale chatbot-backend=3

# Or in Kubernetes
kubectl scale deployment chatbot-backend --replicas=5 -n chatbot
```

## Troubleshooting

### High Memory Usage

Check memory usage:
```bash
docker stats
```

Adjust limits in `docker-compose.yml`:
```yaml
services:
  chatbot-backend:
    deploy:
      resources:
        limits:
          memory: 2G
```

### Slow Database Queries

Enable query logging:
```sql
ALTER DATABASE chatbot_db SET log_statement = 'all';
ALTER DATABASE chatbot_db SET log_duration = on;
```

View slow queries:
```bash
docker compose exec postgres tail -f /var/log/postgresql/postgresql.log
```

### Connection Pool Exhausted

Increase pool size:
```properties
spring.r2dbc.pool.max-size=100
```

See also:
- [Getting Started](./GETTING_STARTED.md)
- [Development Guide](./DEVELOPMENT.md)
- [Architecture Documentation](../architecture/SYSTEM_OVERVIEW.md)
