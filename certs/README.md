# SSL Certificate Setup for Corporate Proxies

## Why This Is Needed
If you're behind a corporate proxy like Zscaler that performs SSL inspection,
Docker containers need to trust the proxy's root certificate.

**Note: This is OPTIONAL** - only needed if you see certificate errors like:
```
tls: failed to verify certificate: x509: certificate signed by unknown authority
```

## How to Export Zscaler Certificate

### From Windows:
1. Open Microsoft Management Console (Win + R, type `mmc`, press Enter)
2. File → Add/Remove Snap-in → Certificates → Add → Computer account
3. Navigate to: Trusted Root Certification Authorities → Certificates
4. Find "Zscaler Root CA" certificate
5. Right-click → All Tasks → Export
6. Choose "Base-64 encoded X.509 (.CER)" format
7. Save as `zscaler-root-ca.crt` in this directory

### From macOS:
1. Open Keychain Access
2. Select "System" keychain
3. Find "Zscaler Root CA" certificate
4. Right-click → Export
5. Save as `zscaler-root-ca.crt` in this directory

### From Linux:
1. Export from browser's certificate store, or
2. Copy from `/etc/ssl/certs/` if already installed system-wide

## Configuration

After placing the certificate here, edit your `.env` file:

```bash
# Uncomment and set the path to your certificate
ZSCALER_ROOT_CA_PATH=./certs/zscaler-root-ca.crt
```

## Verification

After placing the certificate here, rebuild containers:
```bash
docker compose down
docker compose up -d --build
```

Check Ollama can now pull models:
```bash
docker exec chatbot-ollama-1 ollama pull llama3.2
```

## Troubleshooting

If containers fail to start after adding the certificate:
1. Check that the certificate file is in PEM format (text file starting with `-----BEGIN CERTIFICATE-----`)
2. Remove the certificate and restart containers to verify backward compatibility
3. Check container logs: `docker compose logs ollama` (or litellm, n8n)

## Security Note
Never commit actual certificate files to git. They are automatically excluded via `.gitignore`.
