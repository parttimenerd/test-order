// Test switch expressions (Java 14+)
// Expected: 1 class, 2 methods
class SwitchTest {
    String getDayName(int day) {
        return switch(day) {
            case 1 -> "Monday";
            case 2 -> "Tuesday";
            default -> "Other";
        };
    }
    
    int getQuarter(int month) {
        return switch(month) {
            case 1, 2, 3 -> 1;
            case 4, 5, 6 -> 2;
            case 7, 8, 9 -> 3;
            case 10, 11, 12 -> 4;
            default -> 0;
        };
    }
}
