# Tilda - Shared Car Booking

A simple booking system for a shared car among a small group.

## Quick Start

```bash
# Run dev server with hot reload
clj -M:dev

# Open http://localhost:8080/calendar

# Run tests
clj -M:test
```

## How It Works

**Two tables, simple state model:**

```
Requests (pending)  ──resolve──>  Bookings (confirmed)
     │                                  │
     └── deleted = cancelled ──────────┘
```

- **Request exists** = someone wants this slot (pending)
- **Booking exists** = slot is confirmed
- **Deleted** = cancelled/resolved (XTDB history preserves audit trail)

**Calendar interaction:**
- Drag to select dates → creates request
- Click/drag starting on YOUR pending indicator → cancels it
- SSE pushes updates to all connected browsers

## Project Structure

```
src/tilda/
├── booking.clj      # Domain logic (pure where possible)
├── routes.clj       # HTTP handlers (Ring + Reitit)  
├── sse.clj          # Server-sent events pub/sub
├── core.clj         # Entry point
└── views/           # Hiccup templates
    ├── layout.clj
    └── calendar.clj
```

## Key Files to Understand

1. **[booking.clj](src/tilda/booking.clj)** - Core domain logic
   - `create-request!` - Submit a request (idempotent per tenant+overlap)
   - `resolve-slot!` - Pick winner, create booking, delete all requests
   - Deciders: `decide-first-come-first-serve`, `decide-by-priority`, `decide-random-lottery`

2. **[routes.clj](src/tilda/routes.clj)** - API endpoints (see docstring for full list)

3. **[calendar.clj](src/tilda/views/calendar.clj)** - Calendar UI components

## Configuration

Edit `config.edn`:

```edn
{:server {:port 8080}
 ;; Uncomment for persistent storage:
 ;; :storage-dir "data/xtdb"
}
```

## API Cheatsheet

```bash
# Create request
curl -X POST localhost:8080/requests \
  -H "Content-Type: application/json" \
  -d '{"tenant-name":"alice","start-date":"2026-03-01T00:00:00Z","end-date":"2026-03-03T23:59:59Z"}'

# List pending requests
curl localhost:8080/requests

# Resolve competing requests (admin)
curl -X POST localhost:8080/requests/resolve \
  -H "Content-Type: application/json" \
  -d '{"start-date":"2026-03-01T00:00:00Z","end-date":"2026-03-03T23:59:59Z","decider":"fcfs"}'

# Cancel request
curl -X DELETE localhost:8080/requests/{id}

# List bookings
curl localhost:8080/bookings

# Cancel booking
curl -X DELETE localhost:8080/bookings/{id}
```

## Development

```bash
# REPL experimentation
# Start REPL, then in dev/fiddle.clj:
(start!)       # Fresh XTDB node
(seed-data!)   # Sample data
(stop!)        # Cleanup
```

## Completed

- [x] Continuous calendar grid with infinite scroll
- [x] Cross-month drag selection
- [x] Sticky headers (page title + weekday labels)
- [x] Subtle month indicators on 1st of each month
- [x] Swappable auth (dev / Cloudflare Access / magic links)

## Authentication

Configured in `config.edn` via `:auth`:

```edn
;; Development (default) - no auth, stub user
{:auth {:strategy :dev}}

;; Cloudflare Access - magic link email auth handled externally
{:auth {:strategy :cloudflare}}

;; Magic links - personal URLs, zero friction for users
{:auth {:strategy :magic-link
        :users {"alice-xK9mP2" {:name "Alice" :email "alice@example.com"}
                "bob-qR7nL4"   {:name "Bob"   :email "bob@example.com"}}}}
```

Magic link users bookmark `https://car.example.com/u/alice-xK9mP2` - cookie persists 1 year.

## Not Yet Implemented

- [ ] Admin UI for resolving conflicts (API works, no UI)
- [ ] Request extension (drag to extend existing request)
- [ ] Deployment: Dockerfile for container builds
- [ ] Deployment: Read PORT/STORAGE_DIR from env vars
- [ ] Deployment: Add to [lab orchestrator](https://github.com/Schroedingberg/lab)

## Code Metrics

| File | Lines | Functions |
|------|-------|-----------|
| routes.clj | 346 | 25 |
| views/calendar.clj | 280 | 23 |
| sse.clj | 191 | 9 |
| booking.clj | 173 | 16 |
| core.clj | 115 | 7 |
| auth.clj | 96 | 5 |
| views/layout.clj | 87 | 5 |
| **Total** | **1288** | **90** |

## Architecture Decisions

See [.copilot-instructions.md](.copilot-instructions.md) for full context:
- Why XTDB (bitemporal audit trail)
- Why Datastar (server-driven reactivity, no frontend build)
- Why "existence = state" (simpler than status fields)
