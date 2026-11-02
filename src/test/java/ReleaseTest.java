import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The release steps of {@code scripts/Release.java}, which {@code .github/workflows/release.yml} runs.
 */
class ReleaseTest {

    private static Release.Version v(String text) {
        return Release.Version.parse(text).orElseThrow();
    }

    // ── Version ────────────────────────────────────────────────────────────

    @Test
    void parsesPlainVersions() {
        assertEquals("1.2.3", v("1.2.3").toString());
        assertEquals("0.10.0", v("0.10.0").toString());
    }

    @Test
    void rejectsVersionsThatAreNotMajorMinorPatch() {
        for (String bad : List.of("", "1", "1.2", "1.2.3.4", "01.2.3", "1.2.x", "1.2.3-SNAPSHOT", "v1.2.3", " 1.2.3")) {
            assertTrue(Release.Version.parse(bad).isEmpty(), bad);
        }
    }

    @Test
    void comparesVersionsByNumberNotByText() {
        assertTrue(v("0.10.0").compareTo(v("0.9.9")) > 0);
    }

    // ── Tags ───────────────────────────────────────────────────────────────

    @Test
    void newestTagIgnoresTagsThatAreNotVersions() {
        assertEquals(Optional.of(v("0.10.0")),
                Release.newestTag(List.of("v0.9.0", "v0.10.0", "latest", "v1.0.0-rc1", "0.20.0")));
        assertEquals(Optional.empty(), Release.newestTag(List.of()));
    }

    // ── Next version ───────────────────────────────────────────────────────

    @Test
    void wordsCountFromTheNewestTag() {
        Optional<Release.Version> newest = Optional.of(v("1.2.3"));
        assertEquals(v("1.2.4"), Release.nextVersion("patch", v("1.2.3"), newest));
        assertEquals(v("1.3.0"), Release.nextVersion("minor", v("1.2.3"), newest));
        assertEquals(v("2.0.0"), Release.nextVersion("major", v("1.2.3"), newest));
        assertEquals(v("1.5.0"), Release.nextVersion("1.5.0", v("1.2.3"), newest));
    }

    @Test
    void anExactVersionMustBeNewerThanTheNewestTag() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Release.nextVersion("1.2.3", v("1.2.3"), Optional.of(v("1.2.3"))));
        assertTrue(e.getMessage().contains("not newer"), e.getMessage());
    }

    @Test
    void theFirstReleaseIsThePomVersion() {
        assertEquals(v("0.1.0"), Release.nextVersion("patch", v("0.1.0"), Optional.empty()));
        assertEquals(v("0.1.0"), Release.nextVersion("major", v("0.1.0"), Optional.empty()));
        assertEquals(v("0.1.0"), Release.nextVersion("0.1.0", v("0.1.0"), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> Release.nextVersion("2.0.0", v("0.1.0"), Optional.empty()));
    }

    @Test
    void rejectsAnInputThatIsNotAWordOrAVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> Release.nextVersion("bump", v("1.0.0"), Optional.of(v("1.0.0"))));
        assertThrows(IllegalArgumentException.class,
                () -> Release.nextVersion("bump", v("1.0.0"), Optional.empty()));
    }

    // ── pom.xml ────────────────────────────────────────────────────────────

    private static final String POM = String.join("\n",
            "<project>",
            "    <modelVersion>4.0.0</modelVersion>",
            "    <groupId>com.agentcat</groupId>",
            "    <artifactId>agentcat-java-sdk</artifactId>",
            "    <version>0.1.0</version>",
            "    <dependencies>",
            "        <dependency>",
            "            <groupId>com.agentcat</groupId>",
            "            <artifactId>api</artifactId>",
            "            <version>0.0.5</version>",
            "        </dependency>",
            "    </dependencies>",
            "</project>",
            "");

    @Test
    void readsTheProjectVersionNotADependencyVersion() {
        assertEquals(Optional.of(v("0.1.0")), Release.pomVersion(POM));
    }

    @Test
    void setsOnlyTheProjectVersion() {
        String out = Release.setPomVersion(POM, v("0.2.0"));
        assertTrue(out.contains("<artifactId>agentcat-java-sdk</artifactId>\n    <version>0.2.0</version>"), out);
        assertTrue(out.contains("<version>0.0.5</version>"), out);
        assertEquals(POM.length(), out.length());
    }

    @Test
    void thePomOfThisRepositoryHasAVersion() throws Exception {
        String pom = java.nio.file.Files.readString(java.nio.file.Path.of("pom.xml"));
        assertTrue(Release.pomVersion(pom).isPresent());
    }

    // ── CHANGELOG ──────────────────────────────────────────────────────────

    private static final String REPO = "https://github.com/agentcathq/agentcat-java-sdk";

    private static final String CHANGELOG = String.join("\n",
            "# Changelog",
            "",
            "## [Unreleased]",
            "",
            "### Added",
            "",
            "- A new thing.",
            "",
            "## [0.1.0] - 2026-11-01",
            "",
            "- The first release.",
            "",
            "[Unreleased]: " + REPO + "/compare/v0.1.0...HEAD",
            "[0.1.0]: " + REPO + "/releases/tag/v0.1.0",
            "");

    @Test
    void datesTheUnreleasedSectionAndAddsItsLinks() {
        String out = Release.dateChangelog(CHANGELOG, v("0.2.0"), "2026-11-02", Optional.of(v("0.1.0")));
        assertEquals(String.join("\n",
                "# Changelog",
                "",
                "## [Unreleased]",
                "",
                "## [0.2.0] - 2026-11-02",
                "",
                "### Added",
                "",
                "- A new thing.",
                "",
                "## [0.1.0] - 2026-11-01",
                "",
                "- The first release.",
                "",
                "[Unreleased]: " + REPO + "/compare/v0.2.0...HEAD",
                "[0.2.0]: " + REPO + "/compare/v0.1.0...v0.2.0",
                "[0.1.0]: " + REPO + "/releases/tag/v0.1.0",
                ""), out);
    }

    @Test
    void theFirstReleaseLinksToItsTag() {
        String first = String.join("\n",
                "# Changelog", "", "## [Unreleased]", "", "- The first release.", "",
                "[Unreleased]: " + REPO + "/commits/main", "");
        String out = Release.dateChangelog(first, v("0.1.0"), "2026-11-02", Optional.empty());
        assertTrue(out.contains("## [0.1.0] - 2026-11-02"), out);
        assertTrue(out.contains("[Unreleased]: " + REPO + "/compare/v0.1.0...HEAD\n[0.1.0]: " + REPO + "/releases/tag/v0.1.0\n"), out);
    }

    @Test
    void anEmptyUnreleasedSectionStopsTheRelease() {
        String empty = String.join("\n",
                "# Changelog", "", "## [Unreleased]", "", "### Added", "", "## [0.1.0] - 2026-11-01", "", "- x", "",
                "[Unreleased]: " + REPO + "/compare/v0.1.0...HEAD", "");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Release.dateChangelog(empty, v("0.2.0"), "2026-11-02", Optional.of(v("0.1.0"))));
        assertTrue(e.getMessage().contains("no entry"), e.getMessage());
    }

    @Test
    void rejectsADateThatIsNotYearMonthDay() {
        assertThrows(IllegalArgumentException.class,
                () -> Release.dateChangelog(CHANGELOG, v("0.2.0"), "2026-1-2", Optional.of(v("0.1.0"))));
    }

    @Test
    void readsTheBodyOfADatedSection() {
        assertEquals(Optional.of("- The first release."), Release.datedSection(CHANGELOG, v("0.1.0")));
        assertEquals(Optional.empty(), Release.datedSection(CHANGELOG, v("0.2.0")));
    }

    @Test
    void theChangelogOfThisRepositoryHasAnUnreleasedSection() throws Exception {
        String changelog = java.nio.file.Files.readString(java.nio.file.Path.of("CHANGELOG.md"));
        assertTrue(changelog.lines().anyMatch("## [Unreleased]"::equals));
        assertTrue(changelog.lines().anyMatch(l -> l.startsWith("[Unreleased]: ")));
    }

    @Test
    void theReadmeInstallSnippetNamesThePomVersion() throws Exception {
        String pom = java.nio.file.Files.readString(java.nio.file.Path.of("pom.xml"));
        String readme = java.nio.file.Files.readString(java.nio.file.Path.of("README.md"));
        assertEquals(Release.pomVersion(pom), Release.pomVersion(readme));
    }

    // ── prepare, end to end ────────────────────────────────────────────────

    private static java.nio.file.Path repoWith(String... tags) throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("release-test");
        java.nio.file.Files.writeString(dir.resolve("pom.xml"), POM);
        java.nio.file.Files.writeString(dir.resolve("CHANGELOG.md"), CHANGELOG);
        java.nio.file.Files.writeString(dir.resolve("README.md"),
                "<dependency>\n    <artifactId>agentcat-java-sdk</artifactId>\n    <version>0.1.0</version>\n</dependency>\n");
        git(dir, "init", "-q");
        git(dir, "-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "--allow-empty", "-m", "start");
        for (String tag : tags) git(dir, "tag", tag);
        return dir;
    }

    private static void git(java.nio.file.Path dir, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile()).inheritIO();
        // The developer's own git configuration (signed tags, for example) must not change the test.
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        Process p = builder.start();
        assertEquals(0, p.waitFor(), String.join(" ", command));
    }

    @Test
    void prepareSetsTheVersionInThePomAndTheReadmeAndDatesTheChangelog() throws Exception {
        java.nio.file.Path dir = repoWith("v0.1.0");
        assertEquals("0.2.0", Release.run(new String[] {"prepare", "minor", "2026-11-02"}, dir));
        assertEquals(Optional.of(v("0.2.0")), Release.pomVersion(java.nio.file.Files.readString(dir.resolve("pom.xml"))));
        assertEquals(Optional.of(v("0.2.0")), Release.pomVersion(java.nio.file.Files.readString(dir.resolve("README.md"))));
        String changelog = java.nio.file.Files.readString(dir.resolve("CHANGELOG.md"));
        assertTrue(changelog.contains("## [0.2.0] - 2026-11-02"), changelog);
        assertEquals("0.2.0 publish", Release.run(new String[] {"status"}, dir));
        assertEquals("### Added\n\n- A new thing.", Release.run(new String[] {"notes", "0.2.0"}, dir));
    }

    // ── status ─────────────────────────────────────────────────────────────

    @Test
    void publishesOnlyAnUntaggedVersionWithADatedSection() {
        assertEquals("0.1.0 publish", Release.status(v("0.1.0"), List.of(), CHANGELOG));
        assertEquals("0.1.0 skip", Release.status(v("0.1.0"), List.of("v0.1.0"), CHANGELOG));
        assertEquals("0.2.0 skip", Release.status(v("0.2.0"), List.of("v0.1.0"), CHANGELOG));
    }
}
