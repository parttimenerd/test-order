// Test lambda and functional expressions
// Expected: 1 class, 3 methods
class LambdaTest {
    java.util.function.Predicate<String> verify() {
        return s -> s.length() > 0;
    }
    
    Runnable task() {
        return () -> System.out.println("test");
    }
    
    void acceptLambda() {
    }
}
