package io.committed.speedy.format;

import com.diffplug.spotless.Formatter;
import com.diffplug.spotless.maven.SpotlessApplyMojo;
import com.diffplug.spotless.maven.incremental.UpToDateChecker;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

@Mojo(name = "staged", threadSafe = true)
public class StagedMojo extends SpotlessApplyMojo {

  private static final List<ChangeType> CHANGE_TYPES =
      asList(ChangeType.ADD, ChangeType.COPY, ChangeType.MODIFY, ChangeType.RENAME);

  @Override
  protected void process(String name, Iterable<File> files, Formatter formatter, UpToDateChecker upToDateChecker)
      throws MojoExecutionException {
    if (!files.iterator().hasNext()) {
      return;
    }

    try (Git git = openGitRepo()) {

      Repository repository = git.getRepository();
      // Canonical on both sides: jgit resolves symlinks in the work tree path while Maven keeps
      // the path it was given, and on macOS /tmp and /var are symlinks. Relativizing the two
      // forms against each other yields ../.. paths that match nothing, so the goal would
      // quietly format nothing at all.
      Path workTreePath = canonical(repository.getWorkTree().toPath());

      TreeFilter treeFilter =
          PathFilterGroup.createFromStrings(
              StreamSupport.stream(files.spliterator(), false)
                  .map(f -> canonical(f.toPath()))
                  .map(workTreePath::relativize)
                  .map(f -> f.toString().replace('\\', '/'))
                  .collect(Collectors.toList()));

      List<String> stagedChangedFiles = getChangedFiles(git, true, treeFilter);
      if (stagedChangedFiles.isEmpty()) {
        getLog().debug("No files were changed for this formatter");
        return;
      }
      List<String> unstagedChangedFiles = getChangedFiles(git, false, treeFilter);

      Set<String> partiallyStagedFiles =
          getPartiallyStagedFiles(stagedChangedFiles, unstagedChangedFiles);

      List<String> fullyStagedFiles =
          stagedChangedFiles.stream()
              .distinct()
              .filter(f -> !unstagedChangedFiles.contains(f))
              .collect(Collectors.toList());

      List<File> stagedFiles =
          stagedChangedFiles.stream()
              // Resolve against the working tree, not the git directory's parent: for linked
              // worktrees and submodules the git directory lives outside the checkout.
              .map(filePath -> workTreePath.resolve(filePath).toFile())
              .collect(toList());
      super.process(name, stagedFiles, formatter, upToDateChecker);
      getLog().info("Formatted " + stagedFiles.size() + " staged files");

      for (String f : fullyStagedFiles) {
        stage(git, f);
      }
      if (!partiallyStagedFiles.isEmpty()) {
        throwPartialUnstaged(partiallyStagedFiles);
      }
    } catch (IOException e) {
      throw new MojoExecutionException("Could not open Git repository", e);
    }
  }

  /** Resolves symlinks so work tree and file paths can be compared. */
  private static Path canonical(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException e) {
      // The file came from the formatter's own scan so it should exist; degrade rather than fail.
      return path.toAbsolutePath().normalize();
    }
  }

  private Git openGitRepo() throws IOException {
    // The module's own directory, not the directory Maven was launched from: with -f the two
    // can sit in different repositories, and searching from the wrong one silently finds
    // nothing to format.
    File projectDir = baseDir.getAbsoluteFile();
    try {
      return Git.open(projectDir);
    } catch (IOException e) {
      FileRepositoryBuilder repositoryBuilder = new FileRepositoryBuilder();
      repositoryBuilder.findGitDir(projectDir);
      File gitDir = repositoryBuilder.getGitDir();
      if (gitDir != null) {
        return Git.open(gitDir);
      } else {
        throw new IOException(
            "Could not find git directory scanning upwards from " + projectDir.getPath());
      }
    }
  }

  private void throwPartialUnstaged(Set<String> partiallyStagedFiles)
      throws MojoExecutionException {
    throw new MojoExecutionException(
        format(
            "Partially staged files were formatted but not re-staged:%n%s%n"
                + "Re-stage them with 'git add' and commit again.",
            // Sorted so the message is stable between runs: the set is a HashSet.
            partiallyStagedFiles.stream()
                .sorted()
                .map(f -> "  " + f)
                .collect(joining(System.lineSeparator()))));
  }

  private void stage(Git git, String f) throws MojoExecutionException {
    try {
      git.add().addFilepattern(f).call();
    } catch (GitAPIException e) {
      throw new MojoExecutionException("Failed to stage", e);
    }
  }

  private Set<String> getPartiallyStagedFiles(
      List<String> stagedChangedFiles, List<String> unstagedChangedFiles) {
    return stagedChangedFiles.stream()
        .distinct()
        .filter(unstagedChangedFiles::contains)
        .collect(Collectors.toSet());
  }

  private List<String> getChangedFiles(Git git, boolean staged, TreeFilter pathFilter)
      throws MojoExecutionException {
    try {
      // do we need to include untracked files also? e.g. ls-files --others --exclude-standard
      return git
          .diff()
          .setPathFilter(pathFilter)
          .setShowNameAndStatusOnly(true)
          .setCached(staged)
          .call()
          .stream()
          .filter(e -> CHANGE_TYPES.contains(e.getChangeType()))
          .map(DiffEntry::getNewPath)
          .collect(toList());
    } catch (GitAPIException e) {
      if (!hasCommits(git.getRepository())) {
        throw new MojoExecutionException(
            "Looks like you're executing this on a first commit."
                + " Please run 'mvn me.effegi:speedy-spotless-maven-plugin:apply'"
                + " and then commit with the -n option if you're invoking this from a"
                + " pre-commit hook.");
      }
      throw new MojoExecutionException("Failed to list changed files", e);
    }
  }

  private static boolean hasCommits(Repository repository) {
    try {
      // Ask whether HEAD resolves, rather than whether a reflog exists: the reflog can be empty
      // in a repository full of commits, and non-empty on an unborn branch after --orphan.
      return repository.resolve(Constants.HEAD) != null;
    } catch (IOException e) {
      return false;
    }
  }
}
