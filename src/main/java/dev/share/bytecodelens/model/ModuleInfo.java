package dev.share.bytecodelens.model;

import java.util.List;

public record ModuleInfo(
        String name,
        String version,
        int access,
        List<Requires> requires,
        List<Exports> exports,
        List<Opens> opens,
        List<String> uses,
        List<Provides> provides
) {
    public record Requires(String module, int access, String version) {}
    public record Exports(String packageName, int access, List<String> modules) {}
    public record Opens(String packageName, int access, List<String> modules) {}
    public record Provides(String service, List<String> providers) {}
}
