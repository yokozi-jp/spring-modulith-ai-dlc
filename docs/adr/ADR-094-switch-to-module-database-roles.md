---
type: ADR
title: 'ADR-094: トランザクションの初めにモジュールの DB ロールへ切り替え、アプリロールを業務のスキーマから外す'
description: ログインするロールをアプリロール 1 つにしたまま、モジュールごとのログインできないロール <module>_module を作り、トランザクションの初めに切り替える決定。別のモジュールの表への書き込みと、依存の方向に逆らう読み取りを DB の権限エラーで止める。ADR-011 のアプリロールの規則を改める。
tags: [adr, database, postgresql, security, spring-modulith]
---

# ADR-094: トランザクションの初めにモジュールの DB ロールへ切り替え、アプリロールを業務のスキーマから外す

## Status

Proposed

## Date

2026-11-18

## Context

[ADR-011](ADR-011-use-module-owned-database-schemas.md) は、モジュールごとに DB のスキーマを持たせ、アプリロールに各スキーマの USAGE と表の DML を与えると定めた。
この構成では、アプリロールがすべてのモジュールのスキーマを読み書きできる。
モジュールのコードが別のモジュールの表に書き込んでも、依存の方向に逆らって別のモジュールの表を読んでも、DB は止めない。
Java の型と ArchUnit は、`JdbcTemplate` や生の JDBC からの抜け道と、型に現れない逆向きの依存を完全には止められない。

[issue #369](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/369) は、テナントで業務のデータを分ける。
テナントの分離は、DB が行を止める仕組み（RLS）に頼る。
RLS だけでは、別のモジュールのスキーマを越える読み書きは止まらない。
モジュールの境界を DB の権限で守る仕組みが先に要る。

[issue #307](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/307) で、別の実装の事例（モジュールごとのロールへ切り替える方式）を調べ、一部を取り込むと決めた。
この調査は、使い捨ての PostgreSQL 18 のコンテナで次の動作を確かめている。

- `set_config('role', ?, true)` で、トランザクションの間だけロールを切り替えられる。commit と rollback のあとは、接続がアプリロールに戻る。
- `GRANT <module>_module TO <app> WITH INHERIT FALSE, SET TRUE` で、アプリロールは切り替えたときだけモジュールの表を使える。
- SELECT の権限なしに、`modulith.event_publication` へ INSERT できる。

この ADR が Proposed の間に、#377 でロールを切り替える SQL を、テナントも同じ文で設定する形に改めた（[ADR-107](ADR-107-isolate-tenants-with-rls-set-per-transaction.md)）。
ロールとテナントを、トランザクションを始めた直後の 1 か所で、1 つの SQL で設定すると ADR-107 で決めたからである。
Decision の切り替えの SQL を、その文に合わせて書き直した。

## Decision

ログインするロールはアプリロール 1 つのままにする。
モジュールごとに、ログインできないロール `<module>_module` を作る。
アプリロールは、継承なしで、切り替えだけを許されたメンバーにする。

アプリロールは業務のスキーマに権限を持たない。
モジュールのロールに切り替えたときだけ、そのモジュールのスキーマを読み書きできる。
アプリロールが触れるのは、Spring Modulith のイベントの表、その archive、ShedLock の表（`modulith` スキーマ）だけにする。

モジュールのロールには、次の権限だけを与える。

- 自分のスキーマの USAGE と、表の SELECT、INSERT、UPDATE、DELETE。
- 依存の方向（ordering から product、payment から ordering）にだけ、呼び出し元のロールへ依存先のスキーマの USAGE と SELECT。
- イベントの出版の表（`modulith.event_publication`）への INSERT と、`modulith` スキーマの USAGE。

新しい表の権限は、スキーマごとの `ALTER DEFAULT PRIVILEGES` で決める。
権限の漏れは、カタログのテストが止める。

`JdbcTransactionManager`（`DataSourceTransactionManager` の子クラス）を継承したトランザクションマネージャーの `prepareTransactionalConnection` で、トランザクションを始めた直後に、トランザクションの名前（クラス名とメソッド名）からモジュールを引き、ロールを切り替える。
切り替えの SQL は、テナントも設定する 1 つの文 `SELECT set_config('role', ?, true), set_config('app.current_tenant', ?, true)` で、設定するものの `set_config` だけを並べる（ADR-107）。
モジュールが分からないトランザクション（Spring Modulith の内部、再投入、ShedLock）は、アプリロールのまま動かす。
ロールを決めるのは、物理的なトランザクションを始めた外側の名前である。
別のモジュールの公開 API を呼ぶと、その処理は `REQUIRED` で外側のトランザクションに参加し、呼び出し元のロールで動く。
依存の方向に SELECT を与えるのはこのためである。
設定の名前は SQL に固定し、ロールの値はバインド変数で渡す。
接続プールの初期化の SQL とセッション単位の設定には書かない。

ロールは環境を作る手順で先に作る。
ローカルは `docker/initdb`、本番は構築の手順である。
マイグレーション用のロールに `CREATEROLE` を持たせない。
ロールの名前は `<module>_module` に固定し、1 つの DB クラスターに 1 つの環境を置く。
表の所有は、マイグレーション用のロールのままにする。

ADR-011 の次の規則を、この ADR で改める。

- 各 changeset が、所有するスキーマの USAGE と表の DML をアプリロールへ与える規則。業務のスキーマは、アプリロールでなくモジュールのロールへ与える。`modulith` スキーマは、これまでどおりアプリロールへ与える。

ADR-009 の二ロール（アプリ用とマイグレーション用）と資格情報の分離は、維持する。

ArchUnit で、次を検査する。

- モジュールの `infrastructure.persistence` は、自分のモジュールのスキーマの jOOQ の生成クラスだけを使う。
- モジュールのコードは、`JdbcTemplate`、生の JDBC、接続の設定を変える `DSLContext` と `DSL` の API を使わない。

アプリロールは、どの SQL からでも `SET ROLE` や `RESET ROLE` でロールを切り替えられる。
DB のロールで止められるのは誤りで、故意の迂回は止められない。
この限界を、上の ArchUnit の規則で補う。

## Consequences

### Positive

- 別のモジュールの表への書き込みと、依存の方向に逆らう読み取りが、DB の権限エラーで止まる。
- Java の型で捕まえられない逆向きの依存を、実行時に確かめられる。
- ロールとテナントの切り替えを、トランザクションマネージャーの 1 か所に集められる。後続の RLS の設定も同じ箇所に足せる。
- ロールの設定はトランザクションの間だけ効くため、プールの接続に残らない。

### Negative

- 業務のスキーマにアクセスするコードは、名前からモジュールを引けるトランザクションの中で動かす必要がある。トランザクションの外の DB アクセスは、権限エラーになる。
- 新しい changeset（012）が、既存の changeset がアプリロールへ与えた業務のスキーマの権限を取り上げる。適用済みの DB は `task be-migrate` で追従できるが、モジュールのロールを先に作る必要がある。
- ステージングと本番は、ロールを先に作る手順が増える。`GRANT ... WITH INHERIT FALSE, SET TRUE` を使うため、PostgreSQL 16 以上が要る。
- 012 の changeset を適用すると、モジュールのロールへ切り替えない旧版のアプリケーションは、業務のスキーマの USAGE と DML を失い、読み取りも書き込みもできなくなる。012 より前の DB に、切り替える新版のアプリケーションを向けても、モジュールのロールに権限がなく、同じく権限エラーになる。後方互換な changeset の順序（先にマイグレーション、後にデプロイ）では、この切り替えを無停止にできない。本番にデータがなく、切り替えの時間帯の停止を許せる間に適用する。
012 を DB の切り戻しで戻すときも、同時に旧版のアプリケーションへ戻す。
- 1 つの DB クラスターに 1 つの環境しか置けない（ロールの名前が固定のため）。
- 別のモジュールから呼ばれた `<Feature>Queries` は、呼び出し元のロールで動く。`<Feature>Queries` が自分のスキーマ以外（依存先のさらに先）を読むと、権限エラーになる。依存の方向の SELECT は直接の依存にだけ与えるため、カタログのテストと ArchUnit はこの誤りを捕まえず、実行時に初めて分かる。別のモジュールの状態を同期で変更しない規約（[バックエンドアーキテクチャ](../backend/architecture.md)の「モジュール間の連携」）を守る限り、書き込みでは起きない。
- アプリロールは `SET ROLE` で切り替えられるため、故意の迂回は DB だけでは止められない。

### Neutral

- テストの掃除と seed は、所有者の接続の `TRUNCATE` と、モジュールごとのロールの切り替えに作り直す。
- モジュールを足すときは、ロールを作る手順と `ModuleRoles.MODULES` に、同じ名前を足す。`ModuleRoles.MODULES` の足し忘れは、`modulith` を除く jOOQ のスキーマとの不一致として `ModuleRolesTest` が止める。ロールを作る手順の足し忘れは、`ModuleRolesTest` とカタログのテストが止める。

## Alternatives Considered

### 選択肢1: モジュールごとにログインするロールを作り、接続プールを分ける

- **Description**：モジュールごとに DB のユーザーと接続プールを持たせる。
- **Pros**：ロールの切り替えが要らない。
- **Cons**：モジュールの数だけ接続プールが増え、接続の予算が膨らむ。モジュールをまたぐトランザクションを 1 つの接続で書けない。

### 選択肢2: 表の所有をモジュールごとのロールに分ける

- **Description**：各モジュールのロールが自分のスキーマの表を所有し、DDL も持つ。
- **Pros**：所有と権限が一致する。
- **Cons**：DDL をモジュールのロールに持たせ、Liquibase もモジュールごとに流すことになる。ADR-011 で、モジュール別の Liquibase の履歴を退けた形に戻る。

### 選択肢3: 現状のまま、ArchUnit だけで境界を守る

- **Description**：アプリロールがすべてのスキーマを読み書きできる構成を維持する。
- **Pros**：変更が要らない。
- **Cons**：`JdbcTemplate` と生の JDBC からの抜け道、型に現れない逆向きの依存を止められない。テナントの分離の前提にならない。

### 選択肢4: セッション単位で `SET ROLE` する

- **Description**：接続を借りたときに `SET ROLE` し、返すときに `RESET ROLE` する。
- **Pros**：トランザクションの外の読み書きにもロールが効く。
- **Cons**：戻し忘れた接続にロールが残り、次のリクエストにモジュールの権限が漏れる。トランザクションの間だけ効く設定なら、戻し忘れが起きない。

## References

- [ADR-009](ADR-009-unify-db-connection-target-credentials-only-role-split.md)
- [ADR-011](ADR-011-use-module-owned-database-schemas.md)
- [DB接続情報とロール分離](../database/connections.md)
- [issue #307](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/307)
- [issue #369](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/369)
- [ADR-107: 業務テーブルを RLS でテナントごとに分け、テナントをロールと同じ SQL でトランザクションごとに設定する](ADR-107-isolate-tenants-with-rls-set-per-transaction.md)
- [PostgreSQL GRANT](https://www.postgresql.org/docs/current/sql-grant.html)
- [PostgreSQL SET ROLE](https://www.postgresql.org/docs/current/sql-set-role.html)
- [PostgreSQL ALTER DEFAULT PRIVILEGES](https://www.postgresql.org/docs/current/sql-alterdefaultprivileges.html)
