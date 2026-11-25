/** 商品の参照を公開する機能モジュール。 */
@ApplicationModule(allowedDependencies = {"shared :: security", "jooq"})
@NullMarked
package com.example.demo.product;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
