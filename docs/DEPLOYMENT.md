# Tilda Deployment Guide

## Prerequisites

- Java 21+ (for XTDB 2.x)
- Clojure CLI tools
- Reverse proxy (Caddy, nginx, or Cloudflare Tunnel)

## Quick Deploy

```bash
# Build uberjar (TODO: add alias)
clj -T:build uber

# Or run directly
clj -M -m tilda.core
```

## Configuration

Copy and edit `config.edn`:

```edn
{:server {:port 8080}
 :storage-dir "data/xtdb"  ; Persistent storage
 :resource {:name "Shared Car"
            :description "VW Golf, parking spot A3"}
 :auth {:strategy :magic-link
        :users {"xK9mP2wQr3" {:name "Alice" :email "alice@example.com"}}}}
```

---

## Authentication Options

### Option 1: Magic Links (Recommended for small groups)

**How it works:** Each user gets a secret URL. First visit sets a cookie, then they're logged in forever.

```edn
:auth {:strategy :magic-link
       :users {"xK9mP2wQr3" {:name "Alice" :email "alice@example.com"}
               "qR7nL4yZm8" {:name "Bob"   :email "bob@example.com"}
               "pT5jH9kXv2" {:name "Carol" :email "carol@example.com"}}}
```

**Generate tokens:**
```bash
# Generate random tokens
for name in alice bob carol; do
  echo "\"$(openssl rand -base64 8 | tr -d '/+=')\" {:name \"${name^}\" :email \"$name@example.com\"}"
done
```

**User experience:**
1. You text them: `https://car.example.com/u/xK9mP2wQr3`
2. They click → redirected to calendar
3. Cookie persists 1 year
4. Done. No login ever again.

**Security notes:**
- Use HTTPS (reverse proxy handles this)
- Tokens should be random, not guessable
- Distribute links privately (text message, not posted publicly)
- Revoke by removing from config + restarting

---

### Option 2: Cloudflare Access (Recommended for public internet)

**How it works:** Cloudflare handles login via email magic links. Your app just reads the authenticated email from headers.

```edn
:auth {:strategy :cloudflare}
```

**Setup:**
1. Add domain to Cloudflare
2. Zero Trust → Access → Applications → Add
3. Set application domain (e.g., `car.example.com`)
4. Create policy: Allow specific emails
5. Point DNS to your server (A record or Tunnel)

**User experience:**
1. Visit `car.example.com`
2. Cloudflare shows "Enter email"
3. Click link in email → logged in
4. Session lasts ~30 days

**Pros:**
- You don't manage passwords
- Easy approval (add/remove emails in dashboard)
- DDoS protection included
- Free for ≤50 users

---

### Option 3: Development Mode

**For local testing only.** No auth, hardcoded stub user.

```edn
:auth {:strategy :dev}
```

Or omit `:auth` entirely (defaults to `:dev`).

---

## Reverse Proxy Setup

### Caddy (Easiest)

```caddyfile
car.example.com {
    reverse_proxy localhost:8080
}
```

Caddy handles HTTPS automatically via Let's Encrypt.

### nginx

```nginx
server {
    listen 443 ssl http2;
    server_name car.example.com;

    ssl_certificate /etc/letsencrypt/live/car.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/car.example.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        
        # SSE support
        proxy_buffering off;
        proxy_cache off;
    }
}
```

### Cloudflare Tunnel

```bash
cloudflared tunnel --url http://localhost:8080
```

---

## Production Checklist

- [ ] Set `:storage-dir` for persistent data
- [ ] Use random tokens for magic-link auth
- [ ] Put HTTPS in front (Caddy/nginx/Cloudflare)
- [ ] Backup `data/xtdb/` directory
- [ ] Set up process manager (systemd, supervisor)
- [ ] Consider rate limiting at reverse proxy

## Systemd Service

```ini
[Unit]
Description=Tilda Car Booking
After=network.target

[Service]
Type=simple
User=tilda
WorkingDirectory=/opt/tilda
ExecStart=/usr/bin/clojure -M -m tilda.core
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

## Environment Variables (TODO)

Future: `PORT`, `STORAGE_DIR`, `AUTH_STRATEGY` from env for 12-factor compatibility.
