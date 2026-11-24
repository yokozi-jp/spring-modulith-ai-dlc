/** 注文の下書き、確定、取消と参照を公開する機能モジュール。 */
@ApplicationModule(allowedDependencies = {"product", "shared :: *", "jooq"})
@NullMarked
package com.example.demo.ordering;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
