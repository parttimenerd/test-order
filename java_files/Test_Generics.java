// Test file for generic types and wildcard support
// Expected: 1 class, 4 methods with proper type parsing
class GenericsTest {
    void simpleGeneric() {}
    
    <T> T genericMethod(T input) {
        return input;
    }
    
    void wildcardBounds() {}
    
    void nestedGenerics() {}
}
