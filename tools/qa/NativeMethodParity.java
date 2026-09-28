import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.*;

/** Offline exact-method parity; never initializes or executes input classes. */
public final class NativeMethodParity {
  static ClassNode read(Path path) throws IOException {
    ClassNode node = new ClassNode();
    new ClassReader(Files.readAllBytes(path)).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    return node;
  }
  static String text(MethodNode method) {
    Textifier printer = new Textifier();
    method.accept(new TraceMethodVisitor(printer));
    StringWriter output = new StringWriter();
    printer.print(new PrintWriter(output));
    return method.access + "|" + method.name + method.desc + "|" + method.exceptions + "\n" + output;
  }
  public static void main(String[] args) throws Exception {
    if (args.length != 3) throw new IllegalArgumentException("before.class after.class methods required");
    ClassNode before=read(Path.of(args[0])), after=read(Path.of(args[1]));
    if (!before.name.equals(after.name)) throw new IllegalStateException("Canonical class identity changed");
    Set<String> selected = new HashSet<>(Files.readAllLines(Path.of(args[2])));
    int unchanged=0, nativeSelected=0;
    for (MethodNode old : before.methods) {
      List<MethodNode> matches=after.methods.stream().filter(m -> m.name.equals(old.name) && m.desc.equals(old.desc)).toList();
      if (matches.size()!=1) throw new IllegalStateException("Original method removed or duplicated: " + old.name+old.desc);
      MethodNode now=matches.getFirst();
      if (selected.contains(before.name+"#"+old.name+old.desc)) {
        if (text(old).equals(text(now))) throw new IllegalStateException("Selected method unchanged: "+old.name);
        nativeSelected++;
      } else {
        if (!text(old).equals(text(now))) throw new IllegalStateException("Unselected method changed: "+old.name+old.desc);
        unchanged++;
      }
    }
    if (nativeSelected!=selected.size()) throw new IllegalStateException("Selection was not fully represented");
    System.out.println("PASS unchanged-unselected-methods="+unchanged+" selected="+nativeSelected+"; includes existing class initializer");
  }
}
