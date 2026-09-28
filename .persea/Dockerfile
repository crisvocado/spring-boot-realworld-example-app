FROM eclipse-temurin:11-jdk

WORKDIR /workspace

RUN rm -rf /var/lib/apt/lists/* \
    && apt-get -o Acquire::Retries=3 -o Acquire::http::No-Cache=true update \
    && apt-get -o Acquire::Retries=3 install -y --no-install-recommends \
        build-essential \
        ca-certificates \
        curl \
        git \
    && rm -rf /var/lib/apt/lists/*
