FROM mcr.microsoft.com/devcontainers/java:1-21-bookworm

USER vscode

ENV SDKMAN_DIR="/home/vscode/.sdkman"

RUN bash -o pipefail -c 'curl -fsSL https://get.sdkman.io | bash' \
    && bash -c 'source "$SDKMAN_DIR/bin/sdkman-init.sh" && sdk install kotlin 1.9.24 && sdk install gradle 8.10.2'

ENV PATH="${SDKMAN_DIR}/candidates/kotlin/current/bin:${SDKMAN_DIR}/candidates/gradle/current/bin:${PATH}"
