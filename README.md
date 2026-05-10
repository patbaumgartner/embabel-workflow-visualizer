# Embabel Workflow Visualizer

[![CI](https://github.com/patbaumgartner/embabel-workflow-visualizer/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/patbaumgartner/embabel-workflow-visualizer/actions/workflows/ci.yml)
[![Snapshot](https://github.com/patbaumgartner/embabel-workflow-visualizer/actions/workflows/snapshot.yml/badge.svg?branch=main)](https://github.com/patbaumgartner/embabel-workflow-visualizer/actions/workflows/snapshot.yml)
[![CodeQL](https://github.com/patbaumgartner/embabel-workflow-visualizer/actions/workflows/codeql.yml/badge.svg?branch=main)](https://github.com/patbaumgartner/embabel-workflow-visualizer/actions/workflows/codeql.yml)
[![Dependency Review](https://github.com/patbaumgartner/embabel-workflow-visualizer/actions/workflows/dependency-review.yml/badge.svg?branch=main)](https://github.com/patbaumgartner/embabel-workflow-visualizer/actions/workflows/dependency-review.yml)
[![Scorecards](https://github.com/patbaumgartner/embabel-workflow-visualizer/actions/workflows/scorecards.yml/badge.svg?branch=main)](https://github.com/patbaumgartner/embabel-workflow-visualizer/actions/workflows/scorecards.yml)

A Spring Boot starter that adds a live workflow visualization UI and REST API for [Embabel](https://embabel.com) agents — zero code required.

---

## Project structure

This is a multi-module Maven project:

| Module | Purpose |
|---|---|
| `embabel-workflow-visualizer-starter` | Spring Boot auto-configuration, REST API, actuator endpoint, and visualization UI |
| `embabel-sample-application` | Runnable sample Embabel application that uses the starter |

## Build and test

```bash
# Build and test everything from the repository root
mvn test

# Test only the starter module
mvn -pl embabel-workflow-visualizer-starter test
```

## Usage

### 1. Configure GitHub Packages authentication

This library is published to [GitHub Packages](https://github.com/patbaumgartner/embabel-workflow-visualizer/packages).
GitHub Packages requires authentication even for public packages.

Add the following to your `~/.m2/settings.xml`, replacing the placeholders with your GitHub username and a [personal access token](https://github.com/settings/tokens) with the `read:packages` scope:

```xml
<settings>
  <servers>
    <server>
      <id>github-embabel-workflow-visualizer</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_GITHUB_TOKEN</password>
    </server>
  </servers>
</settings>
```

### 2. Add the repositories to your `pom.xml`

```xml
<repositories>
  <repository>
    <id>github-embabel-workflow-visualizer</id>
    <name>GitHub Packages — embabel-workflow-visualizer</name>
    <url>https://maven.pkg.github.com/patbaumgartner/embabel-workflow-visualizer</url>
    <releases><enabled>true</enabled></releases>
    <snapshots><enabled>true</enabled></snapshots>
  </repository>
  <!-- Required for com.embabel.agent.* transitive dependencies -->
  <repository>
    <id>embabel-snapshots</id>
    <name>Embabel Snapshot Repository</name>
    <url>https://repo.embabel.com/artifactory/libs-snapshot</url>
    <releases><enabled>false</enabled></releases>
    <snapshots><enabled>true</enabled></snapshots>
  </repository>
</repositories>
```

### 3. Add the dependency

```xml
<dependency>
    <groupId>com.patbaumgartner.embabel</groupId>
    <artifactId>embabel-workflow-visualizer-starter</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

### 4. Configure your `application.properties`

```properties
# Expose the actuator endpoint over HTTP
management.endpoints.web.exposure.include=health,info,embabel

# Enable the REST API (GET /embabel-workflows/api) and the visualization UI
embabel.workflow.visualizer.enabled=true
```

## Endpoints

| Endpoint | Requires | Description |
|---|---|---|
| `GET /actuator/embabel` | `management.endpoints.web.exposure.include=embabel` | Returns the workflow catalog as JSON |
| `GET /embabel-workflows/api` | `embabel.workflow.visualizer.enabled=true` | REST API — returns the workflow catalog as JSON |
| `GET /embabel-workflows` | `embabel.workflow.visualizer.enabled=true` | Interactive pan/zoom workflow visualization UI |

## Auto-configuration

The starter activates automatically when:

- The application runs in a **servlet web environment** (`@ConditionalOnWebApplication(SERVLET)`)
- **Spring Boot Actuator** is on the classpath

| Bean | Always registered | Condition |
|---|---|---|
| `EmbabelWorkflowCatalogService` | ✅ | Discovers `@Agent` beans via the `ApplicationContext` |
| `EmbabelWorkflowActuatorEndpoint` | When exposed | Requires `management.endpoints.web.exposure.include=embabel` |
| `EmbabelWorkflowApiController` | Off by default | Requires `embabel.workflow.visualizer.enabled=true` |
| `WorkflowVisualizerPageController` | Off by default | Requires `embabel.workflow.visualizer.enabled=true` |

All beans use `@ConditionalOnMissingBean` — declare your own bean to replace any of them.

## Visualization UI

The UI (`GET /embabel-workflows`) renders each discovered `@Agent` as an interactive flow diagram:

- **Pan** by dragging · **Zoom** by scrolling · **Double-click** to auto-fit
- Per-agent controls: Fit, Zoom In, Zoom Out, Reset
- Node types color-coded with the 42talents brand palette (cyan, yellow, green, pink, orange)
- Light / dark mode toggle, respects `prefers-color-scheme`

## Sample agents

The `embabel-sample-application` module ships six demo agents that cover common enterprise use cases.
Each agent demonstrates a **branching workflow**: an initial analysis step routes the work to either a
fast automated path or a deeper review path before producing the final output.

| Agent | Description | Endpoint |
|---|---|---|
| `KycVerificationAgent` | Screens a customer against risk indicators and produces a risk assessment. | `POST /api/kyc/verify` |
| `FraudDetectionAgent` | Screens financial transactions for fraud and produces an approve/block/review decision. | `POST /api/fraud/detect` |
| `SentimentAnalysisAgent` | Analyses customer feedback sentiment and routes it to an auto-response or an escalation path. | `POST /api/sentiment/analyze` |
| `ResumeScreeningAgent` | Screens candidate resumes against a job position and produces a hiring recommendation. | `POST /api/recruitment/screen` |
| `ContentModerationAgent` | Evaluates user-generated content against platform policies and issues a moderation decision. | `POST /api/moderation/evaluate` |
| `LoanApplicationAgent` | Assesses the credit risk of a loan application and produces an approve, reject or refer decision. | `POST /api/loan/apply` |

Ready-to-run HTTP request examples for all six agents are in [`embabel-sample-application/requests.http`](embabel-sample-application/requests.http).
