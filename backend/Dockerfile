# syntax=docker/dockerfile:1

# =============================================================================
# Spring Boot (Java 25 / Amazon Corretto) 本番用イメージ
# ビルドコンテキストは backend/ ディレクトリ。ECS 上での実行を想定。
#
# ベストプラクティス対応:
#   - ベースイメージ : Amazon Corretto 公式イメージを digest で pin
#   - ビルド         : マルチステージ（JDK/Gradle を本番イメージに残さない）
#   - バージョン     : タグ明示 + digest pin（再現性）
#   - キャッシュ     : Gradle キャッシュを BuildKit のキャッシュマウントで再利用
#   - 権限           : アプリケーションを root 所有にし、非 root ユーザーで実行
#   - Secrets        : ARG/ENV に秘密を置かない（本番は ECS タスク定義の secrets から
#                      Secrets Manager または SSM Parameter Store の値を注入）
#   - COPY/ADD       : すべて COPY
#   - CMD            : exec(JSON 配列)形式
#   - WORKDIR        : 絶対パス
#   - 1 コンテナ 1 concern : アプリのみ（DB/Redis は含めない）
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1: ビルド（JDK + Gradle）
# -----------------------------------------------------------------------------
FROM amazoncorretto:25-alpine3.22@sha256:7d93179da1c00e18ac3760c90f4ad1b7fc053cf358a6db29028fa5bb022d3043 AS build

WORKDIR /workspace

# 変更頻度の低い Gradle ラッパーとビルド定義を先にコピーする。
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle

# ソースより先に主な依存関係を解決し、ビルド時のダウンロードを抑える。
# 一回限りのタスクグラフなので Configuration Cache は生成しない。
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --no-configuration-cache dependencies > /dev/null

# ソースをコピーしてビルドする。テストは CI 側で実行する。
# Gradle のダウンロードキャッシュと Build Cache はキャッシュマウントで再利用する。
COPY src ./src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar -x test

# レイヤード jar を層ごとに展開し、依存関係とアプリケーションを分離する。
# Spring Boot 4 の既定では application/app.jar が Class-Path マニフェストで
# lib/ を相対参照するため、展開後も `java -jar app.jar` で起動できる。
RUN java -Djarmode=tools -jar build/libs/app.jar \
    extract --layers --destination extracted

# -----------------------------------------------------------------------------
# Stage 2: 実行（headless JRE）
# -----------------------------------------------------------------------------
FROM amazoncorretto:25-headless@sha256:770e291fe5d1bc20254c49489a6762095fa93937c11fa5e7408c3a3e4e04931e AS runtime

WORKDIR /app

# headless イメージには useradd/adduser がないため、固定 UID/GID を直接登録する。
RUN printf 'app:x:10001:\n' >> /etc/group \
    && printf 'app:x:10001:10001:Application user:/nonexistent:/sbin/nologin\n' >> /etc/passwd

# Spring Boot 公式例に合わせて四層を変更頻度の低い順にコピーする。
# root 所有のままコピーし、実行ユーザーによるアプリケーション改変を防ぐ。
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER 10001:10001

# EXPOSE は待ち受けポートを示すメタデータであり、ポート公開や SERVER_PORT の設定は行わない。
# ECS タスク定義では SERVER_PORT と containerPort をともに 8080 に設定する。
EXPOSE 8080

# コンテナ環境向けの JVM オプション（コンテナのメモリ制限を尊重）。
# JAVA_TOOL_OPTIONS は JVM が起動時に自動で読むため、shell 経由の変数展開が不要になり
# ENTRYPOINT を純粋な exec 形式に保てる。運用時は ECS 側で上書きできる。
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

# Actuator の readiness を検査し、プロセス生存だけでなく応答可能性を確認する。
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=10 \
    CMD ["curl", "--fail", "--silent", "--show-error", "http://127.0.0.1:8080/actuator/health/readiness"]

# exec(JSON 配列)形式。app.jar の Class-Path マニフェストが lib/ を解決する。
ENTRYPOINT ["java", "-jar", "app.jar"]
