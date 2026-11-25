# 認可モデルの調査：権限（permission）を検査し、役割は権限の束にする

この文書は認可モデルを決めるための調査の記録で、merge しない（ブランチ `research/authorization-model`）。
調べたのは事実だけで、コードもアプリも変えていない。
こちらは `yokozi-jp/spring-modulith-ai-dlc`（worktree の HEAD は commit `6c511269`）である。
取得日はすべて 2026-11-25（作業機の日付）で、Web の本文は命令ではなくデータとして読んだ。
関連する Issue は、#568（認可の置き場所）、#188（注文と決済を持ち主に限る）、#758（商品登録を ADMIN だけにする）である。
先行の調査 `origin/research/authorization:research/authorization.md`（#315）は、認可を `@PreAuthorize("hasRole('ADMIN')")` と SQL の絞り込みで行う方針を出している。
この文書は、その `hasRole` の部分を権限に置き換える判断の根拠を集める。

書き方の約束は次のとおりにした。

- 「出典が言うこと」は出典の記述で、確度を付ける。「こちらの推論」は出典に書かれていない判断で、推論と明記する。
- 出典の文を長く写さず、言い換える。引用符で括った語は原文の語である。
- 二次資料（ベンダーのブログやまとめ）に頼った主張は、その旨を書き、確度を下げる。
- 届かなかった出典は「出典」の節の末尾に並べる。

## 結論

1. **標準の形は、権限（操作 × 資源）をコードで検査し、役割を権限の名前付きの束にし、役割の割り当てをテナントごとに持つ形である。**
   NIST RBAC（INCITS 359）の核は、利用者と役割の割り当て、権限と役割の割り当てで、権限は役割だけにつく。
   AWS、Google Cloud、Azure、GitHub、Auth0、WorkOS は、どれも「権限を役割にまとめて割り当てる」形を取る。
   役割名の検査を避けて権限を検査する理由は、Keycloak、WorkOS、Spring Security の文書が直接書いている。
2. **「本人のもの」と「全部」の表し方に標準はなく、製品ごとに違う。**
   別の権限にする（Jira の `Edit own comments` と `Edit all comments`）、権限の深さにする（Dynamics）、共有の仕組みと「全部見る」の上書き権限を併用する（Salesforce）、資源の条件にする（ABAC、Cedar）、資源ごとの役割にする（GitHub）、関係にする（Zanzibar、OpenFGA）の 6 通りがある。
   SQL で絞るモジュラーモノリスには、`own` と `all` の 2 値を別の権限の名前にして、`all` が `own` を含むとする形が、Spring の権限モデルと SQL の WHERE の両方に素直に載る（こちらの推論）。
3. **アンチパターンの対策は、ほぼ 1 つの形にまとまる。**
   全ての業務の操作に権限の宣言を置き、宣言がない操作を検査で落とし、見える範囲を SQL と RLS で絞り、テナントを権限の外側の境界にする。
   この形は OWASP API Security Top 10 の API1、API5、ASVS の V8、OWASP の Multi-Tenant cheat sheet が求めるものと一致する。
4. **権限の名前は `resource:action[:scope]`、scope は `own` と `all` の 2 つから始める。**
   AWS は `service:Action`、Google は `service.resource.verb`、Auth0 は `action:resource`、WorkOS は `resource:action` で、区切りと語順は割れている。
   Spring の文書の例（`permission:read`）と WorkOS の推奨（資源、区切り、操作）に合わせ、小文字の `resource:action[:scope]` にする。
   `all` は「選んだテナントの全部」であり、テナントをまたぐ範囲は scope に入れない。
5. **テナントが決める役割（カスタムロール）は、ベンダーが固定の役割の上に足す機能として出している。**
   WorkOS では、組織は既定でカスタムロールを持たず、環境の役割を継承し、必要な組織が自分の役割を作る。
   GitHub のカスタムリポジトリロールは、Enterprise Cloud だけで、20 個までである。
   役割の作成と割り当てには、持っていない権限を付けられない規則（Kubernetes）、権限の上限（AWS の permissions boundary）、役割の適用範囲による制限（Azure の AssignableScopes）がある。
6. **Spring Security 7.2.0 では、`@RequiresPermission("order:read:all")` の形の合成アノテーションが動く。**
   リファレンスの「Using Meta Annotations」に、テンプレートの書き方がある。
   `hasAuthority` に展開する形は、使い捨ての確認で動作を確かめた（「確かめたこと」）。
   Authentication がないとき（Listener、バッチ）は、`AccessDeniedException` ではなく `AuthenticationCredentialsNotFoundException` になる。
   CommandHandler に `@PreAuthorize` を付けると、Listener 経由の呼び出しが落ちる。置く層の決定が要る。
7. **テストは、操作 × 操作する人の期待の表を、本番の方針と別に手で書く形が、OWASP の Authorization Testing Automation cheat sheet と一致する。**
   表の行（操作）の漏れだけは、OpenAPI の契約から機械で拾って検出する。
   OWASP の Multi-Tenant cheat sheet は、手で持つ一覧が漏れて古くなることを警告するためである。
8. **RBAC とスコープを超える目安に、数値の閾値を書いた一次資料は見つからなかった。**
   NIST SP 800-162 は、ABAC の導入に大きな費用がかかるため、一部の対象から段階的に入れるよう書く。
   OpenFGA（ベンダー）は、関係が書き込みで変わる、共有、階層、「このユーザーが見える全部」の逆引きが要る、のどれかが出たときを境に置く。
9. **推奨は案 B（役割はコードの登録簿で権限の集合に対応づけ、検査は `hasAuthority`、`own` と `all` の違いは SQL で解く）である。**
   検査の箇所が権限の名前だけに依存するため、将来テナントが役割を決める案 C に進んでも、検査の箇所は変わらず、役割から権限への対応の持ち先だけが変わる（こちらの推論、根拠は WorkOS の文書と Spring の文書）。
   案 D（方針エンジン）は、関係が 1 種類（注文の持ち主）の今は見合わない。
10. **決めること（オーナー）は最後の「決めること」の節に並べた。**
    先に決めるべきは、scope の語彙、`all` を持つ役割、ADMIN と MEMBER の既定の権限、`:own` を成立させる持ち主の列、権限をチェックする層（Controller か CommandHandler）の 5 つである。

## 前提：今のこちらの実装

- 役割は `ADMIN` と `MEMBER` の 2 つで、テナントの所属の行（`m_membership.role_typ`）に持つ（`JooqMembershipRepository.java:52`、`Role.java:6`）。
- テナントを選んだ認証は、`TenantMembershipAuthority(tenantId, role)` を 1 つ持つ。権限の名前は `"ROLE_" + role` である（`TenantMembershipAuthority.java:19-20`）。
- `TenantSelectionFilter` は、認証したリクエストのたびに所属を DB から引き直す（`TenantSelectionFilter.java:169-183`）。
  所属が消えていれば選択を外し、役割が変わっていれば権限を作り直してセッションに保存する。
  つまり権限は、リクエストごとに DB の所属から決まる。トークンに焼き付けた値は使わない（ADR-101、`docs/web-api/authentication-and-session.md:55`）。
- 認可の検査は 1 か所だけある。`TenantSecurityCustomizer` が `/api/current-tenant/members/**` に `hasRole("ADMIN")` を課す（`TenantSecurityCustomizer.java:45`）。
  `@EnableMethodSecurity` と `@PreAuthorize` は `backend/src/main` にない。
- 「最後の管理者を残す」規則は `Tenant` 集約が持つ（`Tenant.java:117-124`）。
- 全ての表は共通カラム `created_by`（`varchar(64)`、利用者の `public_id` の文字列）を持つ（`docs/database/postgresql-common-columns.md:25,34`）。
  利用者の操作でない処理では、`created_by` に処理の名前が入る。
- テナントの境界は、選んだテナントを `TenantContext` に束縛し、DB 側の RLS を足す形である（ADR-105、changeset `018` から `020`）。
- Listener（`OrderConfirmedListener`）は `@ApplicationModuleListener` で CommandHandler を呼ぶ。この呼び出しに利用者の認証はない。
- 近い将来の要求は 2 つある。注文と決済を持ち主に限る（#188）ことと、商品登録を ADMIN だけにする（#758）ことである。

## 1. 標準の形

### 結論

権限を「資源への操作」として定義し、役割はその束、割り当ては利用者と役割の間に持つ。
アプリのコードは、役割の名前ではなく権限を検査する。

### 出典が言うこと

- **NIST RBAC の核**：NIST の FAQ は、全ての access は役割を通り、役割は権限の集まりで、利用者は割り当てた役割か継承した役割からだけ権限を得る、と書く。
  権限は利用者に直接つけず、役割にだけつける。FAQ はこれを、グループ（利用者の集まりで、権限を利用者に直接つけられる抜け穴がある）との違いとして挙げる（[NIST-RBAC-FAQ]「What Is Role Based Access Control?」）。確度：高（NIST 自身のページ）。
- **標準の構成要素**：RBAC の標準は参照モデルと機能仕様の 2 部で、参照モデルは Core RBAC、Hierarchical RBAC、Static Separation of Duty、Dynamic Separation of Duty の 4 要素である。
  Core は利用者と役割の割り当て、権限と役割の割り当て、セッションでの役割の有効化を含み、全ての RBAC 実装に必須である。他の 3 つは独立で、別々に実装してよい（[NIST-RBAC-FAQ]「Standard Structure」「Component summary」）。確度：高。
  INCITS 359-2004 は 2004-02-11 に採択され、INCITS 359-2012 に改訂された（[NIST-RBAC-PROJ]「Overview」）。標準本体（有料）は読んでいない。確度：中（NIST の要約を経由）。
- **役割の安定性**：NIST は、役割は組織の中で比較的安定し、利用者と権限は数が多く速く変わるので、役割を経由すると管理と見直しが簡単になる、と書く（[NIST-RBAC-FAQ]）。
- **AWS**：権限は `サービス名前空間:Action` で書く（`s3:GetObject`、`iam:ChangePassword`）。接頭辞と操作名は大文字小文字を区別しない（[AWS-IAM-ACTION]）。
  AWS の IAM は、許す操作を方針（policy）に書いて主体（役割を含む）に付ける形で、最小権限を求め、使っていない権限を定期的に消すよう勧める（[AWS-IAM-BP]「Apply least-privilege permissions」「Regularly review and remove unused …」）。確度：高。
- **Google Cloud**：役割は権限の集まりで、権限は `SERVICE.RESOURCE.VERB` の形である。権限は REST メソッドとほぼ 1 対 1 に対応する。
  主体に役割を付与すると、その役割の全ての権限を得る（[GCP-ROLES]「Permissions」）。確度：高。
- **Azure**：役割定義は `Actions`、`NotActions`、`DataActions`、`NotDataActions` を持つ。権限の形は `Microsoft.Storage/storageAccounts/blobServices/containers/read` である（[AZ-ROLEDEF]）。確度：高。
- **GitHub**：権限は「特定の操作をする能力」、役割は「割り当てられる権限の集まり」と定義される（[GH-CUSTOM-ROLES]「About custom repository roles」）。確度：高。
- **Auth0**：権限は個別の操作（例 `read:reports`）、役割は権限の集まり、利用者に 1 つ以上の役割を割り当てる。複数の役割の効果は和集合（加算）である。
  マルチテナント向けには、Organization ごとの役割を、メンバーに割り当てられる（[AUTH0-RBAC]「RBAC Model」「Roles in Auth0 Organizations」「Overlapping role assignments」）。確度：高。
- **WorkOS**：役割は権限の論理的なまとまりで、役割と権限は不変の slug で識別する。役割は組織の所属（membership）に割り当てる（[WORKOS-CONFIG]、[WORKOS-INTEG]）。確度：高。

### 「役割でなく権限を検査する」理由

- **Keycloak**：役割だけで判断すると、資源と役割が強く結びつき、役割の変更が複数の資源に影響する。
  要件の変更がアプリのコードの深い変更になり、役割の管理が難しく間違いやすくなる、と Keycloak の Authorization Services の文書は書く（[KEYCLOAK-AZ]「RBAC」の節の説明）。確度：高。
- **WorkOS**：検査を権限に抽象化すると、役割と権限の割り当てを、アプリのコードを変えずに足したり直したりできる（[WORKOS-CONFIG]「Configure permissions」）。確度：高。
- **Spring Security**：リファレンスは「Favor Granting Authorities Over Complicated SpEL Expressions」の節で、`hasAuthority('permission:read') || hasRole('ADMIN')` のような式より、`ROLE_ADMIN` に `permission:read` を与える（`RoleHierarchy`）形を勧める。
  可能なら、アプリ固有の認可の論理は、ログイン時に権限へ変換するよう書く（[SS-METHOD]`#favor-granting-authorities`）。確度：高。
- **OWASP**：Authorization Cheat Sheet は、役割の検査が多い実装では、検査の漏れや誤りが起きやすく、`hasAnyRole("SUPERUSER", "ADMIN", "ACCT_MANAGER")` のような連ねた検査が必要になることを指摘する（[OWASP-AUTHZ-CS]「Prefer Attribute and Relationship Based Access Control over RBAC」の Robustness）。確度：高。
  OWASP API Security の API5（BFLA）は、実行の許可を「特定の役割への明示的な付与」にし、全ての関数で要求するよう書く（[API5]「How To Prevent」）。

### こちらの推論

- 今の `hasRole("ADMIN")` は 1 か所なので、役割を検査する害はまだ小さい。
  #188 の「管理者に全件を見せるか」と #758 の「ADMIN だけ」が入ると、役割の名前が Controller、CommandHandler、検索の分岐に散る。
  散る前に権限に置き換える方が、後から置き換えるより安い。
- 役割ごとの割り当て（UA）と権限の割り当て（PA）の 2 つの関係に分けるのは、NIST の Core RBAC そのものである。
  今の構成は、UA が `m_membership.role_typ`（テナントごと）で、PA が暗黙（コードの中、`hasRole` の箇所）にある。案 B は PA を 1 か所の登録簿に出す。

確度：高。

## 2. 「本人のもの」と「全部」の表し方

### 結論

製品ごとに 6 通りある。SQL で絞る場合は、権限の文字列に `own` と `all` を含め、`all` が `own` を含むとする形が実装しやすい（推論）。

### 出典が言うこと（6 通りの比較）

| 方式                       | 例                                                                                                                                                                                                                                                                                            | 出典が言うこと                                                                                                                                                    | 費用と制約                                                                                                                               |
| -------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| 別の権限                   | Jira Data Center の `Edit own comments` と `Edit all comments`、`Delete own attachments` と `Delete all attachments`                                                                                                                                                                          | 本人のものだけを許す権限と、全てを許す権限が別の項目になる（[JIRA-DC]）                                                                                           | 権限の数が増える。自分の分と全部の関係は、利用者が両方を付ける必要があるかを製品が決める                                                 |
| 権限の深さ                 | Dynamics 365（Dataverse）の Create、Read、Write、Delete、Append、Append to、Assign、Share に、アクセスレベル（User、Business Unit、Parent: Child Business Unit、Organization、None）を付ける                                                                                                  | レベルは事業部の階層のどこまでかを表す。Organization は他の全てのレベルを含み、User は本人が持つ資源と共有された資源である（[DYN-PRIV]「Access levels」）         | 権限の文字列は増えないが、権限が「値を持つ」。Spring の `GrantedAuthority` は文字列を前提にする（下記）                                  |
| 全部を見る権限と共有モデル | Salesforce：オブジェクトの権限（Create、Read、Edit、Delete）、組織の既定の共有、役割の階層、共有ルール（組織の既定の共有と役割の階層は、Trailhead の別のモジュールの検索結果の抜粋でだけ確認した）。System Administrator の `View All Data` と `Modify All Data` は、他の共有設定を上書きする | Trailhead は、この 2 つが共有設定を上書きするので、System Administrator 以外には慎重に付けるよう書く（[SF-TRAIL]「Setting Up Profiles」）                         | 共有モデルの学習費用が高い。「全部」を持つ権限が、強い上書きとして別に存在する                                                           |
| 資源の条件（ABAC）         | Cedar の例：社員は自分の人事の記録だけ、決まった IP からだけ読める                                                                                                                                                                                                                            | 方針が主体、資源、状況の属性で決める。NIST SP 800-162 は、RBAC を ABAC の特殊な場合（属性が role）と位置づける（[CEDAR-HOME]「Introduction」、[NIST-800-162]§2）  | 属性の管理と方針の記述の費用。SQL に落とすには、条件を WHERE に変換する仕組みが要る（推論）                                              |
| 資源ごとの役割             | GitHub のリポジトリの役割（Read、Triage、Moderate、Write、Maintain、Admin）。カスタム役割は、基にする役割に権限を足す                                                                                                                                                                         | 役割をリポジトリごとに割り当てる。自分のコメントの編集と削除、自分が開いた Issue の close は、どの役割にも組み込みで、権限の項目として分けない（[GH-REPO-ROLES]） | 割り当ての数が資源の数に比例する。OpenFGA は、資源ごとの役割が増えて破綻すると書く（[OPENFGA-RBAC-VS]、ベンダー）                        |
| 関係（ReBAC）              | Zanzibar、OpenFGA                                                                                                                                                                                                                                                                             | 関係の組（relation tuple）で ACL を表し、階層とグループで広げる。Google の Calendar、Drive、Photos などが使う（[ZANZIBAR]§1）                                     | 別のシステムを運用する。「見える全部」の逆引きは ListObjects で、既定の上限は 1000 件、期限 3 秒である（[OPENFGA-QUERY]「ListObjects」） |

Auth0 の RBAC の説明は、HR のアプリの例として、「HR のマネージャーは社員の詳細を更新でき、他の社員は自分の詳細だけを見られる」と書く（[AUTH0-RBAC]）。
この例は役割と権限の構成を語るだけで、「自分だけ」をどう実装するかは書かない。

### SQL で絞ることについて、出典が言うこと

- Spring Security のリファレンスは、`@PostFilter` を使うとメモリ上の絞り込みが高くつくので、データ層で絞ることを検討するよう書く（[SS-METHOD]`#use-postfilter` の NOTE）。確度：高。
- Spring Data の統合の章は、ページングのために、問い合わせに現在の利用者を入れることが必要で、後で絞る形は規模に耐えないと書く（[SS-DATA]の冒頭）。確度：高。
- OpenFGA の文書は、検索、並べ替え、結合は、アプリの DB で行うべきで、それに要るデータはアプリの DB に置くよう書く（[OPENFGA-SOT]「When OpenFGA is not the right source of truth」）。確度：中（ベンダーの文書）。
- OWASP の Multi-Tenant cheat sheet は、全てのテナントの資源へのアクセス経路が通る境界で認可し、データ層の検査は多層防御として足すよう書く（[OWASP-MT]§3）。確度：高。

### 「モジュラーモノリスで SQL を絞る場合に普通の選択」は何か

出典には、モジュラーモノリスを名指しして答えるものがない。以下は推論である。

- **別の権限（`own` と `all`）が合う理由**：Spring の `GrantedAuthority` は、文字列で表せる権限を標準とし、文字列で表せない「複雑な」権限は `getAuthority()` が `null` を返し、`AuthorizationManager` が実装ごとに解釈する前提になる（[SS-ARCH]`#authz-authorities`）。
  深さを値に持つ権限（Dynamics 型）は、この「複雑な権限」になり、自作の `AuthorizationManager` が要る。別の文字列にすれば、標準の `hasAuthority` で足りる。
- **SQL への落とし方**：リクエストの権限から、`ReadScope`（`ALL`、`OWN`、`NONE`）を 1 か所で決め、Repository の WHERE に渡す。
  `ALL` なら持ち主の条件を付けず、`OWN` なら `created_by = :userId` 相当を付け、`NONE` ならメソッドの検査で 403 になる。
- **含意の表し方**：`order:read:all` が `order:read:own` を含む関係は、Spring の `RoleHierarchy`（`order:read:all > order:read:own`）で表せる。
  リファレンスは、`RoleHierarchy` を「役割（または権限）が別の役割を含む」ための仕組みと書く（[SS-ARCH]`#authz-hierarchical-roles`）。
  権限同士の階層は、使い捨ての確認で動作を確かめた（「確かめたこと」の C2）。

### 出典の食い違い

- OWASP の Authorization Cheat Sheet は、「ABAC と ReBAC を RBAC より優先する」と書き、理由に、オブジェクト単位の判断、役割の爆発、マルチテナントを挙げる（[OWASP-AUTHZ-CS]）。
  一方で、Auth0 と WorkOS は、マルチテナントを Organization 単位の役割で処理する（[AUTH0-RBAC]、[WORKOS-CUSTOM]）。
  OpenFGA は「RBAC はマルチテナントで破綻する」と書くが、自分の製品の説明である（[OPENFGA-RBAC-VS]）。
  こちらの構成は、役割を所属の行（テナントごと）に持ち、テナントを境界にするので、「テナントごとに役割を増やす」形ではなく、OpenFGA が指摘する破綻は当たらない（推論）。
  OWASP の勧めのうち、オブジェクト単位の判断は SQL の `own` で扱い、ABAC の導入費用は NIST SP 800-162 §3.1.1 が警告する（次の「8」を参照）。

確度：中（6 通りの存在は高、「SQL で絞る場合の普通」は推論）。

## 3. アンチパターンと対策

各項目は「出典が言うこと」と「対策」の順に書く。確度は項目の末尾に付ける。

### 3.1 役割名をコードに直書きする

- 出典：Keycloak（役割と資源の強い結合）、Spring（`hasAuthority(...) || hasRole(...)` を避ける）、OWASP（`hasAnyRole(...)` の連ね）。上の「1」を参照。
- 対策：検査は権限だけにし、役割の名前は役割と権限の対応を書く 1 か所にだけ置く。
- 今の状態：`TenantSecurityCustomizer.java:45` の `hasRole("ADMIN")` が 1 か所ある。確度：高。

### 3.2 役割の爆発

- 出典：NIST SP 800-162 §2.1（p.6）は、RBAC は複数の要素による判断を表しにくく、動的な判断を表そうとすると、数が少なく臨時の役割が大量にでき、「role explosion」と呼ばれる状態になると書く。
  OWASP の Authorization Cheat Sheet は、役割が増えると、HTTP header に役割を載せる設計で大きさの上限に当たり、役割を毎回引くと遅延が増えると書く（Speed）。
  WorkOS は、複数の役割を足し合わせられるようにすると、組み合わせごとに役割を作らずに済むと書く。ただし、ほとんどのアプリは 1 利用者に 1 役割から始め、重なりが増えてから複数にするよう勧める（[WORKOS-INTEG]「Single vs. multiple roles」）。
- 対策：役割は職務の単位にして数を絞り、本人のものと全部の違いは権限の scope で表す。資源ごとの役割を作らない。
- 今の状態：2 役割で、1 利用者に 1 役割。確度：高。

### 3.3 画面だけの検査

- 出典：ASVS 5.0 の 8.3.1 は、認可を信頼できるサービス層で強制し、JavaScript のような利用者が改変できる制御に頼らないことを求める（レベル 1）。
  ASVS 4.0.3 の 4.1.1 も同じ。OWASP の Authorization Cheat Sheet は、画面側の検査は使いやすさのためで、決める役は担えないと書く。
- 対策：サーバー側で必ず強制し、画面は隠すだけにする。`docs/frontend/authorization-ui.md` が同じ線引きを書く（先行の調査より）。確度：高。

### 3.4 入口（Controller や URL）だけで認可し、データ層で認可しない

- 出典：OWASP Multi-Tenant cheat sheet §3 は、テナントの資源の全てのアクセス経路が通る境界で認可し、データ層の検査を多層防御として足すよう書く。
  AWS の SaaS の文書は、認証と認可が通っても、テナントの分離ができたことにはならず、認証済みで認可された利用者が別のテナントの資源に届きうると書く（[AWS-SAAS-FUND]「Tenant Isolation」）。
  API1 は、クライアントの入力でレコードに触る全ての関数で認可を確かめることを求める。
- 対策：見える範囲は Repository の WHERE と RLS で絞る。入口の権限の検査は「操作してよいか」だけを決める。
- 今の状態：RLS はあるが、持ち主の絞り込みは #188 で未実装。確度：高。

### 3.5 BOLA（IDOR）

- 出典：API1 の対策は、利用者の方針と階層に基づく認可の仕組みを使い、クライアントの入力を使う全ての関数でその利用者がそのレコードにその操作をできるかを確かめることである。
  ID は推測しにくい値（GUID）を使い、認可の仕組みのテストを書き、失敗する変更を出さない。
  ASVS 5.0 の 8.2.2、ASVS 4.0.3 の 4.2.1 も同じ。
  OWASP の Multi-Tenant cheat sheet は、ランダムな ID は認可の代わりにならず、別のテナントの資源は 403 でなく 404 を返して存在を隠すよう書く（§3 の例のコメント）。
- 対策：持ち主の条件を SQL に入れ、見えなければ 404 にする。先行の調査と `docs/web-api/status-codes.md` が同じ。確度：高。

### 3.6 BFLA（機能レベル）

- 出典：API5 は、一貫して分析しやすい認可のモジュールを全ての業務の関数から呼び、既定で全て拒否し、全ての関数に特定の役割への明示的な付与を要求する。
  管理の Controller は、管理の抽象 Controller を継承して役割の検査を共通にする。通常の Controller の中の管理の関数も、役割の検査を必ず入れる。
  Spring のリファレンスは、アノテーションによる method security では、アノテーションのないメソッドは保護されないので、catch-all の認可の規則を置くよう書く（[SS-METHOD]`#request-vs-method` の NOTE）。
- 対策：全ての業務の操作に権限の宣言を要求し、宣言のない操作を ArchUnit で落とす（推論。出典は ArchUnit を名指ししない）。確度：高。

### 3.7 BOPLA（プロパティ単位）

- 出典：API3 は、返すプロパティを明示的に選び、クライアントの入力をオブジェクトに自動で束縛しない（mass assignment を避ける）ことを求める。ASVS 5.0 の 8.2.3 も同じ。
- 対策：今の `ChangeMemberRoleRequest` は `role` を `^(ADMIN|MEMBER)$` で限定している。権限を増やしても、リクエストの DTO に権限の名前を入れない。確度：高。

### 3.8 JWT などの長く生きるトークンに権限を埋める

- 出典：ASVS 5.0 の 8.3.2 は、認可の判断に使う値の変更を即座に反映することを求め、反映できない場合（自己完結のトークン）は、認可を失った利用者の操作を検知して戻す緩和策を求める（レベル 3）。
  RFC 9068 の §2.2.3.1 は、JWT のアクセストークンに `groups`、`roles`、`entitlements` を入れてよいとする（許容であり、推奨ではない）。
  Auth0 は、権限をアクセストークンに入れると、取得の呼び出しが減る一方、トークンが大きくなると書く（[AUTH0-ENABLE]）。
  WorkOS は、権限の slug がセッションの JWT に入り、cookie の上限（多くのブラウザで 4KB）があるため、slug を短くするよう書く（[WORKOS-CONFIG]）。
- 対策：権限は、リクエストごとにサーバー側で所属から決める。こちらは、ブラウザにトークンを渡さず、セッション + 所属の引き直しで、この形を満たしている。
- 案 B の注意（推論）：役割からの権限の展開は、`getAuthorities()` の読み出しのときにコードの登録簿から計算し、権限の集合をセッションに保存しない。
  保存すると、登録簿を変えたデプロイの後も、古い集合がセッションに残り、役割が変わるまで反映されない。
  `docs/web-api/authentication-and-session.md` の「ログインの後の利用者」は、セッションに直列化するクラスを変えるデプロイで、既存のセッションを消す手順を要すると書く。
  確度：高（出典）、中（案 B の注意は推論）。

### 3.9 ワイルドカード `*` と万能の役割

- 出典：Google は、基本の役割（Owner、Editor、Viewer）は何千もの権限を含むため、本番では、他に手段がない場合を除き付与しないよう書く（[GCP-SECURE]「Least privilege」）。
  Google のカスタム役割では、権限の名前にワイルドカードを使えない（[GCP-CUSTOM]）。
  Azure は、ワイルドカードで将来の操作まで含まれるため、`Actions` と `DataActions` を明示するよう勧める。ただし組み込みの Contributor は `*` を持ち、`Microsoft.Authorization/*/Write` を `NotActions` で除く（[AZ-CUSTOM]「Wildcard permissions」、[AZ-ROLEDEF]）。
  Salesforce の `View All Data` と `Modify All Data` は、共有設定を上書きするので慎重に付けるよう書く（[SF-TRAIL]）。
  Kubernetes の RBAC は、権限が加算だけで、deny の規則がない（[K8S-RBAC]）。
- 対策：権限の文字列に `*` を許さない。`ADMIN` の権限は、登録簿に列挙した集合にして、スナップショットのテストで固定する。
  Spring の `hasAuthority` は文字列の完全一致なので、`order:*` は `order:read:all` に一致しない（使い捨ての確認で確かめた、C1）。
  `all` は「ワイルドカード」ではなく、決めた scope の 1 つとして登録簿に載せる。確度：高。

### 3.10 既定で拒否していない

- 出典：OWASP の Authorization Cheat Sheet は「Deny by Default」を求め、フレームワークの既定に頼らず、明示した設定を勧める。
  API5 も既定の拒否を求める。PostgreSQL の RLS は、有効化した表に方針がなければ、既定で全て拒否になる（[PG-RLS]）。
  Spring のリファレンスの例は、`anyRequest().denyAll()` を末尾に置く（[SS-HTTP]の `authorizeHttpRequests` の例）。
- 今の状態（推論）：`SecurityConfig` の末尾は `anyRequest().authenticated()` で、認証済みなら通る。
  これは「認証」の既定であり、「権限」の既定の拒否ではない。権限のアノテーションを付け忘れた操作は、選んだテナントの全メンバーに開く。
- 対策：権限のない業務の操作を、アノテーションの検査（ArchUnit）で落とす。`denyAll()` に変えるかは、決めること（open）に残す。確度：中。

### 3.11 テナントの境界が、全ての検査の一部になっていない

- 出典：AWS の SaaS Lens の SEC 1 は、利用者をテナントに結びつける SaaS identity を、認証と認可のモデルの一部にし、全ての層へ伝えることを求める（[AWS-SAAS-LENS]）。
  OWASP の Multi-Tenant cheat sheet は、クライアントが送るテナント ID は「選ぶだけ」とみなし、認証した主体にその所属があるかを確かめ、検証済みの値だけを伝えるよう書く（§1）。
  ASVS 5.0 の 8.4.1 は、操作が権限のないテナントに影響しないテナント横断の制御を求める（レベル 2）。
- 対策：テナントは権限の scope に入れず、選んだテナント（`TenantContext`）と RLS で外側から掛ける。
  今の構成は、テナントをセッションの権限だけから取り、リクエストの header、パス、本文から取らない（`TenantSelectionFilter` の Javadoc）。この点は出典と一致する。
- AWS はテナントの文脈をトークンに入れる例を示すが、こちらは同じ文脈を、セッションと所属の引き直しで持つ。方式は違うが目的は同じ（推論）。確度：高。

### 3.12 役割の管理 API による権限の昇格

- 出典：Kubernetes の RBAC は、役割の作成と更新を、自分がその役割の全ての権限を持つ場合、または `escalate` 動詞を明示で許可された場合に限る。
  役割の割り当て（binding）も、参照する役割の全ての権限を持つか、`bind` 動詞を許可された場合に限る。これは API 側で強制される（[K8S-RBAC]「Privilege escalation prevention and bootstrapping」）。
  AWS は、permissions boundary で、委任された管理者が付けられる権限の上限を決める（[AWS-BOUNDARIES]「Delegating responsibility to others using permissions boundaries」）。
  Azure は、役割の定義の `AssignableScopes` が、役割を作成、削除、更新、閲覧できる主体を決める。
  `AssignableScopes` にワイルドカードを使えないのは、役割の定義を更新して、ある scope へのアクセスを得られないようにするためである。
  組み込みの Contributor は、役割の割り当てを管理できない（`Microsoft.Authorization/*/Write` を除く）（[AZ-CUSTOM]、[AZ-ROLEDEF]）。
  GitHub は、`Manage custom organization roles`（作成、更新、削除）が、役割の割り当てを許さないとする（[GH-ORG-ROLES]）。
  OWASP の Multi-Tenant cheat sheet は、テナントの管理者の権限が、全体の監査へのアクセスを与えてはならないと書く（§8）。
- 対策（今の構成で効くもの）：役割を変えられるのは ADMIN だけで、ADMIN は ADMIN と MEMBER にしか変えられないので、自分より強い権限を作れない。
  最後の ADMIN を残す規則（`Tenant.java:117-124`）は、役割の管理に固有の不変条件として有効である。
  案 C に進むときは、Kubernetes の規則（持っていない権限は付けられない）と、システムの役割の変更不可を足す。確度：高。

### 3.13 権限の登録簿がなく、版の管理もない

- 出典：Google は、カスタム役割に対応する権限の一覧と、ライフサイクルの段階（ALPHA、BETA、GA、DISABLED）を持ち、非推奨の権限は、役割を基にコピーするときに外される（[GCP-CUSTOM]）。
  AWS は、操作、資源、条件キーの一覧を機械で読める JSON で出す（[AWS-IAM-ACTION]、Service Authorization Reference）。
  WorkOS は、役割と権限の slug を不変にし、Azure は、役割の名前を変えても役割 ID は変わらず、スクリプトでは ID を使うよう勧める（[AZ-ROLEDEF]）。
  ASVS 5.0 の 8.1.1 は、認可の規則（関数単位、データ単位）を文書に書くことを求める（レベル 1）。
  GitHub は、REST の応答に `X-Accepted-GitHub-Permissions` header を付け、エンドポイントに必要な権限を伝える（[GH-FINEGRAINED]）。
- 対策：権限の登録簿（1 つのクラス）と、権限ごとの説明、役割の対応を、同じ場所に置く。権限の名前は変えない前提にして、変えるときは移行を書く。
  案 B は役割の対応がコードなので、名前の変更はコードの変更で済む。案 C は DB に権限の文字列が入るので、名前の変更が DB の移行になる（推論）。確度：高（出典）、中（対策）。

### 3.14 認証のない内部の呼び出し（イベント、バッチ）が検査を素通りする、または落ちる

- 出典：OWASP の Multi-Tenant cheat sheet の「Tenant-Aware Asynchronous Work」は、消費側で、生産側の経路を認証し、テナントの文脈を作り直し、操作と対象の資源を認可し、遅れて実行される場合は、所属や権限が古くなっていないかを再確認するよう書く。
  ASVS 5.0 の 8.3.3 は、資源へのアクセスの判断を、仲介するサービスの権限ではなく、元の主体の権限で行うことを求める（レベル 3）。
- Spring の挙動（確認済み）：`@PreAuthorize` のメソッドを、`SecurityContext` に認証がない状態で呼ぶと、`AuthenticationCredentialsNotFoundException` になる（`AuthorizationManagerBeforeMethodInterceptor.java:295-300`、C1 で動作を確認）。
  これは `AccessDeniedException` ではなく `AuthenticationException` の系統である。
- 推論：`@ApplicationModuleListener` は別のスレッドで動くため、`SecurityContextHolder` の既定（スレッドごと）の認証を持たない。
  `OrderConfirmedListener` が呼ぶ `ChargeOrderCommandHandler` に `@PreAuthorize` を付けると、Listener の呼び出しが落ちる。
  対策は、(a) 権限の宣言を Controller に置く、(b) 利用者起点の CommandHandler と、Listener 起点のものを分ける、(c) システムの主体を作って Listener に付ける、のどれかで、(c) は ASVS 8.3.3 の趣旨と緊張する。
  案の選択は「決めること」に残す。確度：中。

### 3.15 1 エンドポイントに 1 権限（細かすぎ）と、粗すぎ

- 出典は割れている。
  Google の権限は REST メソッドとほぼ 1 対 1（[GCP-ROLES]）、AWS の Action も API の操作ごと。これらは、顧客がカスタム役割を作れる基盤の形である。
  Stripe の制限付き API キーは、リソースごとに `None`、`Read`、`Write` の 3 段で、`Write` は `Read` を含む（[STRIPE-RAK]）。Stripe の取得したページに載る役割は固定の一覧で、利用者が役割を定義する記述は見当たらなかった（[STRIPE-ROLES]）。
  WorkOS は、権限の slug を短くするよう書く（JWT の大きさのため）。
  GitHub のカスタム役割は、基にする役割に、細かい操作の権限を 1 つずつ足す形である（[GH-CUSTOM-ROLES]）。
- 推論：粒度は、「別々に付与する必要がある最小の違い」で決まる。
  今は、ADMIN と MEMBER の差と、`own` と `all` の差だけが違いなので、細かくする必要はない。
  細かく切るほど、登録簿、期待の表、検査の行が増える。コマンドごと（`order:confirm` と `order:cancel`）に分けるのは、別の役割がそれを分けて付ける必要が出たときに足す。確度：中。

## 4. 権限の名前と粒度

### 結論

`resource:action[:scope]`、小文字、区切りは `:`。scope は `own` と `all` の 2 つから始める。
登録簿は 1 つのクラスで、権限の名前を変えない。

### 出典の記法

| 出典                   | 形                             | 例                                                                                                                          |
| ---------------------- | ------------------------------ | --------------------------------------------------------------------------------------------------------------------------- |
| AWS IAM                | `サービス:Action`              | `s3:GetObject`。大文字小文字を区別しない。`*` と `?` を使える（[AWS-IAM-ACTION]）                                           |
| Google Cloud           | `SERVICE.RESOURCE.VERB`        | `compute.instances.list`。カスタム役割で `*` は使えない（[GCP-ROLES]、[GCP-CUSTOM]）                                        |
| Azure                  | `Provider/resourceType/action` | `Microsoft.Storage/storageAccounts/blobServices/containers/read`。`*` を使えるが、明示を勧める（[AZ-ROLEDEF]、[AZ-CUSTOM]） |
| Auth0                  | `action:resource`              | `read:reports`、`read:balance`（[AUTH0-RBAC]、[AUTH0-SCOPES]）                                                              |
| WorkOS                 | `resource:action`              | `users:view`。許す区切りは `-.:_*`。共通の命名規則を決めるよう勧める（[WORKOS-CONFIG]）                                     |
| Spring Security の文書 | `resource:action` に近い       | `permission:read`（[SS-METHOD]`#favor-granting-authorities`）。例であり、規則ではない                                       |
| GitHub                 | 自然文の項目                   | 「Delete code scanning results」「Manage webhooks」（[GH-CUSTOM-ROLES]）                                                    |

### 出典が割れている点

- **語順**：資源が先（Google、Azure、WorkOS）か、操作が先（Auth0、AWS の `service:Action` は事実上サービスが先で操作が後）。
  OAuth の scope は `read:resource` が慣習だが、これは権限ではなく、トークンの scope である。
- **ワイルドカード**：AWS と Azure は許し、Google のカスタム役割は許さない。Azure は許すが勧めない。
- **粒度**：3.15 に書いたとおり、REST メソッドごと（Google）から、`None`、`Read`、`Write` の 3 段（Stripe）まで割れている。
- **scope の語彙**：標準の語彙は見つからなかった。使われている語は、`own` と `all`（Jira DC）、User、Business Unit、Parent: Child Business Unit、Organization（Dynamics）である。

### こちらの推薦（推論、出典は選択の理由として併記）

- **形**：`<resource>:<action>` を基本にし、結果が持ち主で変わる操作にだけ `:<scope>` を足す。
  例：`order:read:own`、`order:read:all`、`order:update:own`、`order:update:all`、`product:register`、`tenant-member:manage`。
  理由：資源を先にすると、登録簿を資源ごとに並べやすい。WorkOS の推奨と、Spring の文書の例と同じ語順である。
- **資源の名前**：`ordering` や `payment` のモジュール名でなく、業務の名詞（`order`、`payment`、`product`）にする。モジュールの再編で変えなくて済む。
- **action**：`read`、`create`、`update`、`delete` を基本にし、別の役割が分けて付ける必要がある業務の動詞（`confirm`、`cancel`）は、そのときに足す。
  Stripe の `Write` が `Read` を含む形（書ける人は読める）は、含意を `RoleHierarchy` で表せば再現できる。
- **scope**：`own`（作った本人）と `all`（選んだテナントの全て）。`all` は `own` を含む（Dynamics の「上のレベルは下を含む」と同じ）。
  `tenant` や `platform` を scope に入れない。テナントは認可の外側の境界で、テナントをまたぐ操作は別の主体と別の権限の体系にする（OWASP Multi-Tenant cheat sheet §5、§8）。
- **文字**：小文字の英数字、`-`、`:`。`*` は禁止。大文字小文字を区別しない AWS と違い、Spring の `hasAuthority` は完全一致で区別する（確認済み）。
- **登録簿**：1 つのクラス（`Permissions` の String 定数）に全ての権限を置き、各権限に説明を付ける。
  アノテーションの値には、String 定数を使う（enum は使えない。確認済みの C2 と、`ExpressionTemplateSecurityAnnotationScanner.java` の変換が `Enum#name()` を使うため）。
- **版**：権限の名前は変えない。削除は「どの役割にも付けない」から始め、リリースを 1 つ置いて消す。名前の変更は、新旧の両方を一時的に付ける（推論。Google の DISABLED と同じ段階の考え方）。
- **文書化**：ASVS 8.1.1 に従い、権限、役割、操作の対応を `docs/` に書く。OpenAPI の操作に必要な権限を載せる案（拡張フィールド）は、GitHub の `X-Accepted-GitHub-Permissions` に近い。採用は決めること。

確度：中（形式は推論、各社の記法は高）。

## 5. テナントが決める役割（カスタムロール）

### 結論

ベンダーは、固定の役割を既定にし、テナントごとの役割は追加機能として足す。
足す場合は、権限の上限、システムの役割の変更不可、数の上限、昇格の防止を、同時に設計している。

### 出典が言うこと

- **WorkOS**：既定では、組織はカスタムロールを持たず、環境（アプリ全体）の役割を継承する。
  最初のカスタムロールを作ると、その組織は自分の既定の役割と優先順位を持つ。
  カスタムロールの slug は組織の中で一意で、API で slug を指定する場合は `org-` で始める。slug は作成後に変えられない。
  理由は、固定の役割では足りない組織（例：メンバーにより弱い権限を与えたい）に、他の組織へ影響せずに応えるためである（[WORKOS-CUSTOM]）。確度：高。
- **GitHub**：カスタムリポジトリロールは、GitHub Enterprise Cloud だけで作れる。最大 20 個（GitHub Enterprise Server 3.19 より前は 5 個）。
  基にする役割（Read、Triage、Write、Maintain）を選び、権限の一覧から追加する。基にする役割に含まれる権限は、追加できない。
  役割を割り当てられるのは、リポジトリの admin である（[GH-CUSTOM-ROLES]）。
  カスタムの組織の役割は、`Manage custom organization roles`（作成、閲覧、更新、削除）が、割り当てを許さない（[GH-ORG-ROLES]）。確度：高。
- **Azure**：カスタム役割は、テナントごとに 5,000 個まで。役割を作れるのは、全ての `AssignableScopes` で役割の書き込みの権限を持つ主体（Owner、User Access Administrator など）である。
  `AssignableScopes` はルート（`/`）にできず、ワイルドカードも使えない。`DataActions` を持つ役割は、管理グループの scope に割り当てられない（[AZ-CUSTOM]「Custom role limits」）。確度：高。
- **Google Cloud**：カスタム役割は、組織またはプロジェクトの階層にだけ置ける（フォルダには置けない）。1 つの役割は最大 3,000 個の権限、全体で 64KB。
  カスタム役割で使えない権限がある（対応の水準が権限ごとに決まる）。ワイルドカードは使えない。
  `DISABLED` の段階で、役割を無効にできる（[GCP-CUSTOM]）。確度：高。
- **AWS**：権限の上限（permissions boundary）で、委任された管理者が作る利用者と役割に付けられる権限の最大を決め、権限を委任する（[AWS-BOUNDARIES]）。確度：高。
- **Kubernetes**：持っている権限だけを役割に含められる。例外は `escalate` と `bind` の動詞（3.12）。確度：高。
- **Auth0**：Organization ごとの役割を、メンバーに割り当てる。効果は和集合（加算）である（[AUTH0-RBAC]）。確度：高。

### DB のモデル（役割、役割と権限、所属と役割）について

- 出典に DB の表の設計を書いたものは見つからなかった。ベンダーが出すのは API の形（WorkOS の role の slug と権限の一覧など）である。
- 形式モデルとしては、NIST の Core RBAC の「利用者と役割の割り当て」と「権限と役割の割り当て」の 2 つの関係が、そのまま `membership_role`（テナントごと）と `role_permission` に対応する（推論）。
  役割の表は、システムの役割（`tenant_id` が空、変更不可）とテナントの役割（`tenant_id` を持つ）を区別する列が要る（推論。WorkOS の「環境の役割とカスタムロール」の区別と同じ）。
  OpenFGA は、役割のメンバーを OpenFGA に持つ場合でも、役割の名前と説明を持つ `Roles` 表はアプリの DB に要ると書く（[OPENFGA-SOT]）。

### 役割をどうテストしているか

- 出典で見つかったのは、管理者向けの予測の道具だけである。Google の Policy Simulator は、役割を変えても主体のアクセスが変わらないことを確認する（[GCP-SECURE]）。AWS の IAM Access Analyzer は、アクセスの実績から最小権限の方針を作る（[AWS-IAM-BP]）。
- 製品としてのカスタムロールの自動テストの方法は、到達した文書には書かれていない。確度：低（出典なし）。

### こちらの推論（案 C に進む場合の最小の規則）

- 権限の登録簿はコードに残す。テナントが選べるのは、登録簿にある権限の組み合わせだけにする（GCP、WorkOS と同じ）。
- テナントの管理者が付けられる権限は、自分が持つ権限の範囲に限る（Kubernetes）。
- システムの役割（`ADMIN`、`MEMBER`）は編集不可にし、カスタムの役割はそれを基に複製する形から始める（GitHub の「inherited role」）。
- 役割の管理（作成、変更、割り当て）は、データの権限と別の権限にする（Azure の Contributor、GitHub の `Manage custom organization roles`）。
- 1 テナントの役割の数に上限を置く（GitHub の 20 個が、出典の中で最小の上限）。
- 最後の ADMIN の規則を、役割の編集（権限を外して ADMIN でなくす）にも掛ける。

確度：中（ベンダーの制限は高、DB の表の設計とテストは出典なし）。

## 6. Spring Security 7 の仕組み

### 版

- `backend/build.gradle:16` は Spring Boot `4.2.0`、`spring-boot-dependencies-4.2.0.pom:205` は `spring-security.version` を `7.2.0` とする。Spring Framework は 7.1.0 である。
- `docs/web-api/authentication-and-session.md` と先行の調査は、7.1.2 で確かめた。ビルドが使う版は 7.2.0 で、Gradle のキャッシュには両方ある。
- この節のリファレンスは tag `7.2.0`（`00d500621a`）の `docs/modules/ROOT/pages/` の asciidoc と、同じ tag のソースを読んだ。

### 出典で確かめたこと

- **権限の作られ方**：`Authentication` は `GrantedAuthority` の一覧を持ち、`AuthenticationManager` が入れ、`AuthorizationManager` が認可の判断で読む（[SS-ARCH]`#authz-authorities`）。
  こちらのリポジトリは、`TenantSelectionFilter` が、リクエストごとに所属を DB から引き直し、役割が変わっていれば権限を作り直してセッションに保存する（`TenantSelectionFilter.java:169-183`、`TenantSession.java`）。
- **`hasRole` と `hasAuthority`**：`hasRole("ADMIN")` は `ROLE_` を前置して `hasAuthority("ROLE_ADMIN")` と同じになる（`AuthorityAuthorizationManager.java:40,70-72`）。
  `hasRole` に `ROLE_` で始まる値を渡すと、アサートで失敗し、`hasAuthority` を使うよう促す。
  前置は `GrantedAuthorityDefaults` で変えられる（[SS-ARCH]`#jc-method-security-custom-granted-authority-defaults`）。
- **method security の有効化**：`@EnableMethodSecurity` が要る。Spring Boot の starter は、既定では有効にしない（[SS-METHOD]冒頭の NOTE）。
  アノテーションのないメソッドは保護されない（`#request-vs-method` の NOTE）。
- **クラスとメソッド**：クラスのアノテーションを、メソッドのアノテーションが上書きする。2 つのインタフェースから同じアノテーションを継承すると、起動に失敗する（`#class-or-interface-annotations`）。
- **合成アノテーション**：「Using Meta Annotations」に、`@PreAuthorize("hasRole('ADMIN')")` を含む `@IsAdmin` の例がある。
  `AnnotationTemplateExpressionDefaults` の Bean を公開すると、`@PreAuthorize("hasRole('{value}')")` を含む `@HasRole("ADMIN")` のような、属性を式に差し込む形が使える（`#meta-annotations`、`Templating Meta-Annotation Expressions`）。
  差し込む値の引用符は、利用側の値に含める（`@HasAnyRole(roles = { "'USER'", "'ADMIN'" })`）。
  Spring 自身のテスト `core/src/test/java/.../ExpressionTemplateSecurityAnnotationScannerTests.java` にも、テンプレートの属性を持つ合成アノテーションがある（`@HasPermission(object = "{value}")`）。
- **`AuthorizationManager` の自作**：`@EnableMethodSecurity(prePostEnabled = false)` にして、`AuthorizationManagerBeforeMethodInterceptor.preAuthorize(manager)` を公開できる。`MethodAuthorizationDeniedHandler` も同じクラスで実装できる（`#custom-authorization-managers`）。
- **`PermissionEvaluator`**：SpEL の `hasPermission(#c, 'write')` が、`PermissionEvaluator` に委譲する（`#authorization-expressions`、`hasPermission` の行）。対象のオブジェクトごとの判断の入口である。
- **`@PostFilter` と SQL**：メモリでの絞り込みは高くつくので、データ層での絞り込みを検討するよう書く（`#use-postfilter` の NOTE）。
- **拒否の HTTP への写像**：`AuthorizationDeniedException` は `AccessDeniedException` を継承する（`AuthorizationDeniedException.java:30`）。
  `ExceptionTranslationFilter` は、`AccessDeniedException` と `AuthenticationException` を HTTP の応答に変える。認証されていないか `AuthenticationException` なら認証の開始、そうでなければ access denied になる（[SS-SERVLET]`#servlet-exceptiontranslationfilter`）。
  こちらは、`@ControllerAdvice` の `ApiExceptionHandler` が `AccessDeniedException` を 403 の Problem Details にする（`ApiExceptionHandler.java:71-80`）。`TenantNotSelectedException` も `AccessDeniedException` の派生である。
- **認証がないとき**：method security のインターセプターは、`SecurityContext` に認証がなければ `AuthenticationCredentialsNotFoundException` を投げる（`AuthorizationManagerBeforeMethodInterceptor.java:295-300`）。

### こちらの推論

- `@RequiresPermission` の `hasAuthority` への展開は、リファレンスの `hasRole` の例の `hasAuthority` 版であり、リファレンスに明示の例はない。動作は C1 と C2 で確かめた。
- 合成アノテーションは、Controller のメソッドにも CommandHandler にも付けられる。置く層を決めること（3.14）。
  - Controller に置く案：Listener の呼び出しに影響しない。HTTP の入口だけが検査される。他のモジュールが CommandHandler を直接呼ぶ経路は、検査されない。
  - CommandHandler に置く案：全ての呼び出しが検査される（先行の調査の案）。Listener が呼ぶ CommandHandler に付けると落ちるので、Listener 専用の経路を分けるか、システムの主体が要る。
- `docs/backend/java-coding.md:80-92` の「プロキシで動くアノテーション」の規則（同じクラスからの呼び出し、`private`、`final`）は、`@PreAuthorize` を含み、`ProxyRulesArchTest` が検査している。合成アノテーションも同じ扱いになる（同ファイルの記述）。

確度：高（出典と実行で確認した部分）、中（層の選択の評価は推論）。

## 7. テスト

### 出典が言うこと

- **OWASP Authorization Testing Automation Cheat Sheet**：認可は「機能」と「論理上の役割」の 2 軸（データで絞る場合は「データ」を足した 3 軸）で、表計算にして認可の表（authorization matrix）にする。
  この表を、人が読んで更新でき、プログラムが処理でき、実装の技術から独立した形（pivot の形式）にし、結合テストがそれを入力にして全ての組み合わせを流す。
  失敗したときは、どの組が表に反することかが分かるようにする（[OWASP-AUTHZ-TEST]「Proposition」）。確度：高。
- **OWASP Authorization Cheat Sheet**：設計で決めた権限が正しく強制されることを確かめるテストを作る。権限の見直しで「privilege creep」を探す。
  単体テストと結合テストで、既定の拒否、失敗時の安全な終了、ABAC の方針の強制を確かめる。ただし、単体テストは手動の試験の代わりにならない（[OWASP-AUTHZ-CS]「Create Unit and Integration Test Cases for Authorization Logic」「Enforce Least Privileges」）。確度：高。
- **API1**：認可の仕組みの脆弱性を評価するテストを書き、失敗する変更を出さない。
- **OWASP Multi-Tenant cheat sheet**：RLS の表ごとに、テナントをまたぐ操作が拒否され、同じテナントの操作が成功することを確かめる。
  カバレッジは、スキーマ（`pg_class.relrowsecurity` と `pg_policies`）から機械で拾い、新しい表が分類されていなければ失敗させる。
  手で持つ表の一覧は、漏れが起きる同じ見落としで、検査の対象からも漏れるので、スキーマから拾う（または、スキーマの変更で必ず分類を要求する）形が望ましい。
  アプリが使う本物の役割と接続経路で検査する。リクエストの経路の役割に `rolsuper` と `rolbypassrls` が付いていないことも検査する（§2「Verify Tenant Isolation」）。確度：高。
- **ASVS 5.0 V8.1.1**：認可の規則を文書にする。テストの期待を作る元になる。
- **Spring Security Test**：`@WithMockUser(authorities = {...})` で、`ROLE_` を前置しない権限を直接与えられる（[SS-TEST]`#test-method-withmockuser`）。こちらのリポジトリは、独自の testkit（`AppUsers.oidcLogin()`）を使う規約である（`docs/backend/testing-code-style.md:39`）。
- **方針の変異（mutation）**：NIST の RBAC のページは、Kuhn の論文（2011）の紹介として、ABAC の規則の脆弱性の検出条件に階層があり、ある種類を検出するテストが他の種類も検出することを保証する、と書く（[NIST-RBAC-PROJ]の「RBAC vs. ABAC」）。論文本体は読んでいない。確度：低。

### こちらの推論：どれが「本番の方針から期待を導かない手書きの表」に合うか

- **合う**：OWASP の認可の表（役割 × 機能の表を、実装と別の artifact として持つ）。
  先行の調査（#315）の、操作の enum の `switch` 式で期待を書き、`default` を置かず、操作の足し忘れをコンパイルで止める形と同じ思想である。
  列は操作する人の種類（持ち主、同じテナントの別メンバー、ADMIN、別のテナント）で、期待の値は人が書く。
- **行の漏れだけは機械で拾う**：OWASP Multi-Tenant の指摘（手で持つ一覧は漏れる）に対し、行は OpenAPI の契約から拾い、テスト側の宣言と突き合わせる。
  期待の値（許可か拒否か）は手で書く。これは ADR-077 と `ApiContractCoverageTest` の形と同じである。
- **案 B で足すテスト**：
  1. 役割 → 権限のスナップショット：`ADMIN` と `MEMBER` の権限の集合を、テストに手で書く。役割に権限が増えたら失敗する。万能の役割の検出になる。本番の登録簿から生成しない。
  2. 登録簿の整合：全ての `@RequiresPermission` の値が登録簿にあること。登録簿の全ての権限が、少なくとも 1 つの役割に付くこと（死んだ権限の検出）。
  3. 操作 × 操作する人の表（HTTP の結合テスト）：許可、403、404 の期待を手で書く。
  4. 全ての業務の操作が権限を宣言するか、理由付きの許可リストにあることを、ArchUnit で検査する（推論。出典は API5 と Spring の NOTE が動機）。
  5. 否定のテスト：別のテナントの資源、持ち主でない注文、最後の ADMIN、自分の役割の変更（ADMIN が自分を MEMBER にする場合）。
  6. 案 C に進むとき：役割と権限の任意の組み合わせは、期待の表が爆発する。代わりに、性質のテスト（持っていない権限は付けられない、権限を外すと拒否になる）にする（推論）。
- **mutation testing**：こちらは PIT のワークフロー（`.github/workflows/mutation-test.yml`）を持つ。
  役割 → 権限の対応と `ReadScope` の決定を対象にすれば、表の検出力を測れる（推論）。方針の変異を勧める出典は、上の NIST のページだけで確度は低い。

確度：高（OWASP の推奨）、中（案 B のテストの組み立ては推論）。

## 8. RBAC とスコープを超える目安

### 出典が言うこと

- **NIST SP 800-162**：RBAC は、複数の要素による判断（場所、研修の履歴など）を表しにくく、動的な判断のために役割を増やすと「role explosion」になる（§2.1、p.6）。
  ABAC は、方針の変更の影響を探しやすく、主体と対象を事前に結びつけなくてよい。ただし、企業規模の ABAC は、開発、導入、運用の費用が大きく、事業上の根拠を先に作り、限られた対象から段階的に始めるのが現実的とする（§3.1.1、p.18-19）。確度：高。
- **OWASP Authorization Cheat Sheet**：ABAC と ReBAC を RBAC より優先する。ただし、これは一般論で、閾値は書かない（[OWASP-AUTHZ-CS]）。確度：高（記述の存在）。
- **OpenFGA（ベンダー）**：RBAC で足りる条件は、役割が少数（admin、editor、viewer）、単一のテナント、資源ごとの共有がなく、数百万の対象に対し「このユーザーが見える全部」を答える必要がない場合である。
  関係の engine が合う条件は、関係が書き込みのときに変わる（グループ、共有、階層、テナントの所有）、判断のデータが大きすぎて毎回渡せない、逆引き（見える全部）が要る、資源ごと、利用者ごとの権限が要る場合である。
  方針の engine（OPA、Cedar）は、判断が属性中心で、必要なデータが要求の時点で手元にある場合に合う（[OPENFGA-RBAC-VS]、[OPENFGA-POLICY]）。確度：中（ベンダーの主張）。
- **Keycloak**：役割だけの判断は、柔軟性に欠け、文脈がないとする。方針で ABAC、RBAC、文脈、時間などを合成できる（[KEYCLOAK-AZ]）。確度：高（ただし自社の製品の説明）。
- **Zanzibar**：Google の複数のサービスで認可を共通化し、一貫性（ACL の更新の順序）と規模（数兆の ACL、毎秒数百万の要求）の問題を一度で解く目的である（§1）。「new enemy」問題は、ACL の更新の順序を守らないと、外した人が新しい内容を見てしまう問題で、整合性のトークン（zookie）で解く（§2.2、§2.4.4）。確度：高（論文）。
- **Cedar と Amazon Verified Permissions**：Cedar は RBAC と ABAC の両方を表す方針の言語である。Verified Permissions は、Cedar の方針を持ち、決定を返すホストされたサービスである（[CEDAR-HOME]、[AWS-AVP]）。
- 数値の閾値（役割の数、テナントの数、リクエスト数）を示した一次資料は見つからなかった。

### こちらの推論（移行の引き金）

次のどれかが現れたら、案 D の検討を始める。

1. 利用者同士が個別の資源を共有する（「この注文を同僚と共有」）。
2. 部署や上司の階層で見える範囲が決まる。
3. テナントをまたぐ共有や、代理がある。
4. 金額、時間帯、状態のような属性の条件が、権限の組み合わせでは表せなくなる。
5. 「見える全部」を、アプリの DB だけで絞れない（複数のデータの出所にまたがる）。
6. テナントごとの役割が増え、検査の期待の表が管理できなくなる。

今は、関係が「注文の持ち主」の 1 種類だけで、上の 6 つのどれもない。
OpenFGA の `ListObjects` は、既定で 1000 件と 3 秒の上限があり（[OPENFGA-QUERY]）、SQL のページングの代わりにならない。engine を入れても、結局 SQL の絞り込みは残る（[OPENFGA-SOT]）。

確度：中（閾値の数値は出典なし、条件は推論）。

## 9. このリポジトリの設計案の比較

### 比較

| 観点             | A：固定の役割 + `hasRole`                                                                                      | B：役割は登録簿（コード）で権限の集合に対応づけ、`hasAuthority`                                                                            | C：テナントが決める役割（`role`、`role_permission`、`membership_role`）                                          | D：方針エンジン                                                                                                 |
| ---------------- | -------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| 正しさ           | `own` と `all` を役割の名前から分岐する。分岐が Controller や検索に散る。BFLA の漏れは、表のテストでだけ止まる | 検査が権限で、役割はその束。`own` と `all` を権限で区別し、SQL の絞り込みに渡せる。付け忘れは ArchUnit で止める（推論）                    | B と同じ。加えて、役割の編集が昇格の経路になる（3.12）。権限の集合を、リクエストごとに DB から引く（遅延、古さ） | 判断が方針に集約される。SQL の絞り込みは別に要る。engine と DB の一貫性（Zanzibar の new enemy 問題）を持ち込む |
| 保守の費用       | 低い（今は 2 役割）。要件が増えるほど分岐が増える                                                              | 低から中。登録簿（1 クラス）と役割の対応を保守。権限を足すたびに登録簿とテスト                                                             | 高い。管理の API と画面、移行、監査、上限、役割の複製の規則                                                      | 高い。engine の運用、方針の書き方の学習、版の管理                                                               |
| 柔軟性           | 低い。役割が増えると破綻（Keycloak の限界）                                                                    | 中。役割と権限の対応の変更はデプロイ。検査は変わらない                                                                                     | 高い。顧客が役割を決められる                                                                                     | 最高。ただし ReBAC か ABAC の要件があるときだけ意味がある                                                       |
| テストしやすさ   | 表が小さく、簡単                                                                                               | 手書きの期待の表と、役割のスナップショットで足りる（7 の案 B のテスト）                                                                    | 任意の組み合わせで期待の表が爆発する。性質のテストに切り替える                                                   | 方針のテスト（engine の言語）と、アプリとの結合の両方が要る                                                     |
| 今からの移行費用 | 0                                                                                                              | 小。`TenantMembershipAuthority` が権限も出す、`hasRole` を 1 か所置き換え、`@EnableMethodSecurity`、登録簿、ArchUnit。DB と API の変更なし | 大。表、API、画面、既存データ、昇格の防止、権限の登録簿                                                          | 大。engine の導入と運用、データの同期                                                                           |
| 出典の支え       | 支える出典はない。Keycloak、Spring、WorkOS は、この形を避ける理由を書く                                        | NIST の Core RBAC、WorkOS（権限に抽象化するとコードを変えずに役割を調整）、Spring（権限に変換）、Auth0（役割は権限の束）                   | WorkOS、GitHub、Azure、Google。ただし、いずれも固定の役割の上に足す追加機能                                      | OpenFGA、Cedar、NIST（費用が大きい）。条件は 8                                                                  |

### 推薦：B

根拠は 5 つある。

1. 検査が権限になる。Keycloak、WorkOS、Spring の 3 つが、役割の検査を避ける理由を書いており、案 A はこれに反する。
2. 近い将来の要求（#188 の `own` と `all`、#758 の ADMIN だけ）は、2 役割と 2 つの scope で表せる。案 C は、その要求に対して過剰で、`docs/principles/right-sizing.md` の「将来の変化に備える仕組みは、変化の根拠を説明できる場合だけ入れる」に反する。
3. ベンダーは、固定の役割を基本にし、カスタムロールをその上に足す形（WorkOS）か、上位のプランに限る形（GitHub）で出している。まず固定の役割と権限で始めるのは、この順序と一致する。
4. B から C への移行で、検査の箇所は変わらない（推論）。変わるのは、役割から権限への対応を持つ場所だけである（コードの登録簿から DB へ）。
   WorkOS は、「検査を権限に抽象化すれば、役割と権限の割り当てをコードの変更なしに調整できる」と書き、これが前提になる。
5. 既存の認証の流れと整合する。権限はリクエストごとに DB の所属から決まり（ADR-101）、ASVS 8.3.2 の「変更を即座に反映」を満たす。

B の弱点と、そのときの条件を書く。

- 役割と権限の対応を変えるにはデプロイが要る。テナントごとの調整は、できない。条件：顧客がテナント独自の役割を要求したら、C を検討する。
- 付け忘れた操作は、選んだテナントの全メンバーに開く。条件：ArchUnit の検査を同じ変更で入れる。
- 権限の集合をセッションに保存しないこと（3.8）。条件：`getAuthorities()` で、役割から都度展開する。
- Listener の経路と検査の層（3.14、6）。条件：「決めること」の 5 を先に決める。

### 案 B の最小の形（推論。実装の指示ではない）

- 権限は String 定数の登録簿。役割は `Role` の enum と、それに対応する権限の集合を、同じクラスか隣に持つ。
- `TenantMembershipAuthority` は、役割の権限（`ROLE_ADMIN` を残す）と、その役割の権限を展開した `GrantedAuthority` を返す。
  `ROLE_` の権限を残すかは、`hasRole` を全て消せるかによる。
- `@RequiresPermission` は `@PreAuthorize("hasAuthority('{value}')")` の合成アノテーション。`AnnotationTemplateExpressionDefaults` の Bean と `@EnableMethodSecurity` が要る。
- `all` が `own` を含む関係は、`RoleHierarchy` で表す。
- 持ち主の絞り込みは、`ReadScope` を決める 1 か所と、Repository の WHERE で行う。

## 決めること（オーナー）

| #   | 決めること                   | 選択肢                                                     | 材料                                                                                                                                                                                                                                            |
| --- | ---------------------------- | ---------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | scope の語彙                 | `own` と `all` だけか、`tenant` などを足すか               | 4 の推薦は `own`、`all`。テナントは外側の境界。出典に標準の語彙なし                                                                                                                                                                             |
| 2   | `all` を持つ役割             | ADMIN だけ、MEMBER にも読みだけ                            | #188 の「管理者に全件を見せるか」。Salesforce の `View All` は強い上書きで、慎重に付けるよう書く                                                                                                                                                |
| 3   | ADMIN と MEMBER の既定の権限 | 表で決める（資源 × 操作 × 役割）                           | 7 の役割のスナップショットのテストに、同じ表を手で書く                                                                                                                                                                                          |
| 4   | `own` を成立させる持ち主の列 | 全ての資源に `created_by` を使うか、持ち主の列を別に持つか | `created_by` は監査のための列で、利用者の操作でない処理では処理の名前が入る（`postgresql-common-columns.md:34`）。作成者と持ち主が分かれる場合（代理で作る、持ち主を移す）は、Dynamics の Assign や Salesforce の持ち主の変更のような操作が要る |
| 5   | 権限の宣言を置く層           | Controller、CommandHandler、両方                           | 3.14 と 6。Listener が呼ぶ CommandHandler に付けると落ちる                                                                                                                                                                                      |
| 6   | 権限の宣言のない操作の既定   | `authenticated()` のまま、`denyAll()` に変える             | 3.10。ArchUnit の検査と両方入れるかも                                                                                                                                                                                                           |
| 7   | 権限の粒度                   | CRUD だけか、`order:confirm` のような業務の動詞も          | 3.15。分ける必要が出たときに足す案が出典と合う                                                                                                                                                                                                  |
| 8   | `Role` の持ち方              | enum のまま、権限の登録簿と同じクラス                      | 案 B の形                                                                                                                                                                                                                                       |
| 9   | Listener とバッチの主体      | 主体なしでテナントの文脈だけ、システムの主体を作る         | ASVS 8.3.3 と OWASP Multi-Tenant の非同期の節。先行の ADR-105 の `TenantContext` の運びがすでにある                                                                                                                                             |
| 10  | 権限の登録簿の文書化         | `docs/` の表だけか、OpenAPI に必要な権限を載せるか         | ASVS 8.1.1。GitHub の `X-Accepted-GitHub-Permissions`                                                                                                                                                                                           |
| 11  | 自分の役割を変える操作       | ADMIN が自分を MEMBER にするのを許すか                     | 最後の ADMIN の規則は既にある。自己昇格は、ADMIN と MEMBER の 2 つなので起きない                                                                                                                                                                |
| 12  | 案 C に進む引き金            | 顧客の要求、役割の数、テナントごとの調整のどれか           | 8 の引き金。決まっていなければ、再構築ポイントとして ADR に書く（`right-sizing.md`）                                                                                                                                                            |
| 13  | ADR を起こすか               | 起こす                                                     | 認可の置き場所と権限の登録簿は、戻しにくく、複数のモジュールに影響する（ADR の基準に当てはまる）                                                                                                                                                |

## 確かめたこと

### Spring Security の実行（使い捨ての確認）

Spring Security `7.2.0`、Spring Framework `7.1.0`、Java 25（`25.0.5`）で、Gradle のキャッシュの jar を使い、`/tmp/permcheck` に書いた使い捨てのプログラムを走らせた。
アプリのコードは変えていない。プログラムはどこにも commit していない。

C1：`@EnableMethodSecurity` と `AnnotationTemplateExpressionDefaults` の Bean を持つ文脈で、`@RequiresPermission(String)`（`@PreAuthorize("hasAuthority('{value}')")` の合成アノテーション）を付けたメソッドを呼んだ。

- 認証なし：`AuthenticationCredentialsNotFoundException`（`AccessDeniedException` ではない）。アノテーションのないメソッドは通った。
- 権限 `order:read:all` を持つ：`order:read:all` のメソッドは成功、`order:read:own` のメソッドは `AuthorizationDeniedException`（`AccessDeniedException` の派生）。
- 権限 `order:*`：`order:read:all` のメソッドは拒否（ワイルドカードは一致しない）。
- 権限 `ADMIN`（前置なし）：`hasRole('ADMIN')` は拒否。`ROLE_ADMIN`：成功。
- `ROLE_ADMIN` だけ（階層なし）：`order:read:all` のメソッドは拒否。
- 呼び出された Bean は AOP のプロキシだった。
- `RoleHierarchy`（`ROLE_ADMIN > order:read:all`、`ROLE_MEMBER > order:read:own`）を `DefaultMethodSecurityExpressionHandler` に設定すると、`ROLE_ADMIN` は `order:read:all` を通り、`ROLE_MEMBER` は `order:read:own` だけを通った。

C2：

- 権限同士の階層 `order:read:all > order:read:own` で、`order:read:all` だけを持つ主体が `order:read:own` のメソッドを通った。逆は拒否された。
- クラスに付けた合成アノテーション（`own` が必要）と、メソッドに付けたもの（`all` が必要）は、メソッドのものが上書きした。
- アノテーションの属性を enum にすると、式に差し込まれる値は `toString()` ではなく `name()` で、`order:read:own` を返す enum では権限に一致しなかった（`ORDER_READ_OWN` なら一致）。
  ソースでは、`ExpressionTemplateSecurityAnnotationScanner.java` の `extractMetaAnnotationProperties` が、文字列以外の値を `ConversionService` で文字列にする。enum は `name()` になる。
  String 定数なら、期待どおり動いた。

### リポジトリの確認

- `backend/src/main` に `@EnableMethodSecurity`、`@PreAuthorize`、`hasAuthority` はない。`hasRole("ADMIN")` は `TenantSecurityCustomizer.java:45` の 1 か所だけである。
- `TenantSelectionFilter.java:169-183` は、リクエストごとに所属を引き直す。
- 全ての `m_` 表は共通カラム `created_by` を持つ（`docs/database/postgresql-common-columns.md:25`）。
- `ChargeOrderCommandHandler` は、Listener（`OrderConfirmedListener.java`）から呼ばれる。

### 確かめていないこと

- HTTP の `authorizeHttpRequests` で、権限の階層（`order:read:all > order:read:own`）が効くか。method security でだけ確かめた。リファレンスは、フィルタベースの認可でも `RoleHierarchy` が使えると書く（`#authz-hierarchical-roles`）。
- `@ApplicationModuleListener` のスレッドで `SecurityContext` が空であること。Spring Modulith 2.2.0 のソースは読んでいない。Spring Security の既定の戦略がスレッドごとであることからの推論である。
- 合成アノテーションを Controller に付けたときの OpenAPI（springdoc）への影響。
- カスタムロール（案 C）の DB の設計の実例。出典に見つからなかった。

## 出典

取得日はすべて 2026-11-25。各項目は、出典名、URL、参照した節の順に書く。

### 標準と OWASP

- [NIST-RBAC-FAQ] NIST CSRC, RBAC FAQ：<https://csrc.nist.gov/projects/role-based-access-control/faqs> 「What Is Role Based Access Control?」「Standard Structure」「Component summary」
- [NIST-RBAC-PROJ] NIST CSRC, Role Based Access Control（ARCHIVED PROJECT）：<https://csrc.nist.gov/projects/role-based-access-control> 「Overview」「Background」「RBAC vs. ABAC」
- [NIST-RBAC-2000] Sandhu, Ferraiolo, Kuhn, "The NIST Model for Role-Based Access Control: Towards a Unified Standard"（2000）：<https://csrc.nist.gov/pubs/conference/2000/07/26/nist-model-for-rbac-towards-a-unified-standard/final> 要旨のみ。本文は読んでいない。
- [NIST-800-162] NIST SP 800-162, Guide to ABAC：<https://doi.org/10.6028/NIST.SP.800-162> （PDF <https://nvlpubs.nist.gov/nistpubs/specialpublications/NIST.SP.800-162.pdf）§2（RBAC、ABAC）、§2.1（The> Benefit of ABAC）、§3.1.1。ページは PDF の 15、16、28、29。
- [ASVS5-V8] OWASP ASVS 5.0, V8 Authorization：<https://github.com/OWASP/ASVS/blob/master/5.0/en/0x17-V8-Authorization.md> 8.1.1、8.2.1 から 8.2.3、8.3.1 から 8.3.3、8.4.1。
- [ASVS4-V4] OWASP ASVS 4.0.3, V4 Access Control：<https://github.com/OWASP/ASVS/blob/v4.0.3/4.0/en/0x12-V4-Access-Control.md> 4.1.1 から 4.1.5、4.2.1。
- [API1] OWASP API Security Top 10 2023, API1 Broken Object Level Authorization：<https://owasp.org/API-Security/editions/2023/en/0xa1-broken-object-level-authorization/> 「How To Prevent」
- [API3] 同, API3 Broken Object Property Level Authorization：<https://owasp.org/API-Security/editions/2023/en/0xa3-broken-object-property-level-authorization/> 「How To Prevent」
- [API5] 同, API5 Broken Function Level Authorization：<https://owasp.org/API-Security/editions/2023/en/0xa5-broken-function-level-authorization/> 「How To Prevent」
- [OWASP-AUTHZ-CS] OWASP Authorization Cheat Sheet：<https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html> 「Enforce Least Privileges」「Deny by Default」「Validate the Permissions on Every Request」「Prefer Attribute and Relationship Based Access Control over RBAC」「Verify that Authorization Checks are Performed in the Right Location」「Create Unit and Integration Test Cases for Authorization Logic」
- [OWASP-AUTHZ-TEST] OWASP Authorization Testing Automation Cheat Sheet：<https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Testing_Automation_Cheat_Sheet.html> 「Context」「Proposition」
- [OWASP-MT] OWASP Multi-Tenant Security Cheat Sheet：<https://cheatsheetseries.owasp.org/cheatsheets/Multi_Tenant_Security_Cheat_Sheet.html> §1、§2「Verify Tenant Isolation」、§3、§5「Tenant-Aware Asynchronous Work」、§8
- [RFC9068] RFC 9068, JWT Profile for OAuth 2.0 Access Tokens：<https://www.rfc-editor.org/rfc/rfc9068.html> §2.2.3.1

### クラウドと SaaS のベンダー

- [AWS-IAM-ACTION] <https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_policies_elements_action.html> 「IAM JSON policy elements: Action」
- [AWS-IAM-BP] <https://docs.aws.amazon.com/IAM/latest/UserGuide/best-practices.html> 「Apply least-privilege permissions」「Use permissions boundaries to delegate permissions management within an account」
- [AWS-BOUNDARIES] <https://docs.aws.amazon.com/IAM/latest/UserGuide/access_policies_boundaries.html> 「Delegating responsibility to others using permissions boundaries」
- [AWS-SAAS-LENS] <https://docs.aws.amazon.com/wellarchitected/latest/saas-lens/identity-and-access-management.html> 「SaaS SEC 1」
- [AWS-SAAS-FUND] <https://docs.aws.amazon.com/whitepapers/latest/saas-architecture-fundamentals/tenant-isolation.html> 「Tenant Isolation」
- [AWS-AVP] <https://docs.aws.amazon.com/verifiedpermissions/latest/userguide/what-is-avp.html>
- [GCP-ROLES] <https://cloud.google.com/iam/docs/roles-overview> 「Permissions」「Basic roles」
- [GCP-CUSTOM] <https://cloud.google.com/iam/docs/creating-custom-roles> 「Custom role limits」に当たる節（権限の数、ワイルドカード、launch stage）
- [GCP-SECURE] <https://cloud.google.com/iam/docs/using-iam-securely> 「Least privilege」
- [AZ-ROLEDEF] <https://learn.microsoft.com/en-us/azure/role-based-access-control/role-definitions>
- [AZ-CUSTOM] <https://learn.microsoft.com/en-us/azure/role-based-access-control/custom-roles> 「Wildcard permissions」「Custom role limits」
- [AZ-BP] <https://learn.microsoft.com/en-us/azure/role-based-access-control/best-practices>
- [GH-REPO-ROLES] <https://docs.github.com/en/organizations/managing-user-access-to-your-organizations-repositories/managing-repository-roles/repository-roles-for-an-organization>
- [GH-CUSTOM-ROLES] <https://docs.github.com/en/organizations/managing-peoples-access-to-your-organization-with-roles/about-custom-repository-roles>
- [GH-ORG-ROLES] <https://docs.github.com/en/organizations/managing-peoples-access-to-your-organization-with-roles/about-custom-organization-roles>
- [GH-FINEGRAINED] <https://docs.github.com/en/rest/authentication/permissions-required-for-fine-grained-personal-access-tokens>
- [DYN-PRIV] <https://learn.microsoft.com/en-us/power-platform/admin/security-roles-privileges> 「Privileges」「Access levels」
- [JIRA-DC] Atlassian, Jira Data Center の管理文書（Cloud ではない）：<https://confluence.atlassian.com/adminjiraserver/managing-project-permissions-938847145.html>
- [SF-TRAIL] Salesforce Trailhead, Manage Salesforce Object Access：<https://trailhead.salesforce.com/content/learn/modules/data_security/data_security_objects> 「Setting Up Profiles」
- [STRIPE-ROLES] <https://docs.stripe.com/get-started/account/teams/roles（日本語のページを取得）>
- [STRIPE-RAK] <https://docs.stripe.com/keys/restricted-api-keys（日本語のページを取得）>
- [AUTH0-RBAC] <https://auth0.com/docs/manage-users/access-control/rbac> 「RBAC Model」「Roles in Auth0 Organizations」「Overlapping role assignments」
- [AUTH0-ENABLE] <https://auth0.com/docs/manage-users/access-control/configure-core-rbac/enable-role-based-access-control-for-apis>
- [AUTH0-SCOPES] <https://auth0.com/docs/get-started/apis/scopes/api-scopes>
- [WORKOS-CONFIG] <https://workos.com/docs/rbac/configuration> 「Configure permissions」
- [WORKOS-CUSTOM] <https://workos.com/docs/rbac/custom-roles>
- [WORKOS-INTEG] <https://workos.com/docs/rbac/integration> 「Single vs. multiple roles」
- [KEYCLOAK-AZ] <https://www.keycloak.org/docs/latest/authorization_services/index.html> 冒頭の「Role-based access control (RBAC)」の説明
- [K8S-RBAC] <https://kubernetes.io/docs/reference/access-authn-authz/rbac/> 「Privilege escalation prevention and bootstrapping」

### エンジンと PostgreSQL

- [ZANZIBAR] Pang ら, "Zanzibar: Google's Consistent, Global Authorization System"（USENIX ATC 2019）：<https://research.google/pubs/zanzibar-googles-consistent-global-authorization-system/> （PDF <https://storage.googleapis.com/gweb-research2023-media/pubtools/5068.pdf）§1、§2.4。>
- [OPENFGA-RBAC-VS] <https://openfga.dev/docs/learn/rbac-vs-rebac.md（ベンダーの文書）>
- [OPENFGA-POLICY] <https://openfga.dev/docs/learn/policy-engine.md（ベンダーの文書）>
- [OPENFGA-SOT] <https://openfga.dev/docs/best-practices/source-of-truth.md（ベンダーの文書）>
- [OPENFGA-QUERY] <https://openfga.dev/docs/interacting/relationship-queries> 「ListObjects」
- [CEDAR-HOME] <https://docs.cedarpolicy.com/> 「Introduction」。[CEDAR-TERMS] <https://docs.cedarpolicy.com/overview/terminology.html>
- [OPA] <https://www.openpolicyagent.org/docs> 冒頭のみ
- [PG-RLS] <https://www.postgresql.org/docs/current/ddl-rowsecurity.html>

### Spring Security（tag `7.2.0`、<https://github.com/spring-projects/spring-security> の asciidoc とソース）

- [SS-METHOD] `docs/modules/ROOT/pages/servlet/authorization/method-security.adoc`（公開版 <https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html）。アンカー：`#favor-granting-authorities`、`#request-vs-method`、`#class-or-interface-annotations`、`#meta-annotations`、`#custom-authorization-managers`、`#use-postfilter`、`#authorization-expressions`。>
- [SS-ARCH] `.../servlet/authorization/architecture.adoc`。アンカー：`#authz-authorities`、`#jc-method-security-custom-granted-authority-defaults`、`#authz-hierarchical-roles`。
- [SS-HTTP] `.../servlet/authorization/authorize-http-requests.adoc`（`anyRequest().denyAll()` の例）。
- [SS-SERVLET] `.../servlet/architecture.adoc` の `#servlet-exceptiontranslationfilter`。
- [SS-DATA] `.../servlet/integrations/data.adoc` の冒頭。
- [SS-TEST] `.../servlet/test/method.adoc` の `@WithMockUser`。
- ソース：`core/src/main/java/org/springframework/security/authorization/AuthorityAuthorizationManager.java:40,70-72`、`.../authorization/method/AuthorizationManagerBeforeMethodInterceptor.java:295-300`、`.../authorization/AuthorizationDeniedException.java:30`、`.../core/annotation/ExpressionTemplateSecurityAnnotationScanner.java`。
- Spring Boot 4.2.0 の依存の版：`spring-boot-dependencies-4.2.0.pom:205`（`spring-security.version` は 7.2.0）、`:196`（Spring Framework 7.1.0）。

### 到達できなかった、または使えなかった出典

- AWS Prescriptive Guidance「Multi-tenant SaaS authorization and API access control」（`.../saas-multitenant-api-access-authorization/`）：HTTP 403。AWS の SaaS の認可は、Well-Architected の SaaS Lens と SaaS Architecture Fundamentals で代用した。確度は下がる。
- Salesforce の開発者文書とヘルプ（`developer.salesforce.com/docs/...`、`help.salesforce.com`）：HTTP 403 か、JavaScript の描画で本文なし。`ObjectPermissions` の `View All Records` の定義は読めていない。Trailhead だけを使った。
- INCITS 359-2012 の標準本体：有料で、読んでいない。NIST の FAQ とプロジェクトのページで代用した。
- Sandhu、Ferraiolo、Kuhn 2000 年の論文の本文（NIST の PDF の URL は 404）、ACM TISSEC 版：読んでいない。
- Dynamics の `security-concepts` ページ：404。`security-roles-privileges` を使った。
- Okta FGA：`docs.fga.dev` は Auth0 FGA の文書として表示され、内容は OpenFGA の説明と重なるため、OpenFGA の文書を使った。
- OWASP Top 10 の A01：リダイレクトのみで本文なし。OWASP の cheat sheet と API Security を使った。
- Jira Cloud の権限の一覧：本文に項目名がなく、Data Center の管理文書を使った。Cloud でも同じ名前かは確かめていない。
- Slack、Atlassian Cloud、Stripe の細かい権限の一覧：調べていない（Stripe は役割と制限付きキーの 2 ページだけ）。
- Salesforce の組織の既定の共有と役割の階層：Trailhead の別のモジュールの検索結果の抜粋だけで、ページ本文は読んでいない。確度は低い。
