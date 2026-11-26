# Flag Circuit Breaker

**Automatic kill switch for feature flags.** If a flag you just switched on makes errors jump,
Flag Circuit Breaker switches it back off in [Flagsmith](https://www.flagsmith.com), on its own,
within seconds.

Built for Toggle Wars (WeMakeDevs × Flagsmith), Track 03: self-hosted Flagsmith.

## The problem

Feature flags let a team ship new code switched off and turn it on later, without a redeploy.
But when the new feature is broken, real users hit errors from the moment the flag goes on
until a person notices, works out which flag caused it, and switches it off again.
That can take minutes, or hours if it happens at night.

## What it does

1. A demo shop reads the flag `new_checkout` from Flagsmith. When the flag is on, checkouts go
   through a new checkout that has a planted bug, so about half of them fail.
2. The breaker watches the shop's error rate. If the flag was switched on recently and errors go
   over a threshold, it switches the flag **off** through the Flagsmith admin API.
3. It prints an alert with the time, the flag, and the error rate before and after.
4. A live page shows the error rate as a graph, the flag's state, and a red
   **BREAKER TRIPPED** banner when the breaker fires. A button sends fake traffic, so the demo
   needs no other tool.

Because the flag is switched off in Flagsmith itself, every app that reads that flag is
protected, not only the one that was being watched.

## Architecture

```
                 reads the flag every 2 s (environment key)
   ┌───────────┐ ─────────────────────────────────────────► ┌──────────────────────┐
   │ Demo shop │                                             │ Flagsmith            │
   │ (Java,    │                                             │ self-hosted in Docker│
   │ port 8080)│                                             │ (port 8000)          │
   └───────────┘                                             └──────────────────────┘
     ▲      │ /metrics (error rate in the last 10 s)                   ▲
     │      ▼                                                          │
     │   ┌─────────┐  switches the flag OFF (admin API, PATCH)         │
     │   │ Breaker │ ──────────────────────────────────────────────────┘
     │   │ (Java)  │
     │   └─────────┘
     │        │ reports the trip (POST /breaker-event)
     │        ▼
   ┌──────────────────────────────┐
   │ Live page (HTML/CSS/JS),     │  polls /metrics every second
   │ served by the shop at /      │
   └──────────────────────────────┘
```

- **`shop/ShopApp.java`**: Java's built-in `HttpServer`. `/checkout` uses the old or the new
  checkout depending on the flag; `/metrics` returns the error count and rate in a sliding window
  as JSON.
- **`breaker/Breaker.java`**: a polling loop with Java's built-in `HttpClient`. It trips when all
  of these are true: the flag is on, it was switched on less than `WATCH_SECONDS` ago, the window
  holds at least `MIN_REQUESTS` checkouts, and the error rate is over `ERROR_THRESHOLD`.
- **`web/index.html`**: plain HTML, CSS and JavaScript, with no libraries.

No frameworks and no build tools: plain Java (JDK 11 or newer runs each file directly),
plain HTML/CSS/JS, and Docker for Flagsmith.

## How to run it (3 steps)

You need Docker Desktop and Java 11 or newer.

**1. Start Flagsmith and create the flag.** Follow [SETUP.md](SETUP.md): `docker compose up -d`,
sign up at http://localhost:8000, create the flag `new_checkout` (off), then copy `.env.example`
to `.env` and paste in your two keys.

**2. Start the shop and the breaker** in two terminals, both from this folder:

```bash
java shop/ShopApp.java
```

```bash
java breaker/Breaker.java
```

**3. Open http://localhost:8080**, click **Start fake traffic**, then switch `new_checkout`
on in the Flagsmith dashboard. Within a few seconds the bars go red, the breaker switches the
flag off, and the red banner appears.

## How Flagsmith is used

- **Self-hosted:** Flagsmith runs on your own machine from `docker-compose.yml`, which is
  Flagsmith's official file with its ports bound to `127.0.0.1`.
- **Environment key (read):** the shop calls `GET /api/v1/flags/` with the header
  `X-Environment-Key` to read `new_checkout`.
- **Admin API key (write):** the breaker sends
  `PATCH /api/v1/environments/{environment key}/featurestates/{id}/` with the body
  `{"enabled": false}` and the header `Authorization: Api-Key <key>` to switch the flag off.

## Settings

Everything is set in `.env` (see `.env.example`). `.env` is listed in `.gitignore`, so keys
never reach git.

| Setting | Default | Meaning |
|---|---|---|
| `FLAGSMITH_URL` | `http://localhost:8000` | Where Flagsmith runs |
| `FLAGSMITH_ENV_KEY` | | Environment key (reads flags) |
| `FLAGSMITH_ADMIN_KEY` | | Admin API key (switches the flag off) |
| `FLAG_NAME` | `new_checkout` | The flag the breaker guards |
| `ERROR_THRESHOLD` | `0.3` | Trip above this error rate (0.3 = 30%) |
| `MIN_REQUESTS` | `10` | Ignore the rate until the window has this many requests |
| `WATCH_SECONDS` | `120` | Only trip within this long after the flag was switched on |
| `CHECK_EVERY_SECONDS` | `2` | How often the breaker reads `/metrics` |
| `WINDOW_SECONDS` | `10` | Errors are counted over this many seconds |
| `NEW_CHECKOUT_FAIL_RATE` | `0.5` | How often the planted bug fails (demo only) |
| `SHOP_URL` | `http://localhost:8080` | Where the breaker finds `/metrics` |

## Why this exists

Flagsmith's own blog post
[Automatic Rollback: What It Is and How to Do It Safely](https://www.flagsmith.com/blog/automatic-rollback)
describes automatic rollback as a workflow teams set up themselves: you define the trigger, such
as an error rate crossing a threshold, and wire your monitoring to it. Flag Circuit Breaker is a
small, working example of that pattern for one flag.

## Limits

- It watches one flag in one environment.
- The error rate comes from the demo shop's `/metrics`. A real app would feed it from its own
  monitoring instead.
- The JSON is read with simple text search, not a JSON library, to keep it dependency-free.
