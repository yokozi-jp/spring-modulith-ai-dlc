/** 決済の記録と参照を公開する機能モジュール。 */
@ApplicationModule(allowedDependencies = {"ordering", "shared :: *", "jooq"})
@NullMarked
package com.example.demo.payment;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
