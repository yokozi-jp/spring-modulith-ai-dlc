package com.example.demo;

import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.modulith.Modulithic;

/** Spring Boot アプリケーションのエントリポイント。 */
@SpringBootApplication
// 共通処理の shared モジュールを、モジュール単位の統合テストでも常に起動する（ADR-044）。
@Modulithic(sharedModules = "shared")
public class DemoApplication {

  /** 現在時刻を取得するための、UTC固定でマイクロ秒単位に切り捨てたClockを提供する。 */
  @Bean
  public Clock clock() {
    return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
  }

  /** アプリケーションを起動する。 */
  public static void main(final String[] args) {
    SpringApplication.run(DemoApplication.class, args);
  }
}
