#!/usr/bin/env bash
# stats.sh - snapshot product stats for compose-hot-reload (GitHub + JetBrains Marketplace)
#
# Usage:
#   scripts/stats.sh            print a readable snapshot to stdout
#   scripts/stats.sh --csv      also append one row to ~/.hotreload-stats.csv
set -euo pipefail

REPO="xception-hash/compose-hot-reload"
PLUGIN_XML_ID="dev.hotreload.ide"
CSV_FILE="${HOME}/.hotreload-stats.csv"
CSV_MODE=0

for arg in "$@"; do
  case "$arg" in
    --csv)
      CSV_MODE=1
      ;;
  esac
done

TIMESTAMP="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

echo "Compose Hot Reload - product stats snapshot"
echo "Generated: ${TIMESTAMP}"
echo "Repo: ${REPO}"
echo

# ---------------------------------------------------------------------------
# 1. GitHub repo: stars, forks, open issues
# ---------------------------------------------------------------------------
STARS=""
FORKS=""
OPEN_ISSUES=""

echo "== GitHub repo =="
if REPO_JSON="$(gh api "repos/${REPO}" 2>/dev/null)"; then
  STARS="$(printf '%s' "$REPO_JSON" | jq -r '.stargazers_count // empty')"
  FORKS="$(printf '%s' "$REPO_JSON" | jq -r '.forks_count // empty')"
  OPEN_ISSUES="$(printf '%s' "$REPO_JSON" | jq -r '.open_issues_count // empty')"
  echo "Stars:        ${STARS:-unavailable}"
  echo "Forks:        ${FORKS:-unavailable}"
  echo "Open issues:  ${OPEN_ISSUES:-unavailable}"
else
  echo "unavailable"
fi
echo

# ---------------------------------------------------------------------------
# 2. Release downloads
# ---------------------------------------------------------------------------
RELEASE_DOWNLOADS_TOTAL=""

echo "== Release downloads =="
if RELEASES_JSON="$(gh api "repos/${REPO}/releases" 2>/dev/null)"; then
  RELEASE_COUNT="$(printf '%s' "$RELEASES_JSON" | jq 'length')"
  if [ "$RELEASE_COUNT" -eq 0 ]; then
    echo "No releases found."
  else
    printf '%s' "$RELEASES_JSON" | jq -r '
      .[] |
      "- \(.tag_name):" as $tag |
      $tag,
      (.assets[] | "    \(.name): \(.download_count)")
    '
  fi
  RELEASE_DOWNLOADS_TOTAL="$(printf '%s' "$RELEASES_JSON" | jq '[.[].assets[].download_count] | add // 0')"
  echo "Total downloads across all assets: ${RELEASE_DOWNLOADS_TOTAL}"
else
  echo "unavailable"
fi
echo

# ---------------------------------------------------------------------------
# 3. Traffic (14-day rolling): views + clones
# ---------------------------------------------------------------------------
VIEWS_14D=""
VIEWS_UNIQUES_14D=""
CLONES_14D=""
CLONES_UNIQUES_14D=""

echo "== Traffic (14-day rolling) =="
if VIEWS_JSON="$(gh api "repos/${REPO}/traffic/views" 2>/dev/null)"; then
  VIEWS_14D="$(printf '%s' "$VIEWS_JSON" | jq -r '.count // empty')"
  VIEWS_UNIQUES_14D="$(printf '%s' "$VIEWS_JSON" | jq -r '.uniques // empty')"
  echo "Views:        ${VIEWS_14D:-unavailable} (uniques: ${VIEWS_UNIQUES_14D:-unavailable})"
else
  echo "Views:        unavailable (requires push access to the repo)"
fi

if CLONES_JSON="$(gh api "repos/${REPO}/traffic/clones" 2>/dev/null)"; then
  CLONES_14D="$(printf '%s' "$CLONES_JSON" | jq -r '.count // empty')"
  CLONES_UNIQUES_14D="$(printf '%s' "$CLONES_JSON" | jq -r '.uniques // empty')"
  echo "Clones:       ${CLONES_14D:-unavailable} (uniques: ${CLONES_UNIQUES_14D:-unavailable})"
else
  echo "Clones:       unavailable (requires push access to the repo)"
fi
echo

# ---------------------------------------------------------------------------
# 4. JetBrains Marketplace
# ---------------------------------------------------------------------------
MP_DOWNLOADS=""
MP_RATING=""

echo "== JetBrains Marketplace (${PLUGIN_XML_ID}) =="
MP_ID=""

if SEARCH_JSON="$(curl -s 'https://plugins.jetbrains.com/api/searchPlugins?search=hot+reload+compose&max=50' 2>/dev/null)"; then
  MP_ID="$(printf '%s' "$SEARCH_JSON" | jq -r --arg xmlid "$PLUGIN_XML_ID" '.plugins[]? | select(.xmlId==$xmlid) | .id // empty' 2>/dev/null | head -n1 || true)"
fi

if [ -z "$MP_ID" ]; then
  if DIRECT_JSON="$(curl -s "https://plugins.jetbrains.com/api/plugins/intellij/${PLUGIN_XML_ID}" 2>/dev/null)"; then
    MP_ID="$(printf '%s' "$DIRECT_JSON" | jq -r '.id // empty' 2>/dev/null || true)"
  fi
fi

if [ -n "$MP_ID" ] && [ "$MP_ID" != "null" ]; then
  if PLUGIN_JSON="$(curl -s "https://plugins.jetbrains.com/api/plugins/${MP_ID}" 2>/dev/null)"; then
    MP_DOWNLOADS="$(printf '%s' "$PLUGIN_JSON" | jq -r '.downloads // empty' 2>/dev/null || true)"
  fi
  if RATING_JSON="$(curl -s "https://plugins.jetbrains.com/api/plugins/${MP_ID}/rating" 2>/dev/null)"; then
    MP_RATING="$(printf '%s' "$RATING_JSON" | jq -r 'if type=="object" then (.rating // .average // .meanRating // empty) else empty end' 2>/dev/null || true)"
  fi
  echo "Plugin ID:    ${MP_ID}"
  echo "Downloads:    ${MP_DOWNLOADS:-unavailable}"
  echo "Rating:       ${MP_RATING:-unavailable}"
else
  echo "Marketplace: plugin not yet public (0.1.2 in moderation)"
fi
echo

# ---------------------------------------------------------------------------
# 6. Optional CSV append
# ---------------------------------------------------------------------------
if [ "$CSV_MODE" -eq 1 ]; then
  if [ ! -f "$CSV_FILE" ]; then
    echo "timestamp,stars,forks,open_issues,release_downloads_total,views_14d,views_uniques_14d,clones_14d,clones_uniques_14d,mp_downloads,mp_rating" > "$CSV_FILE"
  fi
  echo "${TIMESTAMP},${STARS},${FORKS},${OPEN_ISSUES},${RELEASE_DOWNLOADS_TOTAL},${VIEWS_14D},${VIEWS_UNIQUES_14D},${CLONES_14D},${CLONES_UNIQUES_14D},${MP_DOWNLOADS},${MP_RATING}" >> "$CSV_FILE"
  echo "Appended snapshot row to ${CSV_FILE}"
fi
