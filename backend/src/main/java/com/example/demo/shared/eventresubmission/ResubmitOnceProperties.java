package com.example.demo.shared.eventresubmission;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 手での 1 回の再投入の設定値。
 *
 * @param target 再投入する出版。{@code all}、{@code policy:<方針の名前>}、{@code event-type:<イベントの完全修飾名>}
 *     のいずれか。起動の引数か環境変数で渡し、{@code application.yaml} には書かない
 * @param completionTimeout 再投入した出版の処理が終わるのを待つ上限
 */
@ConfigurationProperties("event-publication.resubmit-once")
@Validated
record ResubmitOnceProperties(
    @Nullable String target, @NotNull @DurationMin(seconds = 1) Duration completionTimeout) {}
