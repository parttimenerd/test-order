package com.example.advanced;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.containers.GenericContainer;

/**
 * Pattern 8: TestContainers Integration
 * Testing @Testcontainers with @Container
 * (Requires Docker - may be skipped if not available)
 */
@Testcontainers(disabledWithoutDocker = true)
class P8TestContainersTests {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @Test
    void containerIsRunning() {
        assert redis.isRunning();
    }

    @Test
    void canAccessContainer() {
        assert redis.getFirstMappedPort() > 0;
    }

    @Test
    void multipleContainerTests() {
        assert redis.isRunning();
    }

    // Total: 3 tests with container
}
