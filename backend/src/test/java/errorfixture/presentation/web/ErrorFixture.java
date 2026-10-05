package errorfixture.presentation.web;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/** package-private の ErrorFixtureController をパッケージの外のテストから読み込む構成。 */
@TestConfiguration(proxyBeanMethods = false)
@Import(ErrorFixtureController.class)
public class ErrorFixture {}
