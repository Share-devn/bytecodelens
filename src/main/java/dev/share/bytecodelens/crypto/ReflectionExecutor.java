package dev.share.bytecodelens.crypto;

import dev.share.bytecodelens.model.ClassEntry;
import dev.share.bytecodelens.model.LoadedJar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Opt-in executor that loads classes from the jar into a dedicated ClassLoader
 * and calls decryptor methods via reflection. Only meant for cases where the
 * symbolic interpreter cannot handle the decryptor (external dependencies, etc).
 *
 * Users must explicitly enable this mode because it runs arbitrary code from the jar.
 */
public final class ReflectionExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReflectionExecutor.class);

    private final JarClassLoader classLoader;

    public ReflectionExecutor(LoadedJar jar) {
        this.classLoader = new JarClassLoader(jar);
    }

    public String tryInvoke(String ownerInternal, String methodName, String desc,
                            String stringArg, Integer intArg) {
        try {
            Class<?> cls = classLoader.loadClass(ownerInternal.replace('/', '.'));
            if ("(Ljava/lang/String;)Ljava/lang/String;".equals(desc)) {
                Method m = cls.getDeclaredMethod(methodName, String.class);
                m.setAccessible(true);
                Object r = m.invoke(null, stringArg);
                return r instanceof String s ? s : null;
            }
            if ("(ILjava/lang/String;)Ljava/lang/String;".equals(desc) && intArg != null) {
                Method m = cls.getDeclaredMethod(methodName, int.class, String.class);
                m.setAccessible(true);
                Object r = m.invoke(null, intArg, stringArg);
                return r instanceof String s ? s : null;
            }
            if ("(Ljava/lang/String;I)Ljava/lang/String;".equals(desc) && intArg != null) {
                Method m = cls.getDeclaredMethod(methodName, String.class, int.class);
                m.setAccessible(true);
                Object r = m.invoke(null, stringArg, intArg);
                return r instanceof String s ? s : null;
            }
        } catch (Throwable ex) {
            log.debug("Reflection invoke of {}.{}{} failed: {}", ownerInternal, methodName, desc, ex.getMessage());
        }
        return null;
    }

    private static final class JarClassLoader extends ClassLoader {
        private final Map<String, byte[]> bytesByFqn = new HashMap<>();

        JarClassLoader(LoadedJar jar) {
            super(JarClassLoader.class.getClassLoader());
            for (ClassEntry c : jar.classes()) {
                bytesByFqn.put(c.name(), c.bytes());
            }
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = bytesByFqn.get(name);
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
