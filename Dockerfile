# Stage 1: Get Typst binary
FROM ghcr.io/typst/typst:latest AS typst

# Stage 2: Shared base
FROM arm64v8/clojure:tools-deps AS base

COPY --from=typst /bin/typst /usr/local/bin/typst

WORKDIR /usr/src/app

# Pre-download dependencies (will be overridden by volume mount in dev)
COPY deps.edn ./
RUN clojure -P

COPY . .

# ── Dev stage ──
FROM base AS dev

RUN apt-get update && apt-get install -y \
    rlwrap \
    git \
    curl \
    unzip \
    && rm -rf /var/lib/apt/lists/*

# Install Babashka
RUN curl -sL https://raw.githubusercontent.com/babashka/babashka/master/install | bash

# Install bbin
RUN curl -o- -L https://raw.githubusercontent.com/babashka/bbin/v0.2.5/bbin > /usr/local/bin/bbin && \
    chmod +x /usr/local/bin/bbin

ENV PATH="$PATH:/root/.local/bin"

# Install clojure-mcp-light tools
RUN bbin install https://github.com/bhauman/clojure-mcp-light.git 
RUN bbin install https://github.com/bhauman/clojure-mcp-light.git  \
    --as clj-nrepl-eval --main-opts '["-m" "clojure-mcp-light.nrepl-eval"]'
RUN bbin install https://github.com/bhauman/clojure-mcp-light.git  \
    --as clj-paren-repair --main-opts '["-m" "clojure-mcp-light.paren-repair"]'

EXPOSE 7888

CMD ["clojure", "-M:dev"]

# ── Prod stage ──
FROM base AS prod

CMD ["clojure", "-M:run"]
