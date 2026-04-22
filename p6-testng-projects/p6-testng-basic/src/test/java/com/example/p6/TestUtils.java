package com.example.p6;

public class TestUtils {
    
    public boolean isReady() {
        return true;
    }

    public int add(int a, int b) {
        return a + b;
    }

    public String greet(String name) {
        return "Hello, " + name + "!";
    }

    public boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    public double divide(double a, double b) {
        return a / b;
    }

    public String reverse(String s) {
        return new StringBuilder(s).reverse().toString();
    }

    public boolean isPrime(int n) {
        if (n < 2) return false;
        for (int i = 2; i * i <= n; i++) {
            if (n % i == 0) return false;
        }
        return true;
    }

    public int[] createArray(int size) {
        return new int[size];
    }

    public long factorial(int n) {
        if (n <= 1) return 1;
        return n * factorial(n - 1);
    }
}
