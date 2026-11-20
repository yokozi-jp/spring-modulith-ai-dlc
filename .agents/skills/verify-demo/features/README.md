# デモアプリケーションの検証の機能一覧

このディレクトリは、デモアプリケーションの利用者に見える振る舞いを、検証スタックで確かめる手順を持つ。
アプリを操作する前にこの一覧を読み、該当する機能のファイルを手順として使う。
アプリの振る舞いと E2E の規約の正文は、コードと `docs/e2e/testing-strategy.md` にある。
手順と食い違ったら、手順を直す。

## 共通の前提

- `stack.sh up` で起動した検証スタック（`http://localhost:<web のポート>`、slot 0 は 5173）だけを操作する。
- journey の前に `stack.sh doctor` を実行し、すべて OK であることを確かめる。
- 代表データと、journey が作るデータの扱いは `docs/e2e/testing-strategy.md` の「テストデータ」に従う。
  客先注文番号は `orders.uniqueCode("VERIFY")` で作る。

## 操作の決まり

- 文言は `frontend/apps/web/src/i18n/locales/ja.json` から取る。
- 決済代行の応答の切り替えは `docs/e2e/testing-strategy.md` の「外部システムの偽物」に従い、`gateway.failChargesFor` で注文ごとに足す。
  journey の終わりに、返された関数で取り除く。

## 証明と省いた入口の報告

- 更新の証明には、画面とは別の見え方（`sql` の行、`gateway.chargeCount`、一覧の画面）を添える。
- 証拠には、確かめた機能の ID と使った入口を書く。
- 届かなかった入口は、試したコマンドと満たせなかった前提を報告する。
- 省いた入口を、別の入口で確かめたことにしない。

## 機能ファイルの形

各ファイルは H1 の題と、利用者に見える振る舞いを述べる 1 段落で始め、次の 4 つの H2 をこの順に持つ。

1. `Sub-features`：短い ID と、振る舞いを 1 行ずつ
2. `How to get to it (user POV)`：利用者の入口のすべて
3. `Driving it with drive.mjs`：`Preconditions:` で始め、操作、コマンド、見える結果を対にした箇条書き
4. `Gotchas`：検証を無駄にする、または無効にする落とし穴

## 機能

- [ログインとログアウト](./login-logout.md)：Keycloak の画面からのログイン、ヘッダーのログアウト、ログアウト後の画面
- [注文の一覧と絞り込み](./order-list.md)：一覧、状態の絞り込み、詳細への移動
- [注文の作成](./order-create.md)：作成の画面、入力の誤り、明細の追加と削除
- [注文の確定と決済](./order-payment.md)：確定、Listener の請求、決済済み、拒否、一時障害の再投入待ち
- [明細の変更と取り消し](./order-edit.md)：下書きの明細の保存、楽観的ロックの競合と回復、取り消し
