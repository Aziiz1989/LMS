FROM arm64v8/clojure:tools-deps

# Install development tools
RUN apt-get update && apt-get install -y \
    rlwrap \
    git \
    && rm -rf /var/lib/apt/lists/*

RUN apt update && apt install -y curl

WORKDIR /usr/src/app

# Pre-download dependencies (will be overridden by volume mount in dev)
COPY deps.edn ./
RUN clojure -P

# Copy source (overridden by volume in development)
COPY . .

# Expose nREPL port for editor connectivity
EXPOSE 7888

# Default command: start a REPL with nREPL server
CMD ["clojure", "-M:dev"]
