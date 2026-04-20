// Inner class edge cases: basic inner + static nested + deep nesting
// Expected: outer class + 4 inner types + methods at all levels
class InnerClassEdge_BasicAndDeep {

    // Basic inner class
    class Inner {
        void innerMethod() {
            System.out.println("inner");
        }
    }

    // Static nested class
    static class StaticNested {
        void staticNestedMethod() {
            System.out.println("static nested");
        }
    }

    // Deeply nested: 3 levels
    class Level1 {
        void level1Method() {}

        class Level2 {
            void level2Method() {}

            class Level3 {
                void level3Method() {
                    System.out.println("three levels deep");
                }
            }
        }
    }

    void outerMethod() {
        new Inner().innerMethod();
    }
}
