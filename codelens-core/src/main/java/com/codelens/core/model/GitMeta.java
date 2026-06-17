package com.codelens.core.model;

/**
 * Git metadata for a single indexed entity (type, method, or field).
 *
 * Populated during the git-blame phase of a scan. All fields may be null
 * when the scanned project is not inside a Git repository (graceful degradation).
 */
public class GitMeta {

    /** FQN of the entity this record belongs to (PK / FK). */
    private String entityFqn;

    /** Human-readable name of the last committer. */
    private String lastAuthorName;

    /** Email address of the last committer. */
    private String lastAuthorEmail;

    /**
     * Unix epoch seconds of the last commit that touched the entity's source
     * file on the lines covered by this entity.
     */
    private long lastCommitTime;

    /** Short (7-char) SHA of the most-recent touching commit. */
    private String lastCommitHash;

    /** First line of the most-recent commit message. */
    private String lastCommitMsg;

    /**
     * Total number of distinct commits that touched the entity's source file.
     * Used as a proxy for "change frequency" / code churn.
     */
    private int commitCount;

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public String getEntityFqn()                      { return entityFqn; }
    public void   setEntityFqn(String e)             { this.entityFqn = e; }

    public String getLastAuthorName()                 { return lastAuthorName; }
    public void   setLastAuthorName(String n)        { this.lastAuthorName = n; }

    public String getLastAuthorEmail()                { return lastAuthorEmail; }
    public void   setLastAuthorEmail(String e)       { this.lastAuthorEmail = e; }

    public long   getLastCommitTime()                 { return lastCommitTime; }
    public void   setLastCommitTime(long t)          { this.lastCommitTime = t; }

    public String getLastCommitHash()                 { return lastCommitHash; }
    public void   setLastCommitHash(String h)        { this.lastCommitHash = h; }

    public String getLastCommitMsg()                  { return lastCommitMsg; }
    public void   setLastCommitMsg(String m)         { this.lastCommitMsg = m; }

    public int    getCommitCount()                    { return commitCount; }
    public void   setCommitCount(int c)              { this.commitCount = c; }
}
