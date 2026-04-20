// Test annotations and complex modifiers
// Expected: 2 types, multiple annotated methods
@Deprecated
public class AnnotatedClass {
    
    @Override
    public String toString() {
        return "test";
    }
    
    @SuppressWarnings("unchecked")
    public void silenced() {
    }
}

@FunctionalInterface
interface Callback {
    void execute();
}
