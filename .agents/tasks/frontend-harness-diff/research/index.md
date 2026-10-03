# frontend の調査の索引

旧リポジトリ（spring-modulith-ai-harness の yaguchi/frontend-setup ブランチ）との比較で挙げたトピックについて、標準、ベストプラクティス、アンチパターン、デファクトスタンダードを調べた結果の索引である。
各トピックの根拠、既存の規約との整合、利用者が決める論点は担当ファイルに書いてある。

## 調査ファイル

- [a-data-api.md](a-data-api.md)：グループ A（データ取得と API）。C3、C7、C2 を扱う。
- [b-routing-ui.md](b-routing-ui.md)：グループ B（ルーティング、UI、構成）。C6、C8、C13、C15、C16、C5 を扱う。
- [c-lint-agent.md](c-lint-agent.md)：グループ C（Lint、型、エージェント）。C11、C12、C14、C9、C10、C17 を扱う。

C9、C10、C17 は c-lint-agent.md の一つの節にまとめてあり、五つの見出しを三トピックで共有する。

## トピックごとの推奨案

### C2 CSRF token の送信

`src/api/` に fetch wrapper を一つ置いて Orval の `override.mutator` に指定し、更新系の要求だけに `XSRF-TOKEN` cookie の値を `X-XSRF-TOKEN` header で載せる。
同じ wrapper で非 2xx を `status` と Problem Details を持つ例外にする。
担当：[a-data-api.md](a-data-api.md)。
出典：[Spring Security の CSRF（cookie の token repository）](https://docs.spring.io/spring-security/reference/7.1/servlet/exploits/csrf.html#csrf-token-repository-cookie)、[OWASP CSRF Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#javascript-automatically-including-csrf-tokens-as-an-ajax-request-header)、[Orval の custom fetch の例](https://github.com/orval-labs/orval/blob/v8.36.0/samples/react-query/custom-fetch/src/custom-fetch.ts)。

### C3 mutation 後の cache 無効化

`QueryClient` に `MutationCache` の global callback を一つ置き、mutation の完了時に `invalidateQueries()` で全体を無効化する。
個別に書くときは生成された `get<List>QueryKey()` を使い、配列の key を手書きせず、`router.invalidate` も呼ばない。
担当：[a-data-api.md](a-data-api.md)。
出典：[TanStack Query の Invalidations from Mutations](https://github.com/TanStack/query/blob/%40tanstack%2Freact-query%405.103.2/docs/framework/react/guides/invalidations-from-mutations.md)、[TkDodo の Automatic Query Invalidation after Mutations](https://tkdodo.eu/blog/automatic-query-invalidation-after-mutations)。

### C5 ファイル名の表記

kebab-case に統一する。
現 lint はすでに kebab-case を強制しており、PascalCase と書いている `component-design.md` と ADR-032 の記述を直す。
担当：[b-routing-ui.md](b-routing-ui.md)。
出典：[oxlint の unicorn/filename-case](https://oxc.rs/docs/guide/usage/linter/rules/unicorn/filename-case.html)、[Git の core.ignoreCase](https://git-scm.com/docs/git-config#Documentation/git-config.txt-coreignoreCase)。

### C6 route の命名と画面の分け方

一覧、新規、詳細、編集は `index.tsx` を使う route で書き、`_` 接尾辞を標準にしない。
共通の `beforeLoad` とアプリシェルは pathless layout の `route.tsx` に置き、component を持たせるなら `<Outlet />` を描画する。
担当：[b-routing-ui.md](b-routing-ui.md)。
出典：[TanStack Router の File Naming Conventions](https://tanstack.com/router/latest/docs/framework/react/routing/file-naming-conventions)、[Pathless Layout Routes](https://tanstack.com/router/latest/docs/framework/react/routing/routing-concepts#pathless-layout-routes)。

### C7 楽観ロックの 409 Conflict

backend が競合に業務固有の problem `type` を割り当て、frontend はその `type` で分岐する。
競合時は入力を保持したまま最新を取得して通知し、破棄するか最新の版に適用し直すかを利用者に選ばせる。
担当：[a-data-api.md](a-data-api.md)。
出典：[RFC 9110 の 409 Conflict](https://www.rfc-editor.org/rfc/rfc9110#section-15.5.10)、[RFC 9457 の type](https://www.rfc-editor.org/rfc/rfc9457#section-3.1)、[WCAG 2.2 の Status Messages](https://www.w3.org/WAI/WCAG22/Understanding/status-messages.html)。

### C8 状態表示とアプリシェル

Loading と Error は loader の preload と route の境界（router の既定 component）で受け、画面の component は Empty と Content だけを分ける。
primitive は shadcn の `Skeleton`、`Empty`、`AlertDialog` を `components/ui` に入れ、router 既定の状態表示とアプリシェルの置き場所を新たに決める。
担当：[b-routing-ui.md](b-routing-ui.md)。
出典：[TanStack Router の Data Loading](https://tanstack.com/router/latest/docs/framework/react/guide/data-loading#the-route-loading-lifecycle)、[shadcn の Empty](https://ui.shadcn.com/docs/components/base/empty)、[WAI-ARIA 1.2 の status role](https://www.w3.org/TR/wai-aria-1.2/#status)。

### C9 生成物への書込み拒否

`routeTree.gen.ts` と将来の `src/api/generated/**` への書込みを `.kiro/hooks/` の PreToolUse hook で拒否し、最終の検出は CI の再生成差分に置く。
担当：[c-lint-agent.md](c-lint-agent.md)。
出典：[Kiro の Hooks](https://kiro.dev/docs/hooks/)、[Claude Code の Hooks](https://code.claude.com/docs/en/hooks)。

### C10 エージェントが止まる条件

「API が OpenAPI に無いときは手書きの fetch や仮実装で回避せず確認を取る」を、理由と取るべき行動とともに `docs/frontend/runbook-add-feature.md` に書く。
ADR-038 に従い、AGENTS.md や steering には書かない。
担当：[c-lint-agent.md](c-lint-agent.md)。
出典：[Claude の Prompting Best Practices](https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/claude-prompting-best-practices)。

### C11 floating promise の扱い

`no-void` を `allowAsStatement: true` に調整し、`void` は reject しない Promise か内部で失敗を処理した Promise にだけ使う。
async 関数を event handler に直接渡さない。
担当：[c-lint-agent.md](c-lint-agent.md)。
出典：[typescript-eslint の no-floating-promises](https://typescript-eslint.io/rules/no-floating-promises/)、[ESLint の no-void](https://eslint.org/docs/latest/rules/no-void)。

### C12 exactOptionalPropertyTypes と生成型

生成型には条件付き spread で値を渡し、現設定と矛盾する `no-undefined` を off にする。
Orval を入れるときは `override.zod.exactOptional: true` を検討する。
担当：[c-lint-agent.md](c-lint-agent.md)。
出典：[TSConfig の exactOptionalPropertyTypes](https://www.typescriptlang.org/tsconfig/#exactOptionalPropertyTypes)、[ESLint の no-undefined](https://eslint.org/docs/latest/rules/no-undefined)。

### C13 Link を Button の見た目で描画する方法

`<Link className={buttonVariants(...)}>` で書き、`Button` の `render` に link を渡す形は Base UI が role を `button` で上書きするので使わない。
`buttonVariants` の公開方法を決める。
担当：[b-routing-ui.md](b-routing-ui.md)。
出典：[Base UI の Rendering links as buttons](https://base-ui.com/react/components/button#rendering-links-as-buttons)、[HTML の a 要素](https://html.spec.whatwg.org/multipage/text-level-semantics.html#the-a-element)。

### C14 追加の lint 規則

`radix`、`no-new-wrappers`、`consistent-type-definitions`、親ディレクトリへの相対 import の禁止はカテゴリ指定ですでに有効である。
足すのは Tailwind の規則一つで、`enforce-canonical-classes` を選ぶ。
担当：[c-lint-agent.md](c-lint-agent.md)。
出典：[eslint-plugin-better-tailwindcss の enforce-canonical-classes](https://github.com/schoero/eslint-plugin-better-tailwindcss/blob/v4.7.0/docs/rules/enforce-canonical-classes.md)、[ESLint の radix](https://eslint.org/docs/latest/rules/radix)。

### C15 enum の表示 mapping と区分値

区分値は現 `response-body.md` のとおり frontend の定数と message catalog で持つ。
網羅は生成型の union と `satisfies Record<...>`、型付き catalog で検査し、色は Badge の variant か semantic token で表す。
担当：[b-routing-ui.md](b-routing-ui.md)。
出典：[TypeScript 4.9 の satisfies](https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-9.html#the-satisfies-operator)、[i18next の TypeScript](https://www.i18next.com/overview/typescript#type-error-template-literal)。

### C16 component の分割の目安

数値の上限は lint（import 10、300 行、1 ファイル 1 component）に任せる。
docs には、責務の境界で分けることと、状態表示を route の境界へ出すと分割が減ることだけを書く。
担当：[b-routing-ui.md](b-routing-ui.md)。
出典：[React の Thinking in React](https://react.dev/learn/thinking-in-react)、[oxlint の import/max-dependencies](https://oxc.rs/docs/guide/usage/linter/rules/import/max-dependencies.html)。

### C17 SessionStart と Stop の hook

今は入れない。
pre-commit、pre-push、CI の検査と重なり、毎 turn の待ち時間が増えるためである。
担当：[c-lint-agent.md](c-lint-agent.md)。
出典：[Claude Code の Best Practices](https://code.claude.com/docs/en/best-practices)、[Kiro の Hooks のベストプラクティス](https://kiro.dev/docs/hooks/best-practices/)。

## 追加の調査ファイル

- [d-feature-sharing.md](d-feature-sharing.md)：T1（feature 間で機能を共有する形）。C1 を扱う。
- [e-kiro-hooks-v3.md](e-kiro-hooks-v3.md)：T2（Kiro CLI 3 の hook の書き方）。C9、C17 を CLI 3 の書式で見直す。
- [f-out-of-scope.md](f-out-of-scope.md)：T3（frontend 以外の二つの気づき）。HTTP caching と区分値、Spring の `csrf.spa()` を扱う。
- [g-backend-optimistic-locking.md](g-backend-optimistic-locking.md)：T4（楽観ロックと 409 の backend の現状）。C7 の前提を確かめる。

f-out-of-scope.md は二つの気づきのそれぞれに五つの見出しを持つ。
g-backend-optimistic-locking.md は事実の確認なので、確認した事実、判定、frontend の 409 の規約に与える影響の三つの見出しで書いてある。

## 追加のトピックごとの推奨案

### T1 feature 間で機能を共有する形

feature から別 feature のファイルと route への import を例外なく禁じ、data は生成 query options を直接使い、画面の合成は route で行う。
公開ファイルは今は作らず、禁止は jsPlugin と二つの静的 override で書く。
担当：[d-feature-sharing.md](d-feature-sharing.md)。
根拠：同ファイルの「推奨案」の節（bulletproof-react の project-structure.md、FSD の Cross-imports、TanStack Router の `getRouteApi`、ADR-032 の 66、70、79 行目）。

### T2 Kiro CLI 3 の hook の書き方

新しい hook は `.kiro/hooks/*.json` の v1 形式だけで書き、PreToolUse で止めるときは exit 2 だけを使う。
C9 を入れる前に `fs_write` と `str_replace` の `tool_input.path` を一度確かめ、C17 は CLI 3 でも入れない。
担当：[e-kiro-hooks-v3.md](e-kiro-hooks-v3.md)。
出典：[Kiro の Hooks](https://kiro.dev/docs/hooks/)、[Hook actions](https://kiro.dev/docs/hooks/actions/)、[Hooks migration](https://kiro.dev/docs/cli/v3/hooks-migration/)。

### T3-1 cache の例示と区分値

`docs/web-api/headers.md` の 47 行目の例示を区分値からマスタに替え、許可するときは `Cache-Control: private` と `ETag` を明示させる（案 A）。
`response-body.md` は変えない。
担当：[f-out-of-scope.md](f-out-of-scope.md)。
出典：[RFC 9111 の 5.2.2.7](https://www.rfc-editor.org/rfc/rfc9111#section-5.2.2.7)、[RFC 9111 の 3.5](https://www.rfc-editor.org/rfc/rfc9111#section-3.5)。

### T3-2 csrf.spa() の照合方式

ADR-014 に naive double-submit cookie であることと、同じ registrable domain に信頼できない host を置かない前提を書く。
C2 の実装と同じ変更で token の cookie 名を `__Host-XSRF-TOKEN` にし、Fetch Metadata の自前 filter と synchronizer token への切替えは今は行わない。
担当：[f-out-of-scope.md](f-out-of-scope.md)。
出典：[OWASP の Naive Double-Submit Cookie Pattern](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#naive-double-submit-cookie-pattern-discouraged)、[RFC 6265bis の 4.1.3.2](https://datatracker.ietf.org/doc/html/draft-ietf-httpbis-rfc6265bis-22#section-4.1.3.2)。

### T4 楽観ロックと 409 の backend の現状

backend の楽観ロックは規約だけで、`lock_no` 列、競合の例外、409 の type はまだ無い。
frontend の 409 の規約には、自動で再送しない、入力を捨てないといった今書ける内容だけを先に書き、type の値は backend の業務 API と同時に加える。
担当：[g-backend-optimistic-locking.md](g-backend-optimistic-locking.md)。
根拠：同ファイルの「判定」の節（ADR-013 の未決事項、ADR-016 の 63 行目、Problem Details の normalize の 46 から 54 行目）。
