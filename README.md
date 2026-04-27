<p align="left">
  <img src="https://avatars.githubusercontent.com/u/234419170?s=400&u=43571ebf481969baafb8399813ad57f46c19eb95&v=4" alt="AndroidIDE" width="80" height="80"/>
</p>

<h2 align="left"><b>Android Code Studio (piashmsu fork)</b></h2>
<p align="left">
  An IDE to develop real, Gradle-based Android applications on Android devices — with a powerful, multi-provider AI coding agent.
<p><br>

<p align="left">
<img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="License">
<img src="https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white" alt="Android">
<img src="https://img.shields.io/badge/AI%20Providers-7%2B-blueviolet" alt="AI Providers">
</p>

> This is the **piashmsu** fork of [AndroidCodeStudio](https://github.com/AndroidCSOfficial/android-code-studio) with a heavily extended AI agent, build-error auto-fix, streaming responses, diff preview, editor AI actions, and many fixes / optimizations.
> Active branch: [`devin/1777186665-ai-agent-openrouter-autofix`](https://github.com/piashmsu/android-code-studio/tree/devin/1777186665-ai-agent-openrouter-autofix) — open PR: [#1](https://github.com/piashmsu/android-code-studio/pull/1)

---

## What's new in this fork

### AI providers — what you can plug in

| Provider | Status | Notes |
|---|---|---|
| **OpenRouter** | New | 21+ curated free + paid models, custom `vendor/model` slug supported, fallback chain |
| **OpenAI-compatible (custom URL)** | New | Manually configure Base URL + API key + Model id. Works with Together, Groq, DeepInfra, Fireworks, Ollama (`http://10.0.2.2:11434/v1`), self-hosted vLLM, anything that speaks `/v1/chat/completions` |
| OpenAI | Existing | Bug-fixed (was sharing DeepSeek strings) |
| Anthropic Claude | Existing | Bug-fixed |
| Google Gemini | Existing | — |
| DeepSeek | Existing | — |
| xAI Grok | Existing | Bug-fixed |
| Local LLM | Existing | Improved integration |

### AI agent capability matrix

| Capability | Status |
|---|---|
| Token-by-token streaming response (SSE) | New |
| Build-error auto-fix dialog | New |
| Live progress dialog with streamed AI reply, file list, activity log, error excerpt | New |
| Auto-rebuild after AI fix (max 3 cycles) | New |
| Diff preview before applying file changes (line-level, optional) | New |
| Editor selection actions: Explain / Refactor / Add docs / Generate test | New |
| Project memory (`.aistudio/memory.md`) | New |
| Quick-start templates: Calculator, Todo, Chat, Login | New |
| Markdown rendering for AI replies (code blocks, bold, lists, headings) | New |
| Token counter (per request + session total) | New |
| Conversation export (markdown + JSON) | New |
| OpenRouter fallback chain (free → free → free) | New |
| Crash → AI auto-prefill on next launch | New |
| `@filename` mentions in chat auto-attach file contents | New |
| Voice input (Bengali + English, device speech recognizer) | New |
| Provider-preset picker (Together / Groq / DeepInfra / Fireworks / Anyscale / Mistral / Perplexity / OpenAI / OpenRouter / Ollama / vLLM) | New |
| Auto-detect local Ollama | New |
| Settings JSON export / import | New |
| Offline indicator (skip 30 s watchdog when no network) | New |
| Sensitive-data detection in outgoing prompts | New |
| `ai_backups/` retention (30 days / 200 files max) | New |
| Planning mode (think-then-plan-then-code) toggle | New |
| Markdown pipe-table rendering in AI replies | New |
| Image input (vision models) | Planned |
| Inline ghost text (Copilot-style) | Planned |
| Multi-file smart context picker | Planned |
| Tool calling / function calling | Planned |

---

## AI Fix — live build-error workflow

When a Gradle build fails:

1. The IDE captures full Gradle output, the failing task name, and the error tail.
2. A confirmation dialog asks if you want the AI to fix it.
3. Tapping **Fix** opens a live progress dialog (85% screen height, scrollable):
   - **Header card** — spinner, status text, attempt chip (e.g. `Attempt 2/3`)
   - **Build error excerpt** — extracts Kotlin `e: ...` and `error: ...` lines first, filters Gradle deprecation/help noise, deduped, max 12 lines
   - **Files being modified** — RecyclerView with success/fail icons, count badge
   - **AI reply card** — markdown-rendered streaming text (80 ms throttling, smooth)
   - **Activity log** — color-coded (info / file / success / warn / error) with timestamps
4. After AI replies, files are written to disk via `AIFileWriter` (with timestamped backup in `app's filesDir/ai_backups/`).
5. If `Auto-rebuild after AI fix` is enabled, the build is re-run automatically (up to 3 cycles).
6. **Cancel button** aborts a running request. **30-second watchdog** logs an actionable warning if no progress arrives (likely invalid API key, dead model, or unreachable provider).
7. **Provider/model badge** is shown on the dialog so you always know which upstream is being hit.

---

## Settings → AI

- **Provider dropdown** — pick from 7+ providers
- **Model / Agent box** — fully editable; type any model id (e.g. `deepseek/deepseek-chat-v3.1:free`), tap Done, it persists
- **API key dialogs** — per provider
- **OpenAI-compatible group** — Base URL + API key + Model id, with `Save endpoint` button and URL normalization (`/v1`, `/v1/chat/completions`, or just domain — all work)
  - **Use a preset** — one-tap URL fill for Together / Groq / DeepInfra / Fireworks / Anyscale / Mistral / Perplexity / OpenAI / OpenRouter / Ollama (emulator + LAN) / vLLM
  - **Auto-detect local Ollama** — probes `10.0.2.2:11434`, `localhost:11434`, LAN; on hit fills URL and lists installed models
- **Custom OpenRouter model** — saved name appears in the main Model/Agent box and survives app restart
- **Streaming responses** toggle (default ON)
- **Diff preview before applying** toggle (default OFF)
- **Auto-rebuild after AI fix** toggle (default OFF)
- **Auto-switch on quota/rate-limit** toggle
- **Code completion** toggle
- **Planning mode** toggle — asks the model to emit a short plan before any code
- **Export / Import settings** — JSON file in `Downloads/AndroidCodeStudio/`, preserves all provider keys, base URLs, model ids, and toggles (schema-versioned)

### Chat tab extras

- **Voice input** — mic button next to Clear. Uses the device's speech recognizer (Bengali + English on most phones). Recognised text is appended to whatever you've already typed.
- **`@filename.kt` mentions** — type `@MainActivity.kt` in your prompt; the IDE scans the open project, prepends the matched file(s) to the prompt as explicit context (max 8 files × 32 KB), and still passes your original intent through so the model sees both.
- **Offline guard** — if the device has no validated internet, the request returns immediately with a clear message instead of waiting 30 seconds.
- **Sensitive-data warning** — if your prompt contains what looks like a real API key / token / private-key block, a warning is shown before sending.

---

## Build-speed tuning

`gradle.properties` is auto-tuned for new projects via the included project template + `GradlePropertiesPresets`:

- Gradle daemon **ON**
- Parallel builds **ON**
- Build cache **ON**
- Configuration cache **ON**
- JVM heap **4 GB** + `UseParallelGC`
- Kotlin incremental compile **ON**
- AAPT2 daemon + parallel resource processing

**Expected impact:** clean build 4–5 min → 1–2 min, incremental rebuild 30–60 s → 5–15 s.

---

## RAM / performance fixes

- Conversation history capped at **20 messages** for every provider
- File-context loading capped at **40 files × 64 KB** to avoid OOM on large projects
- Replaced 100 ms `SharedPreferences` polling loop with `OnSharedPreferenceChangeListener` (battery + CPU savings)
- Live progress dialog uses 80 ms coalesced UI updates for streaming (no UI jank)

---

## Bug fixes (compared to upstream)

- OpenAI / Anthropic / Grok strings were copy-pasted from DeepSeek (every label said "Deepseek"). Fixed.
- 51 duplicate string IDs in Arabic `strings.xml`. Fixed.
- Duplicate `title_build_variants` resource. Fixed.
- Format string mismatches in Arabic + Chinese translations. Fixed (positional format).
- AAPT2 errors during release build. Fixed.
- `jdk-compiler` module had references to missing OpenJDK source files (`ServerMain`, `SjavacServer`, etc). Excluded from build.
- `ClangLanguageServer` stub added (file was gitignored upstream).
- OpenRouter custom model field couldn't be typed into (`inputType="none"`). Now editable.
- Custom OpenRouter model name didn't persist visually in the main Model/Agent box. Fixed — typed name is added to the dropdown list, persists across app restarts, and is restored from preferences.
- Chat tab Send button was off-screen on narrow phones (Export + Clear were eating row width). Fixed — Export and Clear are now icon-only, Send is anchored full-width.
- Various AI agent message-routing bugs around streaming and modification callbacks.
- Provider switch was leaving stale model ids attached (e.g. picking OpenAI-compat while an OpenRouter `vendor/model` slug was still active made the upstream 400). `AIPreferencesFragment.handleProviderChange` now persists the provider **before** calling `setAgent`, and restores the saved OpenAI-compat model id on switch.
- `AIAgentManager` hardcoded `setProvider("gemini")` on init, so auto-fix ignored the user's saved preference even though chat honoured it. It now reads `Agents.getProvider()`. `BuildErrorAutoFixer` also force-sets the saved provider before hitting the upstream, regardless of what the fallback chain selected.
- Error bubbles were inlining 500-character stack traces. They are now short ("provider / type / message"); the full trace stays in logcat.
- Model dropdown could show the same id twice when the user typed a model that was already part of the curated list — deduped.
- Preferences changes (API key, base URL, model) used to require an app restart to take effect. `AIAgentManager` now registers a `SharedPreferences.OnSharedPreferenceChangeListener` that re-initialises the active agent as soon as anything `ai_agent_*` / `ai_provider*` changes.
- Live-progress `Cancel` button used to leave the dialog open — user had to tap OK separately. It now auto-dismisses 1.2 s after cancellation.

---

## Original feature list

- [x] Gradle support
- [x] JDK 11 & JDK 17 available
- [x] Terminal with essential packages
- [x] Custom environment variables (for builds & terminal)
- [x] SDK & NDK Manager (manage Android SDKs and NDKs via terminal)
- [x] API information for classes and their members (since, removed, deprecated)
- [x] Log reader (real-time logs for your app)
- [ ] Language servers
    - [x] Java
    - [x] XML
    - [x] Kotlin
- [ ] UI Designer
    - [x] Layout inflater
    - [x] Resolve resource references
    - [x] Auto-complete resource values when editing attributes
    - [x] Drag & Drop interface
    - [x] Visual attribute editor
    - [x] Android Widgets
- [ ] String Translator
- [x] Asset Studio (Drawable & Icon Maker)
- [x] Plugin Creator (Create sub-modules or plugins inside your project)
- [x] Git integration
- [x] **AI Agent** – fully **project-aware AI assistant** with multi-provider support, streaming, diff preview, build-error auto-fix, editor selection actions, and project memory.

---

## Installation

[<img src="https://github.com/Kunzisoft/Github-badge/raw/main/get-it-on-github.svg"
    alt="Get it on GitHub"
    height="80">](https://github.com/piashmsu/android-code-studio/releases)

> Please install Android Code Studio from trusted sources only:
> - [piashmsu fork releases](https://github.com/piashmsu/android-code-studio/releases)
> - [Upstream releases](https://github.com/Mohammed-baqer-null/android-code-studio/releases)

- Download the APK from a trusted source.
- Follow the [setup guide](https://docs.androidide.com/tutorials/get-started.html) to install build tools.

## Building from source

```bash
git clone https://github.com/piashmsu/android-code-studio.git
cd android-code-studio
git checkout devin/1777186665-ai-agent-openrouter-autofix

# Provide SDK location:
echo "sdk.dir=/path/to/your/android-sdk" > local.properties

# Debug APK (split per ABI):
./gradlew :core:app:assembleDebug

# Output:
ls core/app/build/outputs/apk/debug/
# android-code-studio-arm64-v8a-debug-*.apk
# android-code-studio-armeabi-v7a-debug-*.apk
```

## Limitations

- Project must use Android Gradle Plugin v7.2.0 or newer.
- SDK Manager is bundled and accessible from the terminal, but some tools (like NDK) are not built for Android.

The app is in active development. Please report issues you encounter.

## Contributing

See the [contributing guide](./CONTRIBUTING.md).

For translations, visit the [Crowdin project page](https://crowdin.com/project/androidide).

## Thanks to

- [Rosemoe](https://github.com/Rosemoe) for the awesome [CodeEditor](https://github.com/Rosemoe/sora-editor)
- [Termux](https://github.com/termux) for the [Terminal Emulator](https://github.com/termux/termux-app)
- [Bogdan Melnychuk](https://github.com/bmelnychuk) for [AndroidTreeView](https://github.com/bmelnychuk/AndroidTreeView)
- [George Fraser](https://github.com/georgewfraser) for the [Java Language Server](https://github.com/georgewfraser/java-language-server)
- [FWCD](https://github.com/fwcd) for the [Kotlin Language Server](https://github.com/fwcd/kotlin-language-server)
- [Itsaky](https://github.com/itsaky) — original developer of [AndroidIDE](https://github.com/AndroidIDEOfficial/AndroidIDE.git)
- [Mohammed-baqer-null](https://github.com/Mohammed-baqer-null) — maintainer of the AndroidCodeStudio upstream this fork is based on.

Thanks to all the developers who have contributed to this project.

## Contact

- Telegram: [t.me/rv2ide](https://t.me/rv2ide)
- Upstream docs: [m.androidide.com](https://m.androidide.com)

## License

```
AndroidCodeStudio is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

AndroidCodeStudio is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
```

Any violations of the license can be reported either by opening an issue or writing a mail to us directly.
