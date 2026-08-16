# Project-Level Rules

> プロジェクト固有の特殊化と修正。`org.md` および `team.md` の後に厳密加算的ガイダンスとしてロードされる。
> より広いポリシーとの矛盾は拒否される。
> practices-discovery と自己学習ループによって投入される。
>
> 控えめに使うこと。大半のチームはプロジェクトレイヤーを必要としない。
> この特定のプロジェクトがチームプラクティスを超えた安定的かつ持続的なガイダンスを必要とする場合にのみ使用する
> （例：パッケージ固有のリリースチェック、レガシーコンポーネント用の追加リグレッションスイートなど）。

## Way of Working

<!-- プロジェクト固有の特殊化。例： -->
<!-- このモノレポではパッケージスコープのブランチ名を要求し、 -->
<!-- チームの通常のマージポリシーに加えてパッケージオーナーのレビューを要求する。 -->

## Walking Skeleton

<!-- プロジェクト固有の特殊化。例： -->
<!-- ウォーキングスケルトンは新しいサービス境界だけでなく、 -->
<!-- レガシーサービスアダプターも演習しなければならない。 -->

## Testing Posture

<!-- プロジェクト固有の特殊化。 -->

## Deployment

<!-- プロジェクト固有の特殊化。 -->

## Code Style

<!-- プロジェクト固有の特殊化。 -->

## Tech Stack

<!-- このプロジェクトで確定した技術選定。 -->

## Decided

<!-- 以前のステージで決定され、再度問うべきでない事項。 -->
<!-- 形式：DECIDED: [決定内容] (Stage [slug], [日付]) -->

## Scope Overrides

<!-- このプロジェクトのカスタムスコープルール。 -->

## Forbidden

<!-- practices-discovery の確認ゲートで投入される。 -->
<!-- 形式：NEVER [振る舞い] (affirmed [日付]) -->
<!-- 例：NEVER throw exceptions across service layer boundaries (affirmed 2026-05-17) -->

## Mandated

<!-- practices-discovery の確認ゲートで投入される。 -->
<!-- 形式：ALWAYS [振る舞い] (affirmed [日付]) -->
<!-- 例：ALWAYS use Result<T,E> for fallible operations in service layer (affirmed 2026-05-17) -->

## Corrections

<!-- 人間のフィードバックによるプロジェクト固有の修正。 -->
<!-- 形式：NEVER/ALWAYS [振る舞い] (learned [日付]) -->
