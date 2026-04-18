package dev.share.bytecodelens.ui;

import java.util.List;

public final class PatternExamples {

    public record Example(String title, String source) {
    }

    public static final String DEFAULT = """
            // Find methods that print to console
            method {
              contains invoke java/io/PrintStream.println
            }
            """;

    private static final List<Example> EXAMPLES = List.of(
            new Example("Methods with println",
                    """
                    // Find methods that call System.out.println
                    method {
                      contains invoke java/io/PrintStream.println
                    }
                    """),
            new Example("Classes with hardcoded secrets",
                    """
                    // Classes with fields named like password/secret/token/apikey
                    class {
                      any field {
                        name ~ /password|secret|token|apikey/i
                      }
                    }
                    """),
            new Example("Reflection abuse",
                    """
                    // Methods that use Method.invoke after setAccessible
                    method {
                      contains invoke java/lang/reflect/AccessibleObject.setAccessible
                      contains invoke java/lang/reflect/Method.invoke
                    }
                    """),
            new Example("Native library loaders",
                    """
                    // Classes that load native .dll / .so / .dylib
                    method {
                      contains invoke java/lang/Runtime.loadLibrary
                      | contains invoke java/lang/System.loadLibrary
                      | contains invoke java/lang/System.load
                    }
                    """),
            new Example("Big switch (ZKM-like flow)",
                    """
                    // Methods with tableswitch and huge instruction count
                    method {
                      contains tableswitch
                      instructions > 200
                    }
                    """),
            new Example("Hardcoded URLs",
                    """
                    // LDC strings that look like URLs
                    method {
                      contains ldc ~ /https?:\\/\\/[^\\s"]+/
                    }
                    """),
            new Example("Crypto usage",
                    """
                    // Methods touching crypto APIs
                    method {
                      contains invoke javax/crypto/Cipher.getInstance
                      | contains invoke java/security/MessageDigest.getInstance
                      | contains invoke javax/crypto/spec/SecretKeySpec.<init>
                    }
                    """),
            new Example("Runtime.exec() backdoor",
                    """
                    // Classes that spawn OS processes
                    method {
                      contains invoke java/lang/Runtime.exec
                      | contains invoke java/lang/ProcessBuilder.<init>
                      | contains invoke java/lang/ProcessBuilder.start
                    }
                    """),
            new Example("Custom classloaders",
                    """
                    // Classes extending ClassLoader that define their own classes
                    class {
                      extends ~ /ClassLoader/
                      any method {
                        contains invoke *.defineClass
                      }
                    }
                    """),
            new Example("Static final string fields",
                    """
                    // Potentially interesting constants
                    field {
                      access static
                      access final
                      desc "Ljava/lang/String;"
                    }
                    """),
            new Example("System.exit callers",
                    """
                    // Methods that terminate the JVM
                    method {
                      contains invoke java/lang/System.exit
                      | contains invoke java/lang/Runtime.exit
                      | contains invoke java/lang/Runtime.halt
                    }
                    """)
    );

    private PatternExamples() {
    }

    public static List<Example> all() {
        return EXAMPLES;
    }
}
