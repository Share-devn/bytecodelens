package dev.share.bytecodelens.decompile;

import dev.share.bytecodelens.service.Decompiler;

public final class CfrDecompiler implements ClassDecompiler {

    private final Decompiler impl = new Decompiler();

    @Override
    public String name() {
        return "CFR";
    }

    @Override
    public String decompile(String internalName, byte[] classBytes) {
        return impl.decompile(internalName, classBytes);
    }
}
