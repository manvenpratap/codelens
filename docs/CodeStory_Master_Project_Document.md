# CodeStory --- Master Product & Technical Specification

**Working Name:** CodeStory\
**Tagline:** Understand any codebase as a story, not a pile of files.\
**Document Status:** Master project document --- Concept / V0.1
planning\
**Purpose:** Product vision, functional specification, technical
architecture, roadmap, and master build context.

------------------------------------------------------------------------

## 1. Executive Summary

CodeStory is a developer productivity platform designed to solve one of
the hardest problems in software engineering: **understanding an
unfamiliar codebase quickly and accurately**.

Traditional code navigation exposes files, classes, methods, imports,
and references. These are useful primitives, but they do not directly
answer the questions developers actually ask:

-   What happens when this operation starts?
-   Why does this class exist?
-   Which components depend on it?
-   Where does this data come from and where does it go?
-   What will break if I change this method?
-   Which configuration, database tables, APIs, jobs, or services are
    involved?
-   Can someone explain this subsystem to me like a story?

CodeStory converts a codebase into an **interactive semantic model** and
then presents that model as a **storyline**.

The key idea is not simply:

> Visualize code.

It is:

> **Turn code into an understandable narrative backed by the actual
> structure and evidence of the repository.**

The product combines:

1.  Static code analysis
2.  Semantic code graphs
3.  Dependency and execution-flow analysis
4.  AI-assisted interpretation
5.  Evidence-backed explanations
6.  Interactive visualizations
7.  Change-impact analysis
8.  Developer workflows through IDE, CLI, and web interfaces

The ultimate goal is to reduce the time required for a developer to form
a reliable **mental model** of a software system.

------------------------------------------------------------------------

# 2. Product Vision

## Vision

Build the world's most useful **code comprehension engine**.

CodeStory should allow a developer to point it at almost any repository
and quickly answer:

> "Tell me how this system actually works."

Instead of forcing the developer to read hundreds of files sequentially,
CodeStory should construct a semantic representation of the system and
explain it progressively.

### Long-term vision

CodeStory becomes a **navigation and reasoning layer over software
systems**.

It should eventually understand:

-   Source code
-   APIs
-   Databases
-   SQL
-   Configuration
-   Infrastructure
-   Build pipelines
-   Runtime telemetry
-   Documentation
-   Tickets
-   Git history
-   Architecture decisions
-   Tests
-   Production dependencies

The result is a continuously evolving **living model of the software
system**.

------------------------------------------------------------------------

# 3. The Core Problem

Modern enterprise codebases are difficult to understand because
complexity is distributed across many dimensions.

A developer may need to understand:

-   Thousands of classes
-   Multiple modules
-   Framework conventions
-   Dependency injection
-   Database interactions
-   Asynchronous processing
-   Batch jobs
-   Event queues
-   REST APIs
-   Configuration
-   Legacy code
-   Generated code
-   External services
-   Hidden dependencies
-   Runtime behavior

Existing IDE navigation primarily answers:

> Where is this symbol?

Developers need answers to:

> Why is this here?

> What happens next?

> What depends on this?

> What happens if I change it?

> What is the business flow?

CodeStory is designed around those questions.

------------------------------------------------------------------------

# 4. Core Product Insight

The central product insight is:

> **Developers understand systems through stories, not through
> disconnected symbols.**

A codebase contains implicit stories.

For example:

``` text
User submits order
        ↓
OrderController
        ↓
OrderService
        ↓
Validation
        ↓
RiskCheck
        ↓
OrderRepository
        ↓
Database
        ↓
EventPublisher
        ↓
SettlementProcessor
```

A traditional IDE may show each class separately.

CodeStory turns the same information into an interactive story:

> "An order enters through the REST controller, is validated by the
> order service, passes a risk check, is persisted, and then produces an
> event consumed by the settlement processor."

The graph provides the evidence.

The story provides the understanding.

------------------------------------------------------------------------

# 5. Product Positioning

CodeStory should not position itself as:

-   Another UML tool
-   Another dependency graph
-   Another code visualization tool
-   Another AI coding assistant
-   Another documentation generator

Instead:

## CodeStory = Code Comprehension Platform

### Primary value proposition

> **Understand unfamiliar software dramatically faster.**

### Supporting capabilities

-   Explore
-   Explain
-   Trace
-   Visualize
-   Investigate
-   Assess impact
-   Learn
-   Document

------------------------------------------------------------------------

# 6. Target Users

## 6.1 Software Developers

Primary use cases:

-   Joining a new project
-   Understanding legacy code
-   Debugging
-   Making changes safely
-   Tracing execution
-   Understanding dependencies

## 6.2 Architects

Use cases:

-   Architecture discovery
-   Dependency analysis
-   Technical debt identification
-   Change-impact analysis
-   System documentation

## 6.3 Engineering Managers

Use cases:

-   Understanding system complexity
-   Onboarding
-   Risk assessment
-   Ownership discovery
-   Modernization planning

## 6.4 Consultants / System Integrators

Use cases:

-   Rapid discovery
-   Legacy-system analysis
-   Customer onboarding
-   Technical due diligence

## 6.5 New Developers

Use cases:

-   Guided onboarding
-   Learning unfamiliar systems
-   Understanding business flows
-   Interactive architecture tours

------------------------------------------------------------------------

# 7. Core Product Workflows

## Workflow 1 --- Understand

User selects a repository.

CodeStory analyzes it and generates:

-   Repository map
-   Modules
-   Components
-   Dependencies
-   Entry points
-   Major workflows
-   External integrations
-   Database interactions

The user receives a high-level story:

> "This repository is primarily a transaction-processing system.
> Requests enter through these APIs, pass through these services,
> interact with these database components, and eventually reach these
> settlement processes."

------------------------------------------------------------------------

## Workflow 2 --- Trace

User asks:

> "Show me what happens when an order is submitted."

CodeStory identifies:

-   Entry point
-   Call chain
-   Validation
-   Services
-   Database operations
-   Events
-   Async processing
-   External services

The result is an interactive storyline.

------------------------------------------------------------------------

## Workflow 3 --- Explain

User selects a class or method.

CodeStory explains:

-   What it does
-   Why it exists
-   Who calls it
-   What it calls
-   Important inputs
-   Important outputs
-   Side effects
-   Database interactions
-   Business significance

Every important explanation should be connected to evidence from the
repository.

------------------------------------------------------------------------

## Workflow 4 --- Impact Analysis

User asks:

> "What happens if I change this method?"

CodeStory determines:

-   Direct callers
-   Indirect callers
-   Implementations
-   Interfaces
-   Tests
-   APIs
-   Database interactions
-   Events
-   Downstream services
-   Configuration
-   Potentially affected workflows

Output:

``` text
CHANGE
  ↓
Method
  ↓
Direct callers
  ↓
Services
  ↓
API / Batch / Event flows
  ↓
Tests
  ↓
Potential production impact
```

------------------------------------------------------------------------

## Workflow 5 --- Teach Me

User asks:

> "Teach me the payment flow."

CodeStory produces a progressive explanation:

### Level 1 --- Executive overview

Short explanation.

### Level 2 --- Architecture

Major components.

### Level 3 --- Execution flow

Detailed sequence.

### Level 4 --- Code

Classes and methods.

### Level 5 --- Evidence

Actual repository references.

This makes CodeStory useful for onboarding and learning.

------------------------------------------------------------------------

## Workflow 6 --- Code Change Story

When a developer changes code, CodeStory can generate:

> "Before this change..."

> "This change modifies..."

> "These workflows are affected..."

> "These tests cover the affected behavior..."

> "These downstream components should be reviewed."

This becomes a powerful developer workflow.

------------------------------------------------------------------------

# 8. The Signature UI Concept

The signature UI is the **Storyline**.

Instead of presenting a giant graph, CodeStory presents a structured
narrative.

Example:

``` text
┌──────────────────┐
│ API Request      │
└────────┬─────────┘
         ↓
┌──────────────────┐
│ Controller       │
└────────┬─────────┘
         ↓
┌──────────────────┐
│ Business Service │
└────────┬─────────┘
         ↓
┌──────────────────┐
│ Validation       │
└────────┬─────────┘
         ↓
┌──────────────────┐
│ Repository       │
└────────┬─────────┘
         ↓
┌──────────────────┐
│ Database         │
└────────┬─────────┘
         ↓
┌──────────────────┐
│ Event            │
└────────┬─────────┘
         ↓
┌──────────────────┐
│ Downstream Job   │
└──────────────────┘
```

Each node is interactive.

Selecting a node opens:

-   Source
-   Explanation
-   Dependencies
-   Callers
-   Tests
-   Data flow
-   Evidence
-   Related stories

------------------------------------------------------------------------

# 9. The Semantic Code Graph

The graph is the technical foundation of CodeStory.

It should represent more than imports.

## Node types

Potential node types:

-   Repository
-   Module
-   Package
-   Class
-   Interface
-   Method
-   Function
-   Variable
-   API
-   Endpoint
-   Database
-   Table
-   Column
-   SQL statement
-   Queue
-   Event
-   Batch job
-   Configuration
-   External service
-   Test
-   Documentation
-   Git commit

## Edge types

Examples:

-   CALLS
-   IMPORTS
-   EXTENDS
-   IMPLEMENTS
-   READS
-   WRITES
-   PUBLISHES
-   CONSUMES
-   CONFIGURED_BY
-   TESTED_BY
-   DEPENDS_ON
-   IMPLEMENTED_BY
-   TRIGGERS
-   RETURNS
-   CREATES
-   UPDATES
-   DELETES

The graph becomes the system's machine-readable semantic model.

------------------------------------------------------------------------

# 10. Evidence Model

A major product principle is:

> **AI should explain the code, not invent the code.**

Every important AI-generated claim should ideally have supporting
evidence.

Example:

> `OrderService.placeOrder()` performs validation before persistence.

Evidence:

``` text
OrderService.java
Line 184
```

The UI should allow the developer to jump directly to the source.

This creates trust.

------------------------------------------------------------------------

# 11. AI Architecture

AI is not the foundation of the product.

The foundation is the semantic representation.

Recommended pipeline:

``` text
Repository
    ↓
Parser
    ↓
AST
    ↓
Symbol Resolution
    ↓
Dependency Extraction
    ↓
Semantic Graph
    ↓
Code Chunking
    ↓
Embeddings / Retrieval
    ↓
AI Reasoning
    ↓
Evidence Validation
    ↓
Story Engine
    ↓
Interactive UI
```

AI should operate over structured facts rather than raw repository text
whenever possible.

------------------------------------------------------------------------

# 12. Story Engine

The Story Engine is one of the primary differentiators.

Its responsibility is to transform graph information into human-readable
explanations.

Example input:

``` text
Controller
 → Service
 → Repository
 → Database
 → Event
 → Consumer
```

Story Engine output:

> "When a customer submits an order, the request first reaches
> OrderController. The controller delegates processing to OrderService,
> which validates the order before persisting it through
> OrderRepository. Once persistence succeeds, an event is published and
> consumed by the settlement processor."

The story should be:

-   Concise
-   Structured
-   Evidence-backed
-   Interactive
-   Expandable
-   Adapted to the user's question

------------------------------------------------------------------------

# 13. Analysis Engine

The Analysis Engine should calculate:

## Structural analysis

-   Dependency depth
-   Fan-in
-   Fan-out
-   Circular dependencies
-   Module coupling
-   Complexity

## Flow analysis

-   Call chains
-   Data flow
-   Event flow
-   API flow
-   Database flow
-   Batch flow

## Risk analysis

-   Highly connected components
-   Single points of failure
-   Large blast radius
-   Untested critical paths
-   Legacy hotspots

## Change-impact analysis

-   Direct impact
-   Indirect impact
-   Test impact
-   API impact
-   Data impact

------------------------------------------------------------------------

# 14. Initial Language Support

The first implementation should prioritize enterprise development
environments.

## Phase 1

-   Java
-   TypeScript / JavaScript

## Phase 2

-   Python
-   C#
-   Kotlin
-   Go

## Enterprise extensions

-   SQL
-   DB2
-   Oracle
-   PostgreSQL
-   Kafka
-   REST
-   Spring
-   Spring Batch

Java should be a particularly strong first-class experience because
enterprise Java applications often contain deep dependency structures
and complex business flows.

------------------------------------------------------------------------

# 15. DB2 / Enterprise Database Understanding

For enterprise systems, database understanding should be a first-class
capability.

CodeStory should eventually understand:

``` text
Java Method
    ↓
DAO / Repository
    ↓
SQL
    ↓
Table
    ↓
Column
```

And:

``` text
Batch Job
    ↓
SQL
    ↓
Database
    ↓
Downstream Process
```

Potential DB2-specific analysis:

-   SQL extraction
-   Table references
-   Read/write detection
-   Stored procedures
-   Index relationships
-   Package cache integration
-   Explain plans
-   Performance hotspots

This can create strong differentiation for enterprise users.

------------------------------------------------------------------------

# 16. Product Interfaces

CodeStory should eventually be available through multiple interfaces.

## 16.1 Web Application

Main exploration interface.

## 16.2 VS Code Extension

Developer sees CodeStory directly while coding.

Example:

Right-click method → **Explain Story**

or:

Right-click class → **Show Impact**

## 16.3 CLI

Example commands:

``` bash
codestory analyze .
codestory story "order submission"
codestory trace OrderService.placeOrder
codestory impact OrderService.placeOrder
codestory explain src/main/java/OrderService.java
```

## 16.4 CI/CD

Potential future integrations:

``` text
Pull Request
     ↓
CodeStory Impact Analysis
     ↓
Risk Report
     ↓
Developer Review
```

------------------------------------------------------------------------

# 17. Recommended Technical Architecture

## Frontend

Possible stack:

-   React
-   TypeScript
-   Next.js
-   React Flow / Cytoscape / D3
-   Monaco Editor

## Backend

Possible stack:

-   Python or TypeScript initially
-   FastAPI / Node.js
-   PostgreSQL
-   Graph database where justified

## Analysis

Potential components:

-   Tree-sitter
-   Language Server Protocol integrations
-   JavaParser / Eclipse JDT
-   TypeScript compiler APIs
-   Custom symbol-resolution engine

## AI

Model-agnostic architecture supporting:

-   Cloud LLMs
-   Enterprise models
-   Local LLMs

## Search / Retrieval

Potential:

-   PostgreSQL + pgvector
-   Dedicated vector store if scale requires it

------------------------------------------------------------------------

# 18. Local-First / Enterprise Architecture

Enterprise adoption will require strong privacy controls.

A key design principle:

> **Source code should not need to leave the customer's environment.**

Possible deployment models:

### Local

Runs on developer machine.

### Private network

Runs inside corporate infrastructure.

### Enterprise cloud

Customer-controlled environment.

### Hybrid

Code analysis remains local while selected metadata is sent to an
approved AI model.

This is especially important for:

-   Financial institutions
-   Banks
-   Insurance companies
-   Government
-   Large enterprises

------------------------------------------------------------------------

# 19. Security Principles

Security must be built into the architecture.

Requirements:

-   Repository isolation
-   Encryption
-   Access control
-   Audit logging
-   Secrets detection
-   Configurable data retention
-   No model training on customer source code by default
-   Enterprise SSO
-   RBAC
-   Private deployment options

Potential future standards:

-   SOC 2
-   ISO 27001
-   GDPR
-   Enterprise security reviews

------------------------------------------------------------------------

# 20. MVP

The first MVP should be intentionally focused.

## MVP Goal

Take a Java repository and answer:

> "How does this system work?"

## MVP capabilities

### Repository ingestion

-   Local repository
-   Git repository
-   Java source

### Static analysis

-   Classes
-   Interfaces
-   Methods
-   Calls
-   Imports
-   Inheritance
-   Implementations

### Graph

-   Dependency graph
-   Call graph
-   Module map

### Story

Generate a workflow story from the graph.

### AI

Ask questions such as:

``` text
How does order creation work?
```

``` text
What calls this method?
```

``` text
What happens if I change this class?
```

``` text
Explain this module.
```

### Evidence

Every important answer links back to source code.

------------------------------------------------------------------------

# 21. MVP Demo

The ideal first demo should use a real-world Java repository.

Demo flow:

### Step 1

Import repository.

### Step 2

CodeStory scans it.

### Step 3

Dashboard appears.

``` text
Repository
├── Modules
├── APIs
├── Services
├── Database
├── Jobs
└── External Systems
```

### Step 4

User selects:

> "Order Processing"

### Step 5

Storyline appears.

### Step 6

User asks:

> "What happens if I modify OrderService?"

### Step 7

Impact graph appears.

### Step 8

User clicks a node and jumps to source.

That is the core product moment.

------------------------------------------------------------------------

# 22. MVP Success Criteria

The MVP should be judged primarily on:

## Time to Mental Model

Measure:

> How long does it take a developer to correctly explain a target
> workflow?

Compare:

-   Traditional repository exploration
-   CodeStory-assisted exploration

Potential target:

> Reduce initial codebase comprehension time by 50%+.

Other metrics:

-   Story correctness
-   Evidence coverage
-   Trace accuracy
-   Impact-analysis precision
-   Developer confidence
-   Questions answered without manual searching

------------------------------------------------------------------------

# 23. Product Roadmap

## V0.1 --- Code Comprehension Prototype

-   Java parser
-   Dependency graph
-   Call graph
-   Storyline UI
-   Basic AI explanation
-   Evidence links

## V0.2 --- Interactive Exploration

-   Search
-   Trace
-   Expand/collapse flows
-   Source navigation
-   Multiple workflows
-   Better graph layout

## V1.0 --- Developer Product

-   VS Code extension
-   CLI
-   Git integration
-   Change-impact analysis
-   Tests
-   Architecture summaries

## V2.0 --- Enterprise Understanding

-   SQL/database graph
-   APIs
-   Kafka/events
-   Batch jobs
-   Configuration
-   Runtime telemetry

## V3.0 --- Living Architecture

Continuously update the system model from:

-   Git
-   CI/CD
-   Deployments
-   Runtime telemetry
-   Tickets
-   Documentation

## V4.0 --- Software Intelligence Platform

Potential capabilities:

-   Architecture optimization
-   Modernization planning
-   Automated documentation
-   Technical debt intelligence
-   Refactoring suggestions
-   Engineering risk prediction
-   Autonomous investigation

------------------------------------------------------------------------

# 24. Competitive Differentiation

The market already contains:

-   Code visualization
-   Dependency graphs
-   Repository search
-   AI coding assistants
-   Documentation generators

CodeStory should not attempt to win by creating the prettiest graph.

The differentiator is the combination of:

``` text
Semantic Code Graph
        +
Story Engine
        +
Evidence-backed AI
        +
Impact Analysis
        +
Interactive Exploration
```

The key positioning:

> **Others show you the code. CodeStory helps you understand the
> system.**

------------------------------------------------------------------------

# 25. Competitive Moat

Potential long-term moat:

## 1. Semantic Code Graph

A structured representation of the entire system.

## 2. Story Engine

Specialized algorithms for converting technical relationships into
useful narratives.

## 3. Evidence System

Reliable grounding between explanations and source.

## 4. Workflow Intelligence

Understanding business flows rather than isolated symbols.

## 5. Change-Impact Model

Understanding how code changes propagate.

## 6. Enterprise Knowledge Layer

Eventually combine:

``` text
Code
+
Runtime
+
Database
+
Documentation
+
Git
+
Tickets
+
Architecture
```

This creates a living engineering knowledge graph.

------------------------------------------------------------------------

# 26. Business Model

Potential pricing:

## Free

-   Small repositories
-   Limited analyses
-   Local use

## Pro

Developer-focused subscription.

Features:

-   Larger repositories
-   Advanced AI
-   Impact analysis
-   IDE integration

## Team

Shared project knowledge.

Features:

-   Team workspaces
-   Shared stories
-   Architecture documentation
-   Collaboration

## Enterprise

Features:

-   Private deployment
-   SSO
-   RBAC
-   Audit
-   Enterprise AI
-   Large repositories
-   Database integrations
-   Custom analyzers

Potential enterprise pricing can be based on:

-   Developers
-   Repositories
-   Compute
-   Enterprise deployment

------------------------------------------------------------------------

# 27. North-Star Metric

The primary product metric should be:

## Time to Mental Model

Definition:

> Time required for a developer to correctly understand a target system
> workflow.

Secondary metrics:

-   Time to first useful answer
-   Story accuracy
-   Evidence coverage
-   Impact-analysis precision
-   Developer adoption
-   Weekly active developers
-   Repository coverage
-   Questions answered
-   Onboarding time reduction

------------------------------------------------------------------------

# 28. Key Product Risks

## Risk 1 --- Graph becomes overwhelming

Mitigation:

-   Story-first interface
-   Progressive disclosure
-   Filtered views
-   Automatic clustering

## Risk 2 --- AI hallucination

Mitigation:

-   Evidence-backed answers
-   Structured graph grounding
-   Source links
-   Confidence indicators

## Risk 3 --- Static analysis misses runtime behavior

Mitigation:

-   Start with static analysis
-   Add runtime telemetry later

## Risk 4 --- Large repositories become slow

Mitigation:

-   Incremental indexing
-   Caching
-   Parallel parsing
-   Lazy graph expansion

## Risk 5 --- Looks like another code map

Mitigation:

-   Make Story Engine central
-   Optimize for questions and workflows
-   Make impact analysis a first-class capability

------------------------------------------------------------------------

# 29. Engineering Principles

## Principle 1

**Evidence before explanation.**

## Principle 2

**Graph before LLM.**

## Principle 3

**Progressive disclosure over information overload.**

## Principle 4

**Local-first where practical.**

## Principle 5

**Language-agnostic architecture.**

## Principle 6

**Repository facts must be distinguishable from AI interpretation.**

## Principle 7

**Every important generated claim should be traceable to evidence.**

## Principle 8

**Optimize for developer understanding, not visualization aesthetics.**

------------------------------------------------------------------------

# 30. Suggested Repository Structure

``` text
codestory/
├── analyzer/
│   ├── parsers/
│   ├── symbols/
│   ├── dependencies/
│   ├── callgraph/
│   └── flows/
│
├── graph/
│   ├── model/
│   ├── storage/
│   └── queries/
│
├── story-engine/
│   ├── workflows/
│   ├── summarization/
│   ├── narratives/
│   └── evidence/
│
├── ai/
│   ├── retrieval/
│   ├── prompts/
│   ├── grounding/
│   └── providers/
│
├── backend/
│   ├── api/
│   ├── services/
│   └── auth/
│
├── frontend/
│   ├── graph/
│   ├── storyline/
│   ├── source/
│   └── dashboard/
│
├── cli/
│
├── vscode-extension/
│
├── tests/
│
└── docs/
```

------------------------------------------------------------------------

# 31. Testing Strategy

Testing must cover both deterministic analysis and AI behavior.

## Static-analysis tests

-   Parser correctness
-   Symbol resolution
-   Call graph accuracy
-   Dependency accuracy

## Story tests

Given known graph:

``` text
A → B → C
```

Expected story:

``` text
A calls B, which calls C.
```

## Evidence tests

Every generated claim must map to valid source evidence.

## Impact tests

Given a changed method, expected impacted components should be known.

## AI evaluation

Measure:

-   Factual correctness
-   Completeness
-   Evidence coverage
-   Hallucination rate
-   Usefulness

------------------------------------------------------------------------

# 32. Example End-to-End Scenario

Imagine a developer joins a large banking application.

They ask:

> "How does a trade move from order submission to settlement?"

CodeStory responds:

``` text
Trade API
   ↓
Order Controller
   ↓
Order Service
   ↓
Validation
   ↓
Risk Engine
   ↓
Order Repository
   ↓
Trade Database
   ↓
Event Publisher
   ↓
Settlement Queue
   ↓
Settlement Processor
```

The developer can then ask:

> "Where is risk validation implemented?"

CodeStory highlights the relevant class.

Then:

> "What database tables are affected?"

CodeStory shows the table relationships.

Then:

> "If I change the risk validation method, what could break?"

CodeStory generates an impact report.

This is the fundamental product experience.

------------------------------------------------------------------------

# 33. Future Runtime Intelligence

Eventually CodeStory should combine static and dynamic evidence.

Example:

``` text
Static Graph
     +
Runtime Trace
     +
Logs
     +
Metrics
     +
Git History
```

This allows CodeStory to distinguish:

> "This method can call X."

from:

> "This method actually called X in production during the last 24
> hours."

This would significantly increase the value of impact analysis and
debugging.

------------------------------------------------------------------------

# 34. Future AI Capabilities

Potential future capabilities:

### Architecture Q&A

> "Why was this service separated?"

### Modernization

> "What would be required to migrate this module?"

### Refactoring

> "How can this dependency cycle be removed?"

### Documentation

> "Generate architecture documentation."

### Onboarding

> "Create a two-hour learning path for this repository."

### Debugging

> "Why could this transaction fail?"

### Change planning

> "Create a change plan for adding support for a new order type."

### Code review

> "Explain the architectural impact of this pull request."

------------------------------------------------------------------------

# 35. The Ultimate Product Vision

The final form of CodeStory is not a visualization tool.

It is a:

# Software Intelligence Layer

It understands:

``` text
SOURCE CODE
     ↓
SEMANTIC GRAPH
     ↓
SYSTEM BEHAVIOR
     ↓
BUSINESS FLOWS
     ↓
RUNTIME BEHAVIOR
     ↓
ENGINEERING KNOWLEDGE
```

And lets developers interact with that understanding conversationally.

The long-term interface could simply be:

> "Show me everything I need to know before changing this component."

CodeStory should then produce:

-   Relevant architecture
-   Execution flow
-   Dependencies
-   Data flow
-   Tests
-   Runtime behavior
-   Historical context
-   Risks
-   Impact
-   Recommended validation

------------------------------------------------------------------------

# 36. Master Build Prompt

The following can be used as the master prompt for an AI development
team or coding agent.

------------------------------------------------------------------------

## MASTER BUILD PROMPT

You are building **CodeStory**, a developer productivity platform for
code comprehension.

### Product mission

Build a system that converts software repositories into an interactive
semantic model and explains them as understandable stories.

The core promise is:

> **Understand any codebase as a story, not a pile of files.**

### Primary MVP

Build a working system that can:

1.  Import a Java repository.
2.  Parse Java source.
3.  Build a semantic representation of:
    -   packages
    -   classes
    -   interfaces
    -   methods
    -   inheritance
    -   implementations
    -   method calls
    -   dependencies
4.  Generate a navigable dependency/call graph.
5.  Identify meaningful workflows.
6.  Generate a storyline explaining those workflows.
7.  Allow users to ask questions about the repository.
8.  Ground answers in repository evidence.
9.  Allow navigation from explanations directly to source.
10. Perform basic change-impact analysis.

### Required architecture

Use a modular architecture:

``` text
Repository
    ↓
Parser
    ↓
AST / Symbol Model
    ↓
Semantic Graph
    ↓
Analysis Engine
    ↓
Story Engine
    ↓
AI / Retrieval
    ↓
Evidence Layer
    ↓
API
    ↓
Interactive UI
```

### Product requirements

The UI must prioritize comprehension rather than showing an enormous
graph.

The main user journey should be:

``` text
Repository
    ↓
System Overview
    ↓
Workflow
    ↓
Storyline
    ↓
Detailed Component
    ↓
Source
    ↓
Impact Analysis
```

### AI requirements

Never rely on an LLM alone to infer repository structure.

Use deterministic analysis wherever possible.

AI should be used for:

-   summarization
-   explanation
-   question answering
-   story generation
-   reasoning over structured graph information

Every important AI-generated claim should have source evidence where
possible.

### Engineering requirements

-   Modular codebase
-   Testable components
-   Incremental indexing
-   Clear domain model
-   Provider-independent AI layer
-   Local-first development
-   Security-conscious architecture
-   Strong observability
-   Documentation maintained alongside implementation

### Development philosophy

Do not build a large graph visualization demo and call it complete.

The product must solve:

> "I have a codebase I don't understand. Help me understand how it
> works."

Optimize every feature for that goal.

### First milestone

Produce a functional V0.1 where a developer can:

``` text
Load Java repository
      ↓
Analyze repository
      ↓
See architecture
      ↓
Select workflow
      ↓
Read generated story
      ↓
Trace source
      ↓
Ask questions
      ↓
View impact
```

### Quality bar

The system should be useful on a real repository, not only a toy
example.

Build the architecture so that future support can include:

-   TypeScript
-   Python
-   C#
-   Kotlin
-   Go
-   SQL
-   DB2
-   Kafka
-   REST
-   Spring
-   Spring Batch
-   Runtime telemetry
-   Git history
-   CI/CD

### Final objective

Create a product that becomes the **semantic and reasoning layer over
software systems**.

The end state is:

> A developer can ask CodeStory how a system works, why it works that
> way, what depends on it, what will be affected by changing it, and
> what they should understand before making the change.

------------------------------------------------------------------------

# 37. Recommended Immediate Build Sequence

## Step 1 --- Build the Java analyzer

Start with:

-   Java files
-   packages
-   classes
-   methods
-   interfaces
-   inheritance
-   method calls

## Step 2 --- Build the graph model

Create a clean internal representation.

## Step 3 --- Build workflow detection

Identify likely entry points and meaningful call chains.

## Step 4 --- Build Story Engine

Convert graph paths into structured narratives.

## Step 5 --- Build evidence links

Connect each story element to source locations.

## Step 6 --- Build interactive UI

Focus on:

-   Storyline
-   Graph
-   Source
-   Search
-   Explain

## Step 7 --- Add AI

Use graph + source evidence as the retrieval foundation.

## Step 8 --- Add impact analysis

Determine what changes when a component changes.

## Step 9 --- Add VS Code integration

Bring the experience into the developer workflow.

## Step 10 --- Test against real enterprise repositories

Use representative Java systems with:

-   Multiple modules
-   Spring
-   Database access
-   Batch processing
-   Async events
-   Complex dependencies

------------------------------------------------------------------------

# 38. Project Decision Log

## Current decisions

### Decision 1

**Code comprehension** is the primary product category.

### Decision 2

The **Storyline** is the signature UX.

### Decision 3

The **Semantic Code Graph** is the technical foundation.

### Decision 4

AI is an interpretation and reasoning layer, not the source of truth.

### Decision 5

Evidence-backed explanations are mandatory for trustworthy enterprise
adoption.

### Decision 6

Java is the first primary language target.

### Decision 7

The architecture must support enterprise technologies such as SQL, DB2,
Spring, batch processing, and messaging.

### Decision 8

The product should support local/private deployment for sensitive source
code.

------------------------------------------------------------------------

# 39. Supersession Policy

This document is intended to be the project's master source of truth.

When future decisions change the architecture, product scope, UX, or
roadmap:

1.  Update the current decision.
2.  Record the previous decision as superseded.
3.  Record the reason for the change.
4.  Record the date/version.
5.  Do not silently mix old and new requirements.

Suggested format:

``` text
## Superseded Decision

Previous:
...

New:
...

Reason:
...

Date:
...

Version:
...
```

This ensures the product documentation remains internally consistent as
implementation progresses.

------------------------------------------------------------------------

# 40. Final Product Statement

## CodeStory

**Understand any codebase as a story, not a pile of files.**

CodeStory turns source code into a semantic model, transforms that model
into understandable workflows, and gives developers an evidence-backed
way to explore, explain, trace, and safely change complex software.

The product starts as a code comprehension tool.

It can evolve into a complete **Software Intelligence Platform**.
