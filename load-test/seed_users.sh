#!/usr/bin/env bash
#
# 부하테스트용 유저 토큰 사전 시딩 (2차 테스트 준비물)
#
# 왜 필요한가:
#   k6 setup()에서 유저를 순차 등록/로그인하면 유저당 ~0.4초가 든다.
#   200명이면 80초지만 10,000명이면 ~66분 → 측정 자체가 불가능.
#   여기서 병렬로 미리 등록+로그인해 토큰만 JSON으로 뽑아두고,
#   k6는 SharedArray로 읽기만 한다 (setup 비용 0, 매 실행 동일 조건).
#
# 사용법:
#   ./load-test/seed_users.sh                       # 기본: 1~1000번 유저
#   START=1 COUNT=10000 PARALLEL=80 ./load-test/seed_users.sh
#   BASE_URL=http://3.24.46.153:8080 COUNT=2000 ./load-test/seed_users.sh
#
# 출력: load-test/tokens.json  (["<jwt>", "<jwt>", ...])

set -uo pipefail

BASE_URL="${BASE_URL:-http://3.24.46.153:8080}"
START="${START:-1}"
COUNT="${COUNT:-1000}"
PARALLEL="${PARALLEL:-50}"
PASSWORD="${PASSWORD:-password123}"
OUT="${OUT:-$(dirname "$0")/tokens.json}"

TMPDIR_SEED=$(mktemp -d)
trap 'rm -rf "$TMPDIR_SEED"' EXIT
# 미리 만들어 두지 않으면 실패 0건일 때 wc가 "No such file" 에러를 뱉는다
: > "$TMPDIR_SEED/tokens.raw"
: > "$TMPDIR_SEED/failed.raw"

echo "[seed] BASE_URL=$BASE_URL"
echo "[seed] 유저 ${START} ~ $((START + COUNT - 1)) (${COUNT}명), 병렬=${PARALLEL}"
echo "[seed] 출력=$OUT"

# 서버 접근 확인 (여기서 막히면 뒤 작업이 전부 무의미)
if ! curl -s -m 8 -o /dev/null "$BASE_URL/actuator/health"; then
  echo "[seed] ❌ 서버에 접근할 수 없습니다: $BASE_URL"
  exit 1
fi

seed_one() {
  local i="$1"
  local email="lt${i}@test.com"

  # 등록 (이미 있으면 409 - 무시하고 로그인으로 진행)
  curl -s -m 15 -o /dev/null -X POST "$BASE_URL/api/members" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"${email}\",\"password\":\"${PASSWORD}\",\"name\":\"LT${i}\",\"phoneNumber\":\"010-0000-$(printf '%04d' $((i % 10000)))\"}"

  # 로그인 → 토큰 추출 (python 없이 sed로: {"token":"...","..."} 형태 가정)
  local token
  token=$(curl -s -m 15 -X POST "$BASE_URL/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"${email}\",\"password\":\"${PASSWORD}\"}" \
    | sed -n 's/.*"token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')

  if [ -n "$token" ]; then
    echo "$token" >> "$TMPDIR_SEED/tokens.raw"
  else
    echo "$i" >> "$TMPDIR_SEED/failed.raw"
  fi
}
export -f seed_one
export BASE_URL PASSWORD TMPDIR_SEED

echo "[seed] 진행 중... (진행률은 아래 카운트로 확인)"
seq "$START" "$((START + COUNT - 1))" \
  | xargs -P "$PARALLEL" -I{} bash -c 'seed_one {}'

OK=$(wc -l < "$TMPDIR_SEED/tokens.raw" 2>/dev/null | tr -d ' ')
FAIL=$(wc -l < "$TMPDIR_SEED/failed.raw" 2>/dev/null | tr -d ' ')
OK=${OK:-0}; FAIL=${FAIL:-0}

if [ "$OK" -eq 0 ]; then
  echo "[seed] ❌ 토큰을 하나도 못 받았습니다. 서버/계정 설정을 확인하세요."
  exit 1
fi

# JSON 배열로 조립
{
  echo -n '['
  awk 'NR>1{printf ","} {printf "\"%s\"", $0}' "$TMPDIR_SEED/tokens.raw"
  echo ']'
} > "$OUT"

echo "[seed] ✅ 완료: 성공 ${OK}명 / 실패 ${FAIL}명 → $OUT"
[ "$FAIL" -gt 0 ] && echo "[seed] ⚠️  실패분이 있으니 tokens.json 개수를 k6 VU 수와 맞춰 확인하세요."
exit 0
