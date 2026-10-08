/**
 * 楽観的ロックの語彙を置く（ADR-054）。画面が読んだ版の {@link com.example.demo.shared.concurrency.ExpectedLockNo}、それを持つ
 * Command の印の {@link
 * com.example.demo.shared.concurrency.VersionedCommand}、版の不一致、行ロックの失敗、一意制約の違反の競合を表す {@link
 * com.example.demo.shared.concurrency.ConflictException} である。競合の種類は {@link
 * com.example.demo.shared.concurrency.ConflictException.Kind} で区別する。一意制約の違反の扱いと、原因を持たないことは ADR-062
 * で決める。
 *
 * <p>Domain、Application、Presentation、Infrastructure のどの層からも使ってよい。業務の概念を置かず、Spring や jOOQ に依存しない。
 */
@NamedInterface("concurrency")
@NullMarked
package com.example.demo.shared.concurrency;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
