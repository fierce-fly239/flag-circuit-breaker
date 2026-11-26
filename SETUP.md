# Setup: self-hosted Flagsmith

Flag Circuit Breaker needs a Flagsmith server. This project runs Flagsmith on your own machine in Docker.

## 1. Start Flagsmith

Install Docker Desktop and open it. Then, in this folder, run:

```bash
docker compose up -d
```

The first start downloads the images and sets up the database, so it can take a few minutes.
Flagsmith is ready when http://localhost:8000 shows the sign-up page.

## 2. Create the flag

1. Sign up at http://localhost:8000. The first account becomes the admin.
2. Create an organisation, then a project with an environment called `Development`.
3. In that project, create a flag called `new_checkout` and leave it **off**.

## 3. Get the two keys

1. Copy `.env.example` to `.env`.
2. **Environment key** (read flags): open the `Development` environment, go to Settings > SDK Keys, and paste the key into `FLAGSMITH_ENV_KEY`.
3. **Admin API key** (change flags): open Organisation Settings > API Keys, create a key, and paste it into `FLAGSMITH_ADMIN_KEY`.

`.env` is listed in `.gitignore`, so your keys never reach git.

## Stop and restart

- Stop without losing data: `docker compose stop`
- Start again: `docker compose start`
- Delete everything, including the flags: `docker compose down -v`
