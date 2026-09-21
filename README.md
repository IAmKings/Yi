# 譯 — Android 纯离线翻译（Compose + llama.kt + Hy-MT2-1.8B @ 1.25-bit）

**譯** 是一台装进口袋的离线翻译机：模型三句不离手，翻译全程不经云端。
底层是 [tencent/Hy-MT2-1.8B-1.25Bit-GGUF](https://huggingface.co/tencent/Hy-MT2-1.8B-1.25Bit-GGUF)
——腾讯 Hunyuan「快思考」翻译模型家族的 1.8B 版本，经 AngelSlim **Sherry 1.25-bit
（STQ1_0, 1.3125 bpw）极限量化后单文件仅 440MB**：

| 项目 | 值 |
| --- | --- |
| 模型文件 | `Hy-MT2-1.8B-1.25Bit.gguf` · 461,860,800 bytes |
| SHA-256 | `cc497fe8f033b52b3b8b00a7669e9661435432f9d4cd43f7ed24400c01507a93` |
| 推理引擎 | [llama.kt](https://github.com/hokanosekai/llama.kt)（LLM 运行时为 llama.cpp，git submodule 绑定） |
| 后端 | CPU（arm64-v8a，钉大核），minSdk 29 / targetSdk 36 |
| UI | Jetpack Compose · Material 3 · 深海靛蓝主题（indigo × sky blue） |
| 语言 | Hy-MT2 全量支持 33+ 种语言互译（中 / 英 / 繁中 / 粤 / 日 / 韩 …） |

---

## 功能总览

- **完全离线翻译**：模型与推理全部在本地完成，不联网（仅首次模型下载需要网络）。
- **模型目录优先加载**：首启优先让你选一个本地目录（SAF），找到 `.gguf` 即
  （可选复制）加载；目录里没有才走 Wi-Fi 首启下载，支持断点续传与 SHA-256 校验。
- **流式生成 UI**：源语言/目标语言实时切换 + `⇄` 交换 + 「自动检测」源（含
  `识别为：xx` 的实时提示）；译文边生成边上屏。
- **历史（Room）**：最近 50 条会话自动入库，支持回填、单条删除、清空。
- **会话语义**：原文与译文成对保存（DataStore 快照），跨进程冷启动仍恢复；
  编辑原文即作废旧译文。
- **设置面板**：temperature / max tokens / 上下文长度（2k/4k/8k/16k，改 ctx
  自动重载模型）/ 下载镜像（huggingface · hf-mirror）。
- **上下文预算守卫**: envelope + source + maxTokens > nCtx 时直接排错误
  （不静默截断源文，避免"翻译完成但内容缺失"）。
- **双语测试通道**：`am start --es source "<text>"` 直接注入输入框（绕过 IME），
  方便自动化验收。

---

## 构建与运行

```bash
git submodule update --init --recursive
brew install cmake glslc gnu-sed          # macOS 依赖（Linux 无需 gnu-sed）
bash libs/llama.kt/scripts/build-opencl.sh  # 生成 jniLibs/libOpenCL.so stub
bash libs/llama.kt/scripts/bootstrap.sh     # vendor llama.cpp + 应用本地补丁
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**环境要求**

| 项 | 值 |
| --- | --- |
| JDK | 21+（本项目实测 JDK 25 + Gradle 9.2.1 可用） |
| AGP / Kotlin | 8.13.0 / 2.2.20 |
| NDK / SDK | r27.2+ / android-36 |
| ABI | arm64-v8a only |
| KSP | 2.2.20-2.0.4（须与 Kotlin 版本配对，见 Retrofit 分析） |

模型不入 APK：首启从目录/HuggingFace 下载到 `filesDir`（可用 Wi-Fi 下载），
通过 SHA-256 校验后即完全离线可用。桌面副本亦已下载校验（`.dsh/models/`）。

---

## llama.kt 集成与本地补丁（关键决策）

### 1. STQ1_0 内核（ggml type id 42）
该 1.25bit GGUF 依赖上游 [PR #22836](https://github.com/ggml-org/llama.cpp/pull/22836)
（Sherry 1.3125 bpw 三值量化 + ARM NEON `vqtbl2q`/`vdotq` vec_dot 内核），截至
撰写时 llama.cpp master **未合并**。`libs/llama.kt/patches/0003-stq1_0-support.patch`
把 PR 的 C 侧改动移植到 vendored 改名体系：
- `LM_GGML_TYPE_STQ1_0 = 42`（vendored llama.cpp 无 `GGML_TYPE_Q2_0`，42 正好是
  空槽，与 AngelSlim GGUF 里的张量 type id 42 对齐）、TYPE_COUNT 43、
  `LLAMA_FTYPE_MOSTLY_STQ1_0 = 42`；
- block 布局（ggml-common.h）/ 码本（quantize + dequantize）/ CPU vec_dot
  （ ARM NEON 内核 + arch-fallback 兜底）/ llama-model-loader ftype。
- 桌面验证（Mac）：与 vendored 同源代码跑 `llama-cli`，
  *"The weather is really nice today."* → 「今天天气真的很不错。」 tg ≈ 48 t/s。

### 2. macOS bootstrap 适配
- llama.kt 的 `sed -i -e`（GNU）与 `nproc` 在 macOS 原生断裂；本地经 GNU sed
  （`brew install gnu-sed`）与 `nproc` shim（`.dsh/bin/`）解决，符号改名
  （`ggml_` → `lm_ggml_`）才真正生效。
- `tensai_jni.cpp` 是**已手工改名**的胶水印，故在 sweep 中跳过（否则重复前缀
  `lm_lm_ggml…`）。

### 3. ggml version macros
vendored CMakeLists 补 `-DLM_GGML_VERSION / LM_GGML_COMMIT`（patch 0002）——
上游由 build 系统注入，而 vendored CMake 缺失，编译期报"未声明"。

### 4. Vulkan UMA 补丁重生（patch 0001）
以 vendored 源码现状重新生成，逻辑与上游 llama.kt 相同（64-bit CEIL_DIV）。
本 app 固定 CPU 推理，Vulkan 仅用于保持编译完整性。实测（Adreno 830，`ngl=all`）
tg 仅 ~9 tok/s，比 CPU（23–27 tok/s）慢 1/3 —— UMA 设备上 dense 模型 CPU 优
于 Vulkan，与上游结论一致。**因此 app 默认 CPU-only**，GPU/NPU 暂不启用。

### 5. Offline 推理的正确姿势（R2 调试实录）
最初用「官方翻译指令直连 completion」在真机上产出退化文本（英文闲聊、杂语
重复、`<｜hy_begin…` 特殊字符泄漏）——同引擎参数的 llama.kt bench 复现 ⇒ 与
STQ1_0 内核无关；根因是 raw prompt **缺少 Hunyuan 的 chat 包络**，模型退化为
base-LM 行为（桌面 `llama-cli -st` 内部自带模板所以干净）。最终方案：
走 **GGUF 内置 chat template**（`LlamaEngine.formatChat(messages,
enableThinking=false)`）生成包络 prompt 再 `completion()` 流式输出。详见
`.trellis/spec/backend/llm-inference-contract.md`。

---

## 目录结构

```
app/
├─ engine/   # TranslationEngine（load/translate/cancel/unload）
│            #   + TranslationPrompts（纯函数，host JVM 可测；指令格式见 spec）+
│            #   Languages（33+ 语种清单 + detectSource 脚本启发式）
├─ download/ # DownloadWorker（WorkManager：stream → .part → SHA-256 → rename）
│            #   + ModelRepository（DataStore 登记）/ LocalModelSource（SAF 目录源）
├─ history/  # HistoryDatabase · TranslationRecord · HistoryDao（Room）
├─ settings/ # SettingsRepository（DataStore：语对/temperature/maxTokens/ctx/镜像）
└── ui/      # MainScreen / MainViewModel / interpreter UI（Compose M3）
libs/llama.kt/  # git submodule；patches/0001-0003 自定义补丁
scripts/        # render_launcher_glyph.swift / center_glyph.py（icon 工具链）
.trellis/spec/  # 项目规约（代码层契约）
.dsh/models/    # 桌面验证用已下载模型（sha256 校验通过）
```

### 推理参数（Hy-MT2 官方 1.8B 推荐）
`temperature=0.7 · top_k=20 · top_p=0.6 · min_p=0.05`；nPredict 默认 1024
（设置可调 64–4096）。上下文预算守卫：`nCtx − nPredict − 32` 为源文本最大
token 数，超限直接返回"输入过长"提示（不静默截断源文，避免"半翻译"）。

### 性能（PJZ110 / Snapdragon 8s / Adreno 830 真机）
| 后端 | 速度 | 说明 |
| --- | --- | --- |
| **CPU 大核 pin**（本项目实际使用） | **tg ≈ 23–27 tok/s**, pp ≈ 31 | UMA 设备上对外官方最优路线 |
| Vulkan GPU（Adreno 830, `ngl=all`） | tg ≈ 9, pp ≈ 25 | 实测：STQ 权重类型不被 Vulkan 支持，自动留 CPU，只把 KV/激活上 GPU，反而更慢 |
| NPU（Hexagon / MTK APU） | — | llama.cpp 无 NPU 后端，需要 QNN/NeuroPilot 完全不同的运行时（Hunyuan 官方有适配但不是这条管线） |

---

## 集成调试

- `am start -n com.yi.translator/com.yi.app.MainActivity --es source "<text>"`
  直接预填输入框（绕过 IME）。手动输入时（`adb shell input text`）中文 IME 会
  把 `%` / `.` 重构成全角（`％` / `。`）——不要用这个通道做自动化测试入口。
- `am start ... --es source` + `adb logcat -s YiEngine` 可直接看到
  formatChat 渲染后的 prompt 与完成状态（`done`、`truncated`、`tokensPerSec`）。

---

## 支持语言（Hy-MT2 全量 33+）

中 / 英 / 繁中 / 粤 / 法 / 葡 / 西 / 日 / 土 / 俄 / 阿 / 韩 / 泰 / 意 / 德 / 越 /
马 / 印尼 / 菲 / 印地 / 波 / 捷 / 荷 / 高棉 / 缅 / 波斯 / 古吉拉特 / 乌尔都 /
泰卢固 / 马拉地 / 希伯来 / 孟加拉 / 泰米尔 / 乌 / 藏 / 哈 / 蒙 / 维 / 乌克兰语
—— 详见 `app/.../engine/Languages.kt`（与官方 README 语言清单一致）。

---

## License

- app 代码：Apache-2.0（llama.cpp/llama.rn 与其依赖各自许可，见 llama.kt
  THIRD_PARTY_LICENSES.md）。
- 模型权重：Apache-2.0（tencent/Hy-MT2 主仓库）。
- STQ1_0 patch 源自 PR #22836（MIT-compatible llama.cpp 专用内核）——上游
  PR 截至 2026-06 未合并；日后 llama.kt/llama.cpp 升级需重新手动移植
  （patch 0003 为 deliberate vendored patch，bootstrap 断言其可应用）。
- 桌面 icon 使用 macOS 系统字体 Songti（苹果用户字体，仅 ARM Mac 生成工具链，
  期间不需要在设备上分发字体）。
