// Inner class edge cases: sealed inner hierarchy, enum with methods inside another enum
// Expected: outer + several nested types at various depths
class InnerClassEdge_SealedAndEnumInEnum {

    sealed interface Shape permits Circle, Rectangle {}

    record Circle(double radius) implements Shape {
        double area() {
            return Math.PI * radius * radius;
        }
    }

    record Rectangle(double w, double h) implements Shape {
        double area() {
            return w * h;
        }
    }

    // Enum with a nested enum
    enum Outer {
        A, B;

        enum Inner {
            X, Y;

            String label() {
                return name().toLowerCase();
            }
        }
    }

    String describe(Shape shape) {
        return switch (shape) {
            case Circle c -> "circle r=" + c.radius();
            case Rectangle r -> "rect " + r.w() + "x" + r.h();
        };
    }
}
