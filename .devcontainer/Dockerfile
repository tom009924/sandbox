FROM mcr.microsoft.com/devcontainers/java:1-21-bookworm

USER root
RUN rm -f /etc/apt/sources.list.d/yarn.list /etc/apt/sources.list.d/yarn*.list

USER vscode
RUN bash -c "source /usr/local/sdkman/bin/sdkman-init.sh \
    && sdk install kotlin 1.9.24 \
    && sdk install gradle 8.10.2 \
    && sdk flush temp \
    && rm -rf /usr/local/sdkman/tmp/*" \
    && bash -c "source /usr/local/share/nvm/nvm.sh \
    && nvm install 20 \
    && nvm alias default 20"

ENV PATH="/usr/local/sdkman/candidates/kotlin/current/bin:${PATH}"
