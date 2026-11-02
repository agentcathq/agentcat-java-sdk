import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The release steps of {@code .github/workflows/release.yml}, on {@code pom.xml},
 * {@code CHANGELOG.md} and the {@code v*} tags of the repository. Run it from the
 * repository root with the JDK alone: {@code java scripts/Release.java <command>}.
 *
 * <ul>
 *   <li>{@code prepare <patch | minor | major | X.Y.Z> <YYYY-MM-DD>} picks the next version,
 *       writes it into {@code pom.xml} and the install snippet of {@code README.md}, turns the {@code [Unreleased]} section of the CHANGELOG
 *       into the section of that version with the date, and prints the version. {@code patch},
 *       {@code minor} and {@code major} count from the newest {@code v*} tag. With no tag yet,
 *       the first release is the version that {@code pom.xml} holds, whatever the input word.
 *       An exact version must be newer than the newest tag. An {@code [Unreleased]} section
 *       with no entry stops the step: a release needs a change.</li>
 *   <li>{@code status} prints {@code <version> publish} when the version of {@code pom.xml} has
 *       no tag and the CHANGELOG has its dated section, else {@code <version> skip}.</li>
 *   <li>{@code notes <X.Y.Z>} prints the body of the CHANGELOG section of that version.</li>
 * </ul>
 *
 * <p>{@code ReleaseTest} tests these steps in the normal Maven build.
 */
public final class Release {

    static final String USAGE = "usage: java scripts/Release.java prepare <patch | minor | major | X.Y.Z> <YYYY-MM-DD> | status | notes <X.Y.Z>";

    /** The repository URL that the CHANGELOG links point to. */
    static final String REPOSITORY = "https://github.com/agentcathq/agentcat-java-sdk";

    static final String POM = "pom.xml";
    static final String CHANGELOG = "CHANGELOG.md";
    static final String README = "README.md";
    static final String UNRELEASED = "## [Unreleased]";

    /**
     * The project version: the first {@code <version>} after the artifactId of the SDK. The
     * same pattern finds the version of the install snippet in {@code README.md}.
     */
    private static final Pattern PROJECT_VERSION = Pattern.compile(
            "(<artifactId>agentcat-java-sdk</artifactId>\\s*<version>)([^<]*)(</version>)");

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private Release() {}

    /** A release version: {@code MAJOR.MINOR.PATCH}, with no pre-release or build part. */
    record Version(long major, long minor, long patch) implements Comparable<Version> {

        private static final Pattern FORM = Pattern.compile("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)");

        static Optional<Version> parse(String text) {
            Matcher m = FORM.matcher(text);
            if (!m.matches()) return Optional.empty();
            try {
                return Optional.of(new Version(
                        Long.parseLong(m.group(1)), Long.parseLong(m.group(2)), Long.parseLong(m.group(3))));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }

        @Override
        public int compareTo(Version o) {
            if (major != o.major) return Long.compare(major, o.major);
            if (minor != o.minor) return Long.compare(minor, o.minor);
            return Long.compare(patch, o.patch);
        }

        @Override
        public String toString() {
            return major + "." + minor + "." + patch;
        }
    }

    /** The newest of the {@code v<version>} tags; other tags do not count. */
    static Optional<Version> newestTag(List<String> tags) {
        return tags.stream()
                .filter(t -> t.startsWith("v"))
                .map(t -> Version.parse(t.substring(1)))
                .flatMap(Optional::stream)
                .max(Version::compareTo);
    }

    /** The version of the release that {@code input} asks for: see the class documentation. */
    static Version nextVersion(String input, Version current, Optional<Version> newest) {
        boolean word = List.of("patch", "minor", "major").contains(input);
        Optional<Version> exact = Version.parse(input);
        if (!word && exact.isEmpty()) {
            throw new IllegalArgumentException("\"" + input + "\" is not patch, minor, major or a version X.Y.Z");
        }
        if (newest.isEmpty()) {
            if (exact.isPresent() && !exact.get().equals(current)) {
                throw new IllegalArgumentException("the first release is the version of " + POM + ", "
                        + current + "; set it there to release " + exact.get());
            }
            return current;
        }
        Version n = newest.get();
        Version next = switch (input) {
            case "patch" -> new Version(n.major(), n.minor(), n.patch() + 1);
            case "minor" -> new Version(n.major(), n.minor() + 1, 0);
            case "major" -> new Version(n.major() + 1, 0, 0);
            default -> exact.get();
        };
        if (next.compareTo(n) <= 0) {
            throw new IllegalArgumentException(next + " is not newer than the newest tag, v" + n);
        }
        return next;
    }

    /** The project version of {@code pom}. */
    static Optional<Version> pomVersion(String pom) {
        Matcher m = PROJECT_VERSION.matcher(pom);
        return m.find() ? Version.parse(m.group(2).trim()) : Optional.empty();
    }

    /** {@code pom} with {@code version} as its project version. */
    static String setPomVersion(String pom, Version version) {
        Matcher m = PROJECT_VERSION.matcher(pom);
        if (!m.find()) throw new IllegalArgumentException(POM + " has no project version");
        return pom.substring(0, m.start(2)) + version + pom.substring(m.end(2));
    }

    /**
     * {@code changelog} with its {@code [Unreleased]} entries moved under
     * {@code ## [<version>] - <date>}, an empty {@code [Unreleased]} section above it,
     * and the links of both sections.
     */
    static String dateChangelog(String changelog, Version version, String date, Optional<Version> previous) {
        if (!DATE.matcher(date).matches()) {
            throw new IllegalArgumentException("\"" + date + "\" is not a date YYYY-MM-DD");
        }
        List<String> lines = changelog.lines().toList();
        int start = lines.indexOf(UNRELEASED);
        if (start < 0) throw new IllegalArgumentException(CHANGELOG + " has no " + UNRELEASED + " section");
        int end = sectionEnd(lines, start);
        if (lines.subList(start + 1, end).stream().noneMatch(l -> l.startsWith("- "))) {
            throw new IllegalArgumentException("the " + UNRELEASED + " section of " + CHANGELOG
                    + " has no entry; add the changes of the release first");
        }
        int unreleasedLink = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("[Unreleased]: ")) {
                unreleasedLink = i;
                break;
            }
        }
        if (unreleasedLink < 0) throw new IllegalArgumentException(CHANGELOG + " has no [Unreleased] link");
        String versionLink = previous
                .map(p -> "[" + version + "]: " + REPOSITORY + "/compare/v" + p + "...v" + version)
                .orElse("[" + version + "]: " + REPOSITORY + "/releases/tag/v" + version);
        List<String> out = new ArrayList<>(lines.size() + 4);
        for (int i = 0; i < lines.size(); i++) {
            if (i == start) {
                out.add(UNRELEASED);
                out.add("");
                out.add("## [" + version + "] - " + date);
            } else if (i == unreleasedLink) {
                out.add("[Unreleased]: " + REPOSITORY + "/compare/v" + version + "...HEAD");
                out.add(versionLink);
            } else {
                out.add(lines.get(i));
            }
        }
        return String.join("\n", out) + "\n";
    }

    /** The body of the {@code ## [<version>] - <date>} section, without its blank edges. */
    static Optional<String> datedSection(String changelog, Version version) {
        List<String> lines = changelog.lines().toList();
        String heading = "## [" + version + "] - ";
        for (int start = 0; start < lines.size(); start++) {
            String line = lines.get(start);
            if (line.startsWith(heading) && DATE.matcher(line.substring(heading.length()).trim()).matches()) {
                String body = String.join("\n", lines.subList(start + 1, sectionEnd(lines, start)));
                return Optional.of(body.replaceAll("^\\n+|\\n+$", ""));
            }
        }
        return Optional.empty();
    }

    /** The index of the line after the section that starts at {@code start}: the next heading or link line. */
    private static int sectionEnd(List<String> lines, int start) {
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("## ") || (line.startsWith("[") && line.contains("]: "))) return i;
        }
        return lines.size();
    }

    /** {@code <version> publish} when the version has no tag and a dated section, else {@code <version> skip}. */
    static String status(Version version, List<String> tags, String changelog) {
        boolean tagged = tags.contains("v" + version);
        boolean dated = datedSection(changelog, version).isPresent();
        return version + (!tagged && dated ? " publish" : " skip");
    }

    // ── Command line ───────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        try {
            System.out.println(run(args, Path.of("")));
        } catch (UsageException e) {
            System.err.println(USAGE);
            System.exit(2);
        } catch (IllegalArgumentException | IOException e) {
            System.err.println("release: " + e.getMessage());
            System.exit(1);
        }
    }

    private static final class UsageException extends RuntimeException {}

    static String run(String[] args, Path root) throws IOException, InterruptedException {
        if (args.length == 3 && args[0].equals("prepare")) return prepare(root, args[1], args[2]);
        if (args.length == 1 && args[0].equals("status")) return status(root);
        if (args.length == 2 && args[0].equals("notes")) return notes(root, args[1]);
        throw new UsageException();
    }

    private static String prepare(Path root, String input, String date) throws IOException, InterruptedException {
        String pom = read(root, POM);
        Version current = pomVersion(pom).orElseThrow(() -> new IllegalArgumentException(POM + " has no valid version"));
        Optional<Version> newest = newestTag(tags(root));
        Version version = nextVersion(input, current, newest);
        String changelog = dateChangelog(read(root, CHANGELOG), version, date, newest);
        if (!version.equals(current)) {
            write(root, POM, setPomVersion(pom, version));
            write(root, README, setPomVersion(read(root, README), version));
        }
        write(root, CHANGELOG, changelog);
        return version.toString();
    }

    private static String status(Path root) throws IOException, InterruptedException {
        Version version = pomVersion(read(root, POM))
                .orElseThrow(() -> new IllegalArgumentException(POM + " has no valid version"));
        return status(version, tags(root), read(root, CHANGELOG));
    }

    private static String notes(Path root, String text) throws IOException {
        Version version = Version.parse(text)
                .orElseThrow(() -> new IllegalArgumentException("\"" + text + "\" is not a version X.Y.Z"));
        return datedSection(read(root, CHANGELOG), version)
                .orElseThrow(() -> new IllegalArgumentException(CHANGELOG + " has no dated section for " + version));
    }

    /** The tags of the repository at {@code root}. */
    private static List<String> tags(Path root) throws IOException, InterruptedException {
        Process git = new ProcessBuilder("git", "tag", "--list")
                .directory(root.toAbsolutePath().toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        String out = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (git.waitFor() != 0) throw new IOException("git tag --list failed");
        return out.lines().toList();
    }

    private static String read(Path root, String path) throws IOException {
        return Files.readString(root.resolve(path), StandardCharsets.UTF_8);
    }

    private static void write(Path root, String path, String text) throws IOException {
        Files.writeString(root.resolve(path), text, StandardCharsets.UTF_8);
    }
}
