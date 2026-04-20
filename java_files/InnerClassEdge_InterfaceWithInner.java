// Inner class edge cases: interface with inner class, static inner with its own inner
// Expected: outer interface + inner class + deeper inner
interface InnerClassEdge_InterfaceWithInner {

    void doWork();

    class Helper {
        static String format(String input) {
            return "[" + input + "]";
        }

        class Formatter {
            String bold(String text) {
                return "**" + text + "**";
            }
        }
    }

    enum Priority {
        LOW, MEDIUM, HIGH;

        boolean isUrgent() {
            return this == HIGH;
        }
    }
}
