/**
 * 機能モジュールが共有する技術的な共通処理を置く Spring Modulith の shared モジュール（ADR-048）。
 *
 * <p>業務の概念を置かない。永続化の共通処理は {@code infrastructure.persistence} に置き、{@code @NamedInterface} で公開する。
 * 楽観的ロックの語彙（{@code ExpectedLockNo}、{@code VersionedCommand}、{@code ConflictException}）は {@code
 * concurrency} に置き、どの層からも使う。
 */
@NullMarked
package com.example.demo.shared;

import org.jspecify.annotations.NullMarked;
