# Web API

- [Web APIの方式とURLの設計](api-style.md)：新しいAPIのエンドポイントやパスを設計するとき、operationIdを付けるとき、RESTで表しにくい操作を追加するとき
- [HTTPメソッドの使い分け](http-methods.md)：エンドポイントにHTTPメソッドを割り当てるとき、更新系の応答や再試行への備えを決めるとき
- [クエリパラメータ](query-parameters.md)：検索APIや一覧APIのパラメータを設計するとき
- [APIのリクエストヘッダーとレスポンスヘッダー](headers.md)：APIにヘッダーを追加するとき、応答のキャッシュの扱いを確かめるとき
- [レスポンスボディの形式](response-body.md)：APIの応答スキーマを設計するとき、区分値の返し方を決めるとき
- [HTTPステータスコードの選択](status-codes.md)：成功応答とエラー応答のステータスコードを決めるとき、OpenAPIにエラー応答を書くとき
- [APIの入力検証の配置](validation.md)：APIのリクエストに検証を追加するとき、フロントエンドとの検証の分担を決めるとき
- [更新の競合制御](optimistic-locking.md)：同時に更新されうるリソースの更新APIや削除APIを設計するとき
- [ファイルのアップロードとダウンロード](file-transfer.md)：画像や文書などのファイルを扱う機能を設計するとき
- [APIの認証、セッション、権限](authentication-and-session.md)：認証方式やセッション設定を変えるとき、対向システムへAPIを公開するとき、権限の判定を実装するとき
- [APIの互換性と廃止](versioning.md)：既存APIの契約を変更するとき、APIを廃止するとき
- [入口とAPIの機能配置](edge-responsibilities.md)：リバースプロキシやCDNの構成を決めるとき、レート制限やタイムアウトを設定するとき
- [APIのデプロイ環境とデプロイ方式](deployment-environments.md)：デプロイ環境のドメインを決めるとき、APIのリリース方式を決めるとき
- [APIの失敗のログレベルと監視](logging-and-monitoring.md)：APIのエラー処理でログを出すとき、APIの監視と通知の条件を決めるとき
