// Test var keyword and local variables
// Expected: 1 class, 2 methods
class VarTest {
    void testVar() {
        var x = 10;
        var list = new java.util.ArrayList<String>();
        var map = java.util.Map.of("a", 1);
    }
    
    void normalDeclaration() {
        int y = 20;
    }
}
