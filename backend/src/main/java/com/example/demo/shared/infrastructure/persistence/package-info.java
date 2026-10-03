/**
 * jOOQ の技術的な共通処理（共通カラムの値、楽観的ロック、NULL から空文字への変換）を置く（ADR-046）。
 *
 * <p>使ってよいのは、他のモジュールの {@code infrastructure.persistence} のアダプターだけとする。業務の概念を置かない。
 */
@NamedInterface("persistence")
@NullMarked
package com.example.demo.shared.infrastructure.persistence;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
