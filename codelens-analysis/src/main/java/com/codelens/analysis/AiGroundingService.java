package com.codelens.analysis;

import com.codelens.core.model.CodeMethod;
import com.codelens.core.model.CodeType;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Local-First Grounded AI & Fact Synthesis Architecture.
 *
 * Adheres strictly to the CodeStory Master Project Document (Sections 10, 11, 18, 36):
 * 1. AI is not the foundation; deterministic semantic facts are.
 * 2. Operates strictly over structured graph facts (AST, Call Graph, Storylines).
 * 3. Evidence before explanation: all assertions link to verified [File:Line] citations.
 * 4. Model-agnostic & Local-First: Supports Ollama (local LLM), OpenAI-compatible APIs,
 *    and a 100% offline deterministic rule-based template synthesizer when no LLM is configured.
 */
public class AiGroundingService {

    private String provider = "local"; // "local" | "ollama" | "openai"
    private String model = "llama3";
    private String endpoint = "http://localhost:11434/api/generate";
    private String apiKey = "";

    private final HttpClient httpClient;

    private static final Set<String> STOP_WORDS = Set.of(
        "how", "does", "the", "what", "is", "a", "an", "to", "work", "do",
        "in", "and", "or", "of", "for", "on", "with", "by", "can", "it",
        "me", "tell", "explain", "show", "find", "where", "are", "which",
        "why", "when", "system", "code", "codebase", "flow", "process"
    );

    public AiGroundingService() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(600))
            .build();
    }

    public AiGroundingService(String provider, String model, String endpoint, String apiKey) {
        this.provider = (provider != null && !provider.isBlank()) ? provider : "local";
        this.model = (model != null && !model.isBlank()) ? model : "llama3";
        this.endpoint = (endpoint != null && !endpoint.isBlank()) ? endpoint : "http://localhost:11434/api/generate";
        this.apiKey = apiKey != null ? apiKey : "";
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(800))
            .build();
    }

    public void configure(String provider, String model, String endpoint, String apiKey) {
        if (provider != null) this.provider = provider;
        if (model != null) this.model = model;
        if (endpoint != null) this.endpoint = endpoint;
        if (apiKey != null) this.apiKey = apiKey;
    }

    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public String getEndpoint() { return endpoint; }
    public String getApiKey() { return apiKey; }

    public static class Citation {
        public String file;
        public int line;
        public String symbol;
        public String role;

        public Citation() {}
        public Citation(String file, int line, String symbol, String role) {
            this.file = file;
            this.line = line;
            this.symbol = symbol;
            this.role = role;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Citation c = (Citation) o;
            return line == c.line && Objects.equals(file, c.file) && Objects.equals(symbol, c.symbol);
        }

        @Override
        public int hashCode() {
            return Objects.hash(file, line, symbol);
        }
    }

    public static class GroundedAnswer {
        public String question;
        public String provider;
        public String model;
        public String answerText;
        public List<Citation> citations = new ArrayList<>();
        public List<String> relevantStorylines = new ArrayList<>();
        public List<String> groundedFacts = new ArrayList<>();
        public long responseTimeMs;
    }

    public static class SymbolExplanation {
        public String targetFqn;
        public String simpleName;
        public String classFqn;
        public String role;
        public String summary;
        public int incomingCallersCount;
        public int outgoingCallsCount;
        public List<String> callers = new ArrayList<>();
        public List<String> callees = new ArrayList<>();
        public List<String> affectedStorylines = new ArrayList<>();
        public List<Citation> citations = new ArrayList<>();
        public String detailedNarrative;
    }

    /**
     * Answers a natural language developer query grounded strictly in AST & call graph facts.
     */
    public GroundedAnswer askQuestion(String question,
                                       List<CodeType> types,
                                       List<CodeMethod> methods,
                                       Graph<String, DefaultEdge> graph,
                                       List<StoryEngine.StorylineSummary> storylines) {
        long startTime = System.currentTimeMillis();
        GroundedAnswer res = new GroundedAnswer();
        res.question = question;

        if (question == null || question.isBlank()) {
            res.answerText = "Please ask a question about the codebase architecture, execution flow, or components.";
            res.provider = this.provider;
            res.model = "deterministic-synthesizer";
            res.responseTimeMs = 0;
            return res;
        }

        // 1. Extract intent tokens
        Set<String> tokens = extractTokens(question);

        // 2. Discover matching storylines
        List<StoryEngine.StorylineSummary> matchedStories = new ArrayList<>();
        if (storylines != null) {
            for (StoryEngine.StorylineSummary s : storylines) {
                String fullText = ((s.title != null ? s.title : "") + " " +
                                   (s.executiveSummary != null ? s.executiveSummary : "") + " " +
                                   (s.category != null ? s.category : "")).toLowerCase(Locale.ROOT);
                for (String t : tokens) {
                    if (fullText.contains(t)) {
                        matchedStories.add(s);
                        break;
                    }
                }
            }
        }

        // 3. Discover matching types
        List<CodeType> matchedTypes = new ArrayList<>();
        if (types != null) {
            for (CodeType t : types) {
                String simpleLower = (t.getSimpleName() != null ? t.getSimpleName() : "").toLowerCase(Locale.ROOT);
                for (String token : tokens) {
                    if (simpleLower.contains(token)) {
                        matchedTypes.add(t);
                        break;
                    }
                }
            }
        }

        // 4. Discover matching methods
        List<CodeMethod> matchedMethods = new ArrayList<>();
        if (methods != null) {
            for (CodeMethod m : methods) {
                String simpleLower = (m.getSimpleName() != null ? m.getSimpleName() : "").toLowerCase(Locale.ROOT);
                for (String token : tokens) {
                    if (simpleLower.contains(token)) {
                        matchedMethods.add(m);
                        break;
                    }
                }
            }
        }

        // 5. Build grounded facts & citations
        LinkedHashSet<Citation> citations = new LinkedHashSet<>();
        List<String> facts = new ArrayList<>();

        for (StoryEngine.StorylineSummary s : matchedStories.stream().limit(3).toList()) {
            res.relevantStorylines.add(s.title);
            facts.add(String.format("Discovered Storyline '%s' [%s]: %s (Entry: %s, %d steps)",
                s.title, s.category, s.executiveSummary, s.entryClass, s.stepCount));
        }

        for (CodeType t : matchedTypes.stream().limit(4).toList()) {
            String role = inferTypeRole(t.getSimpleName());
            facts.add(String.format("Component '%s' [%s] defined in %s:%d with %d methods",
                t.getSimpleName(), role, t.getSourceFile(), t.getStartLine(), t.getMethods().size()));
            citations.add(new Citation(t.getSourceFile(), t.getStartLine(), t.getSimpleName(), role));
        }

        for (CodeMethod m : matchedMethods.stream().limit(6).toList()) {
            String role = inferMethodRole(m.getSimpleName());
            facts.add(String.format("Method '%s' [CC=%d, LOC=%d] in %s (line %d)",
                m.getFqn(), m.getCyclomaticComplexity(), m.getLineCount(), extractClassSimple(m.getDeclaringTypeFqn()), m.getStartLine()));
            CodeType parent = types != null ? types.stream().filter(ty -> ty.getFqn().equals(m.getDeclaringTypeFqn())).findFirst().orElse(null) : null;
            String file = parent != null ? parent.getSourceFile() : "";
            citations.add(new Citation(file, m.getStartLine(), m.getSimpleName(), role));
        }

        res.citations = new ArrayList<>(citations);
        res.groundedFacts = facts;

        // 6. Attempt LLM generation if Ollama or OpenAI provider configured
        String llmResponse = null;
        if ("ollama".equalsIgnoreCase(this.provider)) {
            llmResponse = queryOllama(question, facts, res.relevantStorylines);
        } else if ("openai".equalsIgnoreCase(this.provider)) {
            llmResponse = queryOpenAi(question, facts, res.relevantStorylines);
        }

        if (llmResponse != null && !llmResponse.isBlank()) {
            res.answerText = llmResponse;
            res.provider = this.provider;
            res.model = this.model;
        } else {
            // High-precision deterministic template fallback
            res.answerText = synthesizeDeterministicAnswer(question, facts, res.citations, matchedStories, matchedTypes, matchedMethods);
            res.provider = "local";
            res.model = "deterministic-fact-synthesizer";
        }

        res.responseTimeMs = System.currentTimeMillis() - startTime;
        return res;
    }

    /**
     * Explains the architectural role, dependencies, and business intent of a method or class.
     */
    public SymbolExplanation explainSymbol(String fqn,
                                           List<CodeType> types,
                                           List<CodeMethod> methods,
                                           Graph<String, DefaultEdge> graph,
                                           List<StoryEngine.StorylineSummary> storylines) {
        SymbolExplanation exp = new SymbolExplanation();
        exp.targetFqn = fqn;

        CodeType matchedType = types != null ? types.stream().filter(t -> t.getFqn().equals(fqn)).findFirst().orElse(null) : null;
        CodeMethod matchedMethod = methods != null ? methods.stream().filter(m -> m.getFqn().equals(fqn)).findFirst().orElse(null) : null;

        if (matchedType != null) {
            exp.simpleName = matchedType.getSimpleName();
            exp.classFqn = matchedType.getFqn();
            exp.role = inferTypeRole(matchedType.getSimpleName());
            exp.summary = String.format("%s is a %s component containing %d methods.",
                matchedType.getSimpleName(), exp.role, matchedType.getMethods().size());
            exp.citations.add(new Citation(matchedType.getSourceFile(), matchedType.getStartLine(), matchedType.getSimpleName(), exp.role));
        } else if (matchedMethod != null) {
            exp.simpleName = matchedMethod.getSimpleName();
            exp.classFqn = matchedMethod.getDeclaringTypeFqn();
            exp.role = inferMethodRole(matchedMethod.getSimpleName());
            exp.summary = String.format("%s.%s() is an architectural member method [CC=%d, LOC=%d].",
                extractClassSimple(matchedMethod.getDeclaringTypeFqn()), matchedMethod.getSimpleName(),
                matchedMethod.getCyclomaticComplexity(), matchedMethod.getLineCount());
            CodeType parent = types != null ? types.stream().filter(ty -> ty.getFqn().equals(matchedMethod.getDeclaringTypeFqn())).findFirst().orElse(null) : null;
            String file = parent != null ? parent.getSourceFile() : "";
            exp.citations.add(new Citation(file, matchedMethod.getStartLine(), matchedMethod.getSimpleName(), exp.role));
        } else {
            exp.simpleName = extractSimpleName(fqn);
            exp.classFqn = extractClassFqn(fqn);
            exp.role = "Component";
            exp.summary = "Entity analyzed from call topology.";
        }

        // Trace call graph
        if (graph != null && graph.containsVertex(fqn)) {
            for (DefaultEdge e : graph.incomingEdgesOf(fqn)) {
                exp.callers.add(graph.getEdgeSource(e));
            }
            for (DefaultEdge e : graph.outgoingEdgesOf(fqn)) {
                exp.callees.add(graph.getEdgeTarget(e));
            }
        }
        exp.incomingCallersCount = exp.callers.size();
        exp.outgoingCallsCount = exp.callees.size();

        // Cross-reference storylines
        if (storylines != null) {
            for (StoryEngine.StorylineSummary s : storylines) {
                if (s.entryPoint != null && (s.entryPoint.contains(fqn) || fqn.contains(s.entryPoint))) {
                    exp.affectedStorylines.add(s.title);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("### %s (`%s`)\n\n", exp.simpleName, exp.role));
        sb.append(exp.summary).append("\n\n");
        sb.append(String.format("- **Inbound Coupling**: %d upstream callers invoke this symbol.\n", exp.incomingCallersCount));
        sb.append(String.format("- **Outbound Flow**: %d downstream components called.\n", exp.outgoingCallsCount));
        if (!exp.affectedStorylines.isEmpty()) {
            sb.append(String.format("- **Associated Workflows**: %s\n", String.join(", ", exp.affectedStorylines)));
        }
        exp.detailedNarrative = sb.toString();

        return exp;
    }

    private String synthesizeDeterministicAnswer(String question,
                                                  List<String> facts,
                                                  List<Citation> citations,
                                                  List<StoryEngine.StorylineSummary> matchedStories,
                                                  List<CodeType> matchedTypes,
                                                  List<CodeMethod> matchedMethods) {
        if (facts.isEmpty()) {
            return "### Codebase Intelligence Assessment\n\n" +
                   "No direct architectural components or execution storylines specifically matched your query.\n\n" +
                   "**Suggestions**:\n" +
                   "- Inquire about core domain concepts (e.g., *'How does order processing work?'* or *'What does RiskEngine do?'*)\n" +
                   "- Inspect the **Storylines** tab for auto-discovered workflows across controllers, services, and batches.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("### CodeStory Architecture Synthesis\n\n");

        if (!matchedStories.isEmpty()) {
            StoryEngine.StorylineSummary primary = matchedStories.get(0);
            sb.append(String.format("**Primary Discovered Workflow**: **%s** (%s)\n\n", primary.title, primary.category));
            sb.append(String.format("> %s\n\n", primary.executiveSummary));
            sb.append(String.format("Execution begins at `%s` and spans %d verified architectural steps.\n\n",
                primary.entryClass, primary.stepCount));
        }

        if (!matchedTypes.isEmpty()) {
            sb.append("#### Key Structural Components\n");
            for (CodeType t : matchedTypes.stream().limit(3).toList()) {
                String role = inferTypeRole(t.getSimpleName());
                String shortFile = t.getSourceFile() != null && t.getSourceFile().contains("/")
                    ? t.getSourceFile().substring(t.getSourceFile().lastIndexOf('/') + 1)
                    : t.getSourceFile();
                sb.append(String.format("- **%s** (`%s`): Implemented in `%s:%d` with %d methods.\n",
                    t.getSimpleName(), role, shortFile, t.getStartLine(), t.getMethods().size()));
            }
            sb.append("\n");
        }

        if (!matchedMethods.isEmpty()) {
            sb.append("#### Verified Execution Touchpoints\n");
            for (CodeMethod m : matchedMethods.stream().limit(4).toList()) {
                sb.append(String.format("- `%s.%s()` [Complexity: %d, Line: %d]\n",
                    extractClassSimple(m.getDeclaringTypeFqn()), m.getSimpleName(), m.getCyclomaticComplexity(), m.getStartLine()));
            }
            sb.append("\n");
        }

        sb.append("#### Grounded Source Citations\n");
        if (citations.isEmpty()) {
            sb.append("All assertions grounded in the indexed AST call topology.\n");
        } else {
            for (Citation c : citations.stream().limit(5).toList()) {
                String shortFile = c.file != null && c.file.contains("/")
                    ? c.file.substring(c.file.lastIndexOf('/') + 1)
                    : (c.file != null ? c.file : "source");
                sb.append(String.format("- [`%s:%d`](#) — **%s** (%s)\n",
                    shortFile, c.line, c.symbol, c.role));
            }
        }

        return sb.toString();
    }

    private String queryOllama(String question, List<String> facts, List<String> storylines) {
        try {
            String prompt = buildPrompt(question, facts, storylines);
            String jsonPayload = String.format(
                "{\"model\":\"%s\",\"prompt\":%s,\"stream\":false}",
                escapeJson(this.model),
                quoteJson(prompt)
            );

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(this.endpoint))
                .timeout(Duration.ofSeconds(6))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                // Parse "response" property from Ollama JSON
                Pattern p = Pattern.compile("\"response\"\\s*:\\s*\"(.*?)(?<!\\\\)\"", Pattern.DOTALL);
                Matcher m = p.matcher(resp.body());
                if (m.find()) {
                    return unescapeJson(m.group(1));
                }
            }
        } catch (Exception ignored) {
            // Ollama offline or unreachable; fall back to local template synthesizer
        }
        return null;
    }

    private String queryOpenAi(String question, List<String> facts, List<String> storylines) {
        if (this.apiKey == null || this.apiKey.isBlank()) return null;
        try {
            String prompt = buildPrompt(question, facts, storylines);
            String jsonPayload = String.format(
                "{\"model\":\"%s\",\"messages\":[{\"role\":\"system\",\"content\":\"You are CodeStory, an architectural code intelligence engine. Answer using ONLY the supplied verified facts with [File:Line] citations.\"},{\"role\":\"user\",\"content\":%s}]}",
                escapeJson(this.model),
                quoteJson(prompt)
            );

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(this.endpoint))
                .timeout(Duration.ofSeconds(6))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + this.apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                Pattern p = Pattern.compile("\"content\"\\s*:\\s*\"(.*?)(?<!\\\\)\"", Pattern.DOTALL);
                Matcher m = p.matcher(resp.body());
                if (m.find()) {
                    return unescapeJson(m.group(1));
                }
            }
        } catch (Exception ignored) {
            // OpenAI offline or quota exceeded; fall back to local template synthesizer
        }
        return null;
    }

    private String buildPrompt(String question, List<String> facts, List<String> storylines) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are CodeStory, an architectural intelligence assistant.\n");
        sb.append("Explain the answer to the following question using ONLY the verified facts below.\n");
        sb.append("Every assertion MUST cite [File:Line]. Do NOT hallucinate methods or files.\n\n");
        sb.append("QUESTION: ").append(question).append("\n\n");
        sb.append("VERIFIED CODEBASE FACTS:\n");
        for (String f : facts) {
            sb.append("- ").append(f).append("\n");
        }
        if (!storylines.isEmpty()) {
            sb.append("\nRELEVANT WORKFLOWS: ").append(String.join(", ", storylines)).append("\n");
        }
        return sb.toString();
    }

    private Set<String> extractTokens(String text) {
        Set<String> set = new HashSet<>();
        if (text == null) return set;
        String[] words = text.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+");
        for (String w : words) {
            if (w.length() >= 3 && !STOP_WORDS.contains(w)) {
                set.add(w);
            }
        }
        return set;
    }

    private String inferTypeRole(String simpleName) {
        if (simpleName == null) return "Component";
        String lower = simpleName.toLowerCase(Locale.ROOT);
        if (lower.contains("controller") || lower.contains("resource") || lower.contains("endpoint")) return "REST Controller";
        if (lower.contains("service") || lower.contains("manager") || lower.contains("handler")) return "Domain Service";
        if (lower.contains("repo") || lower.contains("dao")) return "Persistence Repository";
        if (lower.contains("engine") || lower.contains("processor") || lower.contains("calc")) return "Core Processing Engine";
        if (lower.contains("validator") || lower.contains("guard") || lower.contains("check")) return "Validation & Guard";
        if (lower.contains("model") || lower.contains("entity") || lower.contains("dto")) return "Data Model";
        if (lower.contains("bootstrap") || lower.contains("main") || lower.contains("app")) return "System Bootstrap";
        return "Architectural Unit";
    }

    private String inferMethodRole(String methodName) {
        if (methodName == null) return "Execution Step";
        String lower = methodName.toLowerCase(Locale.ROOT);
        if (lower.startsWith("submit") || lower.startsWith("post") || lower.startsWith("create") || lower.startsWith("start")) return "Entrypoint";
        if (lower.startsWith("validate") || lower.startsWith("check") || lower.startsWith("assert")) return "Validation";
        if (lower.startsWith("save") || lower.startsWith("persist") || lower.startsWith("insert") || lower.startsWith("find")) return "Persistence";
        if (lower.startsWith("evaluate") || lower.startsWith("calculate") || lower.startsWith("process")) return "Domain Processing";
        return "Internal Method";
    }

    private String extractSimpleName(String fqn) {
        if (fqn == null) return "";
        int paren = fqn.indexOf('(');
        String base = (paren > 0) ? fqn.substring(0, paren) : fqn;
        int dot = base.lastIndexOf('.');
        return (dot >= 0) ? base.substring(dot + 1) : base;
    }

    private String extractClassFqn(String methodFqn) {
        if (methodFqn == null) return "";
        int paren = methodFqn.indexOf('(');
        String base = (paren > 0) ? methodFqn.substring(0, paren) : methodFqn;
        int dot = base.lastIndexOf('.');
        return (dot >= 0) ? base.substring(0, dot) : base;
    }

    private String extractClassSimple(String classFqn) {
        if (classFqn == null) return "";
        int dot = classFqn.lastIndexOf('.');
        return (dot >= 0) ? classFqn.substring(dot + 1) : classFqn;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private String quoteJson(String s) {
        return "\"" + escapeJson(s) + "\"";
    }

    private String unescapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\n", "\n").replace("\\r", "\r").replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
