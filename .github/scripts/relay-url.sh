#!/usr/bin/env bash
# Prints the relay's address (https://inkcalidraw-live.<subdomain>.workers.dev) from the Cloudflare account behind
# CLOUDFLARE_API_TOKEN, and exports CLOUDFLARE_ACCOUNT_ID for wrangler. Prints nothing if no token is set.
# With --ensure-subdomain it registers a workers.dev subdomain if the account has none yet.
set -euo pipefail
[ -n "${CLOUDFLARE_API_TOKEN:-}" ] || exit 0
api() { curl -fsS -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" -H "Content-Type: application/json" "$@"; }
account="${CLOUDFLARE_ACCOUNT_ID:-}"
if [ -z "$account" ]; then account=$(api https://api.cloudflare.com/client/v4/accounts | jq -r '.result[0].id'); fi
[ -n "$account" ] && [ "$account" != "null" ] || { echo "Cloudflare token can't list accounts" >&2; exit 1; }
[ -n "${GITHUB_ENV:-}" ] && echo "CLOUDFLARE_ACCOUNT_ID=$account" >> "$GITHUB_ENV"
sub=$(api "https://api.cloudflare.com/client/v4/accounts/$account/workers/subdomain" | jq -r '.result.subdomain // empty' || true)
if [ -z "$sub" ] && [ "${1:-}" = "--ensure-subdomain" ]; then
  want="${GITHUB_REPOSITORY_OWNER:-inkcalidraw}"; want=$(echo "$want" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9-')
  sub=$(api -X PUT "https://api.cloudflare.com/client/v4/accounts/$account/workers/subdomain" --data "{\"subdomain\":\"$want\"}" | jq -r '.result.subdomain // empty')
fi
[ -n "$sub" ] || { echo "No workers.dev subdomain on this Cloudflare account yet" >&2; exit 1; }
echo "https://inkcalidraw-live.$sub.workers.dev"
