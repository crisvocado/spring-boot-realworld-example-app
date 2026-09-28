FROM eclipse-temurin:11-jdk-jammy

WORKDIR /workspace

RUN rm -rf /var/lib/apt/lists/* && \
    apt-get -o Acquire::Retries=3 -o Acquire::http::No-Cache=true update && \
    apt-get -o Acquire::Retries=3 -y --no-install-recommends install \
        build-essential \
        ca-certificates \
        curl && \
    rm -rf /var/lib/apt/lists/*
