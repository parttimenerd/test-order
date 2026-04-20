// Test complex try-with-resources and exception handling
import java.io.*;
import java.util.*;

class ExceptionTest {
    void multiCatch() {
        try {
            File f = new File("test.txt");
        } catch (SecurityException | FileNotFoundException e) {
            e.printStackTrace();
        }
    }
    
    void tryWithResources() {
        try (Scanner s = new Scanner(System.in)) {
            String line = s.nextLine();
        } catch (Exception e) {
        }
    }
    
    void nestedTry() {
    }
}
