FROM eclipse-temurin:11.0.26_4-jdk-jammy

WORKDIR /workspace

RUN rm -rf /var/lib/apt/lists/* \
    && apt-get -o Acquire::Retries=3 -o Acquire::http::No-Cache=true update \
    && apt-get -o Acquire::Retries=3 install -y --no-install-recommends \
        build-essential \
        curl \
    && rm -rf /var/lib/apt/lists/*
