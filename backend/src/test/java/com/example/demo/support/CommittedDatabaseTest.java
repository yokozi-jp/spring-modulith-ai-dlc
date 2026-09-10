package com.example.demo.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * コミットを伴うDB統合テスト向けの合成アノテーション。
 *
 * <p>テストトランザクションを張らない（{@code @Transactional} を付けない）ため、各文はそのまま コミットされ、timestamptz
 * 往復のように「実際に保存して読み戻す」忠実さを検証できる。
 *
 * <p>後始末は {@link TruncateGeneratedTablesExtension} が各テスト後に自動で行うので、テストごとの 手動 cleanup は不要。
 *
 * <p>単に副作用を捨てたいだけの大多数のDBテストは、より軽くロールバックで隔離される {@link DatabaseTest} を使う。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@Import(SharedTestConfiguration.class)
@ExtendWith(TruncateGeneratedTablesExtension.class)
public @interface CommittedDatabaseTest {}
