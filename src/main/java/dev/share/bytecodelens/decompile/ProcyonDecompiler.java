package dev.share.bytecodelens.decompile;

import com.strobel.assembler.InputTypeLoader;
import com.strobel.assembler.metadata.Buffer;
import com.strobel.assembler.metadata.CompositeTypeLoader;
import com.strobel.assembler.metadata.ITypeLoader;
import com.strobel.assembler.metadata.MetadataSystem;
import com.strobel.assembler.metadata.TypeDefinition;
import com.strobel.assembler.metadata.TypeReference;
import com.strobel.decompiler.DecompilationOptions;
import com.strobel.decompiler.DecompilerSettings;
import com.strobel.decompiler.PlainTextOutput;
import com.strobel.decompiler.languages.Languages;

import java.io.StringWriter;

public final class ProcyonDecompiler implements ClassDecompiler {

    @Override
    public String name() {
        return "Procyon";
    }

    @Override
    public String decompile(String internalName, byte[] classBytes) {
        try {
            ITypeLoader primary = new SingleClassTypeLoader(internalName, classBytes);
            ITypeLoader fallback = new InputTypeLoader();
            ITypeLoader composite = new CompositeTypeLoader(primary, fallback);

            MetadataSystem meta = new MetadataSystem(composite);
            TypeReference ref = meta.lookupType(internalName);
            if (ref == null) {
                return "// Procyon: type not found: " + internalName;
            }
            TypeDefinition def = ref.resolve();
            if (def == null) {
                return "// Procyon: type not resolvable: " + internalName;
            }

            DecompilerSettings settings = DecompilerSettings.javaDefaults();
            settings.setShowSyntheticMembers(false);
            settings.setForceExplicitImports(true);
            settings.setTypeLoader(composite);

            DecompilationOptions options = new DecompilationOptions();
            options.setSettings(settings);
            options.setFullDecompilation(true);

            StringWriter sw = new StringWriter();
            PlainTextOutput out = new PlainTextOutput(sw);
            Languages.java().decompileType(def, out, options);

            String result = sw.toString();
            return result.isEmpty() ? "// Procyon produced no output" : result;
        } catch (Throwable ex) {
            String msg = ex.getMessage();
            if (msg == null) msg = ex.getClass().getSimpleName();
            return "// Procyon decompilation failed: " + msg;
        }
    }

    private static final class SingleClassTypeLoader implements ITypeLoader {
        private final String internalName;
        private final byte[] bytes;

        SingleClassTypeLoader(String internalName, byte[] bytes) {
            this.internalName = internalName;
            this.bytes = bytes;
        }

        @Override
        public boolean tryLoadType(String typeName, Buffer buffer) {
            if (!typeName.equals(internalName)) return false;
            buffer.reset(bytes.length);
            buffer.putByteArray(bytes, 0, bytes.length);
            buffer.position(0);
            return true;
        }
    }
}
