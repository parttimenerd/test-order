// Inner class edge cases: constructor in inner class, abstract inner, inner with generics
// Expected: outer + 3 inner types, constructors and abstract methods detected
class InnerClassEdge_ConstructorAbstract {

    static class Config {
        private final String name;

        Config(String name) {
            this.name = name;
        }

        String getName() {
            return name;
        }
    }

    abstract static class Handler {
        abstract void handle(String input);

        void logAndHandle(String input) {
            System.out.println("handling: " + input);
            handle(input);
        }
    }

    static class GenericHolder<T> {
        private T value;

        GenericHolder(T value) {
            this.value = value;
        }

        T getValue() {
            return value;
        }

        void setValue(T value) {
            this.value = value;
        }
    }

    void test() {
        Config c = new Config("test");
        GenericHolder<String> h = new GenericHolder<>("hello");
    }
}
