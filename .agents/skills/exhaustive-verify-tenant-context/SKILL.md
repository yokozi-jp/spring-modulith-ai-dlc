---
name: exhaustive-verify-tenant-context
description: TenantContext.call の束縛と、その間だけログに付く MDC の tenant.id が、入れ子、例外、例外の握りつぶしのどの組み合わせでも内側のテナントと一致し、最も外側を出ると何も残らないことを、本物の Java のコードに対する網羅の列挙で検査する。TenantContext、TenantSelectionFilter、TenantListenerAspect、ModuleRoleTransactionManager のテナントの読み出し、logback-spring.xml の mdcAttributesIncluded を変えるときと、ログや span のテナントの ID が別のテナントを指す、消えない、消えるのを疑うときに使う。
---

# テナントの束縛と MDC の網羅検査

`TenantContext.call` は、`ScopedValue` の束縛と MDC の `tenant.id` を同じ場所で出し入れする。
束縛が、トランザクションマネージャーの `set_config('app.current_tenant', ...)` とログの `tenant.id` の両方の元になる。
ログの `tenant.id` が実際に処理したテナントと違うと、障害を調べる人が別のテナントのログを読み、テナントの `public_id` で検索した結果を信じて誤った対応をする。
束縛が外れずに残ると、同じスレッドの次の処理が別のテナントの文脈で動く。

道具は、本物の `TenantContext` を JUnit から動かす網羅の列挙である（`TenantContextExhaustiveTest`）。
TLA+ のモデルにしなかった理由は、この問いに順序の交錯がないためである。
`ScopedValue` も MDC もスレッドごとの値で、別のスレッドの出来事で変わらない。
問いは「一つのスレッドの中で、入れ子と例外がどう組み合わさっても二つの値が揃うか」であり、組み合わせを機械的に尽くす列挙が本物のコードに直接答える。
Java の検証器（JBMC など）は、`ScopedValue` と SLF4J の MDC を持つこのコードを扱えない。

## 実行する

```bash
.agents/skills/exhaustive-verify-tenant-context/scripts/check.sh
.agents/skills/exhaustive-verify-tenant-context/scripts/mutants.py
```

`check.sh` は `TenantContextExhaustiveTest` だけを Gradle で流す（Gradle の daemon が温まっていれば約 10 秒。初回は 1 分ほど）。
同じテストは `task be-test` でも流れるため、テストが捕まえる退行は通常の CI でも落ちる。
`mutants.py`、同期コミットの検査、「変更がこの検証に関わるか」のコマンドは CI で流れない（`task formal-verify` は `formal-verify-*` だけを見る）。手で流す。
どちらの実行も `.env.test`（`task be-test` が作る）を要する。
`mutants.py` は、`mutants.toml` の変異を本物の `TenantContext.java` に一つずつ当て、テストが落ちることを確かめ、ソースを元に戻す（約 3 分。変異ごとに Gradle を流す）。
`mutants.py` は、`TenantContext.java` に未コミットの変更があると、何も書き換えずに止まる。
終了、Ctrl-C、SIGTERM、SIGHUP では実行前の内容に書き戻す。SIGKILL で止めたら `git checkout -- backend/src/main/java/com/example/demo/shared/tenancy/TenantContext.java` で戻す。

## 約束

| 名前                 | 約束                                                                                                                             | 破れたときの害                                                                  |
| -------------------- | -------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| `MdcFollowsBinding`  | `TenantContext.call` の中のどの時点でも、MDC の `tenant.id` と `TenantContext.findCurrent()` が、内側の束縛のテナントと一致する。 | ログが別のテナントの ID を持つ。トランザクションのテナントとログがずれる。      |
| `OuterRestored`      | 入れ子の `call` から戻ると（例外を握りつぶした場合も）、MDC と束縛が外側のテナントに戻る。                                       | 外側の処理の後半のログが、内側のテナントの ID を持つ。                          |
| `NothingRemains`     | 最も外側の `call` を出ると（正常でも例外でも）、MDC の `tenant.id` が消え、束縛が外れる。                                        | 同じスレッドの次の処理のログに前のテナントの ID が残り、別のテナントの調査を誤らせる（束縛は `ScopedValue` が外すため、残るのは MDC）。 |
| `ExceptionUnchanged` | 処理が投げた例外（検査されない例外、検査される例外、`Error`）は、包まれず、握りつぶされず、最も外側からそのまま出る。                                             | フィルターの `IOException` の変換と例外ハンドラーが、別の例外を受ける。          |

## 範囲と前提

検査の範囲は次のとおりで、これを超える保証はない。

- 式は `Probe`（MDC と束縛を観測する）、`Throw`（検査されない例外、検査される例外、`Error` の 3 種類）、`Call(テナント, 式)`、`Seq(式, 式)`、`Catch(式)` の 5 種類で、ノードの数が 7 以下のすべて（49,764 式）。
  テナントは 2 つで、同じテナントの入れ子と違うテナントの入れ子の両方を含む。
  深さ 7 の入れ子や、8 ノード以上の組み合わせは見ない。
  上限は `TenantContextExhaustiveTest.MAX_SIZE` で、上げると式の数が急に増える（6 から 7 で約 5.6 倍）。
- 動かすのは本物の `TenantContext.call` と SLF4J の MDC（Logback の `LogbackMDCAdapter`）である。
  束縛は `findCurrent()` で観測する。
  `ModuleRoleTransactionManager.prepareTransactionalConnection` が読むのは同じ `findCurrent()` で、`set_config` に渡す値は束縛と同じである。
  マネージャー自体と `set_config` の SQL は動かさない。
- 一つのスレッドだけを動かす。
  次の理由で、スレッドをまたぐ組み合わせは今のコードにない。
  `TenantContext.call` を呼ぶのは `TenantSelectionFilter` と `TenantListenerAspect` だけで（ArchUnit が守る）、リスナーは `@Async` の別のスレッドで動く。
  このアプリは `spring.threads.virtual.enabled=true` で、リクエストもリスナーも、使い回さない仮想スレッドで動く。
  `StructuredTaskScope` は使っていない。
  使うと `ScopedValue` は子に継承され、MDC は継承されないため、この検査では見つからない。
- 同じ MDC の key を、`TenantContext` の外が書く場合は扱わない。
  `call` は外側の束縛がなければ `tenant.id` を無条件に消す（ADR-110）。
- 扱わないもの：`TenantSelectionFilter` の `user.id`、HTTP の受信 span の属性、OTLP appender の `mdcAttributesIncluded`、Collector の allowlist。
  それぞれ `TenantSelectionFilterTest`、`ObservabilityContractTest`、`task otel-collector-check` が確かめる。
- Spring のトランザクションに参加する呼び出し（`REQUIRED` の内側）が、外側と別のテナントで `call` を呼ぶ組み合わせは扱わない。
  マネージャーは新しいトランザクションの開始時にだけテナントを設定する。
  今のコードにその呼び出しはなく、ArchUnit が `call` の呼び出し元を 2 つに限る。
  呼び出し元を増やす変更は、この組み合わせを新しく調べる必要がある。

## 切り分けの手がかり

テストが落ちたら、失敗メッセージの `式 ...` を読む。
式は `Call(テナント, ...)` の入れ子で、`Probe` が観測した MDC と束縛が、どのテナントのはずだったかを示す。

1. 失敗した式を `TenantContext.call` の行に置き換える。
   `MDC.put`（入口）、`ScopedValue.where(...).call`（束縛）、`finally`（戻す側）のどれがその式で効かなかったかを見る。
2. `MdcFollowsBinding` の違反で束縛が合っていて MDC だけ違うなら、`MDC.put` の値か戻す側の `BOUND.get()` を疑う。
3. `NothingRemains` の違反は、`finally` の `MDC.remove` が通らない経路（例外、`Error`）があるかを見る。
4. 式の意味（テストの `Expr.throwsOut` と `Run.run`）が `TenantContext.call` の意図した振る舞いと違うなら、それはテストの誤りで、直してから変異を流し直す。
   意図した振る舞いは `TenantContext` の Javadoc と ADR-110 にある。
5. 式が現実に起きない経路を作っていても、テナントの束縛は入れ子と例外を許す（`call` が公開 API）ため、本物の不具合として扱う。

## 変更がこの検証に関わるか

次のコマンドが差分を出せば関わる。

```bash
skill=.agents/skills/exhaustive-verify-tenant-context/SKILL.md
git diff --stat "$(sed -n 's/^同期コミット：`\([0-9a-f]*\)`.*/\1/p' "$skill")" -- \
  backend/src/main/java/com/example/demo/shared/tenancy/TenantContext.java \
  backend/src/main/java/com/example/demo/shared/infrastructure/persistence/TenantListenerAspect.java \
  backend/src/main/java/com/example/demo/shared/infrastructure/persistence/ModuleRoleTransactionManager.java \
  backend/src/main/java/com/example/demo/tenancy/presentation/web/TenantSelectionFilter.java \
  backend/src/main/resources/logback-spring.xml \
  backend/src/main/resources/application.yaml
```

`application.yaml` は `spring.threads.virtual.enabled` と `spring.task.execution` だけが関わる。
スレッドを使い回すようになると、前の処理の MDC が次の処理に残る組み合わせが、この検査の外にある（範囲と前提）。
`TenantSelectionFilter` は、`TenantContext.call` を呼ぶ形（例外の包みと戻し方）だけが関わる。
`TenantContext.call` の新しい呼び出し元ができたら、その呼び出しが、リクエストやイベントの最も外側か、すでに束縛した中かを確かめる。

```bash
grep -rn "TenantContext.call\|TenantContext\.<" backend/src/main/java --include=*.java
```

## 対応表

| テストの要素                    | コード                                                                                                  |
| ------------------------------- | ------------------------------------------------------------------------------------------------------- |
| `Expr.Call(テナント, 式)`       | `TenantContext.call(tenantId, operation)`。`MDC.put`、`ScopedValue.where(BOUND, ...).call`、`finally`   |
| `Expr.Probe`                    | ログの 1 行、または `ModuleRoleTransactionManager.prepareTransactionalConnection` が `findCurrent()` を読む時点 |
| `Expr.Throw`                    | `operation` が投げる検査されない例外（フィルターの下流、リスナーの本体）                               |
| `Expr.Catch`                    | 入れ子の `call` の外で、呼び出し側が例外を握りつぶす処理                                                |
| 最も外側の `call` を出た後      | `TenantSelectionFilter.doFilterInTenant`、`TenantListenerAspect.bindTenant` の呼び出しの終わり          |

## 変異

変異は `mutants.toml` にあり、各項目の `change` が写すコードの退行である。
`mutants.py` は、変異なしのテストが通ること（`control`）と、すべての変異でテストが失敗すること（`caught`）を確かめる。
テストの失敗でなくコンパイルの失敗は `FAIL` で、捕まえたことにならない。
`MISSED` は、変異を入れてもテストが通ったことで、列挙が守るはずの退行を捕まえていない。

| 変異                            | 破る約束                                  |
| ------------------------------- | ----------------------------------------- |
| `outer-mdc-not-restored`        | `OuterRestored`                           |
| `inner-mdc-kept-after-return`   | `OuterRestored`                           |
| `mdc-left-after-outermost`      | `NothingRemains`                          |
| `mdc-not-set`                   | `MdcFollowsBinding`                       |
| `inner-shows-outer-tenant`      | `MdcFollowsBinding`                       |
| `restore-skipped-on-exception`  | `NothingRemains`、`OuterRestored`         |
| `restore-skipped-on-error`      | `NothingRemains`（`Error` のとき）        |
| `exception-wrapped`             | `ExceptionUnchanged`                      |
| `inner-binding-ignored`         | `MdcFollowsBinding`（束縛の側）           |

`mutants.toml` の `old` は、`TenantContext.java` の字下げまで含む完全一致の文字列である。
`call` を整形し直したり文を足したりすると `FAIL ... ちょうど一度は現れない` で止まる。
そのときは変異の `old` を新しい形に合わせ直す。

## 既知の発見

なし。

## 同期の記録

同期コミット：`4fe673a25c619cd0fecbb3b14e7f5a7ed2f7d22b`（main。Pull Request #617 の土台）

`TenantContext.call` の MDC の出し入れは Pull Request #617 が足すため、「変更がこの検証に関わるか」のコマンドは、#617 が main に入るまで #617 の変更を差分に出す。
差分を読み、列挙の式と変異が `TenantContext.call` の今の形と一致することを確かめた。
main の変更のうち、関わるファイルに触れたのは `application.yaml` のセッションの保存先（ADR-111）だけで、`spring.threads` と `spring.task` は変わっていない。
Pull Request の中のコミットを書かない理由と、進め方は `docs/agents/formal-verification.md` の「同期コミットを進める」にある。
