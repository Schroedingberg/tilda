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

## Not Yet Implemented

- [ ] Auth (stub in `auth.clj`, ready for Oak integration)
- [ ] Admin UI for resolving conflicts (API works, no UI)
- [ ] Request extension (drag to extend existing request)

## Architecture Decisions

See [.copilot-instructions.md](.copilot-instructions.md) for full context:
- Why XTDB (bitemporal audit trail)
- Why Datastar (server-driven reactivity, no frontend build)
- Why "existence = state" (simpler than status fields)
