package com.codelens.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.NullOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Pure-Java Git diff extractor using JGit.
 * Computes modified files and exact 1-indexed line intervals across commits or working tree.
 */
public class GitDiffService {

    private static final Logger log = LoggerFactory.getLogger(GitDiffService.class);

    public static class FileDiffRange {
        public String relativePath;
        public String changeType; // MODIFY, ADD, DELETE, RENAME
        public List<int[]> lineRanges = new ArrayList<>(); // [startLine, endLine] 1-indexed

        public FileDiffRange(String relativePath, String changeType) {
            this.relativePath = relativePath;
            this.changeType = changeType;
        }

        public boolean intersects(int startLine, int endLine) {
            for (int[] r : lineRanges) {
                if (r[0] <= endLine && r[1] >= startLine) {
                    return true;
                }
            }
            return false;
        }
    }

    public static class GitDiffReport {
        public String repoPath;
        public String baseRef;
        public String headRef;
        public List<FileDiffRange> changedFiles = new ArrayList<>();
        public int totalFilesChanged;
        public int totalLinesAdded;
        public int totalLinesDeleted;
    }

    /**
     * Compute Git diff between baseRef and headRef for the given repository.
     * If baseRef is null or blank, defaults to "HEAD~1" (or working tree if uncommitted changes exist).
     */
    public GitDiffReport computeDiff(File repoRoot, String baseRef, String headRef) throws IOException {
        Optional<File> located = GitRepoLocator.locate(repoRoot.getAbsolutePath());
        File effectiveRoot = located.orElse(repoRoot);
        File gitDir = new File(effectiveRoot, ".git");
        if (!gitDir.exists()) {
            GitDiffReport empty = new GitDiffReport();
            empty.repoPath = effectiveRoot.getAbsolutePath();
            empty.baseRef = baseRef != null ? baseRef : "WORKING";
            empty.headRef = headRef != null ? headRef : "HEAD";
            return empty;
        }

        try (Repository repo = new FileRepositoryBuilder().setGitDir(gitDir).readEnvironment().build()) {
            GitDiffReport report = new GitDiffReport();
            report.repoPath = effectiveRoot.getAbsolutePath();

            try (DiffFormatter df = new DiffFormatter(NullOutputStream.INSTANCE)) {
                df.setRepository(repo);
                df.setDiffComparator(RawTextComparator.DEFAULT);
                df.setDetectRenames(true);

                List<DiffEntry> entries = null;

                String effBase = (baseRef != null && !baseRef.isBlank()) ? baseRef : "HEAD~1";
                String effHead = (headRef != null && !headRef.isBlank()) ? headRef : "HEAD";

                // Case 1: Inspect explicit or default commit/branch diff
                if (!"WORKING".equalsIgnoreCase(effBase) && !"WORKING_TREE".equalsIgnoreCase(effBase)
                        && !"WORKING".equalsIgnoreCase(effHead) && !"WORKING_TREE".equalsIgnoreCase(effHead)) {
                    ObjectId baseId = repo.resolve(effBase);
                    ObjectId headId = repo.resolve(effHead);
                    if (baseId != null && headId != null) {
                        try (RevWalk rw = new RevWalk(repo)) {
                            RevCommit baseCommit = rw.parseCommit(baseId);
                            RevCommit headCommit = rw.parseCommit(headId);
                            CanonicalTreeParser oldTree = new CanonicalTreeParser();
                            oldTree.reset(rw.getObjectReader(), baseCommit.getTree());
                            CanonicalTreeParser newTree = new CanonicalTreeParser();
                            newTree.reset(rw.getObjectReader(), headCommit.getTree());
                            entries = df.scan(oldTree, newTree);
                            report.baseRef = baseCommit.abbreviate(7).name();
                            report.headRef = headCommit.abbreviate(7).name();
                        }
                    }
                }

                // Case 2: Inspect uncommitted working copy changes
                if (entries == null || entries.isEmpty()) {
                    try {
                        List<DiffEntry> workingDiffs = new Git(repo).diff().call();
                        if (workingDiffs != null && !workingDiffs.isEmpty()) {
                            entries = workingDiffs;
                            report.baseRef = "HEAD";
                            report.headRef = "WORKING_TREE";
                        }
                    } catch (Exception ignored) {}
                }

                if (entries == null) {
                    entries = Collections.emptyList();
                }

                for (DiffEntry entry : entries) {
                    String path = entry.getNewPath();
                    if (DiffEntry.DEV_NULL.equals(path)) {
                        path = entry.getOldPath();
                    }
                    if (path == null) continue;

                    FileDiffRange fileDiff = new FileDiffRange(path, entry.getChangeType().name());
                    try {
                        FileHeader fh = df.toFileHeader(entry);
                        for (Edit edit : fh.toEditList()) {
                            int begin = edit.getBeginB() + 1;
                            int end = Math.max(begin, edit.getEndB());
                            fileDiff.lineRanges.add(new int[]{begin, end});
                            report.totalLinesAdded += (edit.getEndB() - edit.getBeginB());
                            report.totalLinesDeleted += (edit.getEndA() - edit.getBeginA());
                        }
                    } catch (Exception ex) {
                        log.debug("Line edits fallback for {}: {}", path, ex.getMessage());
                        fileDiff.lineRanges.add(new int[]{1, 10000});
                    }

                    // If file is newly added and line count is whole file
                    if (fileDiff.lineRanges.isEmpty() && entry.getChangeType() == DiffEntry.ChangeType.ADD) {
                        fileDiff.lineRanges.add(new int[]{1, 100000});
                    }

                    report.changedFiles.add(fileDiff);
                }

                report.totalFilesChanged = report.changedFiles.size();
                if (report.baseRef == null) report.baseRef = baseRef != null ? baseRef : "HEAD";
                if (report.headRef == null) report.headRef = headRef != null ? headRef : "HEAD";
            }
            return report;
        }
    }
}
