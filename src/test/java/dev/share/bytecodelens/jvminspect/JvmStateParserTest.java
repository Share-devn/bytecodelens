package dev.share.bytecodelens.jvminspect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for JSON → {@link JvmStateSnapshot} decoding. Exercises every section so a
 * shape regression in the agent's serializer breaks tests rather than surfacing as a
 * silent NPE in the UI.
 */
class JvmStateParserTest {

    @Test
    void parsesFullSnapshot() throws Exception {
        String json = """
            {
              "runtime": {
                "name": "12345@host",
                "vmName": "HotSpot",
                "vmVendor": "Oracle",
                "vmVersion": "21",
                "specName": "Java",
                "specVersion": "21",
                "specVendor": "Oracle",
                "classPath": "/a.jar:/b.jar",
                "libraryPath": "/lib",
                "bootClassPath": "?",
                "bootClassPathSupported": false,
                "startTime": 1000,
                "uptime": 5000,
                "pid": 12345,
                "inputArguments": ["-Xmx1G", "-Dfoo=bar"]
              },
              "classLoading": {
                "loadedClassCount": 500,
                "totalLoadedClassCount": 600,
                "unloadedClassCount": 100,
                "verbose": false
              },
              "compilation": {
                "available": true,
                "name": "HotSpot 64-Bit Tiered",
                "timeMonitoringSupported": true,
                "totalCompilationTimeMs": 1500
              },
              "os": {
                "name": "Linux",
                "arch": "amd64",
                "version": "5.15",
                "availableProcessors": 16,
                "systemLoadAverage": 0.5,
                "freeMemorySize": 1000,
                "totalMemorySize": 2000,
                "committedVirtualMemorySize": 500,
                "freeSwapSpaceSize": 300,
                "totalSwapSpaceSize": 400,
                "processCpuTime": 10000,
                "cpuLoad": 0.2,
                "processCpuLoad": 0.1
              },
              "memory": {
                "heap": {"init": 0, "used": 100, "committed": 200, "max": 1000},
                "nonHeap": {"init": 0, "used": 50, "committed": 80, "max": -1},
                "objectPendingFinalizationCount": 0
              },
              "memoryPools": [
                {"name": "Eden", "type": "HEAP",
                 "usage": {"init": 0, "used": 40, "committed": 60, "max": 100},
                 "peak": {"init": 0, "used": 55, "committed": 60, "max": 100}}
              ],
              "gc": [
                {"name": "G1 Young Generation", "collectionCount": 10, "collectionTimeMs": 50}
              ],
              "threads": {
                "threadCount": 25,
                "peakThreadCount": 30,
                "daemonThreadCount": 15,
                "totalStartedThreadCount": 100,
                "deadlocked": [],
                "list": [
                  {"id": 1, "name": "main", "state": "RUNNABLE",
                   "blockedCount": 0, "waitedCount": 0,
                   "stack": ["java.lang.Thread.run(Thread.java:123)"]}
                ]
              },
              "systemProperties": {
                "java.home": "/jdk",
                "file.encoding": "UTF-8"
              }
            }
            """;
        JvmStateSnapshot s = JvmStateParser.parse(json);
        assertNotNull(s);
        assertNotNull(s.runtime());
        assertEquals(12345, s.runtime().pid());
        assertEquals(2, s.runtime().inputArguments().size());
        assertEquals(500, s.classLoading().loadedClassCount());
        assertTrue(s.compilation().available());
        assertEquals("HotSpot 64-Bit Tiered", s.compilation().name());
        assertEquals(16, s.os().availableProcessors());
        assertEquals(100, s.memory().heap().used());
        assertEquals(1, s.memoryPools().size());
        assertEquals("Eden", s.memoryPools().get(0).name());
        assertEquals(1, s.gc().size());
        assertEquals(10, s.gc().get(0).collectionCount());
        assertEquals(25, s.threads().threadCount());
        assertEquals(1, s.threads().list().size());
        assertEquals("main", s.threads().list().get(0).name());
        assertEquals(2, s.systemProperties().size());
        assertEquals("/jdk", s.systemProperties().get("java.home"));
    }

    @Test
    void parsesSnapshotWithDeadlock() throws Exception {
        String json = """
            {
              "threads": {
                "threadCount": 2,
                "peakThreadCount": 2,
                "daemonThreadCount": 0,
                "totalStartedThreadCount": 2,
                "deadlocked": [10, 11],
                "list": []
              }
            }
            """;
        JvmStateSnapshot s = JvmStateParser.parse(json);
        assertEquals(2, s.threads().deadlocked().size());
        assertEquals(10L, s.threads().deadlocked().get(0));
    }

    @Test
    void missingSectionsAreNull() throws Exception {
        JvmStateSnapshot s = JvmStateParser.parse("{}");
        assertTrue(s.runtime() == null || s.runtime().name() == null,
                "Runtime absent/empty should parse without NPE");
        // memoryPools/gc default to empty lists.
        assertNotNull(s.memoryPools());
        assertEquals(0, s.memoryPools().size());
    }

    @Test
    void escapedStringsAreDecoded() throws Exception {
        String json = """
            {"systemProperties": {"key": "line1\\nline2\\tafter tab"}}
            """;
        JvmStateSnapshot s = JvmStateParser.parse(json);
        assertEquals("line1\nline2\tafter tab", s.systemProperties().get("key"));
    }

    @Test
    void unknownKeysAreIgnored() throws Exception {
        String json = """
            {
              "runtime": {"pid": 42, "someFutureField": "ignored"},
              "newTopLevel": "also ignored"
            }
            """;
        JvmStateSnapshot s = JvmStateParser.parse(json);
        assertEquals(42, s.runtime().pid());
    }

    @Test
    void compilationUnavailable() throws Exception {
        JvmStateSnapshot s = JvmStateParser.parse("{\"compilation\":{\"available\":false}}");
        assertFalse(s.compilation().available());
    }
}
