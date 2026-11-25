# Web console built for this instance, served by Caddy together with the API
# and broker routes (deployment/local/Caddyfile).
FROM node:20-bookworm-slim AS console
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
# The console calls the API on its own origin, so the same build works at
# http://localhost:8088, on the local network and through a tunnel.
ARG VITE_SUPABASE_URL
ARG VITE_SUPABASE_PUBLISHABLE_KEY
ENV VITE_BACKEND_URL=same-origin \
    VITE_SUPABASE_URL=$VITE_SUPABASE_URL \
    VITE_SUPABASE_PUBLISHABLE_KEY=$VITE_SUPABASE_PUBLISHABLE_KEY
RUN npm run build

FROM caddy:2
COPY deployment/local/Caddyfile /etc/caddy/Caddyfile
COPY --from=console /src/frontend/dist /srv/console
