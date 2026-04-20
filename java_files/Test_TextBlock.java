// Test text blocks and string literals
// Expected: 1 class, 2 methods
class TextBlockTest {
    String getJson() {
        return """
            {
              "name": "test",
              "value": 42
            }
            """;
    }
    
    void regularString() {
        String s = "normal string";
        char c = 'x';
    }
}
