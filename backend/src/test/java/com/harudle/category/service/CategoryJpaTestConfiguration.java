package com.harudle.category.service;

import com.harudle.TestcontainersConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

@TestConfiguration(proxyBeanMethods = false)
@Import({
        TestcontainersConfiguration.class,
        CategoryCreateService.class,
        DefaultCategoryReader.class,
        CategoryUpdateService.class
})
class CategoryJpaTestConfiguration {
}
