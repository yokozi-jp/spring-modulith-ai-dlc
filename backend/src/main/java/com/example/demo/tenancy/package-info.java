/** テナント、所属、役割、利用者を扱う機能モジュール。IdP の (issuer, subject) からアプリの利用者を引き、ログインの後にテナントを 1 つ選ばせる。 */
@ApplicationModule(allowedDependencies = {"shared :: *", "jooq"})
@NullMarked
package com.example.demo.tenancy;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
