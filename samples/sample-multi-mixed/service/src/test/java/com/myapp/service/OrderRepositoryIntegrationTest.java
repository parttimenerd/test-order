package com.myapp.service;

import com.myapp.api.Order;
import com.myapp.api.OrderRepository;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.*;

/**
 * Additional tests exercising the OrderRepository through the service layer.
 */
public class OrderRepositoryIntegrationTest {

    private OrderRepository repository;
    private OrderService service;

    @BeforeMethod
    public void setUp() {
        repository = new OrderRepository();
        service = new OrderService(repository);
    }

    @DataProvider(name = "customers")
    public Object[][] customerData() {
        return new Object[][]{
                {"Alice", 3},
                {"Bob", 2},
                {"Carol", 1}
        };
    }

    @Test(dataProvider = "customers")
    public void testMultipleOrdersPerCustomer(String customer, int orderCount) {
        for (int i = 0; i < orderCount; i++) {
            service.placeOrder(customer + "-" + i, customer, List.of("Item" + i));
        }
        assertEquals(service.getCustomerOrders(customer).size(), orderCount);
    }

    @Test
    public void testRepositoryCountAfterMultiplePlaces() {
        service.placeOrder("x1", "Alice", List.of("A"));
        service.placeOrder("x2", "Bob", List.of("B"));
        service.placeOrder("x3", "Carol", List.of("C"));
        assertEquals(repository.count(), 3);
    }

    @Test
    public void testOverwriteOrder() {
        service.placeOrder("dup", "Alice", List.of("Original"));
        service.placeOrder("dup", "Alice", List.of("Updated", "Extra"));
        Order found = repository.findById("dup").orElseThrow();
        assertEquals(found.itemCount(), 2);
        assertTrue(found.containsItem("Updated"));
    }
}
