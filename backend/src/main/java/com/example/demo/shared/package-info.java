/**
 * 機能モジュールが共有する技術的な共通処理を置く Spring Modulith の shared モジュール（ADR-048）。
 *
 * <p>業務の概念を置かない。永続化の共通処理は {@code infrastructure.persistence} に置き、{@code @NamedInterface} で公開する。
 * 楽観的ロックの語彙（{@code ExpectedLockNo}、{@code VersionedCommand}、{@code ConflictException}）は {@code
 * concurrency} に置き、どの層からも使う。 業務上の失敗の例外（{@code NotFoundException}、{@code
 * BusinessRuleViolationException}）は {@code failure} に置き、どの層からも使う（ADR-062）。
 *
 * <p>Spring Modulith のイベント出版のレジストリの、失敗した出版の再投入は {@code infrastructure.persistence}
 * に置き、リスナーが再投入の方針を選ぶ注釈 （{@code ResubmissionPolicy}）は {@code events} に置いてどの層からも使う（ADR-079）。
 *
 * <p>共通カラムの {@code *_by} に登録するアプリの利用者を表す認証の principal（{@code AppUserPrincipal}）は {@code security}
 * に置き、どの層からも使う（ADR-096）。テナントを選ぶ前の拒否（{@code TenantNotSelectedException}）は、テナントの ID
 * も役割も持たずログインの状態だけを表すので、認証の語彙として {@code security} に置く（ADR-101）。
 *
 * <p>操作しているテナントの語彙（{@code TenantId}、{@code TenantScoped}、{@code TenantContext}）は {@code tenant}
 * に置き、どの層からも使う（ADR-105）。リスナーでテナントを束縛する {@code TenantListenerAspect} は {@code
 * infrastructure.persistence} に置く。
 */
@NullMarked
package com.example.demo.shared;

import org.jspecify.annotations.NullMarked;
