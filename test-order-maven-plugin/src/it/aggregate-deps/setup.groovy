// Pre-create .deps files for the aggregate IT fixture
import java.nio.file.Files
import java.nio.file.Path

def depsDir = new File(basedir, "target/test-order-deps")
depsDir.mkdirs()

new File(depsDir, "com.example.FooTest.deps").text = "com.example.Foo\ncom.example.Bar\n"
new File(depsDir, "com.example.BarTest.deps").text = "com.example.Baz\n"
