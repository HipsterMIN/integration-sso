#!/bin/bash
# ============================================================
# Kafka 토픽 초기화 스크립트
# 통합인증 플랫폼 — 60,000명 급증 대응 파티션 설계 적용
#
# ── 환경별 파티션 설정 가이드 ──────────────────────────────────
# PoC  (단일 브로커): PARTITIONS_MAIN=12, RF=1, ISR=1
# 운영 (3-broker):   PARTITIONS_MAIN=12, RF=3, ISR=2
# 대규모 (5-broker): PARTITIONS_MAIN=24, RF=3, ISR=2
#
# ── 처리량 계산 ──────────────────────────────────────────────
# qsign.auth.events 12파티션 × concurrency 6 = ~1,200 events/s
# ido.handoff.events 12파티션 × concurrency 6 = ~1,200 events/s
# 60,000명 × 10분 → 피크 100 events/s → 충분
# 60,000명 × 1분  → 피크 1,000 events/s → 한계치, 24파티션 권장
# ============================================================

BROKER="${KAFKA_BROKER:-kafka:29092}"
MAX_RETRY=30
RETRY_INTERVAL=5

# ── 환경 변수로 파티션/복제 설정 오버라이드 가능 ────────────────
PARTITIONS_MAIN="${KAFKA_PARTITIONS_MAIN:-12}"   # 핵심 토픽 파티션 수
PARTITIONS_DLQ="${KAFKA_PARTITIONS_DLQ:-6}"      # DLQ 파티션 수 (MAIN의 절반)
PARTITIONS_QIM="${KAFKA_PARTITIONS_QIM:-12}"     # Q-IM 이벤트 파티션
RF="${KAFKA_REPLICATION_FACTOR:-1}"              # Replication Factor (운영: 3)
ISR="${KAFKA_MIN_INSYNC_REPLICAS:-1}"            # Min ISR (운영: 2)

# ── Kafka 준비 대기 ──────────────────────────────────────────
echo "⏳ Kafka 브로커 준비 대기 중..."
for i in $(seq 1 $MAX_RETRY); do
  if kafka-broker-api-versions --bootstrap-server "$BROKER" > /dev/null 2>&1; then
    echo "✅ Kafka 브로커 연결 성공 (시도 $i)"
    break
  fi
  if [ "$i" -eq "$MAX_RETRY" ]; then
    echo "❌ Kafka 브로커 연결 실패 (${MAX_RETRY}회 시도)"
    exit 1
  fi
  echo "  [$i/$MAX_RETRY] 재시도 대기 ${RETRY_INTERVAL}s..."
  sleep "$RETRY_INTERVAL"
done

echo ""
echo "📋 파티션 설정: MAIN=$PARTITIONS_MAIN DLQ=$PARTITIONS_DLQ RF=$RF ISR=$ISR"

# ── 토픽 생성 함수 ───────────────────────────────────────────
create_topic() {
  local topic="$1"
  local partitions="$2"
  local replication="$3"
  shift 3
  local configs=("$@")

  local config_args=""
  for cfg in "${configs[@]}"; do
    config_args="$config_args --config $cfg"
  done

  if kafka-topics --bootstrap-server "$BROKER" --describe --topic "$topic" > /dev/null 2>&1; then
    echo "  ⏭  이미 존재: $topic"
  else
    # shellcheck disable=SC2086
    kafka-topics --bootstrap-server "$BROKER" \
      --create \
      --topic "$topic" \
      --partitions "$partitions" \
      --replication-factor "$replication" \
      $config_args \
      && echo "  ✅ 생성 완료: $topic (partitions=$partitions, RF=$replication, ISR=$ISR)" \
      || echo "  ❌ 생성 실패: $topic"
  fi
}

# ── 파티션 수 증설 함수 (이미 존재하는 토픽 파티션 확장) ─────────
alter_partitions_if_needed() {
  local topic="$1"
  local target_partitions="$2"

  if kafka-topics --bootstrap-server "$BROKER" --describe --topic "$topic" > /dev/null 2>&1; then
    local current
    current=$(kafka-topics --bootstrap-server "$BROKER" --describe --topic "$topic" \
              | grep "PartitionCount" | awk '{print $4}' | head -1)
    if [ -n "$current" ] && [ "$current" -lt "$target_partitions" ]; then
      kafka-topics --bootstrap-server "$BROKER" \
        --alter \
        --topic "$topic" \
        --partitions "$target_partitions" \
        && echo "  ↗  파티션 증설: $topic ($current → $target_partitions)" \
        || echo "  ⚠  파티션 증설 실패: $topic"
    fi
  fi
}

echo ""
echo "══════════════════════════════════════════════════════"
echo " [1] Q-Sign 인증 이벤트 토픽 (60k 급증 핵심)"
echo "══════════════════════════════════════════════════════"
echo "   역할: Q-Sign 인증 완료 이벤트 → IdO Redis Pre-warming"
echo "   partitionKey: identifierHash"
echo "   consumer: QsignAuthEventConsumer (concurrency=6)"

# §9.3 Q-Sign 인증 결과 이벤트
# 60,000명 급증 핵심 토픽 — AUTH_COMPLETED 수신 즉시 Redis Pre-warming
# IdO QsignAuthEventConsumer → AuthResultCacheService.preWarm()
create_topic "qsign.auth.events" "$PARTITIONS_MAIN" "$RF" \
  "cleanup.policy=delete" \
  "retention.ms=3600000" \
  "compression.type=lz4" \
  "min.insync.replicas=$ISR" \
  "max.message.bytes=1048576"

# 기존 6파티션 → 12파티션 증설 (이미 운영 중인 경우)
alter_partitions_if_needed "qsign.auth.events" "$PARTITIONS_MAIN"

# Q-Sign DLQ
create_topic "qsign.auth.events.dlt" "$PARTITIONS_DLQ" "$RF" \
  "cleanup.policy=delete" \
  "retention.ms=604800000" \
  "compression.type=lz4"

echo ""
echo "══════════════════════════════════════════════════════"
echo " [2] Q-IM 사용자 이벤트 토픽"
echo "══════════════════════════════════════════════════════"
echo "   역할: Q-IM 상태 변경 → IdO 캐시 갱신"
echo "   partitionKey: qimUserId"
echo "   consumer: QimEventConsumer (concurrency=3)"

# §10.5.2 / §11.5.4 Q-IM 사용자 이벤트 (compacted log — 최신 상태 유지)
create_topic "qim.user.events" "$PARTITIONS_QIM" "$RF" \
  "cleanup.policy=compact" \
  "min.compaction.lag.ms=0" \
  "max.compaction.lag.ms=3600000" \
  "segment.bytes=104857600" \
  "delete.retention.ms=86400000" \
  "compression.type=lz4" \
  "min.insync.replicas=$ISR" \
  "max.message.bytes=1048576"

alter_partitions_if_needed "qim.user.events" "$PARTITIONS_QIM"

# Q-IM 사용자 스냅샷 (신규 컨슈머 빠른 복원용)
create_topic "qim.user.snapshot" "$PARTITIONS_QIM" "$RF" \
  "cleanup.policy=compact" \
  "min.compaction.lag.ms=0" \
  "segment.bytes=104857600" \
  "delete.retention.ms=86400000" \
  "compression.type=lz4" \
  "min.insync.replicas=$ISR" \
  "max.message.bytes=10485760"

# Q-IM DLQ
create_topic "qim.user.events.dlt" "$PARTITIONS_DLQ" "$RF" \
  "cleanup.policy=delete" \
  "retention.ms=604800000"

echo ""
echo "══════════════════════════════════════════════════════"
echo " [3] IdO Handoff 이벤트 토픽 (기관 webhook 트리거)"
echo "══════════════════════════════════════════════════════"
echo "   역할: Handoff 발급/소비/만료/취소 → 기관 HTTPS webhook"
echo "   partitionKey: correlationId"
echo "   consumer: HandoffEventConsumer (concurrency=6)"
echo ""
echo "   ⚡ 기관은 Kafka 직접 구독 불가 → HTTPS webhook으로 수신"
echo "      [ido.handoff.events] → HandoffEventConsumer"
echo "        → WebhookDispatcherService → webhook_dispatch_outbox"
echo "          → WebhookDispatchOutboxRelay → 기관 HTTPS POST"

# §16.3 IdO Handoff 이벤트
# HANDOFF_ISSUED → WebhookDispatcherService → 기관 webhook 발송
create_topic "ido.handoff.events" "$PARTITIONS_MAIN" "$RF" \
  "cleanup.policy=delete" \
  "retention.ms=31536000000" \
  "compression.type=lz4" \
  "min.insync.replicas=$ISR" \
  "max.message.bytes=1048576"

alter_partitions_if_needed "ido.handoff.events" "$PARTITIONS_MAIN"

# Handoff DLQ
create_topic "ido.handoff.events.dlq" "$PARTITIONS_DLQ" "$RF" \
  "cleanup.policy=delete" \
  "retention.ms=604800000"

echo ""
echo "══════════════════════════════════════════════════════"
echo " [4] Platform 세션 Advisory 토픽"
echo "══════════════════════════════════════════════════════"
echo "   역할: AUTH_LOCKED → FE 세션 강제 종료"
echo "   partitionKey: qimUserId"
echo "   consumer: FeAdvisoryConsumer (concurrency=3)"

# §14.9 / §12.5 세션 권고 이벤트
# AUTH_LOCKED → SessionAdvisoryPublisher → MANDATORY_SECURITY_TERMINATE
# FeAdvisoryConsumer → FE 세션 Redis 무효화
create_topic "platform.session.advisory" "$PARTITIONS_MAIN" "$RF" \
  "cleanup.policy=delete" \
  "retention.ms=86400000" \
  "compression.type=lz4" \
  "min.insync.replicas=$ISR" \
  "max.message.bytes=1048576"

alter_partitions_if_needed "platform.session.advisory" "$PARTITIONS_MAIN"

# Advisory DLQ
create_topic "platform.session.advisory.dlq" "$PARTITIONS_DLQ" "$RF" \
  "cleanup.policy=delete" \
  "retention.ms=604800000"

echo ""
echo "══════════════════════════════════════════════════════"
echo " [5] 감사 로그 토픽 (법적 보존 2년)"
echo "══════════════════════════════════════════════════════"
echo "   역할: 플랫폼 전역 감사 이벤트 중앙 수집"
echo "   partitionKey: agencyCode"
echo "   retention: 2년 (법적 요건)"

# 플랫폼 전역 감사 로그 (§15)
# AuditLogPublisher → platform.audit.log → DB 이중 저장 (2년)
create_topic "platform.audit.log" "$PARTITIONS_MAIN" "$RF" \
  "cleanup.policy=delete" \
  "retention.ms=63072000000" \
  "compression.type=lz4" \
  "min.insync.replicas=$ISR" \
  "max.message.bytes=2097152"

alter_partitions_if_needed "platform.audit.log" "$PARTITIONS_MAIN"

echo ""
echo "══════════════════════════════════════════════════════"
echo " [6] Q-IM SP 회원 이벤트 토픽 (기관 회원 연동)"
echo "══════════════════════════════════════════════════════"
echo "   역할: 회원 등록/이전/탈퇴 → 기관 webhook 통보"
echo "   partitionKey: instMbrId"
echo "   consumer: QimSpMemberEventConsumer (concurrency=2)"
echo ""
echo "   ⚡ 유관기관 CI/DN 회원조회 Kafka 직접구독 불가"
echo "      대신: QimSpMemberEventHandler → WebhookDispatcherService"
echo "        → webhook_dispatch_outbox → HTTPS POST 기관"

# QimSpReceiverService Outbox 발행 → QimSpMemberEventConsumer 소비
# → QimSpMemberEventHandler → WebhookDispatcherService → 기관 webhook
create_topic "qim.sp.member.events" 6 "$RF" \
  "cleanup.policy=delete" \
  "retention.ms=2592000000" \
  "compression.type=lz4" \
  "min.insync.replicas=$ISR"

# Q-IM SP 회원 DLQ
create_topic "qim.sp.member.events.dlt" 3 "$RF" \
  "cleanup.policy=delete" \
  "retention.ms=604800000"

echo ""
echo "══════════════════════════════════════════════════════"
echo " 토픽 목록 최종 확인"
echo "══════════════════════════════════════════════════════"
kafka-topics --bootstrap-server "$BROKER" --list | sort

echo ""
echo "══════════════════════════════════════════════════════"
echo " 주요 토픽 상세 정보"
echo "══════════════════════════════════════════════════════"
for TOPIC in "qsign.auth.events" "ido.handoff.events" "platform.session.advisory" "platform.audit.log"; do
  echo "--- $TOPIC ---"
  kafka-topics --bootstrap-server "$BROKER" --describe --topic "$TOPIC" 2>/dev/null || echo "  (토픽 없음)"
done

echo ""
echo "✅ Kafka 토픽 초기화 완료"
echo ""
echo "── 아키텍처 요약 ────────────────────────────────────────"
echo " qsign.auth.events ($PARTITIONS_MAIN p): Q-Sign → IdO Pre-warming (60k DB 폭발 방지)"
echo " ido.handoff.events ($PARTITIONS_MAIN p): IdO → 기관 webhook 트리거"
echo " platform.session.advisory ($PARTITIONS_MAIN p): AUTH_LOCKED → FE 세션 강제 종료"
echo " platform.audit.log ($PARTITIONS_MAIN p): 전역 감사 (2년 보존)"
echo " qim.sp.member.events (6p): SP 회원 연동 → 기관 webhook"
echo ""
echo " ⚠  기관은 Kafka 직접 접근 불가 — IdO가 HTTPS webhook으로 push"
echo "────────────────────────────────────────────────────────"
