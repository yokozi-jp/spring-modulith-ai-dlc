# グループ A の調査：データ取得と API（C3、C7、C2）

[report.md](../report.md) の C3、C7、C2 について、標準、ベストプラクティス、アンチパターン、デファクトスタンダードを一次情報で調べた結果である。
「確認済み」は文書またはソースコードを読んで確かめた事実、「推測」はそれ以外を指す。
Orval の生成コードは実際に出力しておらず、v8.36.0 のジェネレータのソースと公式 sample から読み取った。
現リポジトリのコード、docs、設定は変更していない。

## 調べた版

| 対象 | 版 | 根拠 |
| --- | --- | --- |
| `@tanstack/react-query` | 5.103.2 | `frontend/package.json` |
| `@tanstack/react-router` | 1.170.38 | `frontend/package.json` |
| `@tanstack/react-form` | 1.33.5 | `frontend/package.json` |
| `orval` | 8.36.0 | `frontend/package.json` |
| Spring Security | 7.1.1 | `backend/build.gradle` の Spring Boot 4.1.1 と、[spring-boot-dependencies 4.1.1 の pom](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom) の `spring-security.version` |

TanStack と Orval の文書は、上の版の Git tag にある Markdown を読み、その tag へリンクしている。

## 三つのトピックに共通する二つの事実

### F1. Orval の fetch client は既定で非 2xx を例外にしない

Orval 8.36.0 の fetch ジェネレータは、エラー応答を例外にせず値として返すことを既定にしている（確認済み、[packages/fetch/src/index.ts の 510 行目](https://github.com/orval-labs/orval/blob/v8.36.0/packages/fetch/src/index.ts#L510)）。
`!res.ok` で例外を投げるコードは、`override.fetch.forceSuccessResponse` が `true` のときだけ生成される（確認済み、[同 886 から 927 行目](https://github.com/orval-labs/orval/blob/v8.36.0/packages/fetch/src/index.ts#L886-L927)、[output の forceSuccessResponse](https://github.com/orval-labs/orval/blob/v8.36.0/docs/content/docs/reference/configuration/output.mdx#L2729-L2734)、既定は `false`）。
一方、TanStack Query は queryFn が例外を投げるか reject したときだけ error 状態にし、`fetch` のように例外を投げない client では利用者が自分で投げる必要があると明記している（確認済み、[Query Functions](https://github.com/TanStack/query/blob/%40tanstack%2Freact-query%405.103.2/docs/framework/react/guides/query-functions.md)）。

したがって、mutator も `forceSuccessResponse` も無い既定構成では、409 や 403 の応答で mutation が成功扱いになり、`onSuccess` に書いた無効化と画面遷移が実行され、`onError` は呼ばれない（ソースからの帰結、実行による確認はしていない）。
custom mutator を使う場合は、応答の解釈と例外化は mutator の責務になる（確認済み、[output の includeHttpErrorResponse](https://github.com/orval-labs/orval/blob/v8.36.0/docs/content/docs/reference/configuration/output.mdx#L2787)）。
Orval 公式 sample の mutator も非 2xx で例外を投げず、`application/json` を含む `Content-Type` だけを JSON として読む（確認済み、[samples/react-query/custom-fetch/src/custom-fetch.ts](https://github.com/orval-labs/orval/blob/v8.36.0/samples/react-query/custom-fetch/src/custom-fetch.ts)）。
`application/problem+json` は文字列 `application/json` を含まないため、この sample をそのまま写すと Problem Details が JSON ではなく文字列として返る。

この事実により、C3 と C7 は C2（mutator）か `forceSuccessResponse` のどちらかが入るまで正しく動かない。

### F2. Orval の既定の query key は URL の文字列を先頭要素にする

React Query 向けの既定では、query key の先頭要素は route の template literal であり、続けて query parameter の object が入る（確認済み、[query-generator.ts の 1425 から 1497 行目](https://github.com/orval-labs/orval/blob/v8.36.0/packages/query/src/query-generator.ts#L1425-L1497)、[frameworks/index.ts の 85 から 90 行目](https://github.com/orval-labs/orval/blob/v8.36.0/packages/query/src/frameworks/index.ts#L85-L90)）。
公式 sample の生成物では、一覧が `` [`/pets`, ...(params ? [params] : [])] ``、詳細が `` [`/pets/${petId}`] `` になっている（確認済み、[pets.ts の 162 行目と 592 行目](https://github.com/orval-labs/orval/blob/v8.36.0/samples/react-query/custom-fetch/src/gen/pets/pets.ts#L162-L164)）。

TanStack Query の `invalidateQueries` は配列の要素単位で前方一致させる（確認済み、[Query Invalidation](https://github.com/TanStack/query/blob/%40tanstack%2Freact-query%405.103.2/docs/framework/react/guides/query-invalidation.md)）。
そのため、一覧の key で無効化しても、先頭要素の文字列が違う詳細の key は一致しない。

key の形は次の設定で変わる（確認済み、[output.mdx の 1699 から 1770 行目](https://github.com/orval-labs/orval/blob/v8.36.0/docs/content/docs/reference/configuration/output.mdx#L1699-L1770)）。

- **`shouldSplitQueryKey: true`**：route を path segment の配列にする。
  `['pets', petId]` のように階層になり、`['pets']` で一覧と詳細の両方に前方一致する。
- **`useOperationIdAsQueryKey: true`**：先頭要素を operation 名にし、全 parameter を続ける。
  operation ごとに key が独立するため、階層での一括無効化はできない。
- **`shouldExportKeys`**：既定 `true` で、`get<Operation>QueryKey` を export する。

## C3. mutation 後の cache 無効化

### 標準

この領域に RFC や W3C の仕様は無く、TanStack Query の公式文書が規範になる。

- query key は最上位が配列で、`JSON.stringify` で直列化でき、データに対して一意でなければならない（確認済み、[Query Keys](https://github.com/TanStack/query/blob/%40tanstack%2Freact-query%405.103.2/docs/framework/react/guides/query-keys.md)）。
- key は決定的に hash される。
  object の property の順序と `undefined` の property は無視され、配列の要素の順序は区別される（確認済み、同上）。
- `invalidateQueries` は一致した query を stale にし、描画中の query だけを background で再取得する。
  `exact` と `predicate` で一致範囲を絞れる（確認済み、[Query Invalidation](https://github.com/TanStack/query/blob/%40tanstack%2Freact-query%405.103.2/docs/framework/react/guides/query-invalidation.md)）。
- `useMutation` の `onSuccess` が Promise を返すと、その解決まで `isPending` が `true` のまま続く（確認済み、[Invalidations from Mutations の 45 行目](https://github.com/TanStack/query/blob/%40tanstack%2Freact-query%405.103.2/docs/framework/react/guides/invalidations-from-mutations.md#L45)）。
- `mutate` に渡した callback は、mutation の完了前に component が unmount すると実行されない（確認済み、[Mutations の 185 行目](https://github.com/TanStack/query/blob/%40tanstack%2Freact-query%405.103.2/docs/framework/react/guides/mutations.md#L185)）。
- `setQueryData` は immutable に更新しなければならない（確認済み、[Updates from Mutation Responses](https://github.com/TanStack/query/blob/%40tanstack%2Freact-query%405.103.2/docs/framework/react/guides/updates-from-mutation-responses.md)）。
- `queryOptions` は key と queryFn を一箇所にまとめ、key に型を付ける。
  公式 ESLint rule `prefer-query-options` は、key と queryFn を分けると同じ key が別の queryFn に使われる事故が起きると説明している（確認済み、[Query Options](https://github.com/TanStack/query/blob/%40tanstack%2Freact-query%405.103.2/docs/framework/react/guides/query-options.md)、[prefer-query-options](https://github.com/TanStack/query/blob/%40tanstack%2Freact-query%405.103.2/docs/eslint/prefer-query-options.md)）。

TanStack Router 側の規範は次のとおりである。

- Router は loader の結果を短期間 cache し、mutation 後は `router.invalidate` で loader のデータを無効化できる（確認済み、[Data Mutations](https://github.com/TanStack/router/blob/%40tanstack%2Freact-router%401.170.38/docs/router/guide/data-mutations.md#L40-L75)）。
- TanStack Query のような外部 cache を使うときは `defaultPreloadStaleTime` を `0` にし、鮮度の判断を外部 cache に任せる（確認済み、[Data Loading の 327 から 342 行目](https://github.com/TanStack/router/blob/%40tanstack%2Freact-router%401.170.38/docs/router/guide/data-loading.md#L327-L342)、[Preloading](https://github.com/TanStack/router/blob/%40tanstack%2Freact-router%401.170.38/docs/router/guide/preloading.md#L160-L169)）。
  現 `src/main.tsx` はこの設定済みである。
- 公式の連携例は、loader で `ensureQueryData` を呼び、component で同じ query options の `useSuspenseQuery` を使って cache の更新を購読する（確認済み、[External Data Loading](https://github.com/TanStack/router/blob/%40tanstack%2Freact-router%401.170.38/docs/router/guide/external-data-loading.md)）。
- `ensureQueryData` は cache にデータがあれば、stale でもそのまま返す。
  再取得するのは `revalidateIfStale` を指定したときだけである（確認済み、[queryClient.ts の 197 から 223 行目](https://github.com/TanStack/query/blob/%40tanstack%2Freact-query%405.103.2/packages/query-core/src/queryClient.ts#L197-L223)）。

Orval は query key と query options の取得関数を生成する。

- `get<Operation>QueryKey` と `get<Operation>QueryOptions` を operation ごとに生成し、後者の `queryKey` は `DataTag` で型付けされる（確認済み、[sample の pets.ts](https://github.com/orval-labs/orval/blob/v8.36.0/samples/react-query/custom-fetch/src/gen/pets/pets.ts#L162-L190)）。
- key の形は F2 のとおりである。

### ベストプラクティス

TanStack Query の保守者である TkDodo の推奨は次のとおりである。

- key は汎用から具体へ並べ、feature ごとに一つの key factory を作り、feature の `queries.ts` に置く。
  key を何度も手で書くと誤りやすく、後で階層を足すのも難しくなるためである（確認済み、[Effective React Query Keys](https://tkdodo.eu/blog/effective-react-query-keys)）。
- key と queryFn を分けたことは設計上の誤りだったと振り返り、`queryOptions` による query factory を勧めている（確認済み、[The Query Options API](https://tkdodo.eu/blog/the-query-options-api)）。
- 多くの場合は `setQueryData` による直接更新より無効化を選ぶ。
  直接更新は frontend にコードが増え、backend の処理を部分的に複製するためである（確認済み、[Mastering Mutations in React Query](https://tkdodo.eu/blog/mastering-mutations-in-react-query)）。
- 無効化のように必ず必要な処理は `useMutation` の callback に、画面遷移や toast のような UI の処理は `mutate` の callback に置く（確認済み、同上）。
- 無効化を await すると再取得の完了まで form を pending にでき、await しなければ一覧画面へすぐ遷移できる。
  どちらを選ぶかは画面の要件で決める（確認済み、[Automatic Query Invalidation after Mutations](https://tkdodo.eu/blog/automatic-query-invalidation-after-mutations)）。
- `MutationCache` の global callback で、mutation のたびに `invalidateQueries()` を呼んで全 query を無効化する方法を紹介している。
  無効化は描画中の query だけを再取得し、残りは stale にするだけなので、取りこぼしより多めの再取得を選ぶという trade-off である。
  絞り込みたいときは `mutationKey` や `meta` を使う（確認済み、同上）。
  TanStack Query の公式文書もこの記事を参照している（確認済み、[Invalidations from Mutations](https://github.com/TanStack/query/blob/%40tanstack%2Freact-query%405.103.2/docs/framework/react/guides/invalidations-from-mutations.md)）。

Orval は生成 key を使った無効化を設定から生成できる。

- `override.query.mutationInvalidates` に「どの mutation がどの query を無効化するか」を書くと、生成 key 関数を使う `onSuccess` が生成される。
  利用者の `onSuccess` と合成され、`skipInvalidation: true` で実行時に外せる。
  存在しない operation 名を書くと生成時に警告する（確認済み、[output.mdx の 1414 から 1517 行目](https://github.com/orval-labs/orval/blob/v8.36.0/docs/content/docs/reference/configuration/output.mdx#L1414-L1517)）。
- path parameter が必須の query を parameter 無しで指定すると、route の静的な prefix で一致させる predicate を生成する（確認済み、[mutation-generator.ts の 340 から 420 行目](https://github.com/orval-labs/orval/blob/v8.36.0/packages/query/src/mutation-generator.ts#L340-L420)）。
- 生成される無効化の呼出しは await も return もされず、利用者の `onSuccess` の戻り値も返さない（ソースを読んだ結果、[mutation-generator.ts の 476 から 489 行目](https://github.com/orval-labs/orval/blob/v8.36.0/packages/query/src/mutation-generator.ts#L476-L489)、[frameworks/index.ts の 229 から 241 行目](https://github.com/orval-labs/orval/blob/v8.36.0/packages/query/src/frameworks/index.ts#L229-L241)）。
  このため再取得の完了前に `isPending` が `false` になると読める（推測、生成物で要確認）。
- `useSetQueryData: true` で型付きの `setQueriesData` helper を生成する。
  8.11.0 以降は既存の entry だけを更新し、既定で完全一致にした（確認済み、[React Query guide の Set Query Data](https://github.com/orval-labs/orval/blob/v8.36.0/docs/content/docs/guides/react-query.mdx#L134-L176)）。

### アンチパターン

- **手書きの key で無効化する**：F2 のとおり Orval の key の先頭要素は URL の文字列なので、`['categories']` は一致せず、無効化が何も起こさないまま終わる。
  旧リポジトリが記録した不具合はこれである（[旧 frontend-data-patterns.md](https://github.com/yokozi-jp/spring-modulith-ai-harness/blob/yaguchi/frontend-setup/.kiro/steering/frontend-data-patterns.md)）。
- **全 Hook で key を手書きに上書きして合わせる**：旧リポジトリの解決策である。
  key と queryFn の引数を別々に保守することになり、`prefer-query-options` と TkDodo が避けるよう勧める分離を自ら作る。
- **一覧の key で詳細も無効化できると考える**：既定の key では一覧と詳細の先頭要素が別の文字列なので一致しない（F2）。
- **loader と component で別の引数から key を作る**：生成 key は `...(params ? [params] : [])` で作られるので、`{}` を渡すと `['/x', {}]`、`undefined` を渡すと `['/x']` になり、別の cache entry になる（推測、ジェネレータのソースからの帰結）。
  loader で温めた cache を component が使わず、取得が二重になる。
- **無効化を `mutate` の callback だけに書く**：component が先に unmount すると実行されない。
- **非 2xx を例外にしないまま `onSuccess` に無効化と遷移を書く**：F1 のとおり 409 でも成功扱いになる。
- **`router.invalidate` だけで Query の cache が更新されると考える**：loader が `ensureQueryData` を使う限り、loader を再実行しても cache の値が返るだけで再取得しない。
- **mutation の応答を形の違う cache へ `setQueryData` する**：`includeHttpResponseReturnType` が既定の `true` のとき、cache には `{ data, status, headers }` が入る（確認済み、[Fetch Client guide](https://github.com/orval-labs/orval/blob/v8.36.0/docs/content/docs/guides/fetch-client.mdx#L59-L120)）。
  mutation の応答の型と query の cache の型が一致するかを確かめずに書き込むと、画面が想定しない形のデータを読む（推測）。

### デファクトスタンダード

- **bulletproof-react**：mutation の `onSuccess` で、query options の取得関数から得た key を無効化する（確認済み、[apps/react-vite/src/features/discussions/api/create-discussion.ts](https://github.com/alan2207/bulletproof-react/blob/master/apps/react-vite/src/features/discussions/api/create-discussion.ts)）。
  key を手書きせず、query 側の定義から導く形である。
- **query-key-factory**：TanStack Query の文書が key の整理手段として紹介する community package である（確認済み、[Query Keys の Further reading](https://github.com/TanStack/query/blob/%40tanstack%2Freact-query%405.103.2/docs/framework/react/guides/query-keys.md)、[lukemorales/query-key-factory](https://github.com/lukemorales/query-key-factory)）。
  Orval が key を生成する構成では、同じ役割を生成物が担う。
- **Orval の `mutationInvalidates`**：Orval 自身が用意する宣言的な無効化である。
  採用例の規模は調べていない。
- **global な全無効化**：TkDodo は、送信のたびにすべてを無効化する Remix（React Router）の挙動に近いものが数行で得られると述べている（確認済み、[Automatic Query Invalidation after Mutations](https://tkdodo.eu/blog/automatic-query-invalidation-after-mutations)）。

### 現リポジトリへの当てはめ

**既存の規約との整合**

- [api-client-orval.md](../../../../docs/frontend/api-client-orval.md) は生成した query key と query options を正本にし、[routing-and-state.md](../../../../docs/frontend/routing-and-state.md) は生成 query options を loader と component の両方から使い、包むだけの Hook を禁じる。
  生成 key を使う案、Orval の `mutationInvalidates`、global な全無効化のどれもこの規約と衝突しない。
- [browser-storage-and-cache.md](../../../../docs/frontend/browser-storage-and-cache.md) は `staleTime` を既定値（0）のまま使う。
  描画されていない query は取得直後から stale なので、無効化が意味を持つのは描画中の query だけになる（TanStack Query の既定の挙動からの帰結）。
- 旧リポジトリの方法（全 Hook の key を手書きに上書き）は、生成物を正本にする現規約に反するので採らない。

**推奨案**

1. 前提として、C2 の mutator で非 2xx を例外にする（F1）。
2. 既定の無効化は `main.tsx` の `QueryClient` に `MutationCache` の global callback を一つ置き、mutation の完了時に `invalidateQueries()` で全体を無効化する。
   key の対応表を持たないので、手書きの key がずれる不具合が構造上起こらない。
   上限は、関係の無い描画中の query も再取得する点である。
   これが問題になったら `mutationKey` か `meta` で範囲を絞るか、Orval の `mutationInvalidates` に移す（`ponytail:` コメントで上限と移行先を書く）。
3. 個別に無効化を書くときは、`get<List>QueryKey()` を引数無しで呼んで全 filter の一覧に前方一致させ、詳細は `get<Detail>QueryKey(id)` を使う。
   引数無しで呼べるのは一覧の query parameter がすべて省略可能な場合である（ジェネレータが `params?` を生成する）。
   配列の key を手書きしない。
4. 画面遷移や toast は `mutate` の callback に置き、無効化は global callback か `useMutation` の callback に置く。
5. `router.invalidate` は呼ばない。
   component が生成 query options の `useQuery` または `useSuspenseQuery` で cache を購読していれば、`invalidateQueries` だけで画面が更新される。
   例外は、mutation の結果が `beforeLoad` や `useLoaderData` で読む値（[authorization-ui.md](../../../../docs/frontend/authorization-ui.md) の権限のように親 route の `beforeLoad` が API から取る値）を変える場合である。
6. 最初の mutation の component test（MSW）で、更新後に一覧の取得がもう一度発生することを確かめる。

**利用者が決める論点**

- 無効化の方式を global な全無効化、`mutationInvalidates`、mutation ごとの `onSuccess` のどれにするか。
- global callback を `onSuccess` に置くか `onSettled` に置くか。
  `onSettled` にすると 409 のときも最新の値を取り直すので、C7 の再取得を兼ねられる。
- 無効化を await するか。
  await すると `isPending` が再取得の完了まで続き、await しないと一覧へすぐ遷移できる。
- `shouldSplitQueryKey` を有効にするか。
  個別に無効化する方式を選ぶなら、資源単位の前方一致ができるので有効にする価値がある。
  全無効化を選ぶなら影響は小さい。

## C7. 楽観ロックの 409 Conflict を画面でどう扱うか

### 標準

- **409 Conflict**：対象資源の現在の状態との衝突で要求を完了できないことを示し、利用者が衝突を解消して再送できる場面で使う。
  server は衝突の原因を利用者が認識できる内容を返すべき（SHOULD）とされ、version 管理での PUT の衝突が例に挙がっている（確認済み、[RFC 9110 15.5.10](https://www.rfc-editor.org/rfc/rfc9110#section-15.5.10)）。
- **412 Precondition Failed**：要求 header の条件が偽と評価されたことを示す（確認済み、[RFC 9110 15.5.13](https://www.rfc-editor.org/rfc/rfc9110#section-15.5.13)）。
  `If-Match` は lost update を防ぐために状態変更の method と組み合わせて使う（確認済み、[RFC 9110 13.1.1](https://www.rfc-editor.org/rfc/rfc9110#section-13.1.1)）。
  現リポジトリは version を body で送るので header の条件が無く、412 ではなく 409 を返すのは RFC の定義に合う。
- **Problem Details**：`type` が問題種別の第一の識別子であり、`title` は助言的な要約、`detail` は利用者が問題を直すための説明である。
  consumer は `detail` を解析して情報を取り出すべきでなく（SHOULD NOT）、機械が読む情報は拡張 member で渡す。
  未知の拡張 member は無視しなければならない（確認済み、[RFC 9457 3.1](https://www.rfc-editor.org/rfc/rfc9457#section-3.1)、[3.2](https://www.rfc-editor.org/rfc/rfc9457#section-3.2)）。
- **状態メッセージ**：focus を移さずに表示する通知は、role などで支援技術に伝わるようにする（[WCAG 2.2 SC 4.1.3 Status Messages](https://www.w3.org/WAI/WCAG22/Understanding/status-messages.html)、Level AA）。
  競合の通知を画面上部に出すときに該当する。

### ベストプラクティス

主要な設計ガイドは、競合の検出方法と status code で意見が分かれる。

- **Google AIP-154**：etag が一致しない更新や削除は `ABORTED` で失敗させる（確認済み、[AIP-154](https://google.aip.dev/154)）。
  `google.rpc.Code` は `ABORTED` を HTTP 409 に対応づけ、read-modify-write をやり直すべき失敗としている。
  空でない directory の削除のように状態を直すまで再試行すべきでない失敗は `FAILED_PRECONDITION`（HTTP 400）として区別している（確認済み、[google/rpc/code.proto](https://github.com/googleapis/googleapis/blob/master/google/rpc/code.proto)）。
- **Microsoft Azure REST API Guidelines**：`If-Match` と `ETag` で条件付き要求を行い、不一致は 412 を返す。
  ETag には version 番号より表現の hash を勧める。
  version 番号だと、応答を受け取れずに同じ更新を再送したとき、自分の更新が競合として扱われるためである（確認済み、[Conditional Requests](https://github.com/microsoft/api-guidelines/blob/vNext/azure/Guidelines.md#conditional-requests)）。
- **Zalando RESTful API Guidelines**：楽観ロックの方式として ETag と `If-Match`（412）、結果 entity 内の ETag、body の version 番号（409）、`Last-Modified` を比べ、version 番号方式を「完全な楽観ロック」と評価しつつ、header に属する機能が業務 object に入る点を短所に挙げる（確認済み、[Optimistic locking in RESTful APIs](https://opensource.zalando.com/restful-api-guidelines/#optimistic-locking)、[#182](https://opensource.zalando.com/restful-api-guidelines/#182)）。

画面の扱いについての推奨は次のとおりである。

- **入力を保持する**：エラーのときは利用者の入力を残し、最初からやり直させず元の操作を直せるようにする（確認済み、[Nielsen Norman Group: Error-Message Guidelines](https://www.nngroup.com/articles/error-message-guidelines/)）。
- **再送を自動にしない**：TanStack Query は mutation を既定で再試行しない（確認済み、[Mutations の Retry](https://github.com/TanStack/query/blob/%40tanstack%2Freact-query%405.103.2/docs/framework/react/guides/mutations.md#L268-L280)）。
  同じ version で再送しても再び 409 になり、Google のガイドも同じ要求の再送ではなく読み直しからのやり直しを求めている。

TanStack Form 1.33.5 の挙動は、入力の保持に直接効く。

- `useForm` は描画のたびに `formApi.update(opts)` を呼ぶ（確認済み、[useForm.tsx の 284 から 286 行目](https://github.com/TanStack/form/blob/%40tanstack%2Freact-form%401.33.5/packages/react-form/src/useForm.tsx#L284-L286)）。
- `update` は、`defaultValues` が変わっても form が touched なら値を置き換えず、touched でなければ新しい `defaultValues` で置き換える（確認済み、[FormApi.ts の 1732 から 1790 行目](https://github.com/TanStack/form/blob/%40tanstack%2Freact-form%401.33.5/packages/form-core/src/FormApi.ts#L1732-L1790)）。
- したがって、詳細 query の値を `defaultValues` にしている form では、409 の後に詳細を再取得しても、入力済みの値は消えない（推測、ソースからの帰結）。
- 同じ理由で、form の値に入れた `lockNo` も古いまま残る。
  利用者がそのまま再送すると再び 409 になる。

### アンチパターン

- **status だけで分岐する**：現リポジトリは楽観ロックの競合、悲観ロックの取得失敗、一意制約違反をすべて 409 にする（[status-codes.md](../../../../docs/web-api/status-codes.md)）。
  旧リポジトリも、version の競合と「関連データがあるので削除できない」を同じ 409 として扱っていた（[旧 frontend-data-patterns.md の楽観ロック節](https://github.com/yokozi-jp/spring-modulith-ai-harness/blob/yaguchi/frontend-setup/.kiro/steering/frontend-data-patterns.md)）。
  原因ごとに利用者の次の行動が違うので、`type` で分ける必要がある。
- **`detail` の文字列で分岐する**：RFC 9457 と [ADR-013](../../../../docs/adr/ADR-013-standardize-http-api-contracts.md) がともに禁じている。
- **409 で同じ要求を自動で再送する**：version が古いままなので必ず失敗し、Google のガイドとも合わない。
- **409 で入力を捨てて最新の値で上書きする**：利用者の作業が失われる。
- **最新の `lockNo` を黙って差し込んで再送する**：他の利用者の変更を確認させずに上書きするので、楽観ロックで防ぎたい lost update をそのまま起こす。
- **非 2xx を例外にしない**：F1 のとおり 409 が成功として扱われる。
- **二重送信を許す**：送信中にもう一度送ると、二回目は一回目が進めた version と衝突する。
  MediaWiki は保存が遅いときの「自分自身との編集競合」を注意点として挙げている（確認済み、[Help:Edit conflict](https://www.mediawiki.org/wiki/Help:Edit_conflict)）。
  Microsoft のガイドが述べる「応答を失った更新の再送」も同じ形の偽の競合を生む。

### デファクトスタンダード

- **MediaWiki**：編集競合の画面で、上段に利用者の編集内容を残し、下段に保存済みの版との差分を出し、利用者に手で統合させる（確認済み、[Help:Edit conflict](https://www.mediawiki.org/wiki/Help:Edit_conflict)）。
  差分を見せる方式の代表例である。
- **旧リポジトリ**：409 で再読み込みを促す固定文言を表示して `refetch` し、削除時の 409 は `detail` を表示する（[旧 frontend-data-patterns.md](https://github.com/yokozi-jp/spring-modulith-ai-harness/blob/yaguchi/frontend-setup/.kiro/steering/frontend-data-patterns.md)）。
- 業務系の React OSS で 409 の画面処理を一次情報で確かめた例は、上記以外に見つけていない。

### 現リポジトリへの当てはめ

**既存の規約との整合**

- [optimistic-locking.md](../../../../docs/web-api/optimistic-locking.md) の方式（body の `lockNo`、409、DELETE は query parameter、一覧の応答にも version を含める）を前提にする。
  一覧に version があるので、旧リポジトリの「一覧に削除 button を置かない」は当てはまらない。
- [ADR-013](../../../../docs/adr/ADR-013-standardize-http-api-contracts.md) は client が `type` で問題種別を識別し、`detail` を分岐に使わないと定める。
  業務固有の problem type を初めて公開するときは安定した HTTPS URI を `type` に使うと定めているが、URI の基点は未決である。
- [i18n.md](../../../../docs/frontend/i18n.md) は Problem Details をバックエンドが解決した利用者向け文言として扱い、frontend の固定文言と混在させない。

**推奨案**

1. C2 の mutator が非 2xx を例外にし、その例外に `status` と Problem Details（少なくとも `type`）を持たせる。
2. 楽観ロックの競合に業務固有の `type` を backend で割り当て、frontend はその `type` で分岐する。
   悲観ロックの取得失敗と一意制約違反は別の `type` にする。
3. 競合したら次の順に扱う。
   - 入力中の値は残す。
     TanStack Form の form が touched なら、再取得しても値は置き換わらない。
   - 詳細の query を無効化して最新の値を取得する（C3 で `onSettled` を選べば自動で行われる）。
   - 競合を通知する。
     通知は WCAG 4.1.3 に合わせて支援技術へ伝わる形にする。
   - 利用者に「自分の変更を捨てて最新を表示する」か「最新の版に自分の変更を適用し直す」かを明示的に選ばせる。
     前者は `form.reset` で最新を入れ、後者は `lockNo` だけを最新に差し替えて再送する。
4. 差分の表示（MediaWiki の方式）は、業務で同時編集が頻繁に起こると分かるまで作らない。
5. 削除の 409 は form が無いので、通知して一覧または詳細を再取得するだけにする。
6. 送信中は `isPending` を見て二回目の送信を受け付けない。
   [form-validation.md](../../../../docs/frontend/form-validation.md) が禁じているのは入力エラーを理由に送信 button を `disabled` にすることであり、送信中の多重送信の抑止はこれと別の論点である（推測、規約の文面からの解釈）。

**利用者が決める論点**

- 競合時の UX を次のどれにするか（report の Q-e 8）。
  - **再読込を促すだけ**：旧リポジトリの方式。
    実装は最小だが、利用者は入力をやり直す可能性がある。
  - **入力を保持し、最新を取得して、明示的に選ばせる**：上の推奨案。
  - **差分を見せる**：MediaWiki の方式。
    画面ごとの実装が重い。
- 楽観ロックの競合の problem type URI と、その基点（ADR-013 の未決事項）。
- 競合の通知文言を backend の `title` で出すか、`type` ごとの frontend の文言で出すか。
  i18n.md は混在を禁じているので、どちらか一方に決める必要がある（[ADR-016](../../../../docs/adr/ADR-016-localize-api-and-spa-messages.md) が Accepted になると写像方式が変わり得る）。
- 一意制約違反の 409 を、該当する入力欄のエラーとして出すか、form 全体の通知にするか。
- 送信中の多重送信を button の `disabled` で防ぐか、handler で無視するか。
- 規約の置き場所を [form-validation.md](../../../../docs/frontend/form-validation.md) にするか [routing-and-state.md](../../../../docs/frontend/routing-and-state.md) にするか。

## C2. SPA から Spring Security の CSRF token を送る方法

### 標準

- **Fetch の credentials**：`Request.credentials` の既定値は `same-origin` であり、同一 origin の要求には cookie が自動で付く（確認済み、[MDN: Request.credentials](https://developer.mozilla.org/en-US/docs/Web/API/Request/credentials)）。
  同一 origin で公開する現構成（[ADR-014](../../../../docs/adr/ADR-014-use-same-origin-spa-security-boundary.md)）では、session cookie を送るための設定は要らない。
- **cookie-to-header**：OWASP は、server が JavaScript から読める cookie に token を置き、client がそれを読んで状態変更の要求の custom header に載せ、server が両者を照合する方式を SPA の一般的な CSRF 対策として説明している。
  GET、HEAD、OPTIONS は安全な method として token を付けなくてよく、POST、PUT、PATCH、DELETE には付ける（確認済み、[OWASP CSRF Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#javascript-automatically-including-csrf-tokens-as-an-ajax-request-header)）。

Spring Security 7.1 の規範は次のとおりである。

- `CookieCsrfTokenRepository` は `XSRF-TOKEN` cookie に書き、`X-XSRF-TOKEN` header（または `_csrf` parameter）から読む。
  この既定値は Angular に由来する（確認済み、[Spring Security: Using the CookieCsrfTokenRepository](https://docs.spring.io/spring-security/reference/7.1/servlet/exploits/csrf.html#csrf-token-repository-cookie)）。
- `csrf.spa()` は 7.0 で追加され、`CookieCsrfTokenRepository.withHttpOnlyFalse()` と SPA 用の request handler を設定する（確認済み、[CsrfConfigurer.java の 225 から 238 行目](https://github.com/spring-projects/spring-security/blob/7.1.1/config/src/main/java/org/springframework/security/config/annotation/web/configurers/CsrfConfigurer.java#L225-L238)）。
- SPA 用の handler は、header に値があれば cookie の生の token として照合し、無ければ BREACH 対策の XOR 符号化された値として照合する。
  XOR 側の request attribute 名を `null` にしている（確認済み、[同 397 から 417 行目](https://github.com/spring-projects/spring-security/blob/7.1.1/config/src/main/java/org/springframework/security/config/annotation/web/configurers/CsrfConfigurer.java#L397-L417)）。
  attribute 名を `null` にすると、token が要求ごとに読み込まれる（確認済み、[Opt-out of Deferred CSRF Tokens](https://docs.spring.io/spring-security/reference/7.1/servlet/exploits/csrf.html#deferred-csrf-token-opt-out)）。
- token を読み込んだときに repository に無ければ、新しい token を生成して保存する（確認済み、[RepositoryDeferredCsrfToken.java の 61 から 72 行目](https://github.com/spring-projects/spring-security/blob/7.1.1/web/src/main/java/org/springframework/security/web/csrf/RepositoryDeferredCsrfToken.java#L61-L72)）。
- 認証の成功時と logout の成功時に token の cookie は消され、client は新しい token を得るまで状態変更の要求を送れない（確認済み、[Single-Page Applications](https://docs.spring.io/spring-security/reference/7.1/servlet/exploits/csrf.html#csrf-integration-javascript-spa)）。

以上から、filter chain を通る要求のたびに token が読み込まれ、cookie が無ければ新しい `XSRF-TOKEN` が response で設定されると読める（推測、ソースと文書からの帰結で、実行して確かめていない）。
現 `SecurityConfig` は `securityMatcher` を持たない一つの filter chain なので、backend へのすべての要求がこの filter を通る（確認済み、`backend/src/main/java/com/example/demo/SecurityConfig.java`）。

Orval 8.36 の規範は次のとおりである。

- 生成 client に共通処理を足す手段として `override.mutator` を用意し、fetch client でも mutator を指定すると生成関数がそれを呼ぶ（確認済み、[Fetch guide の Custom Fetch Function](https://github.com/orval-labs/orval/blob/v8.36.0/docs/content/docs/guides/fetch.mdx#L92-L127)、[output の mutator](https://github.com/orval-labs/orval/blob/v8.36.0/docs/content/docs/reference/configuration/output.mdx#L1105-L1139)）。
- mutator、transformer などの手書きファイルは `target` と `schemas` の外に置くよう求めている。
  `clean` で消えるためである（確認済み、[output.mdx の 995 行目](https://github.com/orval-labs/orval/blob/v8.36.0/docs/content/docs/reference/configuration/output.mdx#L995)）。
- `useRuntimeFetcher` は生成関数に `fetchFn` 引数を、Hook に `fetcher` option を足すが、呼出しごとの指定であり、mutator を使う operation には効かない（確認済み、[output.mdx の 2870 から 2931 行目](https://github.com/orval-labs/orval/blob/v8.36.0/docs/content/docs/reference/configuration/output.mdx#L2870-L2931)）。
- `output.headers: true` は、OpenAPI が宣言する header parameter を生成関数の引数にする。
  既定は `false` で、header parameter は signature から省かれる（確認済み、[output.mdx の 1018 から 1038 行目](https://github.com/orval-labs/orval/blob/v8.36.0/docs/content/docs/reference/configuration/output.mdx#L1018-L1038)）。

### ベストプラクティス

- **token は要求のたびに cookie から読む**：Spring Security は認証と logout で cookie を作り直すので、起動時に一度読んだ値を持ち続けると古くなる。
- **状態変更の method にだけ付ける**：OWASP と Angular の既定（後述）がともに GET と HEAD には付けない。
- **同一 origin の URL にだけ付ける**：Angular は相対 URL と同一 origin の URL にだけ付ける。
  axios は既定で同一 origin にだけ付け、全 host へ送っていた旧版は脆弱性として修正された（後述）。
- **mutator は小さな native fetch wrapper にする**：[ADR-024](../../../../docs/adr/ADR-024-adopt-orval-for-frontend-api-client.md) は up-fetch と小さな wrapper のどちらかを要件から選ぶとしている。
  CSRF header と Problem Details の例外化だけなら、依存を足さない wrapper で足りる（推測、要件の範囲からの判断）。

### アンチパターン

- **`credentials: "include"` を付ける**：旧リポジトリの mutator はこれを付けている（[旧 api-client.ts](https://github.com/yokozi-jp/spring-modulith-ai-harness/blob/yaguchi/frontend-setup/frontend/src/lib/api-client.ts)）。
  同一 origin では既定の `same-origin` で足り、`include` は別 origin への要求にも cookie を送る設定である。
- **全 host に token を送る**：axios 0.8.1 から 1.5.1 は、すべての host への要求に `X-XSRF-TOKEN` を付けて token を漏らしていた（確認済み、[CVE-2023-45857、GHSA-wf5p-g6vw-rhxx](https://github.com/advisories/GHSA-wf5p-g6vw-rhxx)、1.6.0 で修正）。
- **呼出しごとに header を渡す**：生成 Hook の `fetch` option や `useRuntimeFetcher` の `fetcher` で毎回渡すと、付け忘れた更新系の要求が 403 になる。
- **`globalThis.fetch` を差し替える**：第三者の library を含むすべての fetch に影響し、test で MSW が fetch を捕まえる仕組みとも干渉し得る（推測）。
- **OpenAPI の CSRF header を `output.headers: true` で生成引数にする**：ADR-013 は CSRF header を OpenAPI の components から参照すると定めている。
  header 引数を生成すると、呼出し側が毎回 token を渡す形になり、mutator との二重管理になる（推測、Orval の文書の記述からの帰結）。
- **公式 sample の mutator をそのまま写す**：F1 のとおり、例外を投げず、`application/problem+json` を JSON として読まない。

### デファクトスタンダード

- **Angular HttpClient**：組み込みの interceptor が `XSRF-TOKEN` cookie を読み、`X-XSRF-TOKEN` header に載せる。
  POST などの状態変更の要求で、相対 URL と同一 origin の URL にだけ付け、GET と HEAD には付けない（確認済み、[angular/angular の adev/src/content/guide/security.md](https://github.com/angular/angular/blob/main/adev/src/content/guide/security.md)）。
  Spring Security の既定名はこれに合わせている。
- **axios**：`xsrfCookieName` の既定が `XSRF-TOKEN`、`xsrfHeaderName` の既定が `X-XSRF-TOKEN` であり、`withXSRFToken` の既定では同一 origin の要求にだけ付ける（確認済み、[axios README の Request Config](https://github.com/axios/axios/blob/v1.x/README.md)）。
- **Orval の公式 sample**：header の付与を mutator で行っている（確認済み、[samples/next-app-with-fetch/custom-fetch.ts](https://github.com/orval-labs/orval/blob/v8.36.0/samples/next-app-with-fetch/custom-fetch.ts)、[samples/react-query/custom-fetch/src/custom-fetch.ts](https://github.com/orval-labs/orval/blob/v8.36.0/samples/react-query/custom-fetch/src/custom-fetch.ts)）。
- **旧リポジトリ**：mutator が要求ごとに cookie を読み、全 method に header を付け、非 2xx を `ApiError` にし、Orval が期待する `{ data, status, headers }` を返す（[旧 api-client.ts](https://github.com/yokozi-jp/spring-modulith-ai-harness/blob/yaguchi/frontend-setup/frontend/src/lib/api-client.ts)、[旧 api-error.ts](https://github.com/yokozi-jp/spring-modulith-ai-harness/blob/yaguchi/frontend-setup/frontend/src/lib/api-error.ts)）。

### 現リポジトリへの当てはめ

**既存の規約との整合**

- [api-client-orval.md](../../../../docs/frontend/api-client-orval.md) と ADR-024 は、Problem Details や CSRF を組み込み Fetch だけで表現できないと確認した場合に限り mutator を足すと定める。
  組み込み Fetch には要求ごとに header を足す全体設定が無く（`useRuntimeFetcher` も呼出しごと）、既定では非 2xx を例外にしない。
  `forceSuccessResponse` で例外化はできるが header は足せない。
  したがって、この条件は満たされている（確認済みの事実からの判断）。
- 置き場所は `src/api/generated` の外の `src/api/<custom-mutator>.ts` であり、規約と Orval の文書の両方に合う。
  旧リポジトリの `src/lib/` は採らない。
- backend の `csrf.spa()` と ADR-014 の `X-XSRF-TOKEN` の許可は、client 側の header 名と一致する。

**推奨案**

1. `src/api/` に小さな fetch wrapper を一つ置き、`orval.config.ts` の `override.mutator` に指定する。
2. wrapper は要求のたびに `document.cookie` から `XSRF-TOKEN` を読み、POST、PUT、PATCH、DELETE のときだけ `X-XSRF-TOKEN` に載せる。
   生成 URL は相対 path なので、同一 origin の判定は相対 URL であることで足りる（推測、Orval の既定では `baseUrl` を付けないため）。
3. `credentials` は指定せず、既定の `same-origin` に任せる。
4. 非 2xx では本文を読み、`Content-Type` が `json` を含めば（`application/problem+json` を含む）JSON として解釈し、`status` と Problem Details を持つ `Error` の subclass を投げる（F1、C7）。
5. 戻り値の形は `includeHttpResponseReturnType` の設定と一致させる。
6. test は一ファイルにまとめ、MSW の handler で header の有無（更新系だけに付くこと）と、Problem Details の例外化を確かめる。

**利用者が決める論点**

- mutator のファイル名と、例外 class が持つ項目（`status`、`type`、`title`、`detail`、拡張 member を丸ごと持つか）。
- `includeHttpResponseReturnType` を既定の `true` のままにするか、`false` にして data だけを返すか。
  `false` にすると `query.data?.data` の二重参照が消え、cache の形と mutation の応答の形を揃えやすい。
  代わりに成功時の status code の区別（200 と 201 など）を型で失う。
- token が無い状態で更新系の要求を送る場面（画面の読込直後に GET より先に POST する、など）を想定するか。
  Spring Security の文書は `/csrf` endpoint で token を取らせる方式も紹介している（[Mobile Applications](https://docs.spring.io/spring-security/reference/7.1/servlet/exploits/csrf.html#csrf-integration-mobile)）が、SPA が先に GET を送る限り不要と考えられる（推測、E2E で確かめる必要がある）。
- OpenAPI に CSRF header を宣言したとき、Orval の `output.headers` を既定の `false` に保つことを規約に書くか。
- 追加の変更を ADR-024 の改訂として記録するか（ADR-024 は Proposed）。
- backend 側の論点として、`CookieCsrfTokenRepository` の照合は OWASP が新規の実装には勧めない naive double-submit cookie に分類される可能性がある（推測、[OWASP の Naive Double-Submit Cookie Pattern](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#naive-double-submit-cookie-pattern-discouraged) の定義との比較）。
  OWASP が挙げる攻撃は兄弟 subdomain などから cookie を書ける場合であり、frontend の変更範囲ではないが、ADR-014 の前提として確かめる価値がある。
