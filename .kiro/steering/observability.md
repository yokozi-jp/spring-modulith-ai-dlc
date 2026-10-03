---
inclusion: fileMatch
fileMatchPattern: ["backend/src/main/resources/logback-spring.xml", "docker/otel-collector/**"]
name: observability
description: Logback の設定、Collector の設定、ログの出力、OpenTelemetry へ送る可観測性データを追加や変更するときに使う。ログのレベル、メッセージ、出力箇所、量の規約の入口を示す。
---

# 可観測性とログの入口

詳細は `docs/observability/index.md` から必要な文書だけ読む。

## 行動指針

- ログに値を足す前に、`docs/observability/conventions.md` の allowlist を確認する。
- Collector の設定を変えたら、`task otel-collector-check` を実行する。
- Java のロギングの書き方は `docs/backend/java-coding.md` に従う。
