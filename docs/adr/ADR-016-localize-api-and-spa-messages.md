# ADR-016: API と SPA のメッセージをローカライズする

## Status

Proposed

## Date

2026-09-15

## Context

バックエンドの Problem Details は英語の reason phrase を固定で返し、Bean Validation 用のアプリケーションメッセージもない。
SPA も英語文言を画面へ直書きし、HTML の `lang` を `en` に固定している。

API クライアントは RFC 9457 の `type` で問題種別を判定する契約であり、`title` と `detail` は人が読む文章として変更できる。
言語選択を機械判定と混ぜず、日本語と英語の fallback、HTTP cache、画面の言語属性を揃える必要がある。

## Decision

初期対応言語を日本語（`ja`）と英語（`en`）とし、既定言語を日本語にする。

バックエンドは Spring の `AcceptHeaderLocaleResolver` を使い、`Accept-Language` の優先順位と quality value に従う。
`ja-JP` や `en-US` などは、明示した language-only locale へ fallback する。
ヘッダがない場合と対応言語がない場合は日本語を選ぶ。
URL parameter、Cookie、server の既定 locale では言語を切り替えない。

バックエンドの文言は Spring Boot が自動構成する `MessageSource` へ置く。
基底 bundle を英語、`messages_ja.properties` を日本語とし、system locale への fallback を無効にする。
Bean Validation の業務向け制約は `{validation.required}` のような key を指定し、入力値をメッセージへ埋め込まない。

Problem Details の `type`、`status` と拡張フィールド名はロケールで変えない。
一般的な HTTP エラーの `title` と、人が読む `detail`、検証エラーの説明だけを翻訳する。
クライアントは引き続き `type` で分岐し、翻訳文を分岐条件にしない。
応答には選択した locale の `Content-Language` と `Vary: Accept-Language` を付ける。

SPA は新しい依存を追加せず、TypeScript の型付き message catalog と `Intl` を使う。
`navigator.languages` を優先順に解決し、language subtag が `ja` または `en` に一致しなければ日本語へ fallback する。
選択結果を `document.documentElement.lang` と画面 title に反映する。
日時、数値、複数形は翻訳済みの固定文字列へ変換せず、表示境界で `Intl.DateTimeFormat`、`Intl.NumberFormat`、必要なら `Intl.PluralRules` を使う。

ブラウザ設定を使う間は、API request の `Accept-Language` を JavaScript で上書きしない。
将来、アプリ内の言語選択を追加する場合は、その選択を SPA と API の双方へ同じ規則で反映する。

SPA が Problem Details を扱う際は、既知の `type` をローカル message key へ写像する。
未知の `type` は一般エラーとして扱い、サーバーの `detail` をそのまま HTML へ挿入しない。
現在は API client が存在しないため、未使用の parser は先に作らず、最初の API client と同時にこの規則を実装する。

## Consequences

### Positive

- API と SPA が同じ二言語と fallback 規則を使える。
- Problem Details の機械判定を安定させたまま、人が読む文章を翻訳できる。
- 追加ライブラリなしで現在の文言量に対応できる。
- `Content-Language` と `Vary` により、client と HTTP cache が応答言語を区別できる。

### Negative

- message key と各言語の catalog を同じ変更で更新する必要がある。
- SPA の catalog は ICU MessageFormat を持たず、複雑な文法や翻訳管理が必要になれば移行が必要になる。
- 既定言語を日本語にするため、`Accept-Language` を送らない既存テストと client の表示文言が変わる。

### Neutral

- API の `type` URI と OpenAPI schema はローカライズしない。
- 業務固有の検証 problem type と `errors` schema は、最初の業務 API と管理ドメインが確定した時点で ADR-013 に従って追加する。
- 翻訳の追加は API version を上げる変更ではない。

## Alternatives Considered

### Alternative 1: API は英語だけにする

- Description：SPA だけを翻訳し、バックエンドは英語の Problem Details と検証文言を返す。
- Pros：バックエンドの bundle と locale 解決が不要になる。
- Cons：直接表示する検証文言と API エラーが画面言語と一致せず、client 側で全エラーを再定義する必要がある。

### Alternative 2: i18n ライブラリを導入する

- Description：SPA に ICU MessageFormat と動的 catalog 読み込みを持つライブラリを追加する。
- Pros：複雑な複数形、翻訳管理、遅延ロードへ拡張しやすい。
- Cons：現在は静的な二言語と少数文言だけであり、標準 `Intl` と型付き object で足りる。

### Alternative 3: 翻訳済み `detail` でエラーを分類する

- Description：SPA が Problem Details の文章を比較して画面を分岐する。
- Pros：追加の problem type を定義せずに実装できる。
- Cons：翻訳と文言修正が client の制御フローを壊し、ADR-013 の契約にも反する。

## References

- [RFC 9110: Accept-Language](https://www.rfc-editor.org/rfc/rfc9110.html#name-accept-language)
- [RFC 9110: Content-Language](https://www.rfc-editor.org/rfc/rfc9110.html#name-content-language)
- [RFC 9457: Problem Details for HTTP APIs](https://datatracker.ietf.org/doc/html/rfc9457)
- [Spring Framework: `AcceptHeaderLocaleResolver`](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/i18n/AcceptHeaderLocaleResolver.html)
- [MDN: `Intl`](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Intl)
- [`ADR-013`](./ADR-013-standardize-http-api-contracts.md)
- `backend/src/main/resources/application.yaml`
- `frontend/src/main.ts`
