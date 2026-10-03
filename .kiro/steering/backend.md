---
inclusion: fileMatch
fileMatchPattern: ["backend/**"]
name: backend
description: バックエンド（Spring Boot、Spring Modulith、jOOQ）のコード、テスト、ビルド設定を追加や変更するときに使用する。機能モジュールの追加、クラスの配置、ロギング、アーキテクチャテストや PMD の失敗、テストの作成で読むべき docs を示す。
---

# バックエンドの docs への案内

詳細は `docs/backend/index.md` から必要な文書だけ読む。
規約の本文はここに書かず、docs を正とする。

## 行動指針

- クラスやパッケージを追加する前に、置き場所を docs で確認する。
- 既存の機能モジュールの構成と命名に合わせ、空のパッケージを先に作らない。
- 変更後はアーキテクチャテストと対象機能のテストを実行する。

## ケースごとに読む docs

- 新しい機能モジュールを作る：`docs/backend/runbook-add-feature.md`
- パッケージ構成、モジュール間の参照、ベースパッケージ直下の設定を扱う：`docs/backend/architecture.md`
- クラスを Domain、Application、Presentation、Infrastructure のどこに置くか決める：`docs/backend/layers.md`
- 新しいクラスを作る：`docs/backend/class-roles/index.md` で役割を選び、その役割の文書のチェックリストで点検する
- Lombok、record、ロギングの書き方を決める：`docs/backend/java-coding.md`
- ArchUnit、Spring Modulith の検証、PMD が失敗した：`docs/backend/architecture-tests.md`
- テストを書く、テストの種類を選ぶ：`docs/backend/testing-strategy.md`
- DB に書き込むテストやイベントのテストを書く：`docs/backend/testing-database.md`
- テストコードの命名や可視性で失敗した：`docs/backend/testing-code-style.md`
