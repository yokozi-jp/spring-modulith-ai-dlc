package com.example.demo;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** Spring Boot アプリケーションのエントリポイント。 */
@SpringBootApplication
public class DemoApplication {

  /** 現在時刻を取得するためのUTC固定Clockを提供する。 */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  /** アプリケーションを起動する。 */
  public static void main(final String[] args) {
    SpringApplication.run(DemoApplication.class, args);
  }
}
