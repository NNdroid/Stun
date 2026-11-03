#!/usr/bin/env bash
#
# bump-submodule.sh — 把某个 git submodule 抬到其远端默认分支最新提交，
# 并（可选地）做编译冒烟测试，最后把新指针 git add 暂存。
#
# ⚠️ 不自动提交、不自动推送——提交/推送由调用者按仓库铁律（改动留 working tree）决定。
#
# 用法：
#   scripts/bump-submodule.sh [--dry-run] [--check] [submodule-path]
#     --dry-run   只报告将要做的变更，不 checkout、不编译、不 git add
#     --check     同 --dry-run，但以非零退出码标记「有可用更新」（供 CI 调用）
#   submodule-path  默认 core/jni/myssh
#
# 设计约束（来自本仓库的踩坑记录）：
#   - 本机 PortableGit 在子模块指针漂移 / 非交互凭据上有各种坑；本脚本不假设任何
#     全局配置，凭据走 git 自身（仓库已设 local credential.helper=wincred）。
#   - Android AAR 真正产物需要 NDK + gomobile，本机常缺；缺时退化为 host go build/vet。

set -euo pipefail

DRY_RUN=0
CHECK=0
SUB="core/jni/myssh"

for a in "$@"; do
  case "$a" in
    --dry-run) DRY_RUN=1 ;;
    --check)   CHECK=1; DRY_RUN=1 ;;
    *)         SUB="$a" ;;
  esac
done

# 必须在仓库根目录运行
cd "$(git rev-parse --show-toplevel)"

if [ ! -d "$SUB/.git" ] && [ ! -f "$SUB/.git" ]; then
  echo "::error::'$SUB' 不是已初始化的 submodule（找不到 .git）" >&2
  exit 1
fi

echo "==> 目标子模块: $SUB"

# 1) 取当前记录的指针（父仓库 gitlink）
RECORDED=$(git ls-tree HEAD "$SUB" | awk '{print $3}')
if [ -z "$RECORDED" ] || [ "$RECORDED" = "0000000000000000000000000000000000000000" ]; then
  echo "::error::无法读取 '$SUB' 的记录指针（父仓库未登记该 submodule？）" >&2
  exit 1
fi
RECORDED_MSG=$(git -C "$SUB" log -1 --oneline "$RECORDED" 2>/dev/null || echo "?")
echo "    记录指针 : $RECORDED ($RECORDED_MSG)"

# 2) fetch 子模块远端，确定默认分支最新
git -C "$SUB" fetch origin --quiet || echo "::warning::fetch $SUB 远端失败，以下比对基于本地 origin/*"

# 探测默认分支：优先 origin/HEAD，回落 main / master
DEFAULT_BRANCH=$(git -C "$SUB" symbolic-ref --short refs/remotes/origin/HEAD 2>/dev/null || echo "")
if [ -z "$DEFAULT_BRANCH" ]; then
  for b in main master; do
    if git -C "$SUB" rev-parse --verify -q "origin/$b" >/dev/null; then
      DEFAULT_BRANCH="origin/$b"; break
    fi
  done
fi
if [ -z "$DEFAULT_BRANCH" ]; then
  echo "::error::无法确定 $SUB 的远端默认分支" >&2
  exit 1
fi

LATEST=$(git -C "$SUB" rev-parse "$DEFAULT_BRANCH")
LATEST_MSG=$(git -C "$SUB" log -1 --oneline "$LATEST" 2>/dev/null || echo "?")
echo "    远端最新 : $LATEST ($DEFAULT_BRANCH / $LATEST_MSG)"

if [ "$RECORDED" = "$LATEST" ]; then
  echo "✅ 已是最新，无需 bump。"
  exit 0
fi

echo "==> 将带入以下提交（记录指针 → 远端最新）："
git -C "$SUB" log --oneline "$RECORDED..$LATEST" | sed 's/^/    /'

if [ "$DRY_RUN" -eq 1 ]; then
  echo "（--dry-run：未做任何改动）"
  [ "$CHECK" -eq 1 ] && exit 1   # 有可用更新 → 非零退出，供 CI 判定
  exit 0
fi

# 3) 切到最新（detached HEAD 在最新提交）
echo "==> checkout $LATEST"
git -C "$SUB" checkout --detach "$LATEST"

# 4) 编译冒烟测试
echo "==> 编译冒烟测试"
if command -v gomobile >/dev/null 2>&1 && [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "${ANDROID_NDK_HOME}" ]; then
  echo "    检测到 gomobile + NDK，运行 build_android_libs.sh"
  ( cd "$SUB" && ./scripts/build_android_libs.sh )
else
  echo "    未检测到 gomobile+NDK，退化为 host go build/vet（Android AAR 需 CI / 带 NDK 环境）"
  ( cd "$SUB" && go build ./... && go vet ./... )
fi

# 5) 暂存新指针（不提交）
echo "==> git add $SUB（仅暂存，未提交）"
git add "$SUB"
echo "✅ 完成。父仓库已暂存 $SUB → $LATEST。请审阅后按铁律自行 commit / push。"
