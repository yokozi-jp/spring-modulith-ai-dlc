# バックエンド

- [バックエンドアーキテクチャ](architecture.md)：機能モジュールを追加するとき、パッケージ構成、モジュールルートの公開契約、依存方向、ベースパッケージ直下の設定を確認や変更するとき
- [バックエンドの層の責務](layers.md)：クラスをDomain、Application、Presentation、Infrastructureのどこに置くか決めるとき、トランザクション境界やAdapterの分け方を決めるとき
- [クラスの役割](class-roles/index.md)：新しいクラスを作るとき、既存クラスの役割、形、依存、テストを確かめるとき
- [バックエンドのJava実装規約](java-coding.md)：Lombok、record、Domain Modelの状態変更、ロギングの書き方を決めるとき
- [バックエンドのアーキテクチャテスト](architecture-tests.md)：Spring Modulith、ArchUnit、PMD、Error Proneの検査実装や解析対象を確認するとき
- [バックエンドの機能追加時の確認](runbook-add-feature.md)：新しい機能モジュールを作る手順と、追加後に実行する検証コマンドを確認するとき
- [バックエンドのテスト種別](testing-strategy.md)：新しいテストで検証対象に合う最小のテスト種別を選ぶとき
- [バックエンドのDBテスト](testing-database.md)：DBテストの隔離、ロールバック、コミット時の挙動、Spring Modulithのイベント検証を決めるとき
- [バックエンドのテストコードの書き方](testing-code-style.md)：テストの命名、可視性、Springコンテキスト、失敗診断、非同期待機の書き方を確認するとき
