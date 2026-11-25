FROM rust:1.88-bookworm AS builder

WORKDIR /src/backend
COPY backend/Cargo.toml backend/Cargo.lock ./
COPY backend/src ./src
COPY backend/contracts ./contracts
COPY backend/processors ./processors
COPY backend/models ./models

# Recorded in dataset exports, so an export names the code that made it.
ARG GIT_SHA=unknown
ENV GIT_SHA=$GIT_SHA

RUN cargo build --release

FROM debian:bookworm-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /src/backend/target/release/wearable-backend /usr/local/bin/wearable-backend

ENV RUST_LOG=info
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/wearable-backend"]
