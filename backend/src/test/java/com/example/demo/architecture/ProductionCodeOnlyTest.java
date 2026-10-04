package com.example.demo.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.jooq.Tables;
import com.tngtech.archunit.core.importer.Location;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link ProductionCodeOnly} が jOOQ の生成コードだけを外し、手書きのコードを外さないことを確かめる。 */
class ProductionCodeOnlyTest {

  @Test
  @DisplayName("jooq という名前の手書きのパッケージは ArchUnit の規則の対象にする")
  void includesHandwrittenJooqSubPackage() {
    final Location handwritten =
        Location.of(
            URI.create(
                "file:/app/backend/build/classes/java/main/com/example/demo/"
                    + "order/infrastructure/persistence/jooq/JooqOrderRepository.class"));

    assertThat(ProductionCodeOnly.isHandwritten(handwritten)).isTrue();
  }

  @Test
  @DisplayName("jOOQ の生成コードは ArchUnit の規則の対象から外す")
  void excludesGeneratedJooqCode() throws URISyntaxException {
    final Location generated =
        Location.of(Objects.requireNonNull(Tables.class.getResource("Tables.class")).toURI());

    assertThat(ProductionCodeOnly.isHandwritten(generated)).isFalse();
  }
}
