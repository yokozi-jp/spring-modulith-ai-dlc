# バックエンド

- [バックエンドアーキテクチャ](architecture.md)：機能モジュールを追加するとき、パッケージ構成、モジュールルートの公開契約、依存方向、ベースパッケージ直下の設定を確認や変更するとき
- [バックエンドの層の責務](layers.md)：クラスを Domain、Application、Presentation、Infrastructure のどこに置くか決めるとき、トランザクション境界や Adapter の分け方を決めるとき
- [バックエンドの Java 実装規約](java-coding.md)：Lombok、record、Domain Model の状態変更、ロギングの書き方を決めるとき
- [バックエンドのアーキテクチャテスト](architecture-tests.md)：ArchUnit、Spring Modulith の検証、PMD が失敗したとき、検査規則を追加や変更するとき
- [バックエンドの機能追加時の確認](runbook-add-feature.md)：新しい機能モジュールを作る手順と、追加後に実行する検証コマンドを確認するとき
- [バックエンドのテスト方針と種類の選び方](testing-strategy.md)：テストを新しく書くとき、テストの種類や実行コマンド、失敗時のメッセージ、非同期待機を決めるとき
- [バックエンドの DB テスト](testing-database.md)：DB に書き込むテスト、コミット時の挙動、Spring Modulith のイベントを検証するテストを書くとき
- [バックエンドのテストコードの書き方](testing-code-style.md)：テストコードが PMD や ArchUnit で失敗したとき、テストクラスの命名や可視性を決めるとき
