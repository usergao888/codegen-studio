package com.gaoyc.studio.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

public class GenerationServiceCacheKeyTest {

    @Test
    void tenantIsPartOfCacheKey() {
        assertEquals("tenant-a|做一个待办", GenerationService.cacheKey("tenant-a", "做一个待办"));
        assertNotEquals(
                GenerationService.cacheKey("tenant-a", "做一个待办"),
                GenerationService.cacheKey("tenant-b", "做一个待办"));
    }

    @Test
    void blankTenantFallsBackToDefault() {
        assertEquals("default|p", GenerationService.cacheKey(null, "p"));
        assertEquals("default|p", GenerationService.cacheKey("  ", "p"));
    }
}
