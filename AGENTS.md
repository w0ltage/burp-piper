## Project Overview

This is a burp suite extension named "Piper" and written in Kotlin.

Piper integrates external tools and their pipelines to Burp Suite. The extension can pass HTTP requests and responses from Burp to external programs, then feed the execution result back to Burp. With Piper you can create:

- Commentators: Display the output of an external program in Proxy History as comments. For example, you can display the cryptographic hash of every request by piping their content to sha256sum.
- Highlighters: Highlight items in the proxy history based on their contents. For example, you can highlight items where HTTP response includes elements of a wordlist.
- Message Viewers: Display the contents of HTTP messages with custom formatting. For example, you can display Protobuf structures by piping message contents to protoc.
- Context Menu Items: Invoke external tools from context menu. For example, you can use an external diff GUI to compare HTTP messages.
- Intruder Payload Processors: Transform Intruder payloads. For example, you can apply base64 encoding with a custom alphabet using an external script.
- Macros: You can use external tools as part of Macros. For example, you can automatically generate predictable CSRF tokens for every outgoing request.
- HTTP Listeners: Transform outgoing and incoming HTTP messages. For example, you can use an external Python script to handle custom encryption.

## Build & Packaging
- Run `./gradlew --no-daemon --console=plain build` before finishing work so unit tests and proto generation stay in sync. (The project does not currently define a `shadowJar` task.)
- The distributable lives at `build/libs/auto-file-checker-<version>.jar`. Use this JAR when loading the extension into Burp Suite.
- The plain `jar` task is disabled; do not re-enable it unless you provide an alternative way to supply the Kotlin stdlib.


## Kotlin Implementation Notes
- Follow idiomatic Kotlin style (use `data class`, `sealed` hierarchies, and null-safety instead of Java-style optional handling).
- Prefer immutable collections from `kotlin.collections` unless mutation is required for Montoya callbacks.
- Use coroutines only if you wire them into Montoya's threading expectations; current async work relies on Java executors via Kotlin interop.
- Keep UI code on the Swing EDT by wrapping updates with `SwingUtilities.invokeLater { ... }`.

### Pass-headers behaviour widgets
- Use `createPassHeadersControls` (in `ConfigGUI.kt`) when exposing the “Pass HTTP headers to command” toggle so the checkbox and explanatory note remain consistent across inline editors and dialogs.
- When the input tab is hidden, call `CommandInvocationEditor.setPassHeaders` directly to preserve the stored state.

### Workspace UI components
- Reuse `WorkspaceHeaderPanel` for the standard name/template/status header; populate it with `WorkspaceHeaderValues` and register callbacks with `onChange`.
- The right-hand tab stack lives in `MinimalToolWidget`; add new Behavior controls with `addBehaviorComponent` and extra tabs with `addCustomTab` instead of duplicating layout code.
- `WorkspaceCommandPanel` owns the command invocation editor. Drive it through `display()`/`snapshot()`, and use the new `passHeaders()`/`setPassHeaders()` helpers when relocating the pass-headers toggle to other tabs.
- `WorkspaceFilterPanel` and `WorkspaceOverviewPanel` already encapsulate the common filter and summary layouts; instantiate them directly rather than cloning Swing trees.
- Prefer composing editors from these widgets (as done in `MinimalToolManagerPanel` and `MessageViewerWorkspacePanel`) so split panes resize consistently across tools.

## Montoya API Access
Proactively fetch Montoya API documentation and examples via HTTP GET requests to Context7 using the `fetch` tool.

**When to fetch documentation:**
- **Before implementing**: When you don't know how to implement a feature or where to start
- **For understanding**: When you need to understand how Montoya API components work
- **During development**: Before changing Montoya-dependent logic
- **For debugging**: When encountering compilation errors tied to Montoya classes
- **For verification**: When uncertain about method signatures, parameters, or return types

**Endpoint templates**

Montoya API Documentation (for understanding concepts and API structure):
```
https://context7.com/api/v1/portswigger/burp-extensions-montoya-api?type=json&tokens=100000&topic=<TOPIC>
```

Montoya API Usage Examples (for implementation patterns and code samples):
```
https://context7.com/api/web/docs/code/portswigger/burp-extensions-montoya-api-examples?tokens=100000&type=json&topic=<TOPIC>
```

**Usage guidelines:**
1. **Start with examples**: If you don't know where to start, fetch usage examples first
2. **Form effective topics**: Use 2–4 technical keywords (e.g., `ui%20suite%20tab`, `http%20request%20builder`, `scanner%20check`)
3. **Parse responses**: Extract method signatures, usage patterns, and examples relevant to your task
4. **Iterate if needed**: Retry with refined terms if the response lacks needed details
5. **Combine sources**: Use both endpoints when needed—documentation for understanding, examples for implementation

## Logging & Diagnostics
- Use `api.logging().logToOutput()` for informational messages and `logToError()` for failures.
- Prefer structured log prefixes (e.g., `[AutoChecker]`) so Burp users can filter messages.
- Async errors should be routed through the logging API within completion handlers.

## Compatibility & Dependencies
- Kotlin JVM toolchain pinned to 21—keep it aligned with the Burp Suite runtime.
- Montoya API dependency is `net.portswigger.burp.extensions:montoya-api:2025.4`; update in lockstep with Burp releases and test against the latest Montoya SDK.
- Avoid adding extra dependencies unless absolutely necessary; bundle everything through the shadow JAR to prevent classpath issues for users.
