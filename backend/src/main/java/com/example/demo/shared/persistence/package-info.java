/**
 * jOOQ と DB の技術的な共通処理を置く（ADR-048）。共通カラムの値と {@code *_pgm_cd} を束縛する Aspect（ADR-051）、NULL
 * から空文字への変換、業務テーブルの INSERT と UPDATE を組み立てて実行する唯一の入口である {@link
 * com.example.demo.shared.persistence.TableWriter}（ADR-054）、SQL の観測、DB
 * のロールとテナントの設定をトランザクションの開始時に決める {@code ModuleRoleTransactionManager} を含む。
 *
 * <p>使ってよいのは、他のモジュールの {@code infrastructure.persistence} のアダプターだけとする。業務の概念を置かない。
 */
@NamedInterface("persistence")
@NullMarked
package com.example.demo.shared.persistence;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
