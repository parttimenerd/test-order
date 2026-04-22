package com.example.util;

/**
 * Simple math utility class for integration testing.
 */
public class MathHelper {

    public static int factorial(int n) {
        if (n < 0) throw new IllegalArgumentException("Negative input");
        int result = 1;
        for (int i = 2; i <= n; i++) result *= i;
        return result;
    }

    public static boolean isPrime(int n) {
        if (n < 2) return false;
        for (int i = 2; i * i <= n; i++) {
            if (n % i == 0) return false;
        }
        return true;
    }

    public static int gcd(int a, int b) {
        while (b != 0) {
            int temp = b;
            b = a % b;
            a = temp;
        }
        return Math.abs(a);
    }
}
