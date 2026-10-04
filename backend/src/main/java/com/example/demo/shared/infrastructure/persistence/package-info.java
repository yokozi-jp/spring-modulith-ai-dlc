/**
 * jOOQ の技術的な共通処理（共通カラムの値、NULL から空文字への変換）を置く（ADR-048）。共通カラムの {@code *_pgm_cd} を束縛する Aspect
 * （ADR-051）と、楽観的ロックの更新件数の判定（ADR-054）も含む。
 *
 * <p>使ってよいのは、他のモジュールの {@code infrastructure.persistence} のアダプターだけとする。業務の概念を置かない。
 */
@NamedInterface("persistence")
@NullMarked
package com.example.demo.shared.infrastructure.persistence;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
