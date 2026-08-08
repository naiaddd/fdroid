#!/usr/bin/env bash
# Idempotent GitHub release publisher.
#
# The release is created as a draft, its assets are uploaded with --clobber,
# and only then is it published.  A run which stops after create can therefore
# be resumed with the same tag and version.

set -o pipefail

_release_log() {
    printf '[%s] %s\n' "$(date '+%d/%m/%y %H:%M:%S %z')" "$*" >&2
}

_release_die() {
    _release_log "ERROR: $*"
    return 1
}

# Publish one release.
#
#   github_release_publish REPO TAG TITLE NOTES ASSET [ASSET ...]
#
# gh is deliberately looked up at call time, so tests (and callers) can put a
# fake gh executable first in PATH.
github_release_publish() {
    local repo=${1:?repository is required}
    local tag=${2:?tag is required}
    local title=${3:?title is required}
    local notes=${4:-No release notes provided.}
    shift 4
    local assets=("$@")
    local asset path name
    local view_json api_json view_err view_status
    local is_draft
    local script_root artifact_root artifact_dir manifest manifest_tmp hash
    local -a preserved_assets=()
    local -a expected_names=()
    local asset_count digest expected_hash download_dir downloaded source_hash

    [[ -n "$repo" && -n "$tag" ]] || {
        _release_die "repository and tag must be non-empty"
        return 1
    }
    ((${#assets[@]} > 0)) || {
        _release_die "at least one release asset is required"
        return 1
    }
    # Keep a durable, checksummed copy outside release_staging.  A retry must
    # use these bytes even if the build later rewrites its staging APK.
    script_root=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
    artifact_root=${RELEASE_ARTIFACT_DIR:-"$script_root/tmp/release_artifacts"}
    [[ "$tag" != */* && "$tag" != *..* ]] || {
        _release_die "tag contains an unsafe path component: $tag"
        return 1
    }
    artifact_dir="$artifact_root/$tag"
    manifest="$artifact_dir/assets.sha256"
    mkdir -p "$artifact_dir" || {
        _release_die "cannot create artifact directory: $artifact_dir"
        return 1
    }
    local manifest_name manifest_hash
    if [[ -f "$manifest" ]]; then
        while read -r manifest_hash manifest_name; do
            [[ -n "$manifest_name" ]] || continue
            expected_names+=("$manifest_name")
        done <"$manifest"
        ((${#expected_names[@]} == ${#assets[@]})) || {
            _release_die "preserved asset set does not match requested assets"
            return 1
        }
        for path in "${assets[@]}"; do
            name=${path##*/}
            printf '%s\n' "${expected_names[@]}" | grep -Fqx -- "$name" || {
                _release_die "preserved asset missing from manifest: $name"
                return 1
            }
            [[ -f "$artifact_dir/$name" ]] || {
                _release_die "preserved asset file is missing: $name"
                return 1
            }
            (cd "$artifact_dir" && sha256sum -c assets.sha256 --status) \
                2>/dev/null || {
                _release_die "preserved asset checksum mismatch in $artifact_dir"
                return 1
            }
            preserved_assets+=("$artifact_dir/$name")
        done
        _release_log "Reusing preserved assets from $artifact_dir"
    else
        manifest_tmp="$manifest.tmp.$$"
        : >"$manifest_tmp" || return 1
        for path in "${assets[@]}"; do
            name=${path##*/}
            [[ -f "$path" ]] || {
                rm -f "$manifest_tmp"
                _release_die "asset does not exist: $path"
                return 1
            }
            [[ -s "$path" ]] || {
                rm -f "$manifest_tmp"
                _release_die "asset is empty: $path"
                return 1
            }
            for manifest_name in "${expected_names[@]}"; do
                [[ "$manifest_name" != "$name" ]] || {
                    rm -f "$manifest_tmp"
                    _release_die "duplicate asset name: $name"
                    return 1
                }
            done
            cp -- "$path" "$artifact_dir/$name" || {
                rm -f "$manifest_tmp"
                _release_die "could not preserve asset: $name"
                return 1
            }
            source_hash=$(sha256sum -- "$path" | awk '{print $1}')
            hash=$(sha256sum -- "$artifact_dir/$name" | awk '{print $1}')
            [[ "$hash" == "$source_hash" ]] || {
                rm -f "$manifest_tmp"
                _release_die "preserved asset checksum differs from source: $name"
                return 1
            }
            printf '%s  %s\n' "$hash" "$name" >>"$manifest_tmp"
            expected_names+=("$name")
            preserved_assets+=("$artifact_dir/$name")
        done
        mv -f -- "$manifest_tmp" "$manifest" || return 1
        _release_log "Preserved checksummed assets in $artifact_dir"
    fi

    _release_log "Checking gh authentication and repository API access"
    gh auth status >/dev/null 2>&1 || {
        _release_die "gh authentication is not ready"
        return 1
    }
    gh api "repos/$repo" >/dev/null || {
        _release_die "cannot access repository API: $repo"
        return 1
    }

    _release_log "Checking release $tag in $repo"
    view_err=$(mktemp)
    # Only a real not-found result permits creation. Authentication and network
    # errors must not be mistaken for a missing release.
    if view_json=$(gh release view "$tag" --repo "$repo" \
        --json isDraft,assets 2>"$view_err"); then
        view_status=0
    else
        view_status=$?
    fi
    if ((view_status == 0)); then
        is_draft=$(jq -r '.isDraft // false' <<<"$view_json") || {
            rm -f "$view_err"
            _release_die "gh returned invalid release metadata for $tag"
            return 1
        }
        _release_log "Found existing $([[ "$is_draft" == true ]] && echo draft || echo published) release"
    else
        if ! grep -Eqi 'release not found|HTTP 404|status code 404' "$view_err"; then
            _release_log "gh release view failed: $(tr '\n' ' ' <"$view_err")"
            rm -f "$view_err"
            _release_die "could not determine release state for $tag"
            return 1
        fi
        _release_log "Release was not found: $(tr '\n' ' ' <"$view_err")"
        _release_log "Creating draft $tag"
        if ! gh release create "$tag" --repo "$repo" --title "$title" \
            --notes "$notes" --draft; then
            rm -f "$view_err"
            _release_die "could not create draft release $tag"
            return 1
        fi
    fi
    rm -f "$view_err"

    _release_log "Uploading ${#assets[@]} required asset(s) with --clobber"
    if ! gh release upload "$tag" "${preserved_assets[@]}" --repo "$repo" --clobber; then
        _release_die "asset upload failed for $tag (the draft can be resumed)"
        return 1
    fi

    _release_log "Publishing release $tag"
    # Explicitly clear draft even when the release was already published.  It
    # makes the final state deterministic and lets a resumed draft complete.
    if ! gh release edit "$tag" --repo "$repo" --draft=false; then
        _release_die "could not publish release $tag"
        return 1
    fi

    _release_log "Verifying release metadata and required assets"
    if ! view_json=$(gh release view "$tag" --repo "$repo" \
        --json isDraft,assets); then
        _release_die "published release $tag is not readable"
        return 1
    fi
    if ! jq -e '.isDraft == false' >/dev/null <<<"$view_json"; then
        _release_die "release $tag is still a draft"
        return 1
    fi
    asset_count=$(jq '.assets | length' <<<"$view_json") || {
        _release_die "published release $tag returned invalid asset metadata"
        return 1
    }
    if ((asset_count != ${#assets[@]})); then
        _release_die "release $tag has $asset_count assets; expected ${#assets[@]}"
        return 1
    fi
    for path in "${assets[@]}"; do
        name=${path##*/}
        if ! jq -e --arg n "$name" \
            '.assets[]? | select(.name == $n and ((.size // 0) > 0))' \
            >/dev/null <<<"$view_json"; then
            _release_die "required non-empty asset is missing: $name"
            return 1
        fi
    done

    _release_log "Verifying authenticated GitHub API visibility for $tag"
    if ! api_json=$(gh api "repos/$repo/releases/tags/$tag"); then
        _release_die "release $tag is not visible through the authenticated GitHub API"
        return 1
    fi
    if ! jq -e --arg t "$tag" --argjson n "${#assets[@]}" '
        .tag_name == $t and .draft == false and
        ((.assets // []) | length) == $n and
        ((.assets // []) | all((.size // 0) > 0))
    ' >/dev/null <<<"$api_json"; then
        _release_die "authenticated GitHub API returned an incomplete release for $tag"
        return 1
    fi
    # Confirm the bytes GitHub serves.  Newer APIs include a SHA-256 digest;
    # older GitHub Enterprise/gh combinations do not, so download as a
    # fallback and compare with the preserved manifest.
    download_dir=$(mktemp -d)
    for path in "${assets[@]}"; do
        name=${path##*/}
        expected_hash=$(awk -v n="$name" '$2 == n {print $1}' "$manifest")
        digest=$(jq -r --arg n "$name" \
            '.assets[]? | select(.name == $n) | (.digest // "")' \
            <<<"$api_json" | head -1)
        if [[ "$digest" == sha256:* ]]; then
            [[ "${digest#sha256:}" == "$expected_hash" ]] || {
                rm -rf "$download_dir"
                _release_die "GitHub digest differs for asset: $name"
                return 1
            }
            continue
        fi
        gh release download "$tag" --repo "$repo" --pattern "$name" \
            --dir "$download_dir" >/dev/null || {
            rm -rf "$download_dir"
            _release_die "could not download uploaded asset for verification: $name"
            return 1
        }
        downloaded="$download_dir/$name"
        [[ -f "$downloaded" ]] || {
            rm -rf "$download_dir"
            _release_die "downloaded asset has an unexpected name: $name"
            return 1
        }
        [[ "$(sha256sum -- "$downloaded" | awk '{print $1}')" == "$expected_hash" ]] || {
            rm -rf "$download_dir"
            _release_die "downloaded bytes differ for asset: $name"
            return 1
        }
    done
    rm -rf "$download_dir"
    _release_log "Release $tag is published and visible through authenticated GitHub API"
}

github_release_usage() {
    cat >&2 <<'EOF'
Usage: github_release.sh --repo OWNER/REPO --tag TAG --title TITLE \
  [--notes NOTES] --asset FILE [--asset FILE ...]
EOF
}

github_release_main() {
    local repo= tag= title= notes='No release notes provided.'
    local -a assets=()
    while (($#)); do
        case $1 in
            --repo) [[ $# -ge 2 ]] || { github_release_usage; return 2; }; repo=$2; shift 2 ;;
            --tag) [[ $# -ge 2 ]] || { github_release_usage; return 2; }; tag=$2; shift 2 ;;
            --title) [[ $# -ge 2 ]] || { github_release_usage; return 2; }; title=$2; shift 2 ;;
            --notes) [[ $# -ge 2 ]] || { github_release_usage; return 2; }; notes=$2; shift 2 ;;
            --asset) [[ $# -ge 2 ]] || { github_release_usage; return 2; }; assets+=("$2"); shift 2 ;;
            -h|--help) github_release_usage; return 0 ;;
            *) _release_die "unknown option: $1"; github_release_usage; return 2 ;;
        esac
    done
    [[ -n "$repo" && -n "$tag" && -n "$title" && ${#assets[@]} -gt 0 ]] || {
        github_release_usage
        return 2
    }
    github_release_publish "$repo" "$tag" "$title" "$notes" "${assets[@]}"
}

if [[ ${BASH_SOURCE[0]} == "$0" ]]; then
    github_release_main "$@"
fi
