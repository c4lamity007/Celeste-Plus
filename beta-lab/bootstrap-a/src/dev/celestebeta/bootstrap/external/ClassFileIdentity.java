package dev.celestebeta.bootstrap.external;

import java.io.IOException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Identity across JVM constant-pool rebuilding and method declaration reordering. */
final class ClassFileIdentity {
    private ClassFileIdentity() { }

    static String canonicalHash(byte[] bytes) throws IOException {
        try {
            if (bytes == null) throw new IOException("Missing class bytes");
            ClassNode node = new ClassNode();
            // Keep debug, annotations, frames and all other understood metadata.
            new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
            rejectOpaque(node.attrs);
            Set<String> fields = new HashSet<>();
            for (var field : node.fields) {
                if (!fields.add(field.name + ":" + field.desc)) throw new IOException("Duplicate class field");
                rejectOpaque(field.attrs);
            }
            Set<String> methods = new HashSet<>();
            for (var method : node.methods) {
                if (!methods.add(method.name + method.desc)) throw new IOException("Duplicate class method");
                rejectOpaque(method.attrs);
            }
            if (node.recordComponents != null) for (var component : node.recordComponents) rejectOpaque(component.attrs);
            node.methods.sort(Comparator.comparing((MethodNode method) -> method.name).thenComparing(method -> method.desc));
            // A fresh writer rebuilds references instead of retaining old pool indices.
            // No COMPUTE_FRAMES path can resolve or initialize game classes here.
            ClassWriter canonical = new ClassWriter(0);
            node.accept(canonical);
            return VerifiedBootstrapLoader.hash(canonical.toByteArray());
        } catch (RuntimeException malformed) {
            throw new IOException("Class identity could not be normalized", malformed);
        }
    }
    private static void rejectOpaque(List<Attribute> attributes) throws IOException {
        if (attributes != null && !attributes.isEmpty()) throw new IOException("Opaque class attributes cannot be normalized");
    }
}