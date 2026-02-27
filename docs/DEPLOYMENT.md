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

### Option 2: Header-Based Auth (Caddy, nginx, Authelia)

**How it works:** Reverse proxy handles auth and passes username in `X-Remote-User` header.

```edn
:auth {:strategy :header
       :header-name "X-Remote-User"}  ; optional, this is the default
```

Use with:
- Caddy basicauth (see Reverse Proxy section)
- Caddy + Authelia
- nginx `auth_basic` + `proxy_set_header`
- Any reverse proxy that sets user headers

**User experience:** Depends on proxy auth method - could be password prompt, login form, or auto-login via client cert.

---

### Option 3: Cloudflare Access (Recommended for public internet)

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

### Option 4: Development Mode

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

#### Caddy with Basic Auth

Simple password protection - useful if you want Caddy to handle auth instead of the app:

```caddyfile
car.example.com {
    basicauth /* {
        alice $2a$14$... # bcrypt hash
        bob   $2a$14$...
    }
    reverse_proxy localhost:8080 {
        header_up X-Remote-User {http.auth.user.id}
    }
}
```

Generate password hashes: `caddy hash-password`

**Downside:** Users must type password each visit (browser may cache).

#### Caddy with Authelia (Full SSO)

For a proper login page with "remember me", use Authelia:

```caddyfile
car.example.com {
    forward_auth authelia:9091 {
        uri /api/verify?rd=https://auth.example.com
        copy_headers Remote-User Remote-Groups
    }
    reverse_proxy localhost:8080 {
        header_up X-Remote-User {http.request.header.Remote-User}
    }
}
```

Authelia provides: login form, 2FA, remember-me sessions, user management.

#### Caddy with Client Certificates (Zero-Click Auth)

**Best for tech-savvy users** - generate a certificate per user, install once, never authenticate again:

```caddyfile
car.example.com {
    tls {
        client_auth {
            mode require_and_verify
            trusted_ca_cert_file /etc/caddy/ca.crt
        }
    }
    reverse_proxy localhost:8080 {
        header_up X-Client-CN {http.request.tls.client.subject.CN}
    }
}
```

Generate certs:
```bash
# Create CA
openssl genrsa -out ca.key 4096
openssl req -x509 -new -nodes -key ca.key -sha256 -days 3650 -out ca.crt -subj "/CN=Tilda CA"

# Create user cert
openssl genrsa -out alice.key 2048
openssl req -new -key alice.key -out alice.csr -subj "/CN=Alice"
openssl x509 -req -in alice.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out alice.crt -days 365
openssl pkcs12 -export -out alice.p12 -inkey alice.key -in alice.crt  # Import this to browser/device
```

User installs `.p12` file once → auto-authenticated on every visit.


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
