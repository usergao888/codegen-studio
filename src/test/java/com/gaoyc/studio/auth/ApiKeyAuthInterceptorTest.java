package com.gaoyc.studio.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

public class ApiKeyAuthInterceptorTest {

    @Test
    void parsesKeyTenantPairs() {
        Map<String, String> parsed = ApiKeyAuthInterceptor.parseKeys(
                "key-a:tenant-a, key-b:tenant-b");
        assertEquals("tenant-a", parsed.get("key-a"));
        assertEquals("tenant-b", parsed.get("key-b"));
    }

    @Test
    void blankKeysAreIgnoredAndTenantDefaults() {
        Map<String, String> parsed = ApiKeyAuthInterceptor.parseKeys(" , key-c ,key-d: ");
        assertEquals("default", parsed.get("key-c"));
        assertEquals("default", parsed.get("key-d"));
        assertTrue(ApiKeyAuthInterceptor.parseKeys("").isEmpty());
    }
}
