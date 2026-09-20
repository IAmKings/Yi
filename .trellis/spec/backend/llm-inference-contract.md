# LLM Inference Prompt Contract (llama.kt / GGUF)

> **Scope**: any prompt construction that hits `LlamaEngine` (raw `completion()`
> or template-rendered). Written after a serious production bug where Hy-MT2
> emitted degenerate multilingual garbage (English ramblings, token leakage,
> Hebrew repetition) on-device.

---

## Scenario: Chat-template instruments the prompt for instruct models

### 1. Scope / Trigger
- **Trigger**: cross-layer contract change — Kotlin UI (ViewModel) → engine
  wrapper (`TranslationEngine`) → llama.kt JNI → llama.cpp sampler/tokenizer.
  Any change to *how prompts reach the model* is a cross-layer contract change.
- **Background (R2 bug)**: for Hy-MT2 (hunyuan-dense 1.8B, GGUF embedded chat
  template, special tokens `<｜hy_begin▁of▁sentence｜>`, `<｜hy_User｜>`,
  `<｜hy_Assistant｜>`), feeding the *raw* translation command to plain
  `completion()` made the model behave as a **base LM** — it continued text
  instead of translating. On desktop `llama-cli -st` it looked clean because
  the CLI internally prepends the full template envelope.

### 2. Signatures

```kotlin
// Correct: use the GGUF-embedded chat template. NEVER hand-roll the wrapper.
val prompt: String = LlamaEngine.formatChat(
    messages = listOf(ChatMessage(role = "user", content = <instruction>)),
    enableThinking = false,   // Hy-MT2 supportsThinking=false → no-op but explicit
)
engine.completion(prompt = prompt, params = SamplingParams(...), callback = ...)
```

- `chat()` Flow = `formatChat(...)` + `decode(prompt)`; both fine.
- A *pure* prompt builder must stay in `TranslationPrompts` (host-JVM
  testable) — the template wrapping happens **inside** the engine.

### 3. Contracts
- Input to `completion()` **must** start with the BOS/special prefix the
  model's chat template produces; a plain instruction string is invalid input.
- `SamplingParams` stops: do **not** list template assistant tags in raw-form
  (`"<|hy_Assistant|>"`) — the template's EOS token bounds generation; the
  literal full-width tag text can leak into stop-matching; partial-match hold
  back can silently truncate visible output.
- Sampling defaults for Hy-MT2 (official): `temperature=0.7, topK=20,
  topP=0.6, minP=0.05`; greedy (`temperature=0`) for reproducible tests.
- `nPredict` is the hard cap; compare returned `sampled` against it to detect
  truncation.

### 4. Validation & Error Matrix

| condition                                       | behavior                                  |
| ----------------------------------------------- | ----------------------------------------- |
| raw instruction without template envelope       | **forbidden** — see Wrong/Correct below   |
| stops containing template assistant tags        | forbidden (can truncate visible tokens)   |
| `enableThinking=true` on non-thinking template  | no-op; safe but useless                  |
| `supportsThinking=false` in GGUF metadata       | must render with thinking disabled        |
| streamed text contains `<｜` (special-token text)| means template was NOT applied — bug     |
| output language ≠ target language               | prompt/mailformed or wrong model loaded   |

### 5. Good / Base / Bad Cases
- **Good**: `formatChat([user: 官方指令])` → `completion(..., SamplingParams(0.7f,20,0.6f,0.05f))`
  → 真机验证：`Good morning.` → `早上好。`；技术长句术语保留（KV-cache/prefill）。
- **Base**: `temperature=0`（贪心）用于确定性回归测试；设备回归脚本可用
  `am start ... --es source "..."`（debug 注入，绕过 IME）。
- **Bad**: `engine.completion(buildPrompt(...))`（raw）→ 输出 `It is a good day
  for a good people.` + 特殊 token 文本泄漏 + 无限重复 — 生产严重 bug。

### 6. Tests Required
- **Unit** (`TranslationEngineTest`): `TranslationPrompts.build` output equals
  the official Hy-MT2 Default Translation command verbatim; source not trimmed
  inside builder (trim responsibility at caller).
- **Device/integration** (per language-pair change): drive via
  `adb shell "am start ... --es source '<english>'"`, then assert on
  `logcat YiEngine: done ... text=`:
  - `truncated=false`
  - output language == target language
  - output does **not** contain `<｜` or `<|` special-token text
  - input text itself must not be mangled by the IME — use `--es source`,
    never `adb shell input text` with `%20` (IME converts `%` → `％`, `.` → `。`)

### 7. Wrong vs Correct

#### Wrong
```kotlin
// Raw instruction straight to completion(): no BOS/hy_User/hy_Assistant envelope
val prompt = "Translate ... :\n\n$source"
engine.completion(prompt, params)   // model degenerates to base-LM behavior
```

#### Correct
```kotlin
val prompt = engine.formatChat(
    listOf(ChatMessage("user", "Translate ... only output the translated result")),
    enableThinking = false,
)
engine.completion(prompt, params)   // template supplies <｜hy_begin…｜>/<｜hy_User｜>/<｜hy_Assistant｜>
```

---

### Gotcha: `adb shell input text` corrupts source text

> **Warning**: On a Chinese-IME device `adb shell input text` rewrites ASCII —
> `.`→`。`, `%`→`％`, `%20` stays literal or becomes full-width. Always inject
> test source via the `--es source` intent extra (MainActivity passes it as
> initial input) instead of keyboard input, or you will debug "weird output"
> that is actually corrupted input.
