package dev.share.bytecodelens.jvminspect;

import java.util.List;
import java.util.Map;

/**
 * One-shot snapshot of a remote JVM's state — produced by the agent and parsed on the
 * client side. Every field is optional ({@code null} if the agent couldn't collect it),
 * so downstream UIs should render a placeholder rather than NPE on missing data.
 */
public record JvmStateSnapshot(
        long fetchedAtMs,
        Runtime runtime,
        ClassLoading classLoading,
        Compilation compilation,
        OperatingSystem os,
        Memory memory,
        List<MemoryPool> memoryPools,
        List<GcStats> gc,
        Threads threads,
        Map<String, String> systemProperties) {

    public record Runtime(
            String name, String vmName, String vmVendor, String vmVersion,
            String specName, String specVersion, String specVendor,
            String classPath, String libraryPath, String bootClassPath,
            boolean bootClassPathSupported,
            long startTime, long uptime, long pid,
            List<String> inputArguments) {}

    public record ClassLoading(
            long loadedClassCount, long totalLoadedClassCount, long unloadedClassCount,
            boolean verbose) {}

    public record Compilation(boolean available, String name,
                              boolean timeMonitoringSupported, long totalCompilationTimeMs) {}

    public record OperatingSystem(
            String name, String arch, String version,
            int availableProcessors, double systemLoadAverage,
            long freeMemorySize, long totalMemorySize,
            long committedVirtualMemorySize,
            long freeSwapSpaceSize, long totalSwapSpaceSize,
            long processCpuTime, double cpuLoad, double processCpuLoad) {}

    public record Memory(MemoryUsage heap, MemoryUsage nonHeap,
                         long objectPendingFinalizationCount) {}

    public record MemoryUsage(long init, long used, long committed, long max) {}

    public record MemoryPool(String name, String type, MemoryUsage usage, MemoryUsage peak) {}

    public record GcStats(String name, long collectionCount, long collectionTimeMs) {}

    public record Threads(
            int threadCount, int peakThreadCount, int daemonThreadCount,
            long totalStartedThreadCount,
            List<Long> deadlocked,
            List<ThreadInfo> list) {}

    public record ThreadInfo(long id, String name, String state,
                             long blockedCount, long waitedCount,
                             List<String> stack) {}
}
