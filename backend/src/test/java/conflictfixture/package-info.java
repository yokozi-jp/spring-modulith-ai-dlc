/**
 * 競合の例外を投げるだけの HTTP API。
 *
 * <p>com.example.demo の外に置き、component scan、ArchUnit、OpenAPI の契約の書き出しに含めない。 ApiContractTest だけが
 * {@link ConflictFixture} で読み込む。
 */
@NullMarked
package conflictfixture;

import org.jspecify.annotations.NullMarked;
