package me.bechberger.ide;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;

/**
 * Test class to verify test-order works in IDE context.
 * 
 * This test demonstrates IDE Integration Issue P5-IDE-001:
 * When run from IDE (gutter click, context menu), testorder.index.path
 * and testorder.state.path are not set, causing orderer to silently disable.
 * 
 * Steps to reproduce:
 * 1. First, run via Maven CLI:
 *    mvn clean test
 *    This creates .test-order/test-dependencies.lz4
 * 
 * 2. Then, open this file in IDE and:
 *    - Click test method gutter icon
 *    - Select "Run" from context menu
 *    - Test executes but test-order orderer is silently disabled
 * 
 * Expected: Test orderer loads and applies ordering
 * Actual: Orderer silently disabled (no error message)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class IDEIntegrationTest {
    
    private static StringBuilder executionOrder = new StringBuilder();
    
    @Test
    @Order(3)
    void testA_ShouldRunLast() {
        executionOrder.append("A");
        System.out.println("Executed: Test A");
    }
    
    @Test
    @Order(1)
    void testB_ShouldRunFirst() {
        executionOrder.append("B");
        System.out.println("Executed: Test B");
    }
    
    @Test
    @Order(2)
    void testC_ShouldRunMiddle() {
        executionOrder.append("C");
        System.out.println("Executed: Test C");
    }
}
