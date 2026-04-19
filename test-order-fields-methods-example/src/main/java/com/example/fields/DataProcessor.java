package com.example.fields;

/**
 * Processes data with static field dependencies.
 * Tests dependency on static field modifications.
 */
public class DataProcessor {
    
    private static int processedCount = 0;
    private static long lastProcessTime = 0;
    private static String lastProcessedData = "";
    
    public static void processData(String data) {
        lastProcessedData = data;
        lastProcessTime = System.currentTimeMillis();
        processedCount++;
    }
    
    public static String getLastProcessedData() {
        return lastProcessedData;
    }
    
    public static long getLastProcessTime() {
        return lastProcessTime;
    }
    
    public static int getProcessedCount() {
        return processedCount;
    }
    
    public static void resetStats() {
        processedCount = 0;
        lastProcessTime = 0;
        lastProcessedData = "";
    }
    
    public static int processMultiple(String... dataItems) {
        int count = 0;
        for (String item : dataItems) {
            processData(item);
            count++;
        }
        return count;
    }
}
