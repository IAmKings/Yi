# Changelog

All notable changes to this project will be documented in this file.
Format loosely based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
versioning follows [SemVer](https://semver.org/).

## [Unreleased]
### Added
- 首个可用版本：Hy-MT2-1.8B @ 1.25-bit (STQ1_0, 440MB) 纯离线翻译。
- llama.kt vendored 集成：STQ1_0 NEON 内核（PR #22836 移植，patch 0003），
  ggml version 宏（0002），Vulkan UMA 修复重生成（0001）。
- 模型目录优先加载（SAF 目录扫描 → SHA-256 校验 → 登记）；无文件时 Wi-Fi
  下载 fallback（WorkManager 断点续传）。
- 翻译主屏：流式输出、语对切换（源 / 目标独立）、`⇄` 交换、自动检测源
  （脚本启发式 + `识别为：xx` 提示）、同语言组合禁选。
- 历史（Room，最近 50 条）：回填 / 单条删除 / 清空历史。
- 会话语义：原文⇄译文成对保存与恢复，编辑原文即失效，✕ 一键复位。
- 上下文预算守卫：envelope + source + maxTokens > nCtx 直接报错，不静默截断。
- 设置面板：temperature / maxTokens / 上下文长度（改 ctx 自动重载）/
  下载镜像 host（huggingface / hf-mirror）。
- 深海靛蓝主题（indigo × sky blue），launcher icon 同步主题色，字形选自
  macOS Songti 衬线渲染生成（scripts/render_launcher_glyph.swift +
  scripts/center_glyph.py）。
- GitHub Actions：push → build + unit tests；tag `v*` → Release（debug +
  signed release APK，keystore 由 repo secrets 提供）。

## [0.1.0] - 2026-09-21
### Added
- 首个可用版本：Hy-MT2-1.8B @ 1.25-bit (STQ1_0, 440MB) 纯离线翻译。
- llama.kt vendored 集成 + STQ1_0 内核移植（本地 patch 0001–0003）。
- 模型目录优先加载 + SHA-256 校验 + Wi-Fi 下载 fallback。
- 历史与设置面板、上下文预算守卫、深海靛蓝主题。
