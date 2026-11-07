# Security Policy

## Reporting a vulnerability

Please don't open public issues for security vulnerabilities.

Report privately through either channel:

- **GitHub Security Advisories**: use "Report a vulnerability" on this
  repository (preferred), or
- **Email**: support@vaelii.com with "SECURITY" in the subject line.

Include what you can: affected version or commit, reproduction steps, and
impact. Please practice coordinated disclosure — report privately first and
allow time for a fix to land before publishing details.

## Supported versions

Only the latest release (and `main`) receive security fixes.

## Scope

`vaelii-agents` is a library over the
[vaelii](https://github.com/vaelii/vaelii) engine: model-provider clients and the
tool layer a model reads a KB through. It opens no listening socket. A running
deployment's exposed surface (the browser's unauthenticated write routes, the
headless daemon) belongs to core — see core's full
[security policy](https://github.com/vaelii/vaelii/blob/main/.github/SECURITY.md),
which also tracks third-party advisories for the shared dependency stack.

**This repo treats a model's output as untrusted input.** A tool call names a
tool and carries arguments a model wrote, and the tool layer reads each argument
with `clojure.edn` and runs it against a KB. The tool set is generated from the
read subset of the daemon's op table. Reports worth sending:

- A tool call that writes to the KB, or reaches an op the tool set excludes.
- An argument that reaches `eval`, a reader macro, or the classpath.
- A tool result that escapes its character bound, or a call that exhausts the
  heap or never returns.
- A provider credential that reaches a log line, an exception message or
  `ex-data`. The clients read a credential from the environment and never print it.
- A response body that makes a client hang past its deadline or fail as anything
  other than a typed refusal.
