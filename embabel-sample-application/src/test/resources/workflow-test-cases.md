# Embabel Workflow Visualizer — Test Case Reference

This document defines the **expected graph structure** for every agent in the sample
application.  It serves as the ground truth when visually verifying the workflow
visualizer against a running instance of the sample app.

Each test case lists:
- **Nodes**: every step that must appear (name + visualizer type label)
- **Edges**: the directed connections that must be drawn between nodes
- **Legend** at the bottom explains node colours and edge styles used in the HTML UI

---

## Legend

| Concept | Visualizer type | Colour |
|---|---|---|
| `@Action` | `Action` | Cyan |
| `@Action` + `@AchievesGoal` | `AchievesGoal` | Green border on Cyan card |
| `@Condition` | `Condition` | Yellow |
| `@Cost` method | `Cost` | Pink |

Edge colours:
- **Orange** – type-flow edge (output type of A matches input type of B)
- **Yellow** – pre-condition edge (A must be satisfied before B runs)
- **Green** – post-condition edge (A produces a condition consumed by B)

---

## Agent 1 — FraudDetectionAgent

**Planner**: GOAP (default)
**Pattern**: Linear pipeline
**Entry type**: `TransactionRequest`
**Goal type**: `FraudDecision`

### Expected Nodes

| Name | Visualizer Type | Input(s) | Output |
|---|---|---|---|
| `enrichContext` | `Action` | `TransactionRequest` | `TransactionContext` |
| `screenForPatterns` | `Action` | `TransactionContext` | `PatternScreening` |
| `decideFraud` | `AchievesGoal` | `PatternScreening` | `FraudDecision` |

### Expected Edges

| From | To | Kind | Label |
|---|---|---|---|
| `enrichContext` | `screenForPatterns` | type | `TransactionContext` |
| `screenForPatterns` | `decideFraud` | type | `PatternScreening` |

### Graph (ASCII)

```
[enrichContext]  →TransactionContext→  [screenForPatterns]  →PatternScreening→  [decideFraud ✓]
```

### Notes

- Pure linear flow — no conditions, no branching, no @Cost methods.
- `enrichContext` uses `readOnly = true` which is informational only; no graph impact.
- All three nodes should be in the same horizontal lane (3 columns).

### Detailed Execution Flow

1. **Input arrives** — `POST /api/fraud/screen` accepts a `TransactionRequest` (transactionId,
   accountId, amount, merchantName, merchantCategory, location, timestamp). The controller
   places it on the blackboard as the starting fact.
2. **GOAP planning** — the planner sees a goal-producer (`decideFraud` → `FraudDecision`)
   and works backwards: `decideFraud` needs `PatternScreening`; `screenForPatterns` needs
   `TransactionContext`; `enrichContext` needs `TransactionRequest` (already present). A
   single linear plan is selected.
3. **`enrichContext` runs** — pure Java derivation (no LLM). Computes three boolean signals:
   `highValueTransaction` (amount > 500), `crossBorderTransaction` (location not Switzerland/CH),
   `unusualHour` (Instant parsed → UTC hour 01:00–05:00). `readOnly = true` declares the step
   side-effect-free, allowing the platform to cache/replay it during retries.
4. **`screenForPatterns` runs** — first LLM call. The prompt embeds raw fields plus the
   derived booleans and asks for `riskScore` (0.0–1.0), `detectedPatterns` (label list), and
   a one-sentence `riskRationale`. Output is parsed via `creating(PatternScreening.class)`.
5. **`decideFraud` runs** (`@AchievesGoal`) — second LLM call. The prompt encodes the
   threshold rules: ≤0.25 → APPROVE, ≥0.75 → BLOCK, otherwise MANUAL_REVIEW. Returns
   `FraudDecision` which satisfies the goal and ends the run.
6. **No conditions, no @Cost** — every action runs once; cost defaults to 1.0; the planner
   has no branching choice.

### Test Scenarios

| ID | Description | Observed Result |
|---|---|---|
| TXN-001 | Low-risk domestic transaction | decision: `APPROVE` |
| TXN-002 | High-risk cross-border transaction (stolen card pattern) | decision: `BLOCK` |
| TXN-003 | Borderline unusual transaction | decision: `REVIEW` |

---

## Agent 2 — KycVerificationAgent

**Planner**: GOAP (default)
**Pattern**: Condition-driven branching
**Entry type**: `KycRequest`
**Goal type**: `KycAssessment`

### Expected Nodes

| Name | Visualizer Type | Input(s) | Output |
|---|---|---|---|
| `screenCustomer` | `Action` | `KycRequest` | `KycScreening` |
| `requiresEnhancedDueDiligence` | `Condition` | `KycScreening` | `boolean` |
| `canAssessDirectly` | `Condition` | `KycScreening` | `boolean` |
| `collectEnhancedDueDiligence` | `Action` | `KycScreening` | `EnhancedDueDiligenceReview` |
| `assessDirectRisk` | `AchievesGoal` | `KycScreening` | `KycAssessment` |
| `assessRiskWithEnhancedDueDiligence` | `AchievesGoal` | `KycScreening`, `EnhancedDueDiligenceReview` | `KycAssessment` |

### Expected Edges

| From | To | Kind | Label |
|---|---|---|---|
| `screenCustomer` | `requiresEnhancedDueDiligence` | type | `KycScreening` |
| `screenCustomer` | `canAssessDirectly` | type | `KycScreening` |
| `screenCustomer` | `collectEnhancedDueDiligence` | type | `KycScreening` |
| `screenCustomer` | `assessDirectRisk` | type | `KycScreening` |
| `screenCustomer` | `assessRiskWithEnhancedDueDiligence` | type | `KycScreening` |
| `screenCustomer` | `collectEnhancedDueDiligence` | pre | `requiresEnhancedDueDiligence` |
| `screenCustomer` | `assessDirectRisk` | pre | `canAssessDirectly` |
| `screenCustomer` | `assessRiskWithEnhancedDueDiligence` | pre | `requiresEnhancedDueDiligence` |
| `requiresEnhancedDueDiligence` | `collectEnhancedDueDiligence` | pre | `requiresEnhancedDueDiligence` |
| `requiresEnhancedDueDiligence` | `assessRiskWithEnhancedDueDiligence` | pre | `requiresEnhancedDueDiligence` |
| `canAssessDirectly` | `assessDirectRisk` | pre | `canAssessDirectly` |
| `collectEnhancedDueDiligence` | `assessRiskWithEnhancedDueDiligence` | type | `EnhancedDueDiligenceReview` |

### Graph (ASCII)

```
                    ┌─[requiresEnhancedDueDiligence] ──── (pre) ────→ [collectEnhancedDueDiligence] ──→ [assessRiskWithEnhancedDueDiligence ✓]
[screenCustomer] ──→┤                                                                                 ↗
                    └─[canAssessDirectly]             ──── (pre) ────→ [assessDirectRisk ✓]
```

### Notes

- `screenCustomer.post = [requiresEnhancedDueDiligence, canAssessDirectly]` drives the
  condition routing.
- Both `assessDirectRisk` and `assessRiskWithEnhancedDueDiligence` carry `@AchievesGoal`
  — both branches lead to the same goal type (`KycAssessment`).

### Detailed Execution Flow

1. **Input arrives** — `POST /api/kyc/verify` posts a `KycRequest` (fullName, dateOfBirth,
   nationality, occupation, sourceOfFunds). `customerId` is **deliberately omitted** from
   any LLM prompt — only business data is exposed.
2. **`screenCustomer` runs** (LLM) — checks the customer against PEP lists, sanctions
   databases, and adverse media. Returns `KycScreening { pepMatch, sanctionsMatch,
   adverseMediaMatch, screeningSummary, ... }`. `post = [REQUIRES_EDD, CAN_ASSESS_DIRECTLY]`
   announces to the planner that the two branch conditions become evaluable after this step.
3. **Condition evaluation** — the planner invokes both `@Condition` methods on the
   `KycScreening` result:
   - `requiresEnhancedDueDiligence` = `!sanctionsMatch && (pepMatch || adverseMediaMatch)`
   - `canAssessDirectly` = `sanctionsMatch || (!pepMatch && !adverseMediaMatch)`
   These are mutually exclusive given the rules: a sanctions hit goes direct; a PEP or
   adverse-media-only case goes EDD; clean cases go direct.
4. **Branch A — direct path** (`canAssessDirectly == true`)
   - `assessDirectRisk` runs (`@AchievesGoal`, `pre = [CAN_ASSESS_DIRECTLY]`).
   - Prompt encodes the rule table: sanctions → CRITICAL/REJECT, PEP → HIGH/EDD,
     adverse-only → MEDIUM/EDD, clean → LOW/APPROVE.
   - Returns `KycAssessment` and ends the run.
5. **Branch B — enhanced due diligence path** (`requiresEnhancedDueDiligence == true`)
   - `collectEnhancedDueDiligence` runs (`pre = [REQUIRES_EDD]`) — second LLM call producing
     `EnhancedDueDiligenceReview { reviewSummary, recommendedControls,
     complianceEscalationRequired }`.
   - `assessRiskWithEnhancedDueDiligence` runs (`@AchievesGoal`, `pre = [REQUIRES_EDD]`) —
     consumes both `KycScreening` AND `EnhancedDueDiligenceReview`. Escalation flag from
     EDD elevates the recommendation to ESCALATE_TO_COMPLIANCE.
6. **Goal satisfied** — whichever branch was taken, `KycAssessment` is on the blackboard
   and the run completes. The two `@AchievesGoal` actions both produce the same
   goal type, so the planner is free to pick whichever has a satisfiable precondition.

### Test Scenarios

| ID | Description | Path | Observed Result |
|---|---|---|---|
| KYC-001 | Low-risk customer, no PEP/sanctions | `canAssessDirectly` → `assessDirectRisk` | riskLevel: LOW, eddRequired: false |
| KYC-002 | High-risk PEP customer | `requiresEnhancedDueDiligence` → `collectEnhancedDueDiligence` → `assessRiskWithEnhancedDueDiligence` | riskLevel: HIGH, eddRequired: true |

---

## Agent 3 — SentimentAnalysisAgent

**Planner**: GOAP (default)
**Pattern**: Linear pipeline with dynamic `@Cost` methods
**Entry type**: `FeedbackRequest`
**Goal type**: `FeedbackResponse`

### Expected Nodes

| Name | Visualizer Type | Input(s) | Output |
|---|---|---|---|
| `quickClassify` | `Action` | `FeedbackRequest` | `SentimentClassification` |
| `deepAnalysisCost` | `Cost` | `SentimentClassification` | `double` |
| `deepAnalyze` | `Action` | `SentimentClassification`, `FeedbackRequest` | `SentimentInsight` |
| `responseCost` | `Cost` | `SentimentInsight` | `double` |
| `respondToCustomer` | `AchievesGoal` | `SentimentInsight`, `SentimentClassification`, `FeedbackRequest` | `FeedbackResponse` |

### Expected Edges

| From | To | Kind | Label |
|---|---|---|---|
| `quickClassify` | `deepAnalyze` | type | `SentimentClassification` |
| `quickClassify` | `deepAnalysisCost` | type | `SentimentClassification` |
| `quickClassify` | `respondToCustomer` | type | `SentimentClassification` |
| `deepAnalyze` | `respondToCustomer` | type | `SentimentInsight` |
| `deepAnalyze` | `responseCost` | type | `SentimentInsight` |
| `deepAnalysisCost` | `deepAnalyze` | cost | `costMethod` |
| `responseCost` | `respondToCustomer` | cost | `costMethod` |

### Graph (ASCII)

```
                       [deepAnalysisCost] ──(cost)──→ [deepAnalyze] ──→ [responseCost] ──(cost)──→ [respondToCustomer ✓]
[quickClassify] ──→────────────────────────────────→────────────────→──────────────────────────→
```

### Notes

- Cost-method edges are drawn via the `costMethod` field in `WorkflowStep` (populated
  from `@Action(costMethod = ...)`) and the `costProd` map in the frontend.
- `deepAnalysisCost` and `responseCost` appear as `Cost` type nodes (pink) with dashed
  cost edges to the actions that reference them.

### Detailed Execution Flow

1. **Input arrives** — `POST /api/sentiment/analyze` accepts a `FeedbackRequest`
   (feedbackId, customerName, productName, channel, feedbackText).
2. **`quickClassify` runs** (LLM, **static cost = 1.0** — the cheapest variant of cost
   declaration). Produces `SentimentClassification { sentiment, score (-1..+1),
   urgentIssueDetected, classificationRationale }`.
3. **Dynamic cost evaluation for `deepAnalyze`** — the planner invokes
   `@Cost(name="deepAnalysisCost")` → `computeDeepAnalysisCost(SentimentClassification)`.
   Returns **5.0** when `urgentIssueDetected || score < -0.5`, otherwise **2.0**. The
   planner uses this to weigh whether the deep-analysis step is worth running.
4. **`deepAnalyze` runs** (LLM, `costMethod = "deepAnalysisCost"`) — synthesises
   `SentimentInsight { rootCauseTopics, emotionalTone, suggestedAction, internalNotes }`.
   Even for positive feedback this currently runs (the planner takes the only available
   path to the goal); the cost only matters when alternatives exist.
5. **Dynamic cost evaluation for `respondToCustomer`** — `@Cost(name="responseCost")` →
   `computeResponseCost(SentimentInsight)`. Returns **4.0** if `suggestedAction` contains
   "escalat", otherwise **1.5**.
6. **`respondToCustomer` runs** (`@AchievesGoal`, LLM, `costMethod = "responseCost"`) —
   consumes ALL three intermediate facts (`SentimentInsight`, `SentimentClassification`,
   `FeedbackRequest`) so the response prompt has full context. Produces `FeedbackResponse
   { customerMessage, recommendedAction, escalated, teamNotes }`.
7. **Visualizer note** — `deepAnalysisCost` and `responseCost` appear as pink Cost nodes
   with dashed edges to their owning actions; they are not part of the data-flow chain.

### Test Scenarios

| ID | Description | Path | Observed Result |
|---|---|---|---|
| FB-001 | Positive customer feedback | `quickClassify` → `respondToCustomer` (auto-respond) | Positive sentiment, response generated |
| FB-002 | Negative/angry customer complaint | `quickClassify` → `deepAnalyze` → `respondToCustomer` | Negative sentiment, escalation response |

---

## Agent 4 — DocumentProcessingAgent

**Planner**: GOAP (default)
**Pattern**: Linear pipeline with default-producer action and advanced annotations
**Entry type**: `DocumentRequest`
**Goal type**: `DocumentSummary`

### Expected Nodes

| Name | Visualizer Type | Input(s) | Output | Annotation details |
|---|---|---|---|---|
| `preprocessDocument` | `Action` | `DocumentRequest` | `CleanDocument` | `clearBlackboard=true`, `outputBinding="cleanDoc"` |
| `provideDefaultMetadataHints` | `Action` | `DocumentRequest` | `MetadataHints` | Default-producer: runs only when `MetadataHints` not supplied by caller |
| `extractMetadata` | `Action` | `CleanDocument`, `MetadataHints` | `DocumentMetadata` | `value=0.3` |
| `analyzeContent` | `Action` | `CleanDocument`, `DocumentMetadata` | `ContentAnalysis` | `pre=spel:cleanDoc.wordCount > 50`, `canRerun=true` |
| `summarizeDocument` | `AchievesGoal` | `CleanDocument`, `DocumentMetadata`, `ContentAnalysis` | `DocumentSummary` | `@Export(remote=true)` |

### Expected Edges

| From | To | Kind | Label |
|---|---|---|---|
| `preprocessDocument` | `provideDefaultMetadataHints` | type | `DocumentRequest` |
| `preprocessDocument` | `extractMetadata` | type | `CleanDocument` |
| `preprocessDocument` | `analyzeContent` | type | `CleanDocument` |
| `preprocessDocument` | `summarizeDocument` | type | `CleanDocument` |
| `provideDefaultMetadataHints` | `extractMetadata` | type | `MetadataHints` |
| `extractMetadata` | `analyzeContent` | type | `DocumentMetadata` |
| `extractMetadata` | `summarizeDocument` | type | `DocumentMetadata` |
| `analyzeContent` | `summarizeDocument` | type | `ContentAnalysis` |

### Graph (ASCII)

```
[preprocessDocument] ──→ [provideDefaultMetadataHints] ──MetadataHints──→ ─┐
                                                                             ├──→ [extractMetadata] ──→ [analyzeContent] ──→ [summarizeDocument ✓]
                     (CleanDocument) ──────────────────────────────────────→ ─┘
```

### Notes

- `provideDefaultMetadataHints` is a **default-producer action**: it creates `MetadataHints`
  when the caller did not supply them.  The GOAP planner includes it in the plan when
  `MetadataHints` is absent from the blackboard; it is skipped when the caller already
  provides hints (DOC-002 path).
- `extractMetadata` requires both `CleanDocument` and `MetadataHints`.  This firm
  dependency replaced the previous `@Nullable MetadataHints` parameter pattern.
- The SpEL pre-condition `spel:cleanDoc.wordCount > 50` on `analyzeContent` is shown
  in the node detail (`pre` field) but no upstream step produces a condition with that
  name, so **no pre-condition edge is drawn for it** — the SpEL expression is
  evaluated at runtime by the framework.
- `Ai` parameter type is a framework type; it is filtered out and not shown as an input.

### Detailed Execution Flow

1. **Input arrives** — `POST /api/document/process` posts a `DocumentRequest` (documentId,
   title, rawContent, language, optional caller-supplied `MetadataHints`).
2. **`preprocessDocument` runs** (pure Java, no LLM) — trims, collapses whitespace, drops
   blank lines, counts words. Produces `CleanDocument { documentId, title,
   normalizedContent, language, wordCount }`. Annotation details: `clearBlackboard=true`
   (resets per-call state) and `outputBinding="cleanDoc"` so the named binding is
   available to SpEL expressions later.
3. **Optional default-producer step** — `provideDefaultMetadataHints` runs **only when
   `MetadataHints` is not already on the blackboard**. GOAP encodes this as an
   auto-generated precondition `it:MetadataHints = FALSE`. When the caller supplies hints
   (DOC-002), GOAP proves the precondition false and the step is skipped from the plan
   entirely. When absent (DOC-001), GOAP includes it; it produces a default
   `MetadataHints(null, null, false)`.
4. **`extractMetadata` runs** (LLM via injected `Ai` — lighter than `OperationContext`,
   `value = 0.3`) — consumes both `CleanDocument` AND the now-guaranteed `MetadataHints`.
   Produces `DocumentMetadata { detectedLanguage, primaryTopic, keywords,
   estimatedReadingTime }`. The `value` annotation is informational metadata for the
   visualizer; GOAP ignores it (Utility planners would use it).
5. **`analyzeContent` runs** (LLM, `canRerun = true`) — consumes `CleanDocument` and
   `DocumentMetadata`. **Internal short-circuit**: if `cleanDoc.wordCount() <= 50`,
   returns a stub `ContentAnalysis` without calling the LLM (the SpEL `pre =
   spel:cleanDoc.wordCount > 50` is shown in the node's pre-list but is **not** drawn as
   a graph edge — no upstream condition with that name exists). `canRerun=true` means
   the planner may schedule this step again if new data arrives later.
6. **`summarizeDocument` runs** (`@AchievesGoal`, LLM via `Ai`) — consumes
   `CleanDocument`, `DocumentMetadata`, AND `ContentAnalysis`. Produces `DocumentSummary
   { summary, keyPoints, recommendedAction, confidenceScore }`. The goal annotation
   carries the full payload: `value = 0.9`, `tags = [document, summarization, nlp]`,
   `examples = ["Summarise my quarterly report", ...]`, and `@Export(remote=true,
   name="summarizeDocument", startingInputTypes={DocumentRequest.class})` which
   publishes this goal as an MCP tool callable by external clients.
7. **Visualizer behaviour** — `Ai` and `OperationContext` parameter types are filtered
   out by `FRAMEWORK_PARAMETER_TYPES`, so they never appear as graph inputs.

### Test Scenarios

| ID | Description | Observed Result |
|---|---|---|
| DOC-001 | Q1 Sales Report — no caller hints | `provideDefaultMetadataHints` runs; summary produced |
| DOC-002 | Kubernetes Security Guide — caller provides hints | `provideDefaultMetadataHints` skipped; `extractMetadata` uses caller hints |
| DOC-003 | Short note (< 50 words) | `analyzeContent` skipped by SpEL guard; short summary produced |
| DOC-004 | German sustainability report | Language processed; German-language summary returned |

---

## Agent 5 — LoanApplicationAgent

**Planner**: GOAP (default)
**Pattern**: Branching with static `cost=` on `@Action`
**Entry type**: `LoanRequest`
**Goal type**: `LoanDecision`

### Expected Nodes

| Name | Visualizer Type | Input(s) | Output | Cost |
|---|---|---|---|---|
| `analyzeCreditProfile` | `Action` | `LoanRequest` | `CreditAnalysis` | `cost=2.0` |
| `canAutoDecide` | `Condition` | `CreditAnalysis` | `boolean` | — |
| `requiresUnderwriting` | `Condition` | `CreditAnalysis` | `boolean` | — |
| `makeAutoDecision` | `AchievesGoal` | `CreditAnalysis`, `LoanRequest` | `LoanDecision` | `cost=1.0` |
| `conductUnderwriting` | `Action` | `CreditAnalysis` | `UnderwritingAssessment` | `cost=5.0` |
| `makeUnderwrittenDecision` | `AchievesGoal` | `CreditAnalysis`, `LoanRequest`, `UnderwritingAssessment` | `LoanDecision` | `cost=2.0` |

### Expected Edges

| From | To | Kind | Label |
|---|---|---|---|
| `analyzeCreditProfile` | `canAutoDecide` | type | `CreditAnalysis` |
| `analyzeCreditProfile` | `requiresUnderwriting` | type | `CreditAnalysis` |
| `analyzeCreditProfile` | `makeAutoDecision` | type | `CreditAnalysis` |
| `analyzeCreditProfile` | `conductUnderwriting` | type | `CreditAnalysis` |
| `analyzeCreditProfile` | `makeUnderwrittenDecision` | type | `CreditAnalysis` |
| `analyzeCreditProfile` | `makeAutoDecision` | pre | `canAutoDecide` |
| `analyzeCreditProfile` | `conductUnderwriting` | pre | `requiresUnderwriting` |
| `canAutoDecide` | `makeAutoDecision` | pre | `canAutoDecide` |
| `requiresUnderwriting` | `conductUnderwriting` | pre | `requiresUnderwriting` |
| `conductUnderwriting` | `makeUnderwrittenDecision` | type | `UnderwritingAssessment` |

### Graph (ASCII)

```
                    ┌─[canAutoDecide]        ──(pre)──→ [makeAutoDecision ✓]
[analyzeCreditProfile] ──→┤
                    └─[requiresUnderwriting] ──(pre)──→ [conductUnderwriting] ──→ [makeUnderwrittenDecision ✓]
```

### Notes

- `makeAutoDecision.pre = [canAutoDecide]` and `conductUnderwriting.pre = [requiresUnderwriting]`
- `makeUnderwrittenDecision` takes `CreditAnalysis`, `LoanRequest`, and `UnderwritingAssessment`
  (`LoanRequest` remains on the blackboard throughout the plan).

### Detailed Execution Flow

1. **Input arrives** — `POST /api/loan/apply` posts a `LoanRequest` (fullName, requested
   amount, annualIncome, employmentStatus, creditScore, existingMonthlyDebt, loanTermMonths,
   loanPurpose).
2. **`analyzeCreditProfile` runs** (LLM, `cost = 2.0`, `post = [CAN_AUTO_DECIDE,
   REQUIRES_UNDERWRITING]`) — produces `CreditAnalysis { applicantId, debtToIncomeRatio,
   affordabilityScore, creditRiskCategory, riskFactors, analysisSummary }`. The `post`
   declaration informs the planner that both branch conditions are now evaluable.
3. **Condition evaluation** — pure Java predicates over `CreditAnalysis`:
   - `canAutoDecide` = `EXCELLENT || GOOD || VERY_POOR || (POOR && affordabilityScore < 30)`
   - `requiresUnderwriting` = `FAIR || (POOR && affordabilityScore >= 30)`
   These are mutually exclusive; exactly one branch fires per run.
4. **Branch A — auto decision** (cheaper path: total cost 2.0+1.0 = **3.0**)
   - `makeAutoDecision` runs (`@AchievesGoal`, `cost = 1.0`, `pre = [CAN_AUTO_DECIDE]`).
   - Consumes `CreditAnalysis` + `LoanRequest`. EXCELLENT/GOOD → APPROVED with suggested
     amount and rate; VERY_POOR or POOR-low-affordability → REJECTED with nulls.
5. **Branch B — underwritten decision** (expensive path: total cost 2.0+5.0+2.0 = **9.0**)
   - `conductUnderwriting` runs (`cost = 5.0`, `pre = [REQUIRES_UNDERWRITING]`) — LLM-driven
     manual underwriting. Produces `UnderwritingAssessment { employmentStabilityNotes,
     repaymentCapacityNotes, mitigatingFactors, concerns, approvableWithConditions }`.
   - `makeUnderwrittenDecision` runs (`@AchievesGoal`, `cost = 2.0`,
     `pre = [REQUIRES_UNDERWRITING]`) — consumes `CreditAnalysis`, `LoanRequest`, AND
     `UnderwritingAssessment`. Decision options: APPROVED | REJECTED | REFERRED.
6. **Why the costs matter** — both `@AchievesGoal` actions produce the same `LoanDecision`
   goal type. If conditions allowed both branches simultaneously, the GOAP planner would
   pick the cheaper plan (3.0 vs 9.0). The `@Condition` mutual exclusivity ensures only
   one is reachable per input.

### Test Scenarios

| ID | Description | Path | decision |
|---|---|---|---|
| APP-2001 | Excellent profile (score 820, low debt, stable income) | `canAutoDecide` → `makeAutoDecision` | `APPROVED` |
| APP-2002 | Poor profile (score 520, high debt, irregular income) | `canAutoDecide` → `makeAutoDecision` | `REJECTED` |
| APP-2003 | Borderline (score 660, moderate debt) | `requiresUnderwriting` → `conductUnderwriting` → `makeUnderwrittenDecision` | `APPROVED` (by underwriting) |

---

## Agent 6 — ContentModerationAgent

**Planner**: GOAP (default)
**Pattern**: Converging branches — both branches produce the same type, single `@AchievesGoal`
**Entry type**: `ContentRequest`
**Goal type**: `ModerationDecision`

### Expected Nodes

| Name | Visualizer Type | Input(s) | Output |
|---|---|---|---|
| `analyzeContent` | `Action` | `ContentRequest` | `ContentAnalysis` |
| `isClear` | `Condition` | `ContentAnalysis` | `boolean` |
| `isFlagged` | `Condition` | `ContentAnalysis` | `boolean` |
| `autoTag` | `Action` | `ContentAnalysis` | `TaggedContent` |
| `deepReview` | `Action` | `ContentAnalysis` | `TaggedContent` |
| `recordDecision` | `AchievesGoal` | `TaggedContent` | `ModerationDecision` |

### Expected Edges

| From | To | Kind | Label |
|---|---|---|---|
| `analyzeContent` | `isClear` | type | `ContentAnalysis` |
| `analyzeContent` | `isFlagged` | type | `ContentAnalysis` |
| `analyzeContent` | `autoTag` | type | `ContentAnalysis` |
| `analyzeContent` | `deepReview` | type | `ContentAnalysis` |
| `analyzeContent` | `autoTag` | pre | `isClear` |
| `analyzeContent` | `deepReview` | pre | `isFlagged` |
| `isClear` | `autoTag` | pre | `isClear` |
| `isFlagged` | `deepReview` | pre | `isFlagged` |
| `autoTag` | `recordDecision` | type | `TaggedContent` |
| `deepReview` | `recordDecision` | type | `TaggedContent` |

### Graph (ASCII)

```
                     ┌─[isClear]   ──(pre)──→ [autoTag]    ──→ ─┐
[analyzeContent] ──→─┤                                           ├──→ [recordDecision ✓]
                     └─[isFlagged] ──(pre)──→ [deepReview]  ──→ ─┘
```

### Notes

- Both `autoTag` and `deepReview` output `TaggedContent` — the planner chooses one
  branch based on which condition is met.  The single `recordDecision` step can receive
  `TaggedContent` from either branch.
- `recordDecision` is the single `@AchievesGoal` action.

### Detailed Execution Flow

1. **Input arrives** — `POST /api/moderation/evaluate` posts `ContentRequest` (contentId,
   platform, contentType, contentText).
2. **`analyzeContent` runs** (LLM, `post = [IS_CLEAR, IS_FLAGGED]`) — produces
   `ContentAnalysis { toxicityScore, violatedPolicies, clearViolation,
   potentiallyAmbiguous, analysisSummary }`.
3. **Condition evaluation** — predicates over the toxicity score:
   - `isClear` = `!potentiallyAmbiguous && (toxicityScore <= 0.2 || >= 0.8)` — the
     decision is unambiguous (clearly safe OR clearly violating).
   - `isFlagged` = `potentiallyAmbiguous || (0.2 < toxicityScore < 0.8)` — borderline
     or context-dependent content needing deeper review.
4. **Branch A (fast)** — `autoTag` runs (`pre = [IS_CLEAR]`, LLM). Returns `TaggedContent`
   with `autoTagged = true` (the constructor wraps the LLM result to set this flag).
   `recommendedAction` is APPROVE for safe, REMOVE for clearly violating.
5. **Branch B (slow)** — `deepReview` runs (`pre = [IS_FLAGGED]`, LLM with a richer
   contextual prompt about intent and reasonable-user perception). Returns `TaggedContent`
   with `autoTagged = false`. `recommendedAction` may be FLAG_FOR_REVIEW.
6. **Convergence** — both branches produce the **same type** `TaggedContent`. The planner
   sees one type-flow input for `recordDecision` regardless of which branch ran. The
   `autoTagged` boolean carries the branch identity forward.
7. **`recordDecision` runs** (`@AchievesGoal`, LLM) — consumes `TaggedContent` only. No
   `pre` needed — type-presence is sufficient. Produces `ModerationDecision { action,
   reason, appealable, appliedPolicies }`.

### Test Scenarios

| ID | Description | Path | action |
|---|---|---|---|
| POST-8001 | Clean product review | `isClear` → `autoTag` → `recordDecision` | `APPROVE` |
| POST-8002 | Hate speech / harassment content (toxicity 0.85, clearViolation=true) | `isClear` → `autoTag` → `recordDecision` | `REMOVE` |
| POST-8003 | Ambiguous dark humour (potentiallyAmbiguous=true) | `isFlagged` → `deepReview` → `recordDecision` | `FLAG_FOR_REVIEW` |

---

## Agent 7 — ResumeScreeningAgent

**Planner**: GOAP (default)
**Pattern**: Fan-in — two independent branches converge
**Entry type**: `CandidateRequest`
**Goal type**: `HiringDecision`

### Expected Nodes

| Name | Visualizer Type | Input(s) | Output |
|---|---|---|---|
| `analyzeResume` | `Action` | `CandidateRequest` | `ResumeAnalysis` |
| `assessCultureFit` | `Action` | `CandidateRequest` | `CultureFitScore` |
| `makeHiringDecision` | `AchievesGoal` | `ResumeAnalysis`, `CultureFitScore` | `HiringDecision` |

### Expected Edges

| From | To | Kind | Label |
|---|---|---|---|
| `analyzeResume` | `makeHiringDecision` | type | `ResumeAnalysis` |
| `assessCultureFit` | `makeHiringDecision` | type | `CultureFitScore` |

### Graph (ASCII)

```
[analyzeResume]    ──→ ResumeAnalysis  ──→ ─┐
                                             ├──→ [makeHiringDecision ✓]
[assessCultureFit] ──→ CultureFitScore ──→ ─┘
```

### Notes

- No `@Condition` annotations — the GOAP planner determines execution order based on
  data dependencies.
- Both `analyzeResume` and `assessCultureFit` start from `CandidateRequest`.  They are
  independent; the planner may run them in any order (or in parallel).
- `makeHiringDecision` requires BOTH results; it will not run until both are on the
  blackboard.

### Detailed Execution Flow

1. **Input arrives** — `POST /api/recruitment/screen` posts `CandidateRequest` (fullName,
   position, department, resumeText, coverLetter).
2. **Two independent branches activate** — both `analyzeResume` and `assessCultureFit`
   declare `CandidateRequest` as their only domain input. Neither depends on the other,
   so the GOAP planner schedules both as eligible. The runtime may execute them in
   either order, or **in parallel** if the orchestrator supports concurrency.
3. **`analyzeResume` runs** (LLM) — focus is technical fit. Reads `resumeText`. Produces
   `ResumeAnalysis { coreSkills, yearsOfExperience, relevanceScore (0–100), gaps,
   analysisSummary }`.
4. **`assessCultureFit` runs** (LLM) — focus is values/culture. Reads `coverLetter`
   (with a graceful fallback when blank). Produces `CultureFitScore { fitScore,
   alignedValues, concerns, fitSummary }`.
5. **Fan-in: `makeHiringDecision` runs** (`@AchievesGoal`, LLM) — the planner waits for
   BOTH `ResumeAnalysis` and `CultureFitScore` to land on the blackboard. The action
   signature `(ResumeAnalysis, CultureFitScore, CandidateRequest, OperationContext)`
   declares the synchronisation point. Produces `HiringDecision { recommendation
   (INTERVIEW|HOLD|REJECT), feedback, nextSteps }`.
6. **No conditions** — purely data-driven planning. The graph shape is determined by
   the parameter types alone; no `@Condition` gates and no branching.

### Test Scenarios

| ID | Description | recommendation |
|---|---|---|
| CAND-5001 | Sophie Keller — 8 yrs Java, MSc ETH (strong match) | HOLD (further evaluation) |
| CAND-5002 | Tom Fischer — career switcher, 4 yrs Python, 1 yr Java (borderline) | REJECT |

---

## Agent 8 — TicketRoutingAgent

**Planner**: `PlannerType.UTILITY`
**Pattern**: Utility AI + `@State` interface routing
**Entry type**: `SupportTicket`
**Goal type**: `TicketResolution`

### Expected Nodes

#### Top-level agent methods

| Name | Visualizer Type | Input(s) | Output | Reference |
|---|---|---|---|---|
| `classificationValue` | `Cost` | `SupportTicket` | `double` | referenced by `classifyTicket.valueMethod` |
| `classifyTicket` | `Action` | `SupportTicket` | `TicketClassification` | `valueMethod="classificationValue"` |
| `routingValue` | `Cost` | `TicketClassification` | `double` | referenced by `routeToCategory.valueMethod` |
| `routeToCategory` | `Action` | `SupportTicket`, `TicketClassification` | `TicketCategory` | `valueMethod="routingValue"` |

#### @State inner records (discovered via classpath scan of `TicketCategory` subtypes)

| Name | Visualizer Type | Input(s) | Output | @State class |
|---|---|---|---|---|
| `handleBilling` | `AchievesGoal` | `BillingTicket` | `TicketResolution` | `BillingState(BillingTicket)` |
| `handleTechnical` | `AchievesGoal` | `TechnicalTicket` | `TicketResolution` | `TechnicalState(TechnicalTicket)` |
| `handleGeneral` | `AchievesGoal` | `GeneralTicket` | `TicketResolution` | `GeneralState(GeneralTicket)` |

### Expected Edges

| From | To | Kind | Label |
|---|---|---|---|
| `classifyTicket` | `routeToCategory` | type | `TicketClassification` |
| `classifyTicket` | `routingValue` | type | `TicketClassification` |
| `classificationValue` | `classifyTicket` | cost/value | `valueMethod` |
| `routingValue` | `routeToCategory` | cost/value | `valueMethod` |
| `routeToCategory` | `handleBilling` | type | `BillingTicket` (via `TicketCategory` → `BillingState` record component) |
| `routeToCategory` | `handleTechnical` | type | `TechnicalTicket` (via `TicketCategory` → `TechnicalState` record component) |
| `routeToCategory` | `handleGeneral` | type | `GeneralTicket` (via `TicketCategory` → `GeneralState` record component) |

### Graph (ASCII)

```
[classificationValue] ──(value)──→ [classifyTicket] ──→ [routingValue] ──(value)──→ [routeToCategory]
                                                   ↗                               ↗           │
(SupportTicket) ──────────────────────────────────┘                               │   BillingTicket→[handleBilling ✓]
                                        (TicketClassification) ────────────────────┘   TechnicalTicket→[handleTechnical ✓]
                                                                                        GeneralTicket→[handleGeneral ✓]
```

### Implementation Notes

- **Critical fix**: `routeToCategory` must return a `@State`-annotated interface
  (`TicketCategory`), **not** `Object`.  The framework's `AgentMetadataReader` calls
  `JvmType.children()` (a Spring classpath scan for subtypes) on the declared return
  type.  Scanning `Object` yields nothing useful; scanning `TicketCategory` discovers
  `BillingState`, `TechnicalState`, `GeneralState` — which are then registered as
  actions.
- `@State interface TicketCategory {}` is declared as a static nested interface inside
  `TicketRoutingAgent`.  The three routing records implement it:
  `BillingState(BillingTicket ticket) implements TicketCategory`, etc.
- `routeToCategory` returns one of the three wrapper records (`new BillingState(...)`,
  `new TechnicalState(...)`, or `new GeneralState(...)`), placing that record on the
  blackboard.
- `handleBilling`, `handleTechnical`, `handleGeneral` are methods declared **inside**
  the corresponding `@State` records.  The catalog service adds the record's component
  type (`BillingTicket`, `TechnicalTicket`, `GeneralTicket`) as an implicit input, so
  the visualizer shows them as `BillingTicket → TicketResolution`.
- Java records are implicitly static inner classes — they pass the `validateStateClass`
  check (which rejects non-static inner classes).

### Detailed Execution Flow

1. **Input arrives** — `POST /api/ticketing/route` posts a `SupportTicket` (ticketId,
   subject, description, channel, priority).
2. **Utility AI planner activates** — instead of GOAP's backward-chaining, the Utility
   planner repeatedly evaluates `value − cost` for every applicable action and selects
   the highest. `@Cost` annotations contribute the `value` term when referenced via
   `valueMethod`.
3. **`classificationValue` evaluated** (`@Cost`) — receives `@Nullable SupportTicket`.
   Maps priority to a value: critical=1.0, high=0.8, medium=0.6, low=0.4. With the
   ticket on the blackboard, classification is the highest-value applicable action.
4. **`classifyTicket` runs** (LLM, `valueMethod = "classificationValue"`) — produces
   `TicketClassification { category (billing|technical|general), subcategory,
   urgencyScore, classificationRationale }`.
5. **`routingValue` evaluated** (`@Cost`) — receives `@Nullable TicketClassification`.
   Returns `min(1.0, urgencyScore)` so urgent tickets jump the queue.
6. **`routeToCategory` runs** (pure Java, `valueMethod = "routingValue"`) — switches on
   `classification.category()` and returns one of `BillingState | TechnicalState |
   GeneralState`, all implementing the `@State` interface `TicketCategory`. The returned
   record is placed on the blackboard.
7. **State transition** — the framework's `AgentMetadataReader` discovered the three
   `@State` records via a classpath scan triggered by the `TicketCategory` return type.
   Methods declared inside the produced state record become eligible actions.
8. **Handler runs** (`@AchievesGoal` inside the chosen state record):
   - `BillingState.handleBilling` — implicit input is `BillingTicket` (the record
     component). Prompt template emphasises billing-team escalation when urgency > 0.7.
   - `TechnicalState.handleTechnical` — implicit input `TechnicalTicket`. Escalates to
     engineering-team when urgency > 0.8, else tier-2-support.
   - `GeneralState.handleGeneral` — implicit input `GeneralTicket`. Defaults to
     `auto-resolve` for standard FAQs.
   Each returns a `TicketResolution { ticketId, resolvedCategory, responseTemplate,
   suggestedActions, escalationTarget, autoResolvable }` and ends the run.
9. **Why this matters for the visualizer** — the `routeToCategory` return type
   **must** be `TicketCategory` (the `@State` interface), NOT `Object`. The classpath
   scan needs a typed root to discover subtypes; scanning `Object` would yield nothing
   useful. See "Implementation Notes" below.

### Test Scenarios

| ID | Description | resolvedCategory | autoResolvable | escalationTarget |
|---|---|---|---|---|
| TKT-1001 | Billing — double charge, critical | `billing` | `false` | `billing-team` |
| TKT-1002 | Technical — API 500 errors, high | `technical` | n/a | `engineering-team` |
| TKT-1003 | General — pricing question, low | `general` | `true` | `auto-resolve` |
| TKT-1004 | Billing — upgrade question, medium | `billing` | `true` | auto (resolvable) |
| TKT-1005 | Technical — null channel, critical | `technical` | n/a | `engineering-team` |
| TKT-1006 | General — feature request, low | `general` | `true` | `auto-resolve` |

---

## Agent 9 — ProductResearchAgent

**Planner**: `PlannerType.SUPERVISOR` (LLM-driven planning)
**Pattern**: Linear pipeline with SpEL precondition gate; cross-component action via `@EmbabelComponent`
**Entry type**: `ResearchRequest`
**Goal type**: `ResearchReport`

### Expected Nodes

| Name | Visualizer Type | Input(s) | Output | Source |
|---|---|---|---|---|
| `gatherMarketData` | `Action` | `ResearchRequest` | `MarketData` | `ResearchUtils` (`@EmbabelComponent`) |
| `analyzeCompetitors` | `Action` | `ResearchRequest`, `MarketData` | `CompetitorAnalysis` | `ProductResearchAgent` |
| `generateReport` | `AchievesGoal` | `ResearchRequest`, `MarketData`, `CompetitorAnalysis?` | `ResearchReport` | `ProductResearchAgent` |

### Expected Edges

| From | To | Kind | Label |
|---|---|---|---|
| `gatherMarketData` | `analyzeCompetitors` | type | `MarketData` |
| `gatherMarketData` | `generateReport` | type | `MarketData` |
| `analyzeCompetitors` | `generateReport` | type | `CompetitorAnalysis` |

Note: the SpEL precondition `spel:marketData.confidenceScore > 0.6` on
`analyzeCompetitors` is shown in the node's `pre` field but draws **no graph edge** —
SpEL expressions are evaluated at runtime, not via condition-named producers.

### Graph (ASCII)

```
[gatherMarketData] ──MarketData──→ [analyzeCompetitors] ──CompetitorAnalysis──→ ─┐
        │                          (SpEL: marketData.confidenceScore > 0.6)      ├──→ [generateReport ✓]
        └─────────────────── MarketData ────────────────────────────────────────→─┘
```

### Detailed Execution Flow

1. **Input arrives** — `POST /api/research/analyze` posts `ResearchRequest { requestId,
   productCategory, targetMarket, researchDepth }`.
2. **SUPERVISOR planner activates** — instead of GOAP backward-chaining, the LLM
   supervisor is shown the available actions and the current blackboard state and
   chooses the next step. This makes plan choices explainable and adaptive.
3. **`gatherMarketData` runs** — declared in `ResearchUtils` with `@EmbabelComponent`
   (NOT inside the agent). The framework registers its actions alongside the host agent's.
   `outputBinding = "marketData"` explicitly names the blackboard binding (otherwise the
   simple class name is used). Produces `MarketData { marketSizeUsd, annualGrowthRate,
   keyTrends, confidenceScore, dataSummary }`.
4. **SpEL precondition evaluated** — for `analyzeCompetitors`, the framework evaluates
   `spel:marketData.confidenceScore > 0.6` against the blackboard. The `marketData`
   identifier resolves via the explicit `outputBinding`.
5. **Branch A — high-confidence path** (SpEL true, e.g. EV market in the US)
   - `analyzeCompetitors` runs (LLM). Consumes `ResearchRequest` + `MarketData`. Produces
     `CompetitorAnalysis { topCompetitors, marketLeader, competitiveIntensity,
     whitespaceOpportunities }`.
   - `generateReport` runs (`@AchievesGoal`, LLM) — consumes all three: `ResearchRequest`,
     `MarketData`, and **non-null** `CompetitorAnalysis`. Produces full `ResearchReport`.
6. **Branch B — low-confidence path** (SpEL false, e.g. niche "edible insect protein")
   - `analyzeCompetitors` is **skipped** by the planner.
   - `generateReport` runs with `competitorAnalysis = null` (parameter is
     `@org.jetbrains.annotations.Nullable`). The prompt branches on null to write
     "Competitive analysis was not performed" in the `competitiveLandscape` field.
7. **Goal achieved** — `ResearchReport` lands on the blackboard. Note the `confidenceScore`
   propagates from the gathered `MarketData`.

### Notes

- **`@EmbabelComponent` vs `@Agent`** — `ResearchUtils` is a shared component, not an
  agent itself. Its `@Action` methods can be reused by multiple agents. The visualizer
  shows `gatherMarketData` as a regular Action node within this agent's graph because
  the framework registers it with the host agent.
- **SpEL gate is non-graph** — visible in the node detail panel under `pre:` but not
  drawn as an edge. The decision to run `analyzeCompetitors` is opaque to the static
  graph and is resolved at runtime.
- **Why `@Nullable` is acceptable here** — unlike the `DocumentProcessingAgent` (which
  uses a default-producer pattern), here the absence of `CompetitorAnalysis` is a
  meaningful signal that the report should explicitly note the omission rather than be
  silently completed with default data.

### Test Scenarios

| ID | Description | SpEL outcome | Path |
|---|---|---|---|
| RES-001 | EV sector — well-established | `confidenceScore > 0.6` ✓ | gatherMarketData → analyzeCompetitors → generateReport |
| RES-002 | APAC smartwatch market | likely ✓ | full pipeline |
| RES-003 | Edible insect protein snacks (niche) | likely ✗ | gatherMarketData → generateReport (competitorAnalysis=null) |
| RES-004 | AI developer tools — global | likely ✓ | full pipeline |
| RES-VAL | Blank `requestId` | n/a | 400 Bad Request (Bean Validation `@NotBlank`) |

---

## Agent 10 — StoryWriterAgent

**Planner**: GOAP (default)
**Pattern**: Iterative draft–review–revise loop with `canRerun = true`
**Entry type**: `StoryRequest`
**Goal type**: `FinalStory`

### Expected Nodes

| Name | Visualizer Type | Input(s) | Output | Notes |
|---|---|---|---|---|
| `draftStory` | `Action` | `StoryRequest` | `Draft` | LLM temperature 0.9; persona `Alex Penworth`; throws `ActionException.Permanent`/`Transient` on validation |
| `reviewDraft` | `Action` | `StoryRequest`, `Draft` | `StoryReview` | LLM temperature 0.2; `StoryReview.formatFeedback()` exposed via `@LlmTool` |
| `reviseDraft` | `Action` | `StoryRequest`, `Draft`, `StoryReview` | `Draft` | `canRerun = true`, `pre = ["StoryReview"]`; loops until approval |
| `finalizeStory` | `AchievesGoal` | `StoryRequest`, `Draft`, `StoryReview` | `FinalStory` | `pre = ["StoryReview"]`; runs only when `approvedForFinalization == true` |
| `draftStatus` | (LlmTool method) | — | `String` | `@LlmTool` on the agent — not a workflow step, but a callable LLM tool |

### Expected Edges

| From | To | Kind | Label |
|---|---|---|---|
| `draftStory` | `reviewDraft` | type | `Draft` |
| `draftStory` | `reviseDraft` | type | `Draft` |
| `draftStory` | `finalizeStory` | type | `Draft` |
| `reviewDraft` | `reviseDraft` | type | `StoryReview` |
| `reviewDraft` | `finalizeStory` | type | `StoryReview` |
| `reviseDraft` | `reviewDraft` | type | `Draft` (loop edge — same Draft type re-emitted) |
| `reviseDraft` | `reviseDraft` | type | `Draft` (self-loop because `canRerun = true`) |

### Graph (ASCII)

```
                                          ┌───── Draft (canRerun loop) ─────┐
                                          ▼                                  │
[draftStory] ──Draft──→ [reviewDraft] ──StoryReview──→ [reviseDraft] ────────┘
                                  │
                                  └─── (when approvedForFinalization) ──→ [finalizeStory ✓]
```

### Detailed Execution Flow

1. **Input arrives** — `POST /api/story/write` posts `StoryRequest { storyId, genre,
   theme, maxWords, targetAudience }`.
2. **`draftStory` runs** (LLM)
   - **Validation** happens first: `validateRequest()` throws
     `ActionException.Permanent` for blank `storyId` or `maxWords` outside [100, 2000]
     (no retry — bad data stays bad), and `ActionException.Transient` if `theme.length()
     > 200` (simulated content-policy timeout — platform may retry with backoff).
   - **Prompt construction** uses `LlmOptions.withTemperature(0.9)` for creative
     diversity and injects the static `AUTHOR_PERSONA` (`PersonaSpec.create("Alex
     Penworth", ...)`) as a `PromptContributor` so the system prompt always carries
     the author's voice.
   - Returns `Draft { storyId, title, body, wordCount, revisionNumber=1 }`.
3. **`reviewDraft` runs** (LLM, temperature 0.2 for analytical consistency)
   - Same persona is injected for voice continuity.
   - The `StoryReview.formatFeedback()` method is decorated with `@LlmTool`; it could
     be registered on the prompt runner as a tool object so the LLM can invoke it during
     reasoning to obtain a standardised feedback format.
   - Produces `StoryReview { qualityScore (1–10), strengths, weaknesses,
     revisionInstructions, approvedForFinalization }`. Approval requires
     `qualityScore >= 7` AND no major weaknesses.
4. **Planner decides next step** — both `reviseDraft` and `finalizeStory` declare
   `pre = ["StoryReview"]`, so both are eligible once a `StoryReview` exists. The planner
   picks the action whose post-conditions are satisfiable:
   - `finalizeStory` produces `FinalStory` (the goal). It is selected when the planner
     can prove that `approvedForFinalization == true` (Embabel resolves this via the
     post-condition system; otherwise the action's effects are not provable and it is
     not chosen).
   - When approval is false, only `reviseDraft` advances the plan toward the goal,
     because it produces a fresh `Draft` which then re-triggers `reviewDraft`.
5. **Revision loop** (`canRerun = true`) — `reviseDraft` is allowed to execute multiple
   times within the same run. Without this flag the planner would treat it as a one-shot
   action and refuse to re-schedule it. The cycle is:
   ```
   Draft(rev=N) + StoryReview(approved=false)
     → reviseDraft → Draft(rev=N+1)
     → reviewDraft → StoryReview(approved=?)
     → repeat or finalize
   ```
6. **`finalizeStory` runs** (`@AchievesGoal`) — pure Java assembly (no LLM): copies the
   accepted `Draft` into a `FinalStory` and records `totalRevisions = revisionNumber - 1`
   plus the editor's `qualityScore`.

### Notes

- **`@LlmTool` on a record method** — `StoryReview.formatFeedback()` demonstrates how a
  domain object's helper method can be exposed to the LLM as a tool callable during
  prompt execution. This is distinct from the agent-level `draftStatus(String, int,
  int)` tool, which sits on the agent class.
- **`PersonaSpec`** — created via the `@JvmStatic` factory `PersonaSpec.create(name,
  bio, voice, objective)` on the Kotlin companion object, callable from Java. Passed
  as a `PromptContributor` to every `context.promptRunner(...)` invocation in this agent.
- **`LlmOptions.withTemperature()`** — temperature 0.9 for `draftStory` (creative
  diversity), 0.2 for `reviewDraft` (analytical), 0.8 for `reviseDraft` (creative but
  constrained by feedback).
- **Visualizer treatment of the loop** — the `reviseDraft → Draft` edge that flows back
  into `reviewDraft` is a normal type-flow edge; the cycle is real and visible.
- **Validation exceptions** — `ActionException.Permanent` halts the run; `Transient`
  triggers planner retry with backoff. Both demonstrate the error-classification pattern.

### Test Scenarios

| ID | Description | Path | Outcome |
|---|---|---|---|
| STORY-001 | Adventure — sea captain and eclipse island | draft → review → (possibly revise) → finalize | `FinalStory` produced after N revisions |
| STORY-002 | Sci-fi — last AI erases itself | full pipeline | `FinalStory` |
| STORY-003 | Mystery — overdue library book note | full pipeline | `FinalStory` |
| STORY-VAL-1 | Empty `storyId` | validation | `ActionException.Permanent` |
| STORY-VAL-2 | `maxWords = 9999` | validation | `ActionException.Permanent` (bean validation `@Max(2000)`) |

---

## Summary of Visualization Capabilities (All Verified)

| # | Feature | Affected Agents | Implementation |
|---|---|---|---|
| 1 | `@Cost` → `@Action` edges via `costMethod`/`valueMethod` | SentimentAnalysisAgent, TicketRoutingAgent | `costMethod`/`valueMethod` in `WorkflowStep`; `costProd` map + cost edges in frontend |
| 2 | `@State` inner-class methods scanned | TicketRoutingAgent | Framework's `AgentMetadataReader` calls `JvmType.children()` (Spring classpath scan) on action return type |
| 3 | `@State` record fields as implicit inputs | TicketRoutingAgent | Record components added as `implicitInputs` when scanning @State methods |
| 4 | `@State`-interface routing action connects to handlers via type-flow | TicketRoutingAgent | `routeToCategory` returns `TicketCategory` (a `@State` interface); classpath scan finds implementing records |
| 5 | Framework types (`OperationContext`, `Ai`, etc.) filtered from inputs | All agents | `FRAMEWORK_PARAMETER_TYPES` set in catalog service |
| 6 | SpEL pre-conditions displayed but not linked (no matching producer) | DocumentProcessingAgent, ProductResearchAgent | SpEL expressions are displayed in `pre` field but produce no pre-edge |
| 7 | Default-producer action for optional inputs | DocumentProcessingAgent | `provideDefaultMetadataHints` produces `MetadataHints`; GOAP includes it only when hints not present |
| 8 | `@EmbabelComponent` action sharing | ProductResearchAgent (`ResearchUtils.gatherMarketData`) | Component's `@Action` methods are scanned and registered with the host agent |
| 9 | `@Action(canRerun = true)` self-loop edge | StoryWriterAgent (`reviseDraft`), DocumentProcessingAgent (`analyzeContent`) | Action allowed to run multiple times in one process; visualizer draws self-edges where output type re-feeds an upstream consumer |
| 10 | `PlannerType.SUPERVISOR` (LLM-driven planning) | ProductResearchAgent | LLM picks the next action given blackboard state |
| 11 | `PlannerType.UTILITY` (value − cost ranking) | TicketRoutingAgent | Each action's net value selects highest at each step |
| 12 | `outputBinding = "name"` named blackboard binding | ProductResearchAgent (`gatherMarketData`), DocumentProcessingAgent (`preprocessDocument`) | Used by SpEL expressions and disambiguation when multiple values share a type |
| 13 | `@AchievesGoal(export=@Export(remote=true))` MCP publication | DocumentProcessingAgent (`summarizeDocument`) | Goal exposed as remote MCP tool callable by external agents |
| 14 | `@LlmTool` on domain method and on agent method | StoryWriterAgent (`StoryReview.formatFeedback`, `draftStatus`) | Method exposed to LLM as a callable tool during prompt execution |
| 15 | `PersonaSpec` as `PromptContributor` | StoryWriterAgent (`AUTHOR_PERSONA`) | Injected into every prompt for consistent voice |
| 16 | `LlmOptions.withTemperature(...)` | StoryWriterAgent | Per-action temperature control (0.9 creative, 0.2 analytical) |
| 17 | `ActionException.Permanent` / `.Transient` | StoryWriterAgent (`validateRequest`) | Halt vs retriable error classification for the platform |

---

## Key Framework Lessons

### @State Discovery (TicketRoutingAgent bug fix)

The Embabel framework discovers `@State` handler classes by calling a **classpath scan for
subtypes** of the branching action's declared **return type**.

| Declared return type | What the scan finds | Result |
|---|---|---|
| `Object` | All classes — nothing useful | No @State records discovered → no goals registered |
| `TicketCategory` (`@State` interface) | `BillingState`, `TechnicalState`, `GeneralState` | All three handlers registered correctly |

**Rule**: A branching action that dispatches to `@State` records **must** declare its
return type as a `@State`-annotated interface (or class), not `Object`.

### Java Records as @State Classes

Java records are implicitly static inner classes.  The framework's `validateStateClass`
check rejects **non-static** inner classes (Kotlin `inner class`, Java inner classes).
Records pass this check and are the idiomatic way to declare `@State` types.

Pattern used in `TicketRoutingAgent`:
```java
@State interface TicketCategory {}

@State record BillingState(BillingTicket ticket) implements TicketCategory {
    @AchievesGoal @Action
    TicketResolution handleBilling(OperationContext context) { ... }
}
```

### Default-Producer Pattern (DocumentProcessingAgent)

When a step has an optional input (previously `@Nullable`), the preferred Embabel pattern
is to add a **default-producer action** that generates the optional type when absent:

```java
// Instead of: extractMetadata(@Nullable MetadataHints hints, ...)
// Use a default producer:
@Action MetadataHints provideDefaultMetadataHints(DocumentRequest request) { ... }
@Action DocumentMetadata extractMetadata(CleanDocument doc, MetadataHints hints) { ... }
```

The GOAP planner automatically includes the default producer only when `MetadataHints`
is not already on the blackboard.

### ContentModerationAgent — isClear vs isFlagged Semantics

| Condition | Meaning | Handler | Example |
|---|---|---|---|
| `isClear=TRUE` | Content decision is clear-cut (clearly safe OR clearly violating) | `autoTag` → APPROVE or REMOVE | Clean content, hate speech |
| `isFlagged=TRUE` | Content is ambiguous — needs human review | `deepReview` → FLAG_FOR_REVIEW | Dark humour, satirical content |

---

## Test Results Summary

All 10 agents tested end-to-end against `http://localhost:8080`.

| Agent | Scenarios Tested | All Pass |
|---|---|---|
| FraudDetectionAgent | APPROVE, BLOCK, REVIEW | ✓ |
| KycVerificationAgent | Direct path (low risk), Enhanced Due Diligence path (high risk) | ✓ |
| SentimentAnalysisAgent | Positive (auto-respond), Negative (escalate) | ✓ |
| DocumentProcessingAgent | DOC-001 (no hints), DOC-002 (caller hints), DOC-003 (short/skips analyzeContent), DOC-004 (German) | ✓ |
| LoanApplicationAgent | APP-2001 (excellent → APPROVED auto), APP-2002 (poor → REJECTED), APP-2003 (borderline → underwriting) | ✓ |
| ContentModerationAgent | POST-8001 (clean → APPROVE), POST-8002 (hate speech → REMOVE via autoTag), POST-8003 (ambiguous → FLAG_FOR_REVIEW via deepReview) | ✓ |
| ResumeScreeningAgent | CAND-5001 (strong → HOLD/further eval), CAND-5002 (career switcher → REJECT) | ✓ |
| TicketRoutingAgent | TKT-1001…TKT-1006 (billing/technical/general × various priorities) | ✓ |
| ProductResearchAgent | RES-001 (EV / high confidence → full pipeline), RES-003 (niche / low confidence → SpEL gate skips analyzeCompetitors) | ✓ |
| StoryWriterAgent | STORY-001..003 (full draft–review–revise–finalize loop), validation failures (Permanent/Transient) | ✓ |
