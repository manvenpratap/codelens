# Structural Integrity & Audits

CodeLens provides automated static verification of architecture boundaries and code health through two complementary engines:
1. **The Structural Integrity Engine (14 Active Class Rules)**: Audits architectural boundaries, entity contracts, and semantic drift during Phase 6 of the scan pipeline.
2. **The 32-Rule Static Review Auditor**: Deep code quality, security, and concurrency inspections.

---

## 🛡️ 1. The 14 Active Class Rules

Executed automatically during Phase 6 (`INTEGRITY`) of every scan, these rules detect subtle architectural decay:

| # | Rule Name | Category | Description & Contract | Severity |
|---|---|---|---|---|
| 1 | **DTO Encapsulation** | Boundary | Request/Response Message Objects (`MO_*`) must never access persistence DAOs or repository interfaces. | 🔴 High |
| 2 | **Repository Isolation** | Layering | Repositories and DAOs must only be invoked from domain services, never directly from controllers. | 🔴 High |
| 3 | **Controller Boundary** | API Contract | Controllers must not contain raw SQL, multi-step transaction mutations, or business calculations. | 🟡 Moderate |
| 4 | **Service Layer Integrity** | Architecture | Domain services must implement consistent transaction boundaries and logging sinks. | 🟡 Moderate |
| 5 | **Entity Contract Symmetry** | Reliability | Persistent entities must declare symmetric `equals()` and `hashCode()` implementations. | 🟡 Moderate |
| 6 | **Immutable Value Objects** | DDD Pattern | Classes designated as Value Objects must have `final` fields and no mutating setters. | 🟢 Low |
| 7 | **Interface Segregation** | SOLID Principles | Interfaces exceeding 15 method declarations are flagged for segregation into focused contracts. | 🟡 Moderate |
| 8 | **Dead Storage Methods** | Reachability | DAO and repository methods with zero incoming call edges across the entire repository. | 🟢 Low |
| 9 | **Signature Divergence** | Consistency | Classes with near-identical names but divergent method parameter signatures (Levenshtein distance $\le 2$). | 🟡 Moderate |
| 10 | **AST Body Clones** | Maintainability | Duplicate AST method body hashes across non-inherited class implementations. | 🟡 Moderate |
| 11 | **Exception Swallowing** | Error Safety | Catch blocks catching `Exception` or `Throwable` that neither log nor re-throw. | 🔴 High |
| 12 | **Circular Module Coupling** | Coupling | Tightly coupled packages violating acyclic dependency graph principles. | 🔴 High |
| 13 | **Unsynchronized Mutations** | Concurrency | Multithreaded components mutating shared non-thread-safe collection instances. | 🔴 High |
| 14 | **Naming Convention Drift** | Conventions | Classes violating configured domain prefixes or semantic archetype conventions. | 🟢 Low |

---

## 🔍 2. The 32-Rule Static Code Auditor

Accessible via the **Code Review** tab (<kbd>3</kbd>), this engine performs deep AST pattern analysis:
* **Correctness**: Null pointer hazards, unclosed resource streams, switch fallthroughs, mutable array exposure.
* **Concurrency**: Non-atomic state mutations, volatile field misuse, double-checked locking hazards.
* **Exception Safety**: Swallowed errors, raw runtime exception throws, throwing in `finally` blocks.
* **Code Smells**: God classes ($LOC > 1000$), excessive parameters ($> 5$), high cyclomatic complexity ($CC > 15$).
* **API Contracts**: Broken equals/hashCode symmetry, missing interface contracts.
* **Blast Radius**: Core utility methods with extreme upstream caller fan-in.
