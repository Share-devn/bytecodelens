package dev.share.bytecodelens.usage;

public record CallSite(
        String inClassFqn,
        String inMethodName,
        String inMethodDesc,
        Kind kind,
        String targetOwner,
        String targetName,
        String targetDesc,
        int lineNumber
) {
    public enum Kind {
        INVOKE_VIRTUAL,
        INVOKE_STATIC,
        INVOKE_SPECIAL,
        INVOKE_INTERFACE,
        INVOKE_DYNAMIC,
        GETFIELD,
        PUTFIELD,
        GETSTATIC,
        PUTSTATIC,
        NEW,
        CHECKCAST,
        INSTANCEOF,
        ANEWARRAY,
        TYPE_IN_SIGNATURE;

        /** True for GETFIELD / GETSTATIC — the call site reads a field. */
        public boolean isFieldRead() {
            return this == GETFIELD || this == GETSTATIC;
        }

        /** True for PUTFIELD / PUTSTATIC — the call site writes a field. */
        public boolean isFieldWrite() {
            return this == PUTFIELD || this == PUTSTATIC;
        }

        /** True for any INVOKE_* opcode — the call site invokes a method. */
        public boolean isInvoke() {
            return this == INVOKE_VIRTUAL || this == INVOKE_STATIC
                || this == INVOKE_SPECIAL || this == INVOKE_INTERFACE
                || this == INVOKE_DYNAMIC;
        }

        /** True for NEW / CHECKCAST / INSTANCEOF / ANEWARRAY / TYPE_IN_SIGNATURE. */
        public boolean isTypeUse() {
            return this == NEW || this == CHECKCAST || this == INSTANCEOF
                || this == ANEWARRAY || this == TYPE_IN_SIGNATURE;
        }
    }
}
