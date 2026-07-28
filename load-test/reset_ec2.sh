#!/usr/bin/env bash
#
# 부하테스트 전 EC2 상태 초기화 (클린 출발선 보장)
#
# 1차 실험의 교훈: 통제되지 않은 환경(이전 실행 잔여물)은 거짓 결과를 만든다.
# 2차 스윕은 config를 바꿔가며 여러 번 돌리므로, 매 회차 동일 출발선이 필수다.
#
# 하는 일:
#   1) MySQL - bookings/tickets/payments 전삭제 (회원 계정은 유지: k6 setup 시간 절약)
#   2) MySQL - 테스트 이벤트 재고/상태 리셋 + 공연일시를 미래로
#      ※ EventScheduler.completeExpiredEvents()가 기동 시마다 실행되어
#        start_date_time이 과거인 이벤트를 COMPLETED로 바꿔버림.
#        공연일시를 미래로 두어야 재시작해도 ON_SALE이 유지된다.
#   3) Redis - FLUSHDB (대기열/결제세션 잔여물 제거)
#
# 사용법:
#   ./load-test/reset_ec2.sh              # 기본: 이벤트 3,4 재고 1000
#   STOCK=100 ./load-test/reset_ec2.sh    # 재고 100으로 (SLO 시나리오별)
#   EVENT_IDS=3 ./load-test/reset_ec2.sh  # 특정 이벤트만
#
set -euo pipefail

EC2_HOST=${EC2_HOST:-3.24.46.153}
EC2_USER=${EC2_USER:-ubuntu}
SSH_KEY=${SSH_KEY:-$HOME/ticket-go-ec2-key.pem}
EVENT_IDS=${EVENT_IDS:-3,4}
STOCK=${STOCK:-1000}

MYSQL="docker exec ticketgo-mysql mysql -uroot -pticketgo1234 ticket_go -N -B"
REDIS="docker exec ticketgo-redis redis-cli"

echo "=== EC2 리셋 시작 (events=[$EVENT_IDS], stock=$STOCK) ==="

ssh -i "$SSH_KEY" -o BatchMode=yes -o ConnectTimeout=10 "$EC2_USER@$EC2_HOST" \
    "EVENT_IDS='$EVENT_IDS' STOCK='$STOCK' bash -s" <<'REMOTE'
set -euo pipefail
MYSQL="docker exec ticketgo-mysql mysql -uroot -pticketgo1234 ticket_go -N -B"
REDIS="docker exec ticketgo-redis redis-cli"

echo "--- 1) 예매 데이터 삭제 ---"
# FK 순서: payments -> tickets -> bookings
$MYSQL -e "
SET FOREIGN_KEY_CHECKS=0;
DELETE FROM payments;
DELETE FROM tickets;
DELETE FROM bookings;
SET FOREIGN_KEY_CHECKS=1;
" 2>/dev/null
echo "삭제 완료"

echo "--- 2) 이벤트 리셋 (재고/상태/일시) ---"
# start_date_time을 미래로 두어야 EventScheduler가 COMPLETED로 바꾸지 않음
$MYSQL -e "
UPDATE events SET
  status              = 'ON_SALE',
  total_tickets       = ${STOCK},
  available_tickets   = ${STOCK},
  version             = 0,
  sale_start_date_time= '2020-01-01 00:00:00',
  sale_end_date_time  = '2030-12-30 00:00:00',
  start_date_time     = '2030-12-31 00:00:00',
  updated_at          = NOW()
WHERE id IN (${EVENT_IDS});
" 2>/dev/null
echo "리셋 완료"

echo "--- 3) Redis FLUSHDB ---"
$REDIS FLUSHDB 2>/dev/null

echo ""
echo "--- 검증 ---"
echo "[이벤트]"
$MYSQL -e "SELECT id, status, total_tickets, available_tickets, start_date_time FROM events WHERE id IN (${EVENT_IDS});" 2>/dev/null
echo "[잔여 데이터] bookings/tickets/payments:"
$MYSQL -e "SELECT (SELECT COUNT(*) FROM bookings), (SELECT COUNT(*) FROM tickets), (SELECT COUNT(*) FROM payments);" 2>/dev/null
echo "[Redis 키 수]"
$REDIS DBSIZE 2>/dev/null
REMOTE

echo ""
echo "✅ 리셋 완료 — 클린 출발선 확보"
