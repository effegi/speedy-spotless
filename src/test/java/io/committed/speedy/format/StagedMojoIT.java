package io.committed.speedy.format;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.committed.speedy.format.TestProject.MavenResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the {@code staged} goal, driving a real Maven build against a real git
 * repository. These replace the former test.sh, which covered only the multi-module happy path.
 */
class StagedMojoIT {

  private static final String A = "src/main/java/demo/A.java";
  private static final String B = "src/main/java/demo/B.java";

  @Test
  @DisplayName("a partially staged file fails the build and leaves the index untouched")
  void partiallyStagedFileFailsTheBuild(@TempDir Path dir) throws Exception {
    try (TestProject project = TestProject.singleModule(dir)) {
      project.write(A, tidy("A"));
      project.stageAll();
      project.commit("initial");

      project.write(A, messy("A"));
      project.stageAll();
      String stagedBefore = project.indexContent(A);

      project.write(A, messyWithExtraField("A"));

      MavenResult result = project.runStaged(".");

      assertFalse(result.succeeded(), result.output());
      assertTrue(
          result.output().contains("Partially staged files were formatted but not re-staged"),
          result.output());
      assertTrue(result.output().contains(A), result.output());

      // The point of the goal: a partially staged file must never produce a half-formatted commit.
      assertEquals(stagedBefore, project.indexContent(A));

      // Characterisation of current behaviour, not an endorsement of it: the whole working tree
      // file is rewritten, so the unstaged hunk the user deliberately held back is reformatted too.
      assertEquals(tidyWithExtraField("A"), project.read(A));
    }
  }

  @Test
  @DisplayName("several partially staged files are all named in the failure")
  void allPartiallyStagedFilesAreReported(@TempDir Path dir) throws Exception {
    try (TestProject project = TestProject.singleModule(dir)) {
      project.write(A, tidy("A"));
      project.write(B, tidy("B"));
      project.stageAll();
      project.commit("initial");

      project.write(A, messy("A"));
      project.write(B, messy("B"));
      project.stageAll();

      project.write(A, messyWithExtraField("A"));
      project.write(B, messyWithExtraField("B"));

      MavenResult result = project.runStaged(".");

      assertFalse(result.succeeded(), result.output());
      assertTrue(result.output().contains(A), result.output());
      assertTrue(result.output().contains(B), result.output());
      // Each file on its own line. A literal "\\n" separator once collapsed the list into one
      // unreadable line, and in a pre-commit hook this message is the user's only feedback.
      assertFalse(
          result.output().lines().anyMatch(line -> line.contains(A) && line.contains(B)),
          result.output());
      assertTrue(
          result.output().contains("Re-stage them with 'git add' and commit again."),
          result.output());
    }
  }

  @Test
  @DisplayName("a fully staged file is formatted and re-staged")
  void fullyStagedFileIsFormattedAndReStaged(@TempDir Path dir) throws Exception {
    try (TestProject project = TestProject.singleModule(dir)) {
      project.write(A, tidy("A"));
      project.stageAll();
      project.commit("initial");

      project.write(A, messy("A"));
      project.stageAll();

      MavenResult result = project.runStaged(".");

      assertTrue(result.succeeded(), result.output());
      assertEquals(tidy("A"), project.read(A));
      assertEquals(tidy("A"), project.indexContent(A));
      // Nothing left over: what is committed is exactly what was formatted.
      assertTrue(project.status().getModified().isEmpty(), "unexpected unstaged changes");
    }
  }

  @Test
  @DisplayName("a newly added staged file is formatted and re-staged")
  void newlyAddedFileIsFormattedAndReStaged(@TempDir Path dir) throws Exception {
    try (TestProject project = TestProject.singleModule(dir)) {
      project.write(A, tidy("A"));
      project.stageAll();
      project.commit("initial");

      project.write(B, messy("B"));
      project.stageAll();

      MavenResult result = project.runStaged(".");

      assertTrue(result.succeeded(), result.output());
      assertEquals(tidy("B"), project.read(B));
      assertEquals(tidy("B"), project.indexContent(B));
    }
  }

  @Test
  @DisplayName("unstaged changes are left alone")
  void unstagedChangesAreLeftAlone(@TempDir Path dir) throws Exception {
    try (TestProject project = TestProject.singleModule(dir)) {
      project.write(A, tidy("A"));
      project.stageAll();
      project.commit("initial");

      project.write(A, messy("A"));

      MavenResult result = project.runStaged(".");

      assertTrue(result.succeeded(), result.output());
      // Nothing was staged, so nothing should have been touched.
      assertEquals(messy("A"), project.read(A));
      assertEquals(tidy("A"), project.indexContent(A));
    }
  }

  @Test
  @DisplayName("a clean index means nothing is formatted, even if committed files are messy")
  void nothingStagedMeansNothingFormatted(@TempDir Path dir) throws Exception {
    try (TestProject project = TestProject.singleModule(dir)) {
      project.write(A, messy("A"));
      project.stageAll();
      project.commit("initial");

      MavenResult result = project.runStaged(".");

      assertTrue(result.succeeded(), result.output());
      assertEquals(messy("A"), project.read(A));
    }
  }

  @Test
  @DisplayName("a staged deletion alongside a staged edit does not break the build")
  void stagedDeletionIsIgnored(@TempDir Path dir) throws Exception {
    try (TestProject project = TestProject.singleModule(dir)) {
      project.write(A, tidy("A"));
      project.write(B, tidy("B"));
      project.stageAll();
      project.commit("initial");

      project.delete(B);
      project.write(A, messy("A"));
      project.stageAll();

      MavenResult result = project.runStaged(".");

      assertTrue(result.succeeded(), result.output());
      assertEquals(tidy("A"), project.indexContent(A));
      assertNull(project.indexContent(B), "deletion should still be staged");
      assertFalse(project.exists(B), "deleted file should not be resurrected");
    }
  }

  @Test
  @DisplayName("the goal works when run from a module below the repository root")
  void runsFromChildModule(@TempDir Path dir) throws Exception {
    try (TestProject project = TestProject.multiModule(dir)) {
      String childFile = "child/" + A;
      project.write(childFile, tidy("A"));
      project.stageAll();
      project.commit("initial");

      project.write(childFile, messy("A"));
      project.stageAll();

      MavenResult result = project.runStaged("child");

      assertTrue(result.succeeded(), result.output());
      assertEquals(tidy("A"), project.read(childFile));
      assertEquals(tidy("A"), project.indexContent(childFile));
    }
  }

  @Test
  @DisplayName("the goal follows the project, not the directory Maven was launched from")
  void runsWhenMavenIsLaunchedFromAnotherRepository(@TempDir Path dir, @TempDir Path elsewhere)
      throws Exception {
    try (TestProject project = TestProject.singleModule(dir)) {
      project.write(A, tidy("A"));
      project.stageAll();
      project.commit("initial");

      project.write(A, messy("A"));
      project.stageAll();

      // Launching from inside a different repository used to make the goal inspect that one,
      // find nothing to do, and report success without formatting anything.
      TestProject.unrelatedRepository(elsewhere);

      MavenResult result = project.runStagedFrom(elsewhere, ".");

      assertTrue(result.succeeded(), result.output());
      assertEquals(tidy("A"), project.read(A), result.output());
      assertEquals(tidy("A"), project.indexContent(A), result.output());
    }
  }

  @Test
  @DisplayName("the goal works inside a linked worktree")
  void runsInLinkedWorktree(@TempDir Path dir, @TempDir Path elsewhere) throws Exception {
    try (TestProject main = TestProject.singleModule(dir)) {
      main.write(A, tidy("A"));
      main.stageAll();
      main.commit("initial");

      // In a linked worktree the git dir is <main>/.git/worktrees/<name>, so deriving the
      // working tree from its parent lands outside the checkout entirely.
      try (TestProject linked = main.linkedWorktree(elsewhere.resolve("wt"))) {
        linked.write(A, messy("A"));
        linked.stageAll();

        MavenResult result = linked.runStaged(".");

        assertTrue(result.succeeded(), result.output());
        assertEquals(tidy("A"), linked.read(A));
        assertEquals(tidy("A"), linked.indexContent(A));
      }
    }
  }

  @Test
  @DisplayName("a repository without commits reports an actionable error")
  void repositoryWithoutCommits(@TempDir Path dir) throws Exception {
    try (TestProject project = TestProject.singleModule(dir)) {
      project.write(A, messy("A"));
      project.stageAll();

      MavenResult result = project.runStaged(".");

      assertFalse(result.succeeded(), result.output());
      assertTrue(result.output().contains("first commit"), result.output());
      // The recovery command has to be one Maven can actually parse: a dot between groupId and
      // artifactId makes Maven read the whole thing as a plugin prefix and fail.
      assertTrue(
          result.output().contains("mvn me.effegi:speedy-spotless-maven-plugin:apply"),
          result.output());
    }
  }

  @Test
  @DisplayName("an unborn branch in a repository with history still reports the first-commit error")
  void unbornBranchInRepositoryWithHistory(@TempDir Path dir) throws Exception {
    try (TestProject project = TestProject.singleModule(dir)) {
      project.write(A, tidy("A"));
      project.stageAll();
      project.commit("initial");

      // HEAD is now unborn, but .git/logs/HEAD still holds the entries from before the switch,
      // so a reflog-based "does this repo have commits" check answers yes.
      project.checkoutOrphan("fresh");
      project.write(A, messy("A"));
      project.stageAll();

      MavenResult result = project.runStaged(".");

      assertFalse(result.succeeded(), result.output());
      assertTrue(result.output().contains("first commit"), result.output());
    }
  }

  @Test
  @DisplayName("spotlessFiles narrows which staged files are formatted")
  void spotlessFilesIsHonoured(@TempDir Path dir) throws Exception {
    try (TestProject project = TestProject.singleModule(dir)) {
      project.write(A, tidy("A"));
      project.write(B, tidy("B"));
      project.stageAll();
      project.commit("initial");

      project.write(A, messy("A"));
      project.write(B, messy("B"));
      project.stageAll();

      // Spotless applies filePatterns while collecting files, before this plugin sees them.
      MavenResult result = project.runStaged(".", List.of("-DspotlessFiles=.*A\\.java"));

      assertTrue(result.succeeded(), result.output());
      assertEquals(tidy("A"), project.indexContent(A));
      assertEquals(messy("B"), project.indexContent(B));
    }
  }

  @Test
  @DisplayName("up-to-date checking fails unless spotless itself is declared in the project")
  void upToDateCheckingRequiresSpotlessToBeDeclared(@TempDir Path dir) throws Exception {
    try (TestProject project = TestProject.singleModule(dir, TestProject.Caching.ENABLED)) {
      project.write(A, tidy("A"));
      project.stageAll();
      project.commit("initial");

      project.write(A, messy("A"));
      project.stageAll();

      MavenResult result = project.runStaged(".");

      // Spotless fingerprints the build by looking up com.diffplug.spotless:spotless-maven-plugin,
      // which this plugin does not declare. The failure names a plugin the user never referenced,
      // and it happens inside a final execute(), so there is no seam to improve the message.
      assertFalse(result.succeeded(), result.output());
      assertTrue(
          result.output().contains("Spotless plugin absent from the project"), result.output());
    }
  }

  @Test
  @DisplayName("declaring spotless in pluginManagement lets up-to-date checking stay enabled")
  void upToDateCheckingWorksWithSpotlessInPluginManagement(@TempDir Path dir) throws Exception {
    try (TestProject project =
        TestProject.singleModule(dir, TestProject.Caching.ENABLED_WITH_SPOTLESS_DECLARED)) {
      project.write(A, tidy("A"));
      project.stageAll();
      project.commit("initial");

      project.write(A, messy("A"));
      project.stageAll();

      MavenResult result = project.runStaged(".");

      assertTrue(result.succeeded(), result.output());
      assertEquals(tidy("A"), project.read(A));
      assertEquals(tidy("A"), project.indexContent(A));
    }
  }

  /** Source whose field line carries trailing whitespace, which the configured steps strip. */
  private static String messy(String className) {
    return "package demo;\n\nclass " + className + " {\n  int x;   \n}\n";
  }

  private static String tidy(String className) {
    return "package demo;\n\nclass " + className + " {\n  int x;\n}\n";
  }

  private static String messyWithExtraField(String className) {
    return "package demo;\n\nclass " + className + " {\n  int x;   \n  int y;   \n}\n";
  }

  private static String tidyWithExtraField(String className) {
    return "package demo;\n\nclass " + className + " {\n  int x;\n  int y;\n}\n";
  }
}
