package io.committed.speedy.format;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A throwaway git repository holding a Maven project configured with the speedy-spotless plugin.
 *
 * <p>The projects use only the built-in {@code trimTrailingWhitespace} and {@code endWithNewline}
 * steps. Those need no artifact resolution at format time, which keeps the integration tests fast
 * and offline-capable: what is under test here is the staging logic, not a formatter.
 */
final class TestProject implements AutoCloseable {

  /** Injected by failsafe so the tests run against the artifact installed by this build. */
  private static final String PLUGIN_VERSION = System.getProperty("speedy.version");

  private static final String MAVEN_HOME = System.getProperty("maven.home");

  private static final String SPOTLESS_VERSION = System.getProperty("spotless.version");

  private static final long MAVEN_TIMEOUT_MINUTES = 5;

  private final Path root;
  private final Git git;

  private TestProject(Path root, Git git) {
    this.root = root;
    this.git = git;
  }

  /** How the generated project configures Spotless' incremental up-to-date checking. */
  enum Caching {
    /** Disabled, as this plugin's README instructs. */
    DISABLED,
    /** Enabled, with nothing else declared: Spotless cannot find itself to fingerprint the build. */
    ENABLED,
    /** Enabled, with Spotless declared in pluginManagement so the fingerprint lookup resolves. */
    ENABLED_WITH_SPOTLESS_DECLARED
  }

  /** A single-module project rooted at the repository root. */
  static TestProject singleModule(Path root) throws Exception {
    return singleModule(root, Caching.DISABLED);
  }

  static TestProject singleModule(Path root, Caching caching) throws Exception {
    Files.writeString(root.resolve("pom.xml"), pom("probe", null, false, caching));
    return init(root);
  }

  /**
   * A parent project with one child module, so the plugin runs from a directory below the
   * repository root - the case the old test.sh covered.
   */
  static TestProject multiModule(Path root) throws Exception {
    Files.writeString(root.resolve("pom.xml"), pom("parent", "child", true, Caching.DISABLED));
    Files.createDirectories(root.resolve("child"));
    Files.writeString(root.resolve("child/pom.xml"), childPom());
    return init(root);
  }

  private static TestProject init(Path root) throws Exception {
    Git git = Git.init().setDirectory(root.toFile()).setInitialBranch("main").call();
    return new TestProject(root, git);
  }

  Path root() {
    return root;
  }

  void write(String relativePath, String content) throws IOException {
    Path file = root.resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  String read(String relativePath) throws IOException {
    return Files.readString(root.resolve(relativePath));
  }

  void delete(String relativePath) throws IOException {
    Files.delete(root.resolve(relativePath));
  }

  boolean exists(String relativePath) {
    return Files.exists(root.resolve(relativePath));
  }

  void stageAll() throws Exception {
    git.add().addFilepattern(".").call();
    // addFilepattern does not record deletions; setUpdate picks them up.
    git.add().addFilepattern(".").setUpdate(true).call();
  }

  void commit(String message) throws Exception {
    git.commit().setMessage(message).setAuthor("test", "test@example.com").setSign(false).call();
  }

  /** Contents of a path as currently recorded in the index, or null when it is not staged. */
  String indexContent(String relativePath) throws Exception {
    DirCache cache = git.getRepository().readDirCache();
    DirCacheEntry entry = cache.getEntry(relativePath);
    if (entry == null) {
      return null;
    }
    return new String(git.getRepository().open(entry.getObjectId()).getBytes(), UTF_8);
  }

  Status status() throws Exception {
    return git.status().call();
  }

  /**
   * Creates a linked worktree (git worktree add) and returns a project rooted in it. jgit has no
   * API for this, so it shells out to git.
   */
  TestProject linkedWorktree(Path target) throws Exception {
    runGit("worktree", "add", "-b", "feature", target.toString());
    return new TestProject(target, Git.open(target.toFile()));
  }

  /** Switches to an unborn branch while keeping the repository's existing history. */
  void checkoutOrphan(String branch) throws Exception {
    runGit("checkout", "--orphan", branch);
  }

  private void runGit(String... arguments) throws Exception {
    List<String> command = new java.util.ArrayList<>();
    command.add("git");
    command.addAll(List.of(arguments));
    Process process =
        new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), UTF_8);
    if (!process.waitFor(1, TimeUnit.MINUTES) || process.exitValue() != 0) {
      process.destroyForcibly();
      throw new IllegalStateException("git " + String.join(" ", arguments) + " failed: " + output);
    }
  }

  /** An unrelated repository with a commit, used to prove the goal ignores Maven's own cwd. */
  static void unrelatedRepository(Path directory) throws Exception {
    try (Git git = Git.init().setDirectory(directory.toFile()).setInitialBranch("main").call()) {
      Files.writeString(directory.resolve("notes.txt"), "unrelated\n");
      git.add().addFilepattern(".").call();
      git.commit().setMessage("unrelated").setAuthor("test", "test@example.com").setSign(false).call();
    }
  }

  /** Runs the staged goal in the given directory, relative to the repository root. */
  MavenResult runStaged(String moduleDirectory) throws Exception {
    return runStaged(root.resolve(moduleDirectory), List.of());
  }

  /** Runs the staged goal with extra command line arguments. */
  MavenResult runStaged(String moduleDirectory, List<String> extraArguments) throws Exception {
    return runStaged(root.resolve(moduleDirectory), extraArguments);
  }

  /** Runs the goal with Maven launched from somewhere else entirely, pointed here with -f. */
  MavenResult runStagedFrom(Path workingDirectory, String moduleDirectory) throws Exception {
    return runStaged(
        workingDirectory,
        List.of("-f", root.resolve(moduleDirectory).resolve("pom.xml").toString()));
  }

  private MavenResult runStaged(Path workingDirectory, List<String> extraArguments)
      throws Exception {
    if (MAVEN_HOME == null) {
      throw new IllegalStateException("maven.home is not set; failsafe must pass it through");
    }
    if (PLUGIN_VERSION == null) {
      throw new IllegalStateException("speedy.version is not set; failsafe must pass it through");
    }
    String mvn = Path.of(MAVEN_HOME, "bin", isWindows() ? "mvn.cmd" : "mvn").toString();
    List<String> command = new java.util.ArrayList<>(List.of(mvn, "-B"));
    command.addAll(extraArguments);
    command.add("me.effegi:speedy-spotless-maven-plugin:" + PLUGIN_VERSION + ":staged");

    Process process =
        new ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), UTF_8);
    if (!process.waitFor(MAVEN_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
      process.destroyForcibly();
      throw new IllegalStateException("Maven did not finish within " + MAVEN_TIMEOUT_MINUTES + "m");
    }
    return new MavenResult(process.exitValue(), output);
  }

  @Override
  public void close() {
    git.close();
  }

  private static boolean isWindows() {
    return System.getProperty("os.name").toLowerCase().contains("win");
  }

  private static String pom(
      String artifactId, String module, boolean packagingPom, Caching caching) {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>it.speedy</groupId>
          <artifactId>%s</artifactId>
          <version>1.0.0</version>
          %s
          %s
          <build>
            %s
            <plugins>
              <plugin>
                <groupId>me.effegi</groupId>
                <artifactId>speedy-spotless-maven-plugin</artifactId>
                <version>%s</version>
                <configuration>
                  %s
                  <java>
                    <trimTrailingWhitespace/>
                    <endWithNewline/>
                  </java>
                </configuration>
              </plugin>
            </plugins>
          </build>
        </project>
        """
        .formatted(
            artifactId,
            packagingPom ? "<packaging>pom</packaging>" : "",
            module == null ? "" : "<modules><module>" + module + "</module></modules>",
            caching == Caching.ENABLED_WITH_SPOTLESS_DECLARED ? spotlessInPluginManagement() : "",
            PLUGIN_VERSION,
            caching == Caching.DISABLED
                ? "<upToDateChecking><enabled>false</enabled></upToDateChecking>"
                : "");
  }

  /**
   * Spotless fingerprints the build by looking up its own coordinates in the project. Declaring it
   * here satisfies that lookup without ever running it.
   */
  private static String spotlessInPluginManagement() {
    return """
        <pluginManagement>
              <plugins>
                <plugin>
                  <groupId>com.diffplug.spotless</groupId>
                  <artifactId>spotless-maven-plugin</artifactId>
                  <version>%s</version>
                </plugin>
              </plugins>
            </pluginManagement>"""
        .formatted(SPOTLESS_VERSION);
  }

  private static String childPom() {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>it.speedy</groupId>
            <artifactId>parent</artifactId>
            <version>1.0.0</version>
          </parent>
          <artifactId>child</artifactId>
        </project>
        """;
  }

  /** Outcome of a Maven invocation: its exit code and combined stdout/stderr. */
  record MavenResult(int exitCode, String output) {

    boolean succeeded() {
      return exitCode == 0;
    }
  }
}
