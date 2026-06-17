package com.codelens.git;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Walks up the directory tree from a given start path until it finds a
 * directory that contains a {@code .git} sub-directory, indicating the root
 * of a Git repository.
 *
 * Returns {@link Optional#empty()} when the path is not inside any Git repo;
 * callers should treat that as "git features silently unavailable".
 */
public class GitRepoLocator {

    private static final Logger log = LoggerFactory.getLogger(GitRepoLocator.class);

    private GitRepoLocator() { /* utility class */ }

    /**
     * Attempt to locate the Git repository root for {@code startPath}.
     *
     * @param startPath the source directory that was scanned (absolute)
     * @return the repository root directory, or empty if not in a git repo
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
        log.info("No git repository found for path: {} — git metadata will be skipped", startPath);
        return Optional.empty();
    }
}
