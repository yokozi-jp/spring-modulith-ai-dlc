/**
 * jOOQ の技術的な共通処理を置く（ADR-048）。共通カラムの値、NULL から空文字への変換、共通カラムの {@code *_pgm_cd} を束縛する
 * Aspect（ADR-051）と、業務テーブルの UPDATE と DELETE の唯一の入口である {@link
 * com.example.demo.shared.infrastructure.persistence.TableWriter}（ADR-054）を含む。
 *
 * <p>使ってよいのは、他のモジュールの {@code infrastructure.persistence} のアダプターだけとする。業務の概念を置かない。
 */
@NamedInterface("persistence")
@NullMarked
package com.example.demo.shared.infrastructure.persistence;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
