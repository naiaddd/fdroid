#!/bin/sh
set -eu
: "${GH_STATE:?GH_STATE must name a state directory}"
: "${GH_CALLS:?GH_CALLS must name a call log}"
mkdir -p "$GH_STATE"
printf '%s\n' "$*" >>"$GH_CALLS"

cmd=${1:-}
sub=${2:-}
tag=${3:-}
case "$cmd $sub" in
  "auth status")
    ;;
  "release view")
    if ! test -f "$GH_STATE/created"; then
      echo 'release not found' >&2
      exit 1
    fi
    draft=false
    test -f "$GH_STATE/published" || draft=true
    printf '{"isDraft":%s,"assets":[' "$draft"
    first=true
    for f in "$GH_STATE"/asset.*; do
      test -f "$f" || continue
      base=${f##*/}
      name=${base#asset.}
      size=$(wc -c <"$f" | tr -d ' ')
      $first || printf ','
      first=false
      printf '{"name":"%s","size":%s}' "$name" "$size"
    done
    printf ']}\n'
    ;;
  "release create")
    : >"$GH_STATE/created"
    if test -f "$GH_STATE/fail-create"; then
      rm -f "$GH_STATE/fail-create"
      exit 42
    fi
    ;;
  "release upload")
    shift 3
    while test "$#" -gt 0; do
      case "$1" in
        --repo) shift 2 ;;
        --clobber) shift ;;
        *)
          base=${1##*/}
          cp "$1" "$GH_STATE/asset.$base"
          shift
          ;;
      esac
    done
    ;;
  "release edit")
    : >"$GH_STATE/published"
    ;;
  "api "*)
    case "$2" in
      */releases/tags/*)
        test -f "$GH_STATE/published" || exit 1
        tag=${2##*/}
        ;;
      *) printf '{"full_name":"example/repo"}\n'; exit 0 ;;
    esac
    printf '{"tag_name":"%s","draft":false,"assets":[' "$tag"
    first=true
    for f in "$GH_STATE"/asset.*; do
      test -f "$f" || continue
      base=${f##*/}
      name=${base#asset.}
      size=$(wc -c <"$f" | tr -d ' ')
      $first || printf ','
      first=false
      printf '{"name":"%s","size":%s}' "$name" "$size"
    done
    printf ']}\n'
    ;;
  "release download")
    shift 3
    out=
    pattern=
    while test "$#" -gt 0; do
      case "$1" in
        --repo) shift 2 ;;
        --pattern) pattern=$2; shift 2 ;;
        --dir) out=$2; shift 2 ;;
        *) shift ;;
      esac
    done
    test -n "$out" -a -n "$pattern"
    mkdir -p "$out"
    cp "$GH_STATE/asset.$pattern" "$out/$pattern"
    ;;
  *) exit 2 ;;
esac
