---
type: Runbook
title: バックエンドのテストが遅いときの調べ方
description: バックエンドのテストがCIより遅いとき、他のセッションが動いていて遅いときに、遅いクラスと処理を特定する手順を示す。load averageの確認、CIの結果との比較、キャッシュを使わない再実行、テストのJVMのJFRでの記録を扱う。task test や pre-push の be-test が想定より長くかかるときに読む。
tags: [runbook, backend, testing, performance]
---

# バックエンドのテストが遅いときの調べ方

まずホストの負荷を見る。
負荷が低いと確かめてから、CIのテスト結果とクラスごとに比べて遅いクラスを決め、そのクラスだけをキャッシュを使わずに実行し、テストのJVMをJFRで記録する。
推測で原因を決めず、比較と記録の結果で決める。

## 1. 先に負荷を見る

他のセッションが同じホストでテストやビルドを流していると、CPUを取り合ってテストが遅くなる。
負荷が高い間に測った所要時間は、CIの結果や別の回と比べられない。

```bash
uptime   # 1分のload average
nproc
```

1分のload averageが`nproc`を超えているときは、その状態で測った所要時間をCIの結果や別の回と比べない。
次のコマンドでCPUを使う処理を確かめる。

```bash
ps -eo pcpu,args --sort=-pcpu | head
```

他のセッションの処理が見つかったら、終わるのを待つか、止めてよいか利用者に聞く。
クラスを分けたり、テストを外したりしない。

負荷だけで、所要時間は桁違いに延びる。
[#364](https://github.com/yokozi-jp/spring-modulith-ai-dlc/pull/364)では、`be-lint`、`be-test`、`be-image-build`を同時に実行したとき、ArchUnitの4クラスが単独の約6秒から約141秒（約23倍）に延びた。

## 2. CIと比べて遅いクラスを決める

mainのBackend CIの成果物`backend-test-results`を取得し、ローカルの結果とクラスごとに比べる。

```bash
gh run download <run-id> -n backend-test-results -D /tmp/ci-results
```

クラスの所要時間は、各`TEST-*.xml`の`testsuite`の`timestamp`を開始時刻の順に並べ、次のクラスの開始までの間隔で見る。
`@AnalyzeClasses`を使うArchUnitのクラスの`time`は、クラスの読み込みを待った時間を含み、実際より長く出ることがある。

## 3. 遅いクラスだけを再実行する

Gradleのビルドキャッシュが効くと`test`は実行されないため、`--rerun`を付ける。
テストの依存はテストのJVMが起動するため、composeは起動しない（[バックエンドのテストの実行基盤](testing-infrastructure.md)）。
forkを1つにすると、ほかのクラスの影響を除いて測れる。

```bash
cd backend
./gradlew test --rerun --tests '*<クラス名>' -PtestForks=1
```

## 4. テストのJVMを記録する

テストのJVMは、コマンドラインに`Gradle Test Executor`を含む`GradleWorkerMain`のプロセスである。
`pgrep -f`だけでは、自分のシェルやコンパイラのワーカーにも一致する。
`jcmd`と`jfr`はJDKの`bin`にあり、PATHに無いことがある。

```bash
export PATH="$PATH:$(dirname "$(readlink -f "$(command -v java)")")"
for p in $(pgrep -f GradleWorkerMain); do
  tr '\0' ' ' < /proc/$p/cmdline | grep -q 'Gradle Test Executor' && echo "$p"
done
jcmd <pid> JFR.start name=prof settings=profile filename=/tmp/prof.jfr
# テストの終了前に書き出す
jcmd <pid> JFR.dump name=prof filename=/tmp/prof.jfr
jfr print --events jdk.ExecutionSample --stack-depth 300 /tmp/prof.jfr > /tmp/samples.txt
```

`/tmp/samples.txt`で、スタックに含まれるフレームの割合を数え、時間を使う処理を決める。
GCの影響は`jstat -gcutil <pid>`の`GCT`で確かめる。

## 既知の原因

- Springのコンテキストの起動で、AspectJのpointcutの照合が時間を使う。
  `execution`の宣言型に名前の型パターン（`com.example.demo.*..*CommandHandler.handle(..)`）を書くと、AspectJはすべてのBeanのすべてのメソッドで上位型をたどって照合する。
- ArchUnitの`ClassFileImporter`はクラスパス全体を走査するため、パラメータ化テストの行ごとに呼ばず、結果を共有する。
