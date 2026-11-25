/** 商品の登録と参照を公開する機能モジュール。 */
@ApplicationModule(allowedDependencies = {"shared :: *", "jooq"})
@NullMarked
package com.example.demo.product;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
