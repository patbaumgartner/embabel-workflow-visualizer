# Security Policy

## Supported versions

Security fixes are released for the latest patch of each supported line.

| Visualizer | Spring Boot | Status                       |
| ---------- | ----------- | ---------------------------- |
| `1.1.x`    | 4.1.x       | Supported                    |
| `1.0.x`    | 4.1.x       | Supported                    |
| `0.3.x`    | 3.5.x       | Security fixes only          |
| `< 0.3`    | —           | Unsupported                  |

## Reporting a vulnerability

**Please do not open a public issue for a security problem.**

Report it through GitHub's private vulnerability reporting:

1. Go to the [Security tab](https://github.com/patbaumgartner/embabel-workflow-visualizer/security/advisories/new).
2. Describe the issue, the affected version, and how to reproduce it.

You can expect an acknowledgement within 5 working days and an assessment within
15 working days. If a fix is warranted, a patched release and a GitHub Security
Advisory are published together, crediting you unless you ask otherwise.

## Threat model

This starter reads annotation metadata from the beans in your application
context and exposes it over HTTP. It never executes agents, never reads the
blackboard, and never touches model credentials.

What it *does* expose is a description of your application's internals: agent
class names, method names, goal descriptions, prompts' example strings, and
condition names. Treat those endpoints as privileged.

| Endpoint                 | Enabled by                                          |
| ------------------------ | --------------------------------------------------- |
| `GET /actuator/embabel`  | `management.endpoints.web.exposure.include=embabel` |
| `GET <base-path>`        | `embabel.workflow.visualizer.enabled=true`          |
| `GET <base-path>/api`    | `embabel.workflow.visualizer.enabled=true`          |

Both switches are **off by default**, and neither endpoint adds any
authentication of its own. In an environment where the application is reachable
by untrusted clients, secure them like any other management surface — for
example with Spring Security:

```java
@Bean
SecurityFilterChain visualizerSecurity(HttpSecurity http) throws Exception {
    return http.securityMatcher("/embabel-workflows/**")
        .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("ADMIN"))
        .httpBasic(Customizer.withDefaults())
        .build();
}
```

The bundled UI is a single self-contained page: it loads no third-party scripts,
fonts, or styles, and makes exactly one request — to its own API — so it adds no
external origins to your application.
