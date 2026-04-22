package com.example;

public class LogicService {
    public boolean isPositive(int num) {
        return num > 0;
    }
    
    public boolean isNegative(int num) {
        return num < 0;
    }
    
    public boolean isEven(int num) {
        return num % 2 == 0;
    }
    
    public int max(int a, int b) {
        return a > b ? a : b;
    }
    
    public String classify(int num) {
        if (num > 0) {
            return "positive";
        } else if (num < 0) {
            return "negative";
        }
        return "zero";
    }
}
