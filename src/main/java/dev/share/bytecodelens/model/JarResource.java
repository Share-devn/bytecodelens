package dev.share.bytecodelens.model;

public record JarResource(
        String path,
        String simpleName,
        long size,
        ResourceKind kind
) {
    public enum ResourceKind {
        JAVA_CLASS,
        NATIVE_DLL,
        NATIVE_SO,
        NATIVE_DYLIB,
        NESTED_JAR,
        NESTED_WAR,
        NESTED_ZIP,
        MANIFEST,
        SERVICE,
        PROPERTIES,
        XML,
        JSON,
        YAML,
        TEXT,
        IMAGE,
        FONT,
        SQL,
        SCRIPT,
        BINARY,
        MODULE_INFO,
        OTHER
    }

    public static ResourceKind detect(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".class")) return ResourceKind.JAVA_CLASS;
        if (lower.equals("module-info.class") || lower.endsWith("/module-info.class")) return ResourceKind.MODULE_INFO;
        if (lower.endsWith(".dll")) return ResourceKind.NATIVE_DLL;
        if (lower.endsWith(".so")) return ResourceKind.NATIVE_SO;
        if (lower.endsWith(".dylib") || lower.endsWith(".jnilib")) return ResourceKind.NATIVE_DYLIB;
        if (lower.endsWith(".jar")) return ResourceKind.NESTED_JAR;
        if (lower.endsWith(".war")) return ResourceKind.NESTED_WAR;
        if (lower.endsWith(".zip")) return ResourceKind.NESTED_ZIP;
        if (lower.endsWith("/manifest.mf") || lower.equals("manifest.mf")
                || lower.equals("meta-inf/manifest.mf")) return ResourceKind.MANIFEST;
        if (lower.startsWith("meta-inf/services/") || lower.contains("/meta-inf/services/")) return ResourceKind.SERVICE;
        if (lower.endsWith(".properties")) return ResourceKind.PROPERTIES;
        if (lower.endsWith(".xml") || lower.endsWith(".xsd") || lower.endsWith(".xslt")) return ResourceKind.XML;
        if (lower.endsWith(".json")) return ResourceKind.JSON;
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return ResourceKind.YAML;
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".rst")
                || lower.endsWith(".csv") || lower.endsWith(".log")) return ResourceKind.TEXT;
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".svg") || lower.endsWith(".ico")
                || lower.endsWith(".webp") || lower.endsWith(".bmp")) return ResourceKind.IMAGE;
        if (lower.endsWith(".ttf") || lower.endsWith(".otf") || lower.endsWith(".woff")
                || lower.endsWith(".woff2")) return ResourceKind.FONT;
        if (lower.endsWith(".sql")) return ResourceKind.SQL;
        if (lower.endsWith(".sh") || lower.endsWith(".bat") || lower.endsWith(".cmd")
                || lower.endsWith(".ps1")) return ResourceKind.SCRIPT;
        if (lower.endsWith(".bin") || lower.endsWith(".dat")) return ResourceKind.BINARY;
        return ResourceKind.OTHER;
    }

    public String parentPath() {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    /**
     * Content-based detection fallback. Inspects the first few bytes for well-known magic
     * numbers. Returns {@code null} when nothing recognisable matches — the caller should
     * fall back to extension-based {@link #detect(String)}.
     */
    public static ResourceKind detectByContent(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return null;
        int b0 = bytes[0] & 0xff;
        int b1 = bytes[1] & 0xff;
        int b2 = bytes[2] & 0xff;
        int b3 = bytes[3] & 0xff;

        // Java class magic "CAFEBABE"
        if (b0 == 0xCA && b1 == 0xFE && b2 == 0xBA && b3 == 0xBE) return ResourceKind.JAVA_CLASS;
        // ZIP-family ("PK\x03\x04")
        if (b0 == 'P' && b1 == 'K' && b2 == 0x03 && b3 == 0x04) return ResourceKind.NESTED_JAR;
        // PNG magic "\x89PNG"
        if (b0 == 0x89 && b1 == 'P' && b2 == 'N' && b3 == 'G') return ResourceKind.IMAGE;
        // GIF ("GIF8")
        if (b0 == 'G' && b1 == 'I' && b2 == 'F' && b3 == '8') return ResourceKind.IMAGE;
        // JPEG
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return ResourceKind.IMAGE;
        // BMP
        if (b0 == 'B' && b1 == 'M') return ResourceKind.IMAGE;
        // WOFF
        if (b0 == 'w' && b1 == 'O' && b2 == 'F' && b3 == 'F') return ResourceKind.FONT;
        // WOFF2
        if (b0 == 'w' && b1 == 'O' && b2 == 'F' && b3 == '2') return ResourceKind.FONT;
        // TrueType ("\x00\x01\x00\x00") / OpenType ("OTTO")
        if (b0 == 0 && b1 == 1 && b2 == 0 && b3 == 0) return ResourceKind.FONT;
        if (b0 == 'O' && b1 == 'T' && b2 == 'T' && b3 == 'O') return ResourceKind.FONT;
        // ELF shared object
        if (b0 == 0x7F && b1 == 'E' && b2 == 'L' && b3 == 'F') return ResourceKind.NATIVE_SO;
        // Windows PE ("MZ")
        if (b0 == 'M' && b1 == 'Z') return ResourceKind.NATIVE_DLL;
        // Mach-O (all four byte-order variants)
        int magic = (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
        if (magic == 0xFEEDFACE || magic == 0xCEFAEDFE
                || magic == 0xFEEDFACF || magic == 0xCFFAEDFE) return ResourceKind.NATIVE_DYLIB;
        // XML / JSON / common text heuristics — scan only if bytes look ASCII-printable.
        if (looksLikeText(bytes)) {
            String prefix = new String(bytes, 0, Math.min(bytes.length, 64))
                    .trim().toLowerCase();
            if (prefix.startsWith("<?xml")) return ResourceKind.XML;
            if (prefix.startsWith("{") || prefix.startsWith("[")) return ResourceKind.JSON;
            if (prefix.startsWith("#!")) return ResourceKind.SCRIPT;
            return ResourceKind.TEXT;
        }
        return null;
    }

    private static boolean looksLikeText(byte[] bytes) {
        int limit = Math.min(bytes.length, 256);
        int printable = 0;
        for (int i = 0; i < limit; i++) {
            int b = bytes[i] & 0xff;
            if (b == '\t' || b == '\n' || b == '\r' || (b >= 0x20 && b < 0x7f)) printable++;
            else if (b >= 0xc0) printable++; // UTF-8 continuation/lead bytes — tolerate
        }
        return printable * 10 > limit * 9; // > 90% printable
    }
}
