package conflictfixture;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/** package-private の ConflictFixtureController をパッケージの外のテストから読み込む構成。 */
@TestConfiguration(proxyBeanMethods = false)
@Import(ConflictFixtureController.class)
public class ConflictFixture {}
