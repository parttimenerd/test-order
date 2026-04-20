// Inner class edge cases: mixed type kinds (enum, interface, record inside a class)
// Expected: outer class + inner enum + inner interface + inner record
class InnerClassEdge_MixedKinds {

    enum Status {
        ACTIVE, INACTIVE;

        boolean isActive() {
            return this == ACTIVE;
        }
    }

    interface Processor {
        void process();

        default void log() {
            System.out.println("processing");
        }
    }

    record Point(int x, int y) {
        double distanceTo(Point other) {
            int dx = this.x - other.x;
            int dy = this.y - other.y;
            return Math.sqrt(dx * dx + dy * dy);
        }
    }

    void run() {
        Status s = Status.ACTIVE;
        Point p = new Point(1, 2);
        Processor proc = () -> System.out.println(p);
    }
}
