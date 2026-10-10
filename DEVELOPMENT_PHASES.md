Android Computer-Use AI Agent — Development Roadmap

Project Goal

Build a native Android AI agent that understands natural-language instructions and performs supported actions in real Android apps using screen observation, accessibility information, AI reasoning, and controlled UI interactions.

The developer primarily works from an Android phone. Source editing, version control, and APK builds should therefore work through GitHub and cloud-based tooling.

Non-Negotiable Rule

Phase 1 is a functional MVP, not a UI prototype.

The first phase must already include a real AI model, real Android screen observation, real device interaction, result verification, and task reporting.

Do not postpone the defining feature until Phase 2 or later.

---

Phase 0 — Repository Inspection and Technical Setup

Objective

Prepare the existing repository and choose a practical implementation path.

Tasks

- Inspect the repository and current Git status.
- Identify existing source files, build scripts, dependencies, and GitHub Actions workflows.
- Preserve useful existing implementations.
- Confirm that the project uses a suitable native Android architecture.
- Verify current Android SDK requirements and official API documentation.
- Investigate currently available free-tier AI models that support the required text and image inputs.
- Confirm model availability, structured-output capabilities, rate limits, and usage restrictions.
- Define the AI provider interface and validated action protocol.
- Establish the build environment and debug APK artifact workflow.

Deliverables

- Repository assessment.
- Concise technical plan.
- Verified dependency and model choices.
- Initial Gradle build configuration.
- Working cloud build workflow, or a clearly documented setup plan if repository prerequisites are missing.

Completion Criteria

The project can be built in the intended cloud environment, and the MVP implementation path is clear.

Do not spend this phase building an elaborate interface.

---

Phase 1 — Functional MVP: Real AI-Controlled Android Interaction

Objective

Deliver a working application that can interpret a natural-language task and execute a simple multi-step task in a real Android app.

Priority 1: Minimum Native Application

Implement:

- A basic Jetpack Compose interface.
- Natural-language task input.
- Run and Cancel controls.
- Task status and execution logs.
- AI provider configuration.
- API-key configuration.
- Setup guidance for required Android permissions.

The interface only needs to be usable and understandable.

Priority 2: Real AI Integration

Implement:

- A real provider adapter.
- Actual API requests.
- Validated model responses.
- Structured action parsing.
- Timeouts and error handling.
- Safe handling of missing or invalid credentials.
- Bounded retries and action limits.

Do not use fake AI responses as the normal operating mode.

Priority 3: Actual Android Observation

Implement an AccessibilityService that can inspect supported Android UI elements.

Read available:

- Text and content descriptions.
- UI element roles and states.
- Clickable and editable elements.
- Scrollable containers.
- Accessibility actions.
- Element bounds where available.

Request and handle any required permissions appropriately.

Use screenshots only when useful and when the user has granted the required system consent. Do not assume every screen can be inspected.

Priority 4: Actual Device Actions

Implement the smallest reliable action set:

- Open or navigate to a suitable app when supported.
- Tap an accessible UI element.
- Enter text into an editable field.
- Scroll a view.
- Navigate back.
- Wait for a screen update.
- Re-observe the interface.

Prefer accessibility-node actions over coordinate guessing.

Return the actual result of each action to the agent orchestrator.

Priority 5: Agent Execution Loop

Implement the real workflow:

1. Receive the task.
2. Observe the current state.
3. Send task context and relevant observations to the AI.
4. Validate the proposed action.
5. Execute an authorized action.
6. Observe the updated state.
7. Decide whether to continue, recover, ask for help, or finish.
8. Report the verified outcome.

Include cancellation, bounded execution, and loop detection.

Priority 6: Essential Safety

Before declaring the MVP complete:

- Require explicit Accessibility Service activation.
- Explain the permissions being requested.
- Provide a usable cancellation control.
- Require confirmation for consequential actions.
- Prevent unsupported or malformed actions from executing.
- Avoid logging API keys or unnecessary sensitive data.
- Never report success without checking the result.

Priority 7: Build and Real-Device Test

- Run available automated tests.
- Build the debug APK through GitHub Actions.
- Make the APK downloadable from the workflow artifacts.
- Document installation and setup steps.
- Test on an actual Android device whenever possible.

Required MVP Demonstration

The application must successfully complete a task such as:

"Open Chrome and search for Kotlin Android development tutorials."

The agent must use a real model, perform real UI actions, observe the resulting screen, and report an outcome supported by its observations.

If Chrome is unavailable, use a documented alternative app and task with accessible controls.

Completion Criteria

Phase 1 is complete only when:

- [ ] The app launches.
- [ ] A real AI provider can be configured.
- [ ] The model receives the user's task.
- [ ] Android accessibility setup works.
- [ ] The agent can inspect actual UI state.
- [ ] At least one multi-step task succeeds through real device interactions.
- [ ] The agent verifies the resulting state.
- [ ] Cancellation works.
- [ ] Common failures are reported honestly.
- [ ] The APK can be built and retrieved through the cloud workflow.
- [ ] The setup instructions are sufficient for a phone-only developer.

Do not begin feature expansion while the defining end-to-end interaction remains nonfunctional.

---

Phase 2 — Reliability and Better Screen Understanding

Objective

Make the agent more reliable across different Android apps and screen layouts.

Tasks

- Improve accessibility-node selection.
- Rank candidate elements using text, role, state, and context.
- Detect stale element references.
- Improve wait conditions for changing interfaces.
- Detect whether actions achieved their intended effects.
- Add bounded recovery strategies.
- Improve screenshot-based understanding when authorized and supported.
- Handle keyboard visibility, dialogs, and changing screen layouts.
- Improve action history and task-state tracking.
- Add stronger loop and repeated-action detection.
- Test on multiple accessible apps.

Completion Criteria

The agent handles common UI changes more consistently and recovers from ordinary failures without repeatedly performing the same unsuccessful action.

---

Phase 3 — General-Purpose Task Planning

Objective

Handle longer instructions that require multiple connected steps.

Tasks

- Break larger tasks into manageable subgoals.
- Maintain task progress and relevant context.
- Re-plan after unexpected screen changes.
- Distinguish completed, pending, blocked, and failed steps.
- Add better handling of app switching.
- Improve text entry and navigation reliability.
- Ask clarifying questions when the task is genuinely ambiguous.
- Pause when a task requires user authentication or assistance.
- Add explicit completion criteria for common task types.

Completion Criteria

The agent can complete a broader range of multi-step tasks while keeping the user informed and stopping safely when blocked.

---

Phase 4 — Safety, Privacy, and Operational Controls

Objective

Improve control over consequential actions and sensitive information.

Tasks

- Strengthen the action authorization layer.
- Add configurable action restrictions.
- Improve confirmation flows for consequential actions.
- Reduce unnecessary screen data sent to the AI provider.
- Minimize sensitive execution logs.
- Improve secret storage and provider configuration.
- Add clear indicators when automation is active.
- Improve cancellation behavior.
- Test permission revocation and service interruption.
- Document platform restrictions and privacy implications.

Completion Criteria

The application has understandable user controls, sensible privacy protections, and predictable behavior when permissions or execution conditions change.

---

Phase 5 — AI Provider Flexibility and Cost Optimization

Objective

Make the agent practical to operate within available API quotas and budgets.

Tasks

- Compare supported providers and models using current documentation.
- Measure task success rate, latency, and approximate usage.
- Avoid sending unchanged screen context unnecessarily.
- Reduce redundant model requests.
- Use simpler reasoning for straightforward actions where appropriate.
- Add configurable request and execution limits.
- Handle rate limits and temporary outages.
- Allow provider switching without changing the core action engine.
- Investigate local models only if device performance and compatibility make them practical.

Completion Criteria

The developer can understand the agent's provider requirements, usage constraints, and available alternatives without rebuilding the application around a single vendor.

---

Phase 6 — Advanced Interaction and Optional Features

Objective

Expand capabilities after the core agent is reliable.

Potential additions:

- Better screenshot and visual-element interpretation.
- More robust gesture handling.
- User-approved reusable task workflows.
- Saved task history with privacy controls.
- Voice input and spoken status updates.
- Task templates and configurable agent behavior.
- Improved diagnostics.
- Optional local inference.
- More advanced long-running task management.

Completion Criteria

New capabilities are integrated without breaking the established MVP functionality.

---

Phase 7 — Release Preparation

Objective

Prepare a stable build for broader personal use or future distribution.

Tasks

- Run regression tests.
- Validate supported Android versions.
- Review permissions and privacy disclosures.
- Review API-key handling.
- Improve onboarding and error messages.
- Verify release build configuration.
- Document known limitations.
- Create reproducible build instructions.
- Review applicable Android platform and distribution requirements.

Completion Criteria

The project has a documented, repeatable release process and a clear account of what works, what is restricted by Android, and what remains incomplete.

---

Rules for Every Phase

1. Keep the application buildable after each meaningful change.
2. Preserve working features unless there is a documented reason to replace them.
3. Prefer small, testable increments over large rewrites.
4. Run relevant tests before reporting completion.
5. Do not claim tests passed if they were not executed.
6. Never substitute mocked behavior for a core feature without explicitly labeling it as a temporary test.
7. Keep credentials out of source control and build logs.
8. Respect Android's permission model and the user's control over their device.
9. Document blockers and offer the next practical step.
10. Prioritize the functional MVP over visual polish and optional features.

Definition of Done

A phase is complete when its deliverables work, its acceptance criteria have been checked, and any remaining limitations are documented.

A task is not complete merely because the code was written.

The project's first milestone is a real Android AI agent that observes, acts, verifies, and reports. Everything else builds on that foundation.