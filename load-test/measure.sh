#!/usr/bin/env bash
#
# config 스윕 1회 측정 (워밍업 → 리셋 → 본측정 → Prometheus 카운터 델타로 처리율 산출)
#
# 처리율 = (issued_끝 - issued_시작) / 실측_소요초
#   앱 로그(초 단위) 대신 Prometheus 카운터 실측값을 직접 나눠 정밀도 확보.
#
# 전제: config는 호출 전에 이미 EC2에 적용+재시작+워밍업기동 되어 있어야 함.
# 사용법: ./load-test/measure.sh <label> [VUS]
#
set -uo pipefail
cd "$(dirname "$0")/.."

LABEL="${1:-run}"
VUS="${2:-1000}"
RAMP="${RAMP:-5}"          # t=0 접속 폭증 분산(초). connection reset 제거 + 측정 구간 안정화
BASE=http://3.24.46.153:8080
PROM=http://localhost:9090

q() { curl -s -m 5 "$PROM/api/v1/query?query=$1" 2>/dev/null | \
      python3 -c "import sys,json; r=json.load(sys.stdin)['data']['result']; print(r[0]['value'][1] if r else '0')" 2>/dev/null; }

echo "═══ [$LABEL] VUS=$VUS ═══"

# 1) 워밍업 (결과 버림)
echo "  워밍업 주행..."
./load-test/reset_ec2.sh >/dev/null 2>&1
k6 run -e BASE_URL=$BASE -e EVENT_ID=3 -e STOCK=1000 -e VUS=$VUS -e RAMP=$RAMP load-test/06_throughput.js >/tmp/measure_warmup.log 2>&1

# 2) 본 측정
./load-test/reset_ec2.sh >/dev/null 2>&1
sleep 6                                    # Prometheus 스크레이프(5s) 한 번 지나가게
LOCK_START=$(q queue_admission_lock_conflict_total)
T_START=$(date +%s)

k6 run -e BASE_URL=$BASE -e EVENT_ID=3 -e STOCK=1000 -e VUS=$VUS -e RAMP=$RAMP load-test/06_throughput.js >/tmp/measure_$LABEL.log 2>&1

T_END=$(date +%s)
sleep 8                                    # 마지막 처리분이 스크레이프에 잡히게
LOCK_END=$(q queue_admission_lock_conflict_total)

# 3) 산출 — query_range로 issued 시계열을 받아 "가장 가파른 구간" = 정상상태 처리율
curl -s -m 10 "$PROM/api/v1/query_range?query=queue_admission_issued_total&start=$((T_START-5))&end=$((T_END+8))&step=5" -o /tmp/measure_range.json 2>/dev/null
python3 - "$LOCK_START" "$LOCK_END" /tmp/measure_range.json <<'PY'
import sys,json
l0,l1=sys.argv[1:3]
d=json.load(open(sys.argv[3]))
r=d['data']['result']
if not r:
    print("  ⚠ issued 시계열 없음"); sys.exit()
vals=[(int(t),float(v)) for t,v in r[0]['values']]
# 연속 스크레이프 구간별 기울기 (명/초)
slopes=[]
for i in range(len(vals)-1):
    dt=vals[i+1][0]-vals[i][0]
    dv=vals[i+1][1]-vals[i][1]
    if dt>0 and dv>0: slopes.append(dv/dt)
di=vals[-1][1]-vals[0][1]
peak=max(slopes) if slopes else 0
# 상위 절반 평균 = 안정 처리 구간 (초반 워밍/말단 드레인 제외)
top=sorted(slopes,reverse=True)[:max(1,len(slopes)//2)]
avg=sum(top)/len(top) if top else 0
print(f"  issued Δ: {di:.0f}명, 구간 기울기: {[f'{s:.0f}' for s in slopes]}")
print(f"  ▶ 처리율(피크): {peak:.1f}/s")
print(f"  ▶ 처리율(안정, 상위구간 평균): {avg:.1f}/s")
print(f"  락충돌 Δ: {float(l1)-float(l0):.0f}")
PY

# 4) k6 지표 요약
echo "  ── k6 지표 ──"
grep -E "flow_success|approval_duration\.|e2e_duration\." /tmp/measure_$LABEL.log | sed 's/^/  /'
echo "  reset: $(grep -c 'connection reset by peer' /tmp/measure_$LABEL.log)건"
