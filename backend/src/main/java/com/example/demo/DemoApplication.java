package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot アプリケーションのエントリポイント。 */
@SpringBootApplication
@SuppressWarnings("PMD.UseUtilityClass")
public class DemoApplication {

	/** アプリケーションを起動する。 */
	public static void main(final String[] args) {
		SpringApplication.run(DemoApplication.class, args);
	}

}
