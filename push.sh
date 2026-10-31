#!/bin/sh
# 有了 GitHub Token 之后，一条命令建仓 + 推代码：
#   GH_TOKEN=ghp_xxx sh push.sh
# 推上去后 .github/workflows/build.yml 会自动构建，去 Actions 页下 app-release artifact
set -e
: "${GH_TOKEN:?请设置 GH_TOKEN=你的 token（需要 repo 和 workflow 权限）}"
REPO=${REPO:-pd2546root}

USER=$(curl -s -H "Authorization: Bearer $GH_TOKEN" https://api.github.com/user \
  | sed -n 's/.*"login": *"\([^"]*\)".*/\1/p')
[ -n "$USER" ] || { echo "token 无效"; exit 1; }
echo "账号: $USER  仓库: $REPO"

curl -s -o /dev/null -X POST \
  -H "Authorization: Bearer $GH_TOKEN" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/user/repos \
  -d "{\"name\":\"$REPO\",\"private\":true}" || true

git init -q
git add -A
git -c user.name=pd2546 -c user.email=pd2546@local commit -qm "PD2546 one-click root (Miuix + Shizuku)" || true
git branch -M main
git remote remove origin 2>/dev/null || true
git remote add origin "https://x-access-token:$GH_TOKEN@github.com/$USER/$REPO.git"
git push -u origin main

echo
echo "已推送: https://github.com/$USER/$REPO/actions"
echo "等构建完成后，在该页面的 Artifacts 里下载 app-release"
