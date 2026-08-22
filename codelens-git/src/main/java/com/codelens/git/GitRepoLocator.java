package com.codelens.git;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Validates and locates Git repositories.
 */
public class GitRepoLocator {

    private static final Logger log = LoggerFactory.getLogger(GitRepoLocator.class);

    private GitRepoLocator() { /* utility class */ }

    /**
     * DTO containing validation details for a specified repository path.
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String  repoPath;
        private final String  branch;
        private final String  headCommit;
        private final String  error;

        private ValidationResult(boolean valid, String repoPath, String branch, String headCommit, String error) {
            this.valid      = valid;
            this.repoPath   = repoPath;
            this.branch     = branch;
            this.headCommit = headCommit;
            this.error      = error;
        }

        public static ValidationResult valid(String repoPath, String branch, String headCommit) {
            return new ValidationResult(true, repoPath, branch, headCommit, null);
        }

        public static ValidationResult invalid(String error) {
            return new ValidationResult(false, null, null, null, error);
        }

        public boolean isValid()        { return valid; }
        public String getRepoPath()     { return repoPath; }
        public String getBranch()       { return branch; }
        public String getHeadCommit()   { return headCommit; }
        public String getError()        { return error; }
    }

    /**
     * Explicitly validate whether a given path is a valid Git repository root.
     *
     * @param rawPath absolute or relative directory path entered by the user
     * @return ValidationResult with branch and head commit info, or an error reason
     */
    public static ValidationResult validate(String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            return ValidationResult.invalid("Git repository path cannot be empty.");
        }

        try {
            File dir = new File(rawPath.trim()).getCanonicalFile();
            if (!dir.exists()) {
                return ValidationResult.invalid("Directory does not exist: " + rawPath);
            }
            if (!dir.isDirectory()) {
                return ValidationResult.invalid("Specified path is not a directory: " + rawPath);
            }

            File gitDir = new File(dir, ".git");
            if (!gitDir.exists()) {
                return ValidationResult.invalid("Not a Git repository root: missing .git directory in " + dir.getPath());
            }

            try (Repository repo = new FileRepositoryBuilder()
                    .setGitDir(gitDir)
                    .readEnvironment()
                    .build()) {

                if (!repo.getObjectDatabase().exists()) {
                    return ValidationResult.invalid("Corrupted Git repository: object database missing.");
                }

                String branch = repo.getBranch();
                String headInfo = "";
                ObjectId headId = repo.resolve(Constants.HEAD);
                if (headId != null) {
                    try (RevWalk rw = new RevWalk(repo)) {
                        RevCommit commit = rw.parseCommit(headId);
                        headInfo = commit.abbreviate(7).name() + " (" + commit.getShortMessage() + ")";
                    }
                }

                return ValidationResult.valid(dir.getAbsolutePath(), branch != null ? branch : "HEAD", headInfo);
            }
        } catch (Exception e) {
            return ValidationResult.invalid("Failed to validate Git repository: " + e.getMessage());
        }
    }

    /**
     * Helper to suggest the nearest Git repository root starting from a path.
     */
    public static Optional<File> locate(String startPath) {
        try {
            Path current = Paths.get(startPath).toAbsolutePath().normalize();
            while (current != null) {
                File gitDir = current.resolve(".git").toFile();
                if (gitDir.exists() && gitDir.isDirectory()) {
                    log.debug("Found git repo root: {}", current);
                    return Optional.of(current.toFile());
                }
                current = current.getParent();
            }
        } catch (Exception e) {
            log.warn("Error while locating git repository: {}", e.getMessage());
        }
        return Optional.empty();
    }
}
