package errorfixture.presentation.web;

import errorfixture.application.AdminOnlyCommandHandler;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/** package-private の ErrorFixtureController と、それが呼ぶ CommandHandler をパッケージの外のテストから読み込む構成。 */
@TestConfiguration(proxyBeanMethods = false)
@Import({ErrorFixtureController.class, AdminOnlyCommandHandler.class})
public class ErrorFixture {}
