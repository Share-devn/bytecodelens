package dev.share.bytecodelens.usage;

public sealed interface UsageTarget {

    String label();

    record Method(String ownerInternal, String name, String desc) implements UsageTarget {
        @Override
        public String label() {
            return ownerInternal.replace('/', '.') + "#" + name + desc;
        }
    }

    record Field(String ownerInternal, String name, String desc) implements UsageTarget {
        @Override
        public String label() {
            return ownerInternal.replace('/', '.') + "." + name;
        }
    }

    record Class(String internalName) implements UsageTarget {
        @Override
        public String label() {
            return internalName.replace('/', '.');
        }
    }
}
