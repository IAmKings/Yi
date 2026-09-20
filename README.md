# 译 Yi — Android 离线翻译 (Compose + llama.kt + Hy-MT2-1.8B @ 1.25-bit)

纯离线的 Android 翻译应用：[tencent/Hy-MT2-1.8B-1.25Bit-GGUF](https://huggingface.co/tencent/Hy-MT2-1.8B-1.25Bit-GGUF)
（AngelSlim Sherry/STQ1_0 量化，单文件 440MB，460,186,800 bytes，
SHA-256 `cc497fe8f033b52b3b8b00a7669e9661435432f9d4cd43f7ed24400c01507a93`）
通过 [llama.kt](https://github.com/hokanosekai/llama.kt)（git submodule，
JNI 绑定 llama.cpp）在设备端 CPU（钉大核，arm64-v8a）流式推理，完全离线。
UI 为 Jetpack Compose / Material 3，首启从 HuggingFace 下载模型并做 SHA-256 校验。

## 构建与运行

```bash
git submodule update --init --recursive
brew install cmake glslc gnu-sed   # macOS 依赖（Linux 无需 gnu-sed）
bash libs/llama.kt/scripts/build-opencl.sh   # 生成 jniLibs/libOpenCL.so stub
bash libs/llama.kt/scripts/bootstrap.sh      # vendor llama.cpp + 应用本地补丁
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- minSdk 29 / targetSdk 36 / arm64-v8a only / APK（不含模型）约 190MB（含全部
  vendored 后端）。模型不入包：App 首启在 Wi-Fi 下下载到 `filesDir`。
- 首次启动 UI 引导下载 → SHA-256 校验 → `LlamaEngine.load`（CPU、`nThreads=0`
  钉大核、`flashAttn="off"`、nCtx 4096）→ 装载完成后翻译完全离线可用。

## llama.kt 集成与本地补丁（关键决策）

1. **STQ1_0 内核（ggml type id 42）** — 该 1.25bit GGUF 依赖上游
   [PR #22836](https://github.com/ggml-org/llama.cpp/pull/22836)（Sherry
   1.3125 bpw ternary + ARM NEON `vqtbl2q`/`vdotq` vec_dot 内核，截至当前
   lama.cpp master 未合并）。`libs/llama.kt/patches/0003-stq1_0-support.patch`
   把 PR 的 C 侧改动移植到 vendored 改名体系：
   - `LM_GGML_TYPE_STQ1_0 = 42`（vendored llama.cpp 无 `GGML_TYPE_Q2_0`，42
     正好是空槽，与 AngelSlim GGUF 的张量 type id 42 对齐）、COUNT 43、
     `LLAMA_FTYPE_MOSTLY_STQ1_0 = 42`，block 布局/码本/量化与 dequant、
     CPU vec_dot（含 arm NEON 内核 + arch-fallback 兜底）、loader ftype。
   - 桌面验证（Mac, lib 与 vendored 同源）；"The weather is really nice
     today." → 「今天天气真的很不错。」 tg ≈ 48 t/s。
2. **macOS bootstrap 适配** — llama.kt 的 `sed -i`（GNU）与 `nproc` 在 macOS
   断裂；本地经 GNU sed（`brew install gnu-sed`）与 `nproc` shim 解决，所有
   符号改名（`ggml_`→`lm_ggml_`）才真正生效。`tensai_jni.cpp` 已按改名前缀
   手写，故在 sweep 中跳过（否则重复前缀 `lm_lm_ggml…`）。
3. **ggml version macros** — vendored CMakeLists 补 `-DLM_GGML_VERSION/
   COMMIT`（patch 0002），上游由 build 系统注入而 vendored CMake 缺失。
4. **Vulkan UMA 补丁重生**（patch 0001）— 依 vendored 源码现状重新生成，
   逻辑与上游 llama.kt 相同（64 位 CEIL_DIV 修复，upstream #23057）。
   本应用固定 CPU 推理，Vulkan 路径仅保持编译完整性。

## 推理参数（来自 Hy-MT2 官方 README，1.8B 推荐）

`temperature=0.7, top_p=0.6, top_k=20, min_p=0.05`；不依赖 chat template
（R2 兜底）：直接把官方 Default Translation 指令作为 prompt 走原始
completion 流（`translate()` → llama.kt `completion()`），EOS/stop 序列兜底
截断。性能（llama.kt 实测结论）：dense 模型在 UMA 设备上 CPU（大核）优于
Vulkan，故固定 CPU。

## 结构

- `app/src/main/java/com/yi/app/engine/` — TranslationEngine（Native 加载/
  流式翻译/中断/释放）、TranslationPrompts（Hy-MT2 指令构造，纯函数可测）
- `app/.../download/` — `DownloadWorker`（WorkManager，Stream→part→SHA-256
  校验→rename→登记）、`ModelRepository`（DataStore 登记 + 文件哈希）
- `app/.../settings/` — 语言对 / 采样 / ctx / 下载镜像 host
- `app/.../ui/` — Compose M3：模型管理卡（下载/校验/加载进度）、翻译主屏
  流式双侧文本、语言选择（33 语种全列表）
- `libs/llama.kt/patches/` — 0001 Vulkan UMA、0002 版本宏、0003 STQ1_0
- `.dsh/models/` — 桌面验证用已下载模型（sha256 校验通过）

## 支持语言（Hy-MT2 全量 33+）

中/英/繁中/粤/法/葡/西/日/土/俄/阿/韩/泰/意/德/越/马/印尼/菲/印地/波/捷/
荷/高棉/缅/波斯/古吉拉特/乌尔都/泰卢固/马拉地/希伯来/孟加拉/泰米尔/
乌/藏/哈/蒙/维/乌克兰语 —— `Languages.kt`。

## License

Apache-2.0（模型）。llama.cpp/llama.rn 遵循各仓库许可（见 llama.kt
THIRD_PARTY_LICENSES.md）。STQ1_0 补丁源自 PR #22836（MIT 许可协议对 llama.cpp
同样适用），遵循其上游状态（未合并 PR），未来 llama.kt/llama.cpp 升级时需重新
移植（patch 0003 为 deliberate vendored patch，bootstrap 断言其可应用）。
