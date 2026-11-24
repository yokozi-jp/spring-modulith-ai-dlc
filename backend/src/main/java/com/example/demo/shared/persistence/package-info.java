/**
 * jOOQ の技術的な共通処理を置く（ADR-048）。共通カラムの値、NULL から空文字への変換、共通カラムの {@code *_pgm_cd} を束縛する
 * Aspect（ADR-051）と、業務テーブルの INSERT と UPDATE を組み立てて実行する唯一の入口である {@link
 * com.example.demo.shared.persistence.TableWriter}（ADR-054）を含む。
 *
 * <p>DB のロールとテナントの設定をトランザクションの開始時に決める {@code ModuleRoleTransactionManager} も置く。
 *
 * <p>使ってよいのは、他のモジュールの {@code infrastructure.persistence} のアダプターだけとする。業務の概念を置かない。
 */
@NamedInterface("persistence")
@NullMarked
package com.example.demo.shared.persistence;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
