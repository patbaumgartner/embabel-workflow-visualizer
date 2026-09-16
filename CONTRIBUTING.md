# Contributing

Thanks for taking the time. This is a small project, so the process is short.

## Build

Java 21+ is the only prerequisite; the Maven wrapper takes care of Maven.

```bash
./mvnw verify                                  # everything: both modules, tests, format, coverage
./mvnw verify -pl embabel-workflow-visualizer-starter   # the published artifact only
./mvnw spring-javaformat:apply                 # fix formatting before committing
```

`verify` fails on a formatting violation and on a coverage drop below 95% line /
82% branch in the starter, so run it before opening a pull request. CI runs the
same command on Java 21 and 25.

The sample application needs `OPENAI_API_KEY` set to start its context. No test
calls a model, so any placeholder works:

```bash
OPENAI_API_KEY=dummy ./mvnw verify
```

## Running the visualizer locally

```bash
./mvnw install -DskipTests -Djacoco.skip=true -pl embabel-workflow-visualizer-starter
OPENAI_API_KEY=dummy ./mvnw -pl embabel-sample-application spring-boot:run
```

Both lines matter. `-pl embabel-sample-application` builds that module alone, so
it resolves the starter from your local repository rather than from your working
tree — without the `install` first you will be looking at the last starter you
installed, and a change to the page or the scanner will appear to have done
nothing. Re-run the `install` after every starter change.

The `install` skips the tests and the coverage gate because neither says
anything about a jar you are building to run locally; `./mvnw verify` is where
those belong.

Then open <http://localhost:8080/embabel-workflows>. Add
`-Dspring-boot.run.arguments=--server.servlet.context-path=/demo` to check that a
UI change still works when the application is not mounted at the root — that is
a deployment the visualizer has to support and an easy one to break.

## Design constraints

Two rules shape most of the code in the starter. Please keep them.

**1. Never import Embabel types.** The starter reads `@Agent`, `@Action` and the
rest reflectively, by fully-qualified annotation name, and matches attributes by
shape (see `AnnotationAttributes`). That is why a consumer can upgrade Embabel
without waiting for a visualizer release, and why an attribute a different
Embabel version does not declare degrades to "absent" instead of breaking the
scan. `embabel-agent-api` is a **test-scoped** dependency and must stay that way.

**2. Show what the planner sees.** The catalog exists to mirror Embabel's own
`AgentMetadataReader`. If the reader registers something, the catalog must report
it; if it ignores something, the catalog must ignore it too. When you touch
discovery, check the behaviour against the Embabel source rather than guessing —
inherited actions and the `outputBinding` default are both cases where the
obvious implementation was wrong.

Where the annotations cannot answer the question, `AgentPlatformReader` asks the
running platform instead, again by name and never by import. Its unit tests use
platform-shaped fakes, which prove the reflective reading but cannot prove the
shape still matches Embabel — `EmbabelWorkflowCatalogRuntimeIntegrationTests` in
the sample application is what fails when Embabel changes its runtime API. If you
extend the runtime view, extend that test too, and remember that runtime names
are qualified (`com.foo.MyAgent.myAction`) while nested types arrive in binary
form (`Models$Request`); both have to be reduced to what the annotation scan
reports or the diagram silently loses edges.

Also worth knowing:

- Scanning must never instantiate application beans. Resolve types via
  `getType(name, false)`; anything that calls `getBean` turns a read-only
  diagnostic into a side effect.
- The flow chart (`WorkflowFlowBuilder`) is a derivation, not a simulation: it
  follows the planner's own rules — a goal is reached when its output type is on
  the blackboard, an action is runnable when its inputs are present and its
  preconditions hold — and nothing else. If Embabel's semantics change (say, how
  `Optional` or `@Nullable` inputs bind), the builder changes with them; do not
  add heuristics the planner does not have.
- The catalog JSON is a public contract consumed by the UI and by third-party
  tooling. `WorkflowModelsTests` pins the property names — a change there should
  be deliberate.
- The UI is one self-contained HTML file with no build step and no third-party
  assets. Keep it that way; it is served straight from the classpath.

## Tests

Every behavioural change needs a test that fails without it. The existing suites
show the expected shape:

- `EmbabelWorkflowCatalogServiceTests` — discovery, against real annotated
  fixtures in a real application context rather than mocks
- `WorkflowFlowBuilderTests` — route derivation: branches, forks and joins,
  loops, `@State` fan-out, entry types, costs and the route cap
- `AnnotationAttributesTests` — attribute reading, including absent and
  wrongly-shaped attributes
- `StarterPackagingTests` — what the published jar may and may not contain
- `EmbabelWorkflowVisualizerAutoConfigurationTests` — conditions and wiring

If you change the UI's JavaScript, run the browser functional suite and review
its screenshots. The suite is separate from Maven and uses local catalog
fixtures, so no application server or model credentials are needed.

```shell
# Install the browser tools outside the application modules.
npm install --prefix /tmp/visualizer-browser-tools playwright@1.61.1
/tmp/visualizer-browser-tools/node_modules/.bin/playwright install --with-deps chromium firefox webkit
NODE_PATH=/tmp/visualizer-browser-tools/node_modules node embabel-workflow-visualizer-starter/src/test/browser/functional.cjs
```

See [the functional test plan](tests/plans/ui-functional.md) for individual
scenarios, environment overrides, and expected outcomes. The default run covers
Chromium, Firefox and WebKit; missing browsers fail explicitly. Results and
screenshots are written to `/tmp/visualizer-functional` by default. Native touch
gestures use Chromium's device protocol; touch-handler checks run in all engines.
An optional `UI_TEST` regular expression selects case IDs for diagnosis; final
verification must run without that filter.

The smaller `src/test/browser/visualizer.cjs` script in the starter accepts a
captured catalog JSON file for a quick check against a running application's
data. The checked-in `catalog.json` is a snapshot of the 12 sample agents, not a
replacement for the Maven catalog/controller integration tests.

## Commits and pull requests

Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/)
(`fix:`, `feat:`, `docs:`, `ci:`, `refactor:`, `perf:`, `test:`, `build:`) — the
release changelog is generated from them. Explain *why* in the body; the diff
already says what.

Keep pull requests focused, and make sure `./mvnw verify` passes.

By taking part you agree to uphold the [Code of Conduct](CODE_OF_CONDUCT.md).

## Reporting security issues

Please do not open a public issue. See [SECURITY.md](SECURITY.md).
