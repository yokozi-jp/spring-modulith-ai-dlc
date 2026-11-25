/**
 * shared に後から足した語彙のパッケージ。使う場所を限る規則に載せていないので、規則と docs を変えずに Application と Presentation から使えることを確かめる。
 */
@NamedInterface("idempotency")
@NullMarked
package archfixture.conforming.shared.idempotency;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
