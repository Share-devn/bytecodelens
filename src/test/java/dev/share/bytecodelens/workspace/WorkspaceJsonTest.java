package dev.share.bytecodelens.workspace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorkspaceJsonTest {

    @Test
    void roundTripsFullState() throws Exception {
        WorkspaceState s = new WorkspaceState();
        s.jarPath = "/path/to/app.jar";
        s.mappingFile = "/path/to/mapping.txt";
        s.mappingFormat = "PROGUARD";
        s.activeTab = "com.foo.Bar";
        s.darkTheme = false;
        s.mainSplit1 = 0.15;
        s.mainSplit2 = 0.78;
        s.openTabs.add("com.foo.Bar");
        s.openTabs.add("com.foo.Baz");
        s.comments.put("CLASS:com.foo.Bar", "This is the auth class");
        s.comments.put("METHOD:com.foo.Bar:login:(Ljava/lang/String;)Z", "entry point");

        String json = WorkspaceJson.serialize(s);
        WorkspaceState restored = WorkspaceJson.parse(json);

        assertEquals(s.jarPath, restored.jarPath);
        assertEquals(s.mappingFile, restored.mappingFile);
        assertEquals(s.mappingFormat, restored.mappingFormat);
        assertEquals(s.activeTab, restored.activeTab);
        assertEquals(s.darkTheme, restored.darkTheme);
        assertEquals(s.mainSplit1, restored.mainSplit1, 0.0001);
        assertEquals(s.mainSplit2, restored.mainSplit2, 0.0001);
        assertEquals(s.openTabs, restored.openTabs);
        assertEquals(s.comments, restored.comments);
    }

    @Test
    void nullsAreSerializedAndRestored() throws Exception {
        WorkspaceState s = new WorkspaceState();
        s.jarPath = "/p";
        // mappingFile stays null
        String json = WorkspaceJson.serialize(s);
        WorkspaceState restored = WorkspaceJson.parse(json);
        assertEquals("/p", restored.jarPath);
        assertNull(restored.mappingFile);
    }

    @Test
    void specialCharsInCommentsAreEscaped() throws Exception {
        WorkspaceState s = new WorkspaceState();
        s.jarPath = "/p";
        s.comments.put("CLASS:a.b",
                "Multi\nline\ttext with \"quotes\" and backslash \\ yes");
        String json = WorkspaceJson.serialize(s);
        WorkspaceState restored = WorkspaceJson.parse(json);
        assertEquals(s.comments, restored.comments);
    }

    @Test
    void unicodeSurvives() throws Exception {
        WorkspaceState s = new WorkspaceState();
        s.jarPath = "/p";
        s.comments.put("CLASS:ru/Пакет/Класс", "Комментарий с \u4e2d\u6587");
        String json = WorkspaceJson.serialize(s);
        WorkspaceState restored = WorkspaceJson.parse(json);
        assertEquals(s.comments, restored.comments);
    }

    @Test
    void codeFontSizeRoundTrips() throws Exception {
        WorkspaceState s = new WorkspaceState();
        s.jarPath = "/p";
        s.codeFontSize = 17.5;
        String json = WorkspaceJson.serialize(s);
        WorkspaceState restored = WorkspaceJson.parse(json);
        assertEquals(17.5, restored.codeFontSize, 0.0001);
    }

    @Test
    void codeFontSizeMissingFromLegacyJsonKeepsDefault() throws Exception {
        // Older sessions wrote a workspace without this key; reading must fall back to
        // the class-level default (13.0), not crash or set zero.
        String legacy = "{\n  \"jarPath\": \"/old.jar\",\n  \"openTabs\": []\n}";
        WorkspaceState restored = WorkspaceJson.parse(legacy);
        assertEquals(13.0, restored.codeFontSize, 0.0001);
    }

    @Test
    void excludedPackagesRoundTrip() throws Exception {
        WorkspaceState s = new WorkspaceState();
        s.jarPath = "/p";
        s.excludedPackages.add("com.google.*");
        s.excludedPackages.add("kotlin.jvm");
        s.excludedPackages.add("org.jetbrains.annotations.*");
        String json = WorkspaceJson.serialize(s);
        WorkspaceState restored = WorkspaceJson.parse(json);
        assertEquals(s.excludedPackages, restored.excludedPackages);
    }

    @Test
    void excludedPackagesMissingFromLegacyJsonGivesEmpty() throws Exception {
        // Old workspace files without the excludedPackages field must parse with an
        // empty (not null) list — consumers iterate it unconditionally.
        String legacy = "{\n  \"jarPath\": \"/old.jar\"\n}";
        WorkspaceState restored = WorkspaceJson.parse(legacy);
        org.junit.jupiter.api.Assertions.assertNotNull(restored.excludedPackages);
        org.junit.jupiter.api.Assertions.assertTrue(restored.excludedPackages.isEmpty());
    }
}
