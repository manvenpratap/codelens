package com.codelens.git;

import com.codelens.core.model.*;
import org.eclipse.jgit.api.BlameCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Paths;
import java.util.*;

/**
 * Reads Git blame and log data for every source file produced by a scan
 * and produces a {@link GitMeta} record for each indexed entity (type,
 * method, field).
 *
 * <p>Algorithm (per entity):</p>
 * <ol>
 *   <li>Determine which source file the entity lives in and which lines it spans.</li>
 *   <li>Run {@code git blame} on that file — JGit produces a {@link BlameResult}
 *       mapping each line to the {@link RevCommit} that last touched it.</li>
 *   <li>Pick the most-recent commit from the entity's line range as the
 *       "last change" author / hash / message.</li>
 *   <li>Count the total number of commits in the file's history for the
 *       churn/heat metric.</li>
 * </ol>
 *
 * <p>All exceptions are caught and logged at WARN level; callers receive an
 * empty list rather than a failure so the rest of the scan still completes.</p>
 */
public class GitBlameService {

    private static final Logger log = LoggerFactory.getLogger(GitBlameService.class);

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Produce {@link GitMeta} annotations for all entities in {@code result}.
     *
     * @param result   the scan result to annotate
     * @param repoRoot the Git repository root directory
     * @return list of {@link GitMeta} objects (one per entity), never null
     */
    public List<GitMeta> annotate(ScanResult result, File repoRoot) {
        List<GitMeta> allMeta = new ArrayList<>();
        try (Repository repo = openRepo(repoRoot);
             Git git         = new Git(repo)) {

            // ── Step 1: Build file-level blame cache ─────────────────────────
            // sourceFile → BlameResult (lazy, built on demand)
            Map<String, BlameResult> blameCache  = new HashMap<>();
            // sourceFile → commit count
            Map<String, Integer>     countCache  = new HashMap<>();

            // ── Step 2: Annotate types ────────────────────────────────────────
            for (CodeType t : result.types) {
                if (t.getSourceFile() == null) continue;
                BlameResult blame  = getBlame(git, repo, repoRoot, t.getSourceFile(), blameCache);
                int         count  = getCommitCount(git, repoRoot, t.getSourceFile(), countCache);
                GitMeta     meta   = buildMeta(t.getFqn(), t.getStartLine(), t.getEndLine(),
                                               blame, count);
                if (meta != null) allMeta.add(meta);
            }

            // ── Step 3: Annotate methods ──────────────────────────────────────
            for (CodeMethod m : result.methods) {
                String file = resolveFile(repoRoot, m.getDeclaringTypeFqn(), result.types);
                if (file == null) continue;
                BlameResult blame = getBlame(git, repo, repoRoot, file, blameCache);
                int         count = getCommitCount(git, repoRoot, file, countCache);
                GitMeta     meta  = buildMeta(m.getFqn(), m.getStartLine(), m.getEndLine(),
                                              blame, count);
                if (meta != null) allMeta.add(meta);
            }

            // ── Step 4: Annotate fields ───────────────────────────────────────
            for (CodeField f : result.fields) {
                String file = resolveFile(repoRoot, f.getDeclaringTypeFqn(), result.types);
                if (file == null) continue;
                BlameResult blame = getBlame(git, repo, repoRoot, file, blameCache);
                int         count = getCommitCount(git, repoRoot, file, countCache);
                GitMeta     meta  = buildMeta(f.getFqn(), f.getStartLine(), f.getStartLine(),
                                              blame, count);
                if (meta != null) allMeta.add(meta);
            }

            log.info("Git annotation complete: {} entities annotated", allMeta.size());
        } catch (Exception e) {
            log.warn("Git annotation failed (non-fatal): {}", e.getMessage());
        }
        return allMeta;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Repository openRepo(File repoRoot) throws Exception {
        return new FileRepositoryBuilder()
            .setGitDir(new File(repoRoot, ".git"))
            .readEnvironment()
            .build();
    }

    /**
     * Returns a cached {@link BlameResult} for the given absolute source file.
     * The path is made relative to the repository root before calling JGit.
     */
    private BlameResult getBlame(Git git, Repository repo, File repoRoot,
                                 String absoluteFile,
                                 Map<String, BlameResult> cache) {
        return cache.computeIfAbsent(absoluteFile, f -> {
            try {
                String relPath = repoRoot.toPath()
                                         .relativize(Paths.get(f).toAbsolutePath())
                                         .toString()
                                         .replace(File.separatorChar, '/');
                BlameCommand blameCmd = git.blame()
                    .setFilePath(relPath)
                    .setFollowFileRenames(true);
                BlameResult result = blameCmd.call();
                if (result != null) result.computeAll();
                return result;
            } catch (Exception e) {
                log.debug("Blame failed for {}: {}", f, e.getMessage());
                return null;
            }
        });
    }

    /**
     * Returns the total number of commits that have ever touched {@code file},
     * used as the "change frequency / heat" metric.
     */
    private int getCommitCount(Git git, File repoRoot, String absoluteFile,
                               Map<String, Integer> cache) {
        return cache.computeIfAbsent(absoluteFile, f -> {
            try {
                String relPath = repoRoot.toPath()
                                         .relativize(Paths.get(f).toAbsolutePath())
                                         .toString()
                                         .replace(File.separatorChar, '/');
                Iterable<RevCommit> commits = git.log()
                    .addPath(relPath)
                    .call();
                int count = 0;
                for (RevCommit c : commits) {
                    if (c != null) {
                        count++;
                    }
                }
                return count;
            } catch (Exception e) {
                log.debug("Log count failed for {}: {}", f, e.getMessage());
                return 0;
            }
        });
    }

    /**
     * Builds a {@link GitMeta} from the blame data for the given line range.
     * Picks the most-recent commit touching any line in [startLine, endLine].
     *
     * @param startLine 1-based start line
     * @param endLine   1-based end line (inclusive)
     */
    private GitMeta buildMeta(String entityFqn,
                              int startLine, int endLine,
                              BlameResult blame, int commitCount) {
        if (blame == null) return null;

        RevCommit newest  = null;
        int       start0  = Math.max(0, startLine - 1);          // convert to 0-based
        int       end0    = Math.max(start0, endLine - 1);
        int       lineMax = blame.getResultContents().size() - 1;

        for (int i = start0; i <= Math.min(end0, lineMax); i++) {
            RevCommit c = blame.getSourceCommit(i);
            if (c == null) continue;
            if (newest == null || c.getCommitTime() > newest.getCommitTime()) {
                newest = c;
            }
        }
        if (newest == null) return null;

        PersonIdent author = newest.getAuthorIdent();
        GitMeta meta = new GitMeta();
        meta.setEntityFqn(entityFqn);
        meta.setLastAuthorName(author.getName());
        meta.setLastAuthorEmail(author.getEmailAddress());
        meta.setLastCommitTime(author.getWhenAsInstant().getEpochSecond());
        meta.setLastCommitHash(newest.abbreviate(7).name());
        String fullMsg = newest.getFullMessage();
        meta.setLastCommitMsg(fullMsg != null
            ? fullMsg.trim().split("\\r?\\n", 2)[0]   // first line only
            : "");
        meta.setCommitCount(commitCount);
        return meta;
    }

    /**
     * Resolves the absolute source file path for an entity by looking up its
     * declaring type in the scan result's type list.
     */
    private String resolveFile(File repoRoot,
                               String declaringTypeFqn,
                               List<CodeType> types) {
        for (CodeType t : types) {
            if (declaringTypeFqn.equals(t.getFqn())) {
                return t.getSourceFile();
            }
        }
        return null;
    }

    // ── Inner helper: scan result bundle ─────────────────────────────────────

    /**
     * Lightweight DTO that bundles scan result collections.
     * Mirrors {@link com.codelens.parser.JavaSourceScanner.ScanResult}.
     */
    public static class ScanResult {
        public final List<CodeType>   types;
        public final List<CodeMethod> methods;
        public final List<CodeField>  fields;

        public ScanResult(List<CodeType> types,
                          List<CodeMethod> methods,
                          List<CodeField> fields) {
            this.types   = types;
            this.methods = methods;
            this.fields  = fields;
        }
    }
}
