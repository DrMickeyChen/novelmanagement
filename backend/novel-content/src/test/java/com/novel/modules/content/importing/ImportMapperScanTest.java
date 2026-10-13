package com.novel.modules.content.importing;

import com.novel.ContentApplication;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScans;
import org.mybatis.spring.mapper.ClassPathMapperScanner;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import static org.junit.jupiter.api.Assertions.*;

class ImportMapperScanTest {
    @Test void applicationRegistersBothImportMappersWithoutScanningOtherInterfaces() {
        var registry = new DefaultListableBeanFactory();
        var scans = ContentApplication.class.getAnnotation(MapperScans.class);
        assertNotNull(scans);
        for (var scan : scans.value()) {
            if (scan.basePackages().length == 0) continue;
            var scanner = new ClassPathMapperScanner(registry);
            scanner.setAnnotationClass(scan.annotationClass());
            scanner.registerFilters();
            scanner.doScan(scan.basePackages());
        }
        assertTrue(registry.containsBeanDefinition("novelSourceMapper"));
        assertTrue(registry.containsBeanDefinition("novelImportTaskMapper"));
        assertEquals(2, registry.getBeanDefinitionCount());
    }
}
