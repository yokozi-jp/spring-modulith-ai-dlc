/** shared モジュールが全ての層へ公開する、後から足した語彙のパッケージ。規則と docs を変えずに使えることを確かめる。 */
@NamedInterface("idempotency")
@NullMarked
package archfixture.conforming.shared.idempotency;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
