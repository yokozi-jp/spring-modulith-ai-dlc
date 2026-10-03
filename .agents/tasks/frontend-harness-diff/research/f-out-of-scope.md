# T3 の調査：frontend 以外の二つの気づき

旧リポジトリとの比較の途中で見つけた、frontend の外にある二つの論点を一次情報で調べた結果である。
一つは `docs/web-api/headers.md` の cache の例示と `docs/web-api/response-body.md` の区分値の方針の読み合わせ、もう一つは Spring Security の `csrf.spa()` が OWASP のどの方式に当たり、現構成で実際にリスクがあるかである。

各主張には「確認済み」（一次情報やファイルの行で確かめた）か「推測」（確かめた事実からの判断で、実行や実機では確かめていない）を付けた。
版を固定できる情報源は commit または tag を固定したリンクにした。
Spring Security は Spring Boot 4.1.1 が管理する 7.1.1 を対象にした（確認済み、[spring-boot-dependencies-4.1.1.pom](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom) の `spring-security.version`、`backend/build.gradle` の 16 行目）。

## 1. headers.md の cache の例示と response-body.md の区分値

### 標準

RFC 9111 は、cache が応答を保存してよい条件と、`private` と `public` の意味を次のように定める。

- **private**：共有 cache（CDN や proxy）は保存してはならず、利用者ごとの private cache（ブラウザ）は保存してよい（確認済み、[RFC 9111 の 5.2.2.7](https://www.rfc-editor.org/rfc/rfc9111#section-5.2.2.7)）。
  `private` は保存場所を制御するだけで、内容の秘匿は保証しないと注記している（確認済み、同節の Note）。
- **public**：保存が禁じられる条件の一部を外し、明示的に cacheable にする。
  例として、`Authorization` 付きの要求への応答を共有 cache が再利用することを許す（確認済み、[RFC 9111 の 5.2.2.9](https://www.rfc-editor.org/rfc/rfc9111#section-5.2.2.9)）。
- **認証付きの要求**：共有 cache が再利用を控える規定は、要求に `Authorization` header がある場合に限られる。
  `public`、`s-maxage`、`must-revalidate` のどれかがあれば、その制限は外れる（確認済み、[RFC 9111 の 3.5](https://www.rfc-editor.org/rfc/rfc9111#section-3.5)、[3 章の保存条件](https://www.rfc-editor.org/rfc/rfc9111#section-3)）。
  cookie で認証した要求について、同等の規定はない（確認済み、3 章と 3.5 の条件に cookie は現れない）。
- **Set-Cookie**：`Set-Cookie` は caching を妨げず、それを含む応答も再利用されうる。
  制御したい server は `Cache-Control` を付けるよう勧めている（確認済み、[RFC 9111 の 7.3](https://www.rfc-editor.org/rfc/rfc9111#section-7.3)）。

Spring Security は既定で `Cache-Control: no-cache, no-store, max-age=0, must-revalidate` を付け、application が自分で cache の header を付けた応答では引き下がる（確認済み、[Spring Security 7.1 の Cache Control](https://docs.spring.io/spring-security/reference/7.1/features/exploits/headers.html#headers-cache-control)、[CacheControlHeadersWriter.java の 59 から 63 行目](https://github.com/spring-projects/spring-security/blob/7.1.1/web/src/main/java/org/springframework/security/web/header/writers/CacheControlHeadersWriter.java#L59-L63)）。

### ベストプラクティス

- **Zalando**：client 側と経路上の HTTP cache は、負荷の高い master data のサービスのように、service が自分を守るために必要とする場合を除いて避ける。
  既定は `no-store` とし、cache させる endpoint は `Cache-Control`、`Vary`、`ETag` を API 定義に書く。
  OAuth で認可する endpoint の既定値として `Cache-Control: private, must-revalidate, max-age=300` を示し、`Expires` は付けない（確認済み、[Zalando RESTful API Guidelines の #227](https://github.com/zalando/restful-api-guidelines/blob/19a1905ad50ad71968c473009b157fd9245f5c66/chapters/performance.adoc#L209-L305)）。
  HTTP 層の cache より、service または gateway の層に cache を置くことを強く勧めている（確認済み、同ファイルの Caching strategy）。
- **Microsoft（Azure）**：cache と楽観ロックのために `ETag` と `If-None-Match` などの条件付き要求に従い、一致すれば `304` を返す。
  `Cache-Control` の細かい制御は HTTP の仕様に委ねている（確認済み、[Azure REST API Guidelines の Conditional Requests](https://github.com/microsoft/api-guidelines/blob/a7022a299442a8352431874e63ec4dff548a1b81/azure/Guidelines.md#conditional-requests)）。
- **Google AIP**：`Cache-Control` を扱う AIP は見つからなかった（確認済み、[google.aip.dev](https://github.com/aip-dev/google.aip.dev/tree/23e176e7333ea3bc6b085f9950a5da03d2bbfc72/aip) の全文を `cache-control` で検索して該当なし）。
- **フューチャーのガイドライン（headers.md の出典）**：既定は `no-store` とし、CDN に保存させないには `private` 以上が要り、共有端末を考えてブラウザにも保存させないと理由を書いている（確認済み、[web_api_guidelines.md の 1168 から 1178 行目](https://github.com/future-architect/arch-guidelines/blob/e309a6d/documents/forWebAPI/web_api_guidelines.md?plain=1#L1168-L1178)）。

### アンチパターン

- **cookie で認証する応答に `public` や `s-maxage` を付ける**：RFC 9111 3.5 の保護は `Authorization` にしか効かないので、共有 cache が保存した応答を、session を持たない別の要求へ返しうる（推測、RFC 9111 の 3 章と 3.5 からの帰結で、CDN ごとの cache key の既定は確かめていない）。
- **`Set-Cookie` を含む応答を共有 cache に保存させる**：RFC 9111 7.3 のとおり再利用されうる。
  `csrf.spa()` は token の cookie が無い要求の応答で新しい `XSRF-TOKEN` を設定するので、共有 cache を許した API の応答に一人分の token が乗る可能性がある（推測、[前回の調査 a-data-api.md の C2](a-data-api.md) の source の読みからの帰結）。
- **「利用者全員に同じ内容」を「認証なしで渡してよい」と同一視する**：前者は private cache で足り、後者は共有 cache の条件である。
  業務システムは利用者ごとに権限が異なり、GET の cache が基本的に使えないとフューチャーのガイドラインも書いている（確認済み、[web_api_guidelines.md の 526 行目](https://github.com/future-architect/arch-guidelines/blob/e309a6d/documents/forWebAPI/web_api_guidelines.md?plain=1#L526)）。
- **cache の header を全体で無効化する**：Zalando も Spring Security の既定を保つ例を挙げている（確認済み、#227）。
  `headers.md` の 45 行目がすでに禁じている。

### デファクトスタンダード

区分値、参照データ、マスタの扱いは次のように分かれる。

- **小さく変更の少ない値の集合は契約に入れる**：Google AIP-126 は、新しい値の追加がまれ（目安は年に一度以下）なら enum にし、頻繁に変わるなら string にして値を文書化する（確認済み、[AIP-126](https://github.com/aip-dev/google.aip.dev/blob/23e176e7333ea3bc6b085f9950a5da03d2bbfc72/aip/general/0126.md)）。
  Microsoft は、値が増えうる集合を extensible enum として定義し、client に未知の値を想定させる（確認済み、[Azure REST API Guidelines の Enums & SDKs](https://github.com/microsoft/api-guidelines/blob/a7022a299442a8352431874e63ec4dff548a1b81/azure/Guidelines.md#enums--sdks-client-libraries)）。
  どちらも、値の一覧を取得する API を求めていない（確認済み、両文書の該当節）。
- **区分値の一覧は frontend に持つ**：フューチャーのガイドラインは区分値を参照データとも呼び、モバイルアプリが無ければ frontend に持つ案を推奨している（確認済み、[web_api_guidelines.md の 1208 から 1225 行目](https://github.com/future-architect/arch-guidelines/blob/e309a6d/documents/forWebAPI/web_api_guidelines.md?plain=1#L1208-L1225)）。
- **運用で増減するマスタは API で返す**：Zalando が HTTP cache を認める例は、変更がまれで参照の多い master data のサービスである（確認済み、#227）。
  現リポジトリも区分値とマスタを分けている。
  区分値は名称との対応をソースコードか定義ファイルで管理してテーブルを作らず、マスタは顧客や商品のような参照データのテーブルである（確認済み、`docs/database/postgresql-logical-design.md` の 22 行目と 44 から 48 行目）。

### 現リポジトリへの当てはめ

**食い違いの有無**

両文書の該当行は次のとおりである。

- `docs/web-api/headers.md` の 47 行目：「区分値のように利用者間で共有できる応答に限り、APIごとに判断してキャッシュを許可する。」
- `docs/web-api/response-body.md` の 62 行目：「区分値の一覧と表示名はフロントエンドが持ち、区分値を取得するAPIを作らない。」
- 同 66 から 69 行目：公開 API とモバイルアプリの場合は、この方針を見直す。

論理の矛盾は無い。
`headers.md` は条件付きの許可であり、区分値の API は `response-body.md` の見直し条件のもとでだけ存在しうるからである。
しかし、例示は既定の方針では存在しない API を指している。
cache を許可するか決める読者に使えない例を渡し、区分値の API があるかのように読ませる。
この食い違いは出典に由来する。
フューチャーのガイドライン自体が、cache の例に「区分値取得API」を挙げ（1177 行目）、別の章で区分値を frontend に持つ案を推奨している（1224 行目）（確認済み、上記の行）。

もう一つの問題は「利用者間で共有できる」という語にある。
RFC 9111 では、利用者全員に同じ内容かどうか（private cache で足りる）と、session を確かめずに返してよいかどうか（共有 cache の条件）は別の判断である。
現リポジトリはブラウザを cookie で認証する（`docs/web-api/authentication-and-session.md` の 13 から 16 行目）ので、`Authorization` を前提にした 3.5 の保護が効かない。
47 行目の語は、この二つを区別していない（推測、文言の解釈）。

**推奨案**

案 A（最小の差分）は、47 行目の例示を区分値からマスタに替え、`private` を明示させる。

```text
マスタの参照のように、利用者や権限によって内容が変わらない応答に限り、APIごとに判断してキャッシュを許可する。
許可するときは、そのAPIの応答で`Cache-Control: private`と`max-age`を明示し、`ETag`で再検証できるようにする。
共有キャッシュ（CDNやプロキシ）に保存させる`public`と`s-maxage`は、認証なしで公開してよい応答に限る。
```

根拠は、区分値とマスタの区別（`postgresql-logical-design.md` の 22 行目と 44 から 48 行目）、`private` と `public` の定義（[RFC 9111 の 5.2.2.7](https://www.rfc-editor.org/rfc/rfc9111#section-5.2.2.7)、[5.2.2.9](https://www.rfc-editor.org/rfc/rfc9111#section-5.2.2.9)）、cookie 認証に 3.5 が効かないこと（[RFC 9111 の 3.5](https://www.rfc-editor.org/rfc/rfc9111#section-3.5)）、Zalando の既定値（[#227](https://github.com/zalando/restful-api-guidelines/blob/19a1905ad50ad71968c473009b157fd9245f5c66/chapters/performance.adoc#L294-L305)）である。
48 行目（`Cache-Control` の明示）は案 A の二文目に吸収し、49 行目（`Vary: Accept-Language`）は残す。
`response-body.md` は変えない。

案 B は、47 から 49 行目を消し、cache を許可する API が実際に必要になったときに規約を足す。
現時点で cache を許可する API は無く（推測、`backend/src/main/java` に業務の controller が見当たらないことからの判断）、既定の禁止だけで規約は成り立つ。

推奨は案 A である。
`headers.md` の description と index.md の行が「応答のキャッシュを許可するか決めるとき」を読む場面に挙げており（`headers.md` の 4 行目、`docs/web-api/index.md` の 6 行目）、その場面で判断の基準が要るためである。
どちらの案も `headers.md` だけの変更で、出典の改変は [ADR-040](../../../../docs/adr/ADR-040-import-future-architecture-guidelines.md) の方針の範囲に収まる（推測、`headers.md` の出典の注記からの判断）。
変更後は `task okf-check` を実行する。

**利用者が決める論点**

- 案 A と案 B のどちらにするか。
  案 A の場合、マスタの API のうち、利用者や権限で内容が変わらないものが実際にありそうか。
- 共有端末を想定して、`private` で許可した応答もブラウザに残したくないか。
  その場合は `max-age` を付けず、`no-cache` と `ETag` による再検証だけを許す書き方になる（推測、[RFC 9111 の 5.2.2.4](https://www.rfc-editor.org/rfc/rfc9111#section-5.2.2.4) からの判断）。
- 共有 cache を許可した応答から `Set-Cookie` を除く手順を規約に書くか（2 章の CSRF token の cookie と関係する）。

## 2. csrf.spa() の照合方式と現構成でのリスク

### 標準

OWASP CSRF Prevention Cheat Sheet は、token を使う方式を次のように分ける（確認済み、[OWASP の cheat sheet の source](https://github.com/OWASP/CheatSheetSeries/blob/be333201dc8bbf9380327dd755c1deff1525f9b3/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.md)）。

- **Synchronizer Token Pattern**：server が session に token を持ち、要求の token と照合する。
  状態を持つ software はこの方式を使い、状態を持たない software は double-submit cookie を使う（確認済み、[原則の 19 と 20 行目](https://github.com/OWASP/CheatSheetSeries/blob/be333201dc8bbf9380327dd755c1deff1525f9b3/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.md?plain=1#L19-L20)、[Synchronizer Token Pattern](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#synchronizer-token-pattern)）。
- **Signed Double-Submit Cookie（推奨）**：server の秘密鍵による HMAC で、token を認証済みの session の値に結び付ける。
  session に結び付けずに署名するだけでは、cookie の注入に弱いままだとしている（確認済み、[Signed Double-Submit Cookie](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#signed-double-submit-cookie-recommended)）。
- **Naive Double-Submit Cookie（非推奨）**：暗号論的な乱数を cookie と要求の header または parameter の両方に載せ、server は両者の一致だけを見る。
  target の domain に cookie を書ける攻撃者（脆弱な兄弟 subdomain、DNS の乗っ取り、`__Host-` でない cookie への平文 HTTP での注入）に回避されるので、新しいコードでは使わないよう警告している（確認済み、[Naive Double-Submit Cookie Pattern](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#naive-double-submit-cookie-pattern-discouraged)）。
- **custom request header**：custom header の付いた cross-origin の要求は CORS の preflight を経るので、header の存在が browser からの同一 origin の要求であることを示す（確認済み、[Employing Custom Request Headers for AJAX/API](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#employing-custom-request-headers-for-ajaxapi)）。

cookie と browser の標準は、naive 方式が破られる条件を次のように定める。

- **兄弟 domain からの書込み**：cookie は兄弟 domain に対して完全性を保証しない。
  `foo.site.example` は `Domain=site.example` の cookie を設定でき、`bar.site.example` はそれを自分の cookie と区別できない。
  Path 属性も完全性を守らず、能動的なネットワーク攻撃者は平文 HTTP の応答を偽って HTTPS の site に cookie を注入できる（確認済み、[RFC 6265bis draft 22 の 8.6 Weak Integrity](https://datatracker.ietf.org/doc/html/draft-ietf-httpbis-rfc6265bis-22#section-8.6)）。
- **cookie tossing の順序**：同じ名前の cookie が複数あると、path の長いものが `Cookie` header の先に並ぶ（確認済み、[同 5.8.3](https://datatracker.ietf.org/doc/html/draft-ietf-httpbis-rfc6265bis-22#section-5.8.3)）。
- **Secure cookie の上書き禁止**：安全でない接続から、既存の Secure cookie と名前と domain が重なり path が一致する cookie は設定できない（確認済み、[同 5.7 の手順 16](https://datatracker.ietf.org/doc/html/draft-ietf-httpbis-rfc6265bis-22#section-5.7)）。
- **`__Host-` 接頭辞**：Secure 属性付きで、Path が `/` で、Domain 属性が無いときだけ受け入れられ、cookie は設定した host に固定される（確認済み、[同 4.1.3.2](https://datatracker.ietf.org/doc/html/draft-ietf-httpbis-rfc6265bis-22#section-4.1.3.2)）。
- **Sec-Fetch-Site**：値は `same-origin`、`same-site`、`cross-site`、`none` であり、`https://subdomain.example.com/` から `https://example.com/` への要求は `same-site` になる（確認済み、[Fetch Metadata Request Headers](https://w3c.github.io/webappsec-fetch-metadata/#sec-fetch-site-header)）。
- **SameSite**：site は registrable domain で決まるので、`SameSite` は兄弟 subdomain からの要求を防がない（確認済み、[OWASP の Limitations of SameSite](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#limitations-of-samesite)）。

Spring Security 7.1.1 の `csrf.spa()` の動作は次のとおりである。

- `spa()` は `CookieCsrfTokenRepository.withHttpOnlyFalse()` と非公開の `SpaCsrfTokenRequestHandler` を設定する（確認済み、[CsrfConfigurer.java の 225 から 238 行目](https://github.com/spring-projects/spring-security/blob/7.1.1/config/src/main/java/org/springframework/security/config/annotation/web/configurers/CsrfConfigurer.java#L225-L238)）。
- repository は `UUID.randomUUID()` で token を作り、cookie にだけ保存し、照合のときも同名の cookie の最初の一つを読む。
  session にも署名にも結び付けていない（確認済み、[CookieCsrfTokenRepository.java の 89 から 141 行目と 190 から 192 行目](https://github.com/spring-projects/spring-security/blob/7.1.1/web/src/main/java/org/springframework/security/web/csrf/CookieCsrfTokenRepository.java#L89-L192)）。
- `CsrfFilter` は、repository から読んだ token と要求から取り出した値を定数時間で比べるだけである（確認済み、[CsrfFilter.java の 108 から 136 行目](https://github.com/spring-projects/spring-security/blob/7.1.1/web/src/main/java/org/springframework/security/web/csrf/CsrfFilter.java#L108-L136)）。
- SPA 用の handler は、`X-XSRF-TOKEN` header があれば生の値として照合し、無ければ XOR の handler に渡す（確認済み、[CsrfConfigurer.java の 397 から 418 行目](https://github.com/spring-projects/spring-security/blob/7.1.1/config/src/main/java/org/springframework/security/config/annotation/web/configurers/CsrfConfigurer.java#L397-L418)）。
  XOR の handler も header、次に `_csrf` parameter の順に値を探す（確認済み、[CsrfTokenRequestHandler.java の 50 から 69 行目](https://github.com/spring-projects/spring-security/blob/7.1.1/web/src/main/java/org/springframework/security/web/csrf/CsrfTokenRequestHandler.java#L50-L69)）。
- XOR の符号化は、token と同じ長さの乱数と「乱数と token の XOR」を連結して base64url にするだけで、秘密鍵を使わない。
  復号はその逆である（確認済み、[XorCsrfTokenRequestAttributeHandler.java の 77 から 138 行目](https://github.com/spring-projects/spring-security/blob/7.1.1/web/src/main/java/org/springframework/security/web/csrf/XorCsrfTokenRequestAttributeHandler.java#L77-L138)）。
  Spring の文書もこれを BREACH 対策として、要求ごとに乱数を混ぜて値を変える仕組みと説明している（確認済み、[Using the XorCsrfTokenRequestAttributeHandler (BREACH)](https://docs.spring.io/spring-security/reference/7.1/servlet/exploits/csrf.html#csrf-token-request-handler-breach)）。

以上から、`csrf.spa()` は OWASP の naive double-submit cookie に当たる。
XOR は署名ではない。
生の token を知る者は誰でも有効な符号化値を作れ、token は session にも秘密鍵にも結び付いていないからである（推測、source と OWASP の定義の照合による分類）。

### ベストプラクティス

- **cookie に書く token には `__Host-` を付ける**：OWASP は、他の subdomain からの上書きを防ぐため、`SameSite` に加えて `__Host-` 接頭辞を使うよう勧めている（確認済み、[Using Cookies with Host Prefixes to Identify Origins](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#using-cookies-with-host-prefixes-to-identify-origins)）。
- **多層の防御を一つ以上入れる**：OWASP は、SameSite、Origin の検査、Fetch Metadata などの多層防御のうち少なくとも一つを求めている（確認済み、[原則の 22 行目](https://github.com/OWASP/CheatSheetSeries/blob/be333201dc8bbf9380327dd755c1deff1525f9b3/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.md?plain=1#L22)）。
- **Fetch Metadata は same-site を慎重に扱う**：`cross-site` の非安全 method を拒否し、`same-site` は兄弟 subdomain を信頼する脅威モデルのときだけ許す。
  header が無い browser のために Origin の検査を fallback に置くことを必須としている（確認済み、[Fetch Metadata headers](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#fetch-metadata-headers)、[ポリシーの 1.3](https://github.com/OWASP/CheatSheetSeries/blob/be333201dc8bbf9380327dd755c1deff1525f9b3/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.md?plain=1#L226)）。
- **Origin の検査は origin 全体で比べる**：proxy の背後では target の origin を設定で持つか、`X-Forwarded-Host` を使う（確認済み、[Using Standard Headers to Verify Origin](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#using-standard-headers-to-verify-origin)）。
- **XSS は全ての CSRF 対策を破る**：どの方式を選んでも XSS 対策が前提である（確認済み、[cheat sheet の 13 行目](https://github.com/OWASP/CheatSheetSeries/blob/be333201dc8bbf9380327dd755c1deff1525f9b3/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.md?plain=1#L13)）。

### アンチパターン

- **XOR による masking を署名と見なす**：BREACH 対策は応答の圧縮から token を推測されることへの対策であり、cookie の注入には効かない（推測、上記の source の読み）。
- **`spa()` より前に `csrfTokenRepository()` を呼ぶ**：7.1.1 の `spa()` は repository を無条件に上書きする（確認済み、[CsrfConfigurer.java の 234 から 238 行目](https://github.com/spring-projects/spring-security/blob/7.1.1/config/src/main/java/org/springframework/security/config/annotation/web/configurers/CsrfConfigurer.java#L234-L238)）。
  この挙動の issue は重複として閉じられ、修正の PR は merge されていない（確認済み、[spring-security#18718](https://github.com/spring-projects/spring-security/issues/18718)、[#18720](https://github.com/spring-projects/spring-security/pull/18720)）。
  `spa()` の後に `csrfTokenRepository()` を呼べば、repository だけを差し替え SPA 用の handler を残せる（確認済み、[同 119 から 123 行目](https://github.com/spring-projects/spring-security/blob/7.1.1/config/src/main/java/org/springframework/security/config/annotation/web/configurers/CsrfConfigurer.java#L119-L123)）。
- **cookie に `Domain` を付けて subdomain 間で共有する**：OWASP は session cookie を domain 全体に設定しないよう警告している（確認済み、[cheat sheet の原則](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#introduction)）。
  現リポジトリは `Domain` を省くと定めている（確認済み、`docs/web-api/authentication-and-session.md` の 33 行目）。
- **SameSite だけに頼る**：Spring Security も SameSite を多層防御の一つとして勧め、唯一の防御にしない（確認済み、[Spring Security の SameSite Attribute](https://docs.spring.io/spring-security/reference/7.1/features/exploits/csrf.html#csrf-protection-ssa)）。

### デファクトスタンダード

- **Spring の公式**：SPA の構成として `csrf.spa()` を示している（確認済み、[Single-Page Applications](https://docs.spring.io/spring-security/reference/7.1/servlet/exploits/csrf.html#csrf-integration-javascript-spa)）。
  一方で、既定で token を cookie に置かない理由として、別の domain から header を設定される攻撃が知られていることを挙げている（確認済み、[CSRF and Session Timeouts](https://docs.spring.io/spring-security/reference/7.1/features/exploits/csrf.html#csrf-considerations-timeouts)）。
  session に token を置き、`@ControllerAdvice` で応答 header に載せる方式も紹介している（確認済み、[Other JavaScript Applications](https://docs.spring.io/spring-security/reference/7.1/servlet/exploits/csrf.html#csrf-integration-javascript-other)）。
- **Spring の保守者の認識**：兄弟 subdomain から `XSRF-TOKEN` を設定し `_csrf` parameter で送る攻撃手順が 2023 年に issue で示され、保守者は OWASP の推奨を検討する issue として開いたままにしている（確認済み、[spring-security#13717](https://github.com/spring-projects/spring-security/issues/13717)）。
- **Spring の Fetch Metadata 対応**：`csrf.crossOriginProtection()` を足す PR が出ており、`Sec-Fetch-Site` が `same-origin` か `none` なら通し、`same-site` と `cross-site` を拒否し、header が無ければ Origin で判定する設計である。
  調査時点で issue と PR はともに open で triage 待ちである（確認済み、[spring-security#18361](https://github.com/spring-projects/spring-security/issues/18361)、[#19822](https://github.com/spring-projects/spring-security/pull/19822)）。
- **JHipster**：Spring の SPA の生成器も、`CookieCsrfTokenRepository.withHttpOnlyFalse()` と SPA 用 handler の組合せを生成する（確認済み、[SecurityConfiguration_imperative.java.ejs の 164 から 167 行目](https://github.com/jhipster/generator-jhipster/blob/934b7b30970c038c696861e646bde487de10a705/generators/spring-boot/templates/src/main/java/_package_/config/SecurityConfiguration_imperative.java.ejs#L164-L167)）。
  多くの Spring の SPA は naive 方式のまま運用されていると考えられる（推測、公式文書と代表的な生成器が同じ構成であることからの判断）。
- **他の framework**：
  - Django は Spring と同じく cookie の秘密値と masking した値を照合し、加えて Origin と、HTTPS では Referer を厳格に検査して subdomain からの攻撃を防ぐ。
    それでも subdomain が cookie を設定できる場合の限界を文書に明記している（確認済み、[Django の CSRF の How it works と Limitations](https://github.com/django/django/blob/main/docs/ref/csrf.txt)）。
  - Laravel は `XSRF-TOKEN` cookie を暗号化して渡し、照合は session の token と行う（確認済み、[Laravel の X-XSRF-TOKEN](https://github.com/laravel/docs/blob/12.x/csrf.md#x-xsrf-token)、[VerifyCsrfToken.php の tokensMatch](https://github.com/laravel/framework/blob/12.x/src/Illuminate/Foundation/Http/Middleware/VerifyCsrfToken.php)）。
  - Go 1.25 の `CrossOriginProtection` は token を使わず、`Sec-Fetch-Site` が `same-origin` か `none` 以外の非安全 method を拒否し、header が無ければ Origin と Host を比べる（確認済み、[net/http/csrf.go の 122 から 162 行目](https://github.com/golang/go/blob/go1.25.0/src/net/http/csrf.go#L122-L162)）。
  - Rails は `Sec-Fetch-Site` を既定の検査に採用したが、`same-site` を許可する（確認済み、[rails/rails#56350](https://github.com/rails/rails/pull/56350)、[request_forgery_protection.rb の verified_via_header_only?](https://github.com/rails/rails/blob/241de974daffe71a8a057d9fdf15badacfa240ea/actionpack/lib/action_controller/metal/request_forgery_protection.rb#L642-L653)）。
    この設定では兄弟 subdomain からの要求を防げない（推測、Fetch Metadata の定義からの帰結）。

### 現リポジトリへの当てはめ

**前提**

- token は `csrf.spa()` で扱う（確認済み、`backend/src/main/java/com/example/demo/SecurityConfig.java` の 99 から 100 行目）。
- session cookie は `APP_SESSION` で、`SameSite=Lax` である（確認済み、`backend/src/main/resources/application.yaml` の 185 行目と 191 行目）。
- 同一 origin を保つ間は CORS を有効にしない（確認済み、ADR-014 の 39 行目）。
- 本番の配信先とリバースプロキシは決まっていない（確認済み、ADR-014 の 23 行目）。
  HSTS の `includeSubDomains` は全 subdomain の HTTPS 化を確認してから有効にする（確認済み、ADR-014 の 70 行目）。
- backend にはまだ業務の更新系 endpoint が無く、非安全 method の endpoint は Spring Security の `POST /logout` だけである（確認済み、`backend/src/main/java` に `@PostMapping` などが無いこと、`SecurityConfig.java` の 106 から 114 行目）。

**攻撃が成り立つ条件**

攻撃には次の三つがすべて要る（推測、上記の標準と source からの組立てで、実行して確かめていない）。

1. 攻撃者が app の host に届く `XSRF-TOKEN` cookie を書ける。
   同じ registrable domain の下にある、乗っ取られた subdomain、利用者の content を置く subdomain、他の system の host がこれに当たる。
   平文 HTTP の注入は、Secure の `XSRF-TOKEN` がまだ無い間（初回、login と logout の直後）に限られる（5.7 の手順 16 による）。
   本番では proxy 越しに `request.isSecure()` が真になり、cookie は Secure で発行される（推測、`application.yaml` の `forward-headers-strategy: framework` と [CookieCsrfTokenRepository.java の 94 行目](https://github.com/spring-projects/spring-security/blob/7.1.1/web/src/main/java/org/springframework/security/web/csrf/CookieCsrfTokenRepository.java#L94) からの判断）。
2. 被害者の session cookie と一緒に token を送れる。
   兄弟 subdomain は same-site なので、`SameSite=Lax` の `APP_SESSION` は POST でも送られる。
   header で送るには CORS の preflight が要り、CORS が無いので失敗する。
   HTML の form で `_csrf` parameter に XOR 符号化した値を送れば、`CsrfFilter` を通る。
3. 受け側の endpoint が form の本文か本文の無い POST を受け付ける。
   JSON を `@RequestBody` で受ける endpoint は form の content type を受け付けない。
   現在これに当たるのは `POST /logout` だけで、被害は強制 logout に留まる。

したがって、現時点の実害は小さい。
ただし本番の domain 構成が未定で、条件 1 が成り立つかは決まっていない。
本文を取らない操作の POST（承認や取消など）を足すと、条件 3 の対象が増える。

**推奨案**

1. ADR-014 に、CSRF の照合は naive double-submit cookie であり、同じ registrable domain に信頼できない host を置かないことを前提にすると書く。
   根拠は [OWASP の Naive Double-Submit Cookie Pattern](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#naive-double-submit-cookie-pattern-discouraged) と、本番の配信先が未定であること（ADR-014 の 23 行目）である。
2. frontend の C2（fetch wrapper）を実装する同じ変更で、token の cookie 名を `__Host-XSRF-TOKEN` にする。
   兄弟 subdomain と平文 HTTP からの cookie の書込みを同時に塞げ、変更は `SecurityConfig` の数行と wrapper が読む cookie 名に限られる。
   根拠は [RFC 6265bis の 4.1.3.2](https://datatracker.ietf.org/doc/html/draft-ietf-httpbis-rfc6265bis-22#section-4.1.3.2) と [OWASP の Host prefix の節](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#using-cookies-with-host-prefixes-to-identify-origins) である。
   7.1.1 では `spa()` の後に repository を差し替える。

   ```java
   .csrf(
       csrf -> {
         final CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
         repository.setCookieName("__Host-XSRF-TOKEN");
         // __Host- は Secure を要求する。ローカルの HTTP でも付ける。
         repository.setCookieCustomizer(cookie -> cookie.secure(true));
         csrf.spa().csrfTokenRepository(repository);
       })
   ```

   path は context path が無ければ `/` になり、`Domain` は既定で付かない（確認済み、[CookieCsrfTokenRepository.java の 93 から 98 行目と 173 から 176 行目](https://github.com/spring-projects/spring-security/blob/7.1.1/web/src/main/java/org/springframework/security/web/csrf/CookieCsrfTokenRepository.java#L93-L176)）。
   ローカルの Vite（`http://localhost`）で `__Host-` の cookie が受け入れられるかは確かめていない。
   MDN は localhost が設定する Secure 属性には https の要件を課さないと書いている（確認済み、[MDN の Set-Cookie](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Set-Cookie#secure)）が、接頭辞の判定まで同じかは browser ごとに E2E で確かめる必要がある（推測）。
   cookie 名と header 名は frontend と backend の契約なので、ADR-014 の更新に含める（ADR-007 の 31 行目は SPA モードとだけ書いている）。
3. Fetch Metadata と Origin の検査は、今は自前の filter を書かない。
   Spring の [#19822](https://github.com/spring-projects/spring-security/pull/19822) が入れば設定一つで足り、推奨案 2 で cookie の注入は塞がるためである。
   本番の domain 構成で信頼できない兄弟 host が避けられないと分かった場合は、[OWASP の Fetch Metadata の方針](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#fetch-metadata-headers)（`same-site` も拒否し、Origin を fallback にする）で filter を足す。
   その場合、OIDC の callback（`/login/oauth2/code/*`）は Keycloak の既定の query 応答では GET なので対象外である（推測、response mode を `form_post` にしていないことからの判断で、`docker/keycloak/realm.json` は確かめていない）。
4. synchronizer token（`HttpSessionCsrfTokenRepository` と応答 header）への切替えは勧めない。
   OWASP の「状態を持つ software は synchronizer token」には合うが、Spring の SPA の既定から外れ、frontend の C2 の設計（cookie を読む wrapper）も作り直しになる。
   推奨案 2 と同じ攻撃を塞ぐのに、変更が backend と frontend の両方で大きくなる（推測、[Other JavaScript Applications](https://docs.spring.io/spring-security/reference/7.1/servlet/exploits/csrf.html#csrf-integration-javascript-other) の構成からの見積り）。

**利用者が決める論点**

- 本番の公開 domain の下に、このリポジトリが管理しない host（他の system、外部 service の CNAME、利用者の content）を置く予定があるか。
  あれば推奨案 3 の filter を前倒しする根拠になる。
- 推奨案 2 の `__Host-` を C2 と同時に入れるか、推奨案 1 の前提の明記だけにするか。
  入れる場合、ローカルの HTTP で動くかを先に確かめるか。
- 前提と cookie 名を ADR-014 の改訂で記録するか、CSRF の方式を独立した ADR にするか。
  利用者は ADR の更新を実装と合わせて行う方針なので、どちらでも C2 の実装の変更に含める。
- Spring の `crossOriginProtection` が release されたら採用を再検討する、と ADR に書いておくか。
