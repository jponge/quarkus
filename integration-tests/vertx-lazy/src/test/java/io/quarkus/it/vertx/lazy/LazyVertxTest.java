package io.quarkus.it.vertx.lazy;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;

@QuarkusMainTest
public class LazyVertxTest {

    @Test
    @Launch(exitCode = 0)
    public void testLazyVertxInitialization(LaunchResult result) {
        assertTrue(result.getOutput().contains("PHASE1_OK"), "Expected PHASE1_OK in output");
        assertTrue(result.getOutput().contains("PHASE2_OK"), "Expected PHASE2_OK in output");
        assertTrue(result.getOutput().contains("PHASE3_OK"), "Expected PHASE3_OK in output");
    }
}
