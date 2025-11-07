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

## Important Notes

### Container Image Limitations
Some container images run as non-root users or have read-only filesystems for security.
If you see warnings like:
```
Warning: Failed to install custom CA certificate for <service>, using defaults
```

This means the certificate couldn't be installed at runtime, but:
1. ✅ The container will still start normally (backward compatibility)
2. ✅ The certificate is mounted and accessible via environment variables
3. ✅ Applications that support custom CA bundles via environment variables will work

### For Production Use with Zscaler
If you need full certificate trust for all HTTPS operations, consider:
1. **Building custom Docker images** with the certificate baked in during the build process
2. **Using enterprise container images** that allow runtime certificate installation
3. **Configuring applications** to use the mounted certificate via environment variables

The current setup provides the certificate to all containers via:
- Volume mount at `/tmp/zscaler-root-ca.crt`
- Environment variables (`SSL_CERT_FILE`, `REQUESTS_CA_BUNDLE`, `NODE_EXTRA_CA_CERTS`, etc.)

## Troubleshooting

If containers fail to start after adding the certificate:
1. Check that the certificate file is in PEM format (text file starting with `-----BEGIN CERTIFICATE-----`)
2. Remove the certificate and restart containers to verify backward compatibility
3. Check container logs: `docker compose logs ollama` (or litellm, n8n)

## Security Note
Never commit actual certificate files to git. They are automatically excluded via `.gitignore`.
