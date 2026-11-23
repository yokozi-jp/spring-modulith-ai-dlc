/** APIエラー契約を提供する横断モジュール。 */
@ApplicationModule(allowedDependencies = "shared :: *")
@NullMarked
package com.example.demo.error;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
