# syntax=docker/dockerfile:1.7

FROM rust:1-alpine AS builder

WORKDIR /app

RUN apk add --no-cache ca-certificates cmake g++ make musl-dev perl pkgconf

ARG TARGETPLATFORM

# The Android JNI shim is a workspace member, so cargo refuses to load the
# workspace without its manifest and a lib source -- even though
# `default-members` keeps it out of every build in this image.
COPY Cargo.toml Cargo.lock rust-toolchain.toml ./
COPY crates/android-jni/Cargo.toml ./crates/android-jni/Cargo.toml

RUN --mount=type=cache,id=cargo-registry,target=/usr/local/cargo/registry \
    --mount=type=cache,id=cargo-git,target=/usr/local/cargo/git \
    --mount=type=cache,id=target-${TARGETPLATFORM},target=/app/target \
    mkdir -p src crates/android-jni/src && \
    printf 'fn main() {}\n' > src/main.rs && \
    printf '// lib\n' > src/lib.rs && \
    printf '// lib\n' > crates/android-jni/src/lib.rs && \
    cargo build --release --locked && \
    rm -rf src crates/android-jni/src

COPY src ./src
COPY crates ./crates

RUN --mount=type=cache,id=cargo-registry,target=/usr/local/cargo/registry \
    --mount=type=cache,id=cargo-git,target=/usr/local/cargo/git \
    --mount=type=cache,id=target-${TARGETPLATFORM},target=/app/target \
    cargo build --release --locked && \
    cp target/release/tg-ws-proxy /usr/local/bin/tg-ws-proxy

FROM scratch AS runtime

COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=builder /usr/local/bin/tg-ws-proxy /usr/local/bin/tg-ws-proxy

COPY <<EOF /etc/passwd
tgws:x:1000:1000:tg-ws-proxy user:/nonexistent:/sbin/nologin
EOF
COPY <<EOF /etc/group
tgws:x:1000:
EOF

USER 1000:1000
EXPOSE 1443

ENTRYPOINT ["tg-ws-proxy"]
