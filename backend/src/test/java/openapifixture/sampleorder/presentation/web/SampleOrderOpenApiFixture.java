package openapifixture.sampleorder.presentation.web;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/** package-private の SampleOrderController をパッケージの外のテストから読み込む構成。 */
@TestConfiguration(proxyBeanMethods = false)
@Import(SampleOrderController.class)
public class SampleOrderOpenApiFixture {}
