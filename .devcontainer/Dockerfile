# Dev container for the memos-app Android / Kotlin Multiplatform build.
# Mirrors CI: JDK 25 (Temurin) + Android SDK. Gradle itself comes from the
# project wrapper (gradle-9.5.1), so it is not installed here.
FROM eclipse-temurin:25-jdk-noble

# --- Android SDK ---------------------------------------------------------
# compileSdk = 37 (androidx.core 1.19.0 requires API 37); AGP 9.2.1 picks
# build-tools 37.0.0. Keep these in sync with the *.build.gradle.kts files
# and .github/workflows/build.yml.
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=${ANDROID_HOME}
ENV ANDROID_USER_HOME=/home/vscode/.android

ARG CMDLINE_TOOLS_VERSION=14742923
ARG ANDROID_PLATFORM=platforms;android-37.0
ARG ANDROID_BUILD_TOOLS=build-tools;37.0.0

# Tools needed to fetch/unpack the SDK; git/zip are handy inside the container.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        wget unzip git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Install the Android command-line tools into the canonical
# ${ANDROID_HOME}/cmdline-tools/latest layout sdkmanager expects.
RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" \
    && cd /tmp \
    && wget -q "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" -O clt.zip \
    && unzip -q clt.zip \
    && mv cmdline-tools "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm clt.zip

ENV PATH=${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools

# Accept licenses and install the SDK packages the project compiles against.
RUN yes | sdkmanager --licenses > /dev/null \
    && sdkmanager --install \
        "platform-tools" \
        "${ANDROID_PLATFORM}" \
        "${ANDROID_BUILD_TOOLS}"

# --- Non-root user (matches the devcontainer "vscode" convention) --------
# The temurin base already occupies uid/gid 1000, so create vscode at 1001.
RUN groupadd --gid 1001 vscode \
    && useradd --uid 1001 --gid 1001 -m vscode \
    && mkdir -p "${ANDROID_USER_HOME}" \
    && chown -R vscode:vscode "${ANDROID_HOME}" /home/vscode

USER vscode
WORKDIR /workspaces/memos-app
