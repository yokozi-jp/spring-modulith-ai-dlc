package com.example.demo.testkit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jooq.test.autoconfigure.JooqTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * ロールバックで隔離される、jOOQ スライスのDBテスト向け合成アノテーション。
 *
 * <p>{@code @JooqTest} は各テストの後に自動ロールバックするため、書き込んだ行の後始末は不要。 リポジトリ/DAO のような「書いて読む」大多数のDBテストはこれを既定にする。
 *
 * <p>{@code @JooqTest} は {@code @Configuration}
 * を読み込まないため、トランザクションの名前からモジュールのロールへ切り替えるトランザクションマネージャーを {@code @Import}
 * する（ADR-094）。テストのトランザクションは、テストクラスの名前（{@code
 * com.example.demo.<module>.…}）からモジュールのロールで動く。モジュールの外のテストクラスは、アプリロールのままで、{@link AsModule} で切り替える。
 *
 * <p>テストのトランザクションは、{@link DatabaseTestTransactionManager} がテストの利用者のテナント（{@link
 * AsTenant#TENANT}）を束縛して始める。別のテナントの読み書きは {@link AsModule} にそのテナントを渡す。
 *
 * <p>{@code replace = NONE} で組み込みDBへの差し替えを止め、生成コードと同じ方言の実 PostgreSQL を使う。
 *
 * <p>実際にコミットして読み戻す忠実さが要るテスト（timestamptz 往復やイベント発行など）は、 代わりに {@link CommittedDatabaseTest} を使う。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@JooqTest
@Transactional(label = DatabaseTestTransactionManager.LABEL)
@Import(DatabaseTestTransactionManager.Config.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public @interface DatabaseTest {}
