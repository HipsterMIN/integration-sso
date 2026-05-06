#!/bin/bash
# ============================================================
# Kafka 토픽 초기화 스크립트
# 통합인증 플랫폼 PoC — 설계서 토픽 설계 기반
# ============================================================

BROKER="kafka:29092"
MAX_RETRY=30
RETRY_INTERVAL=5

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
      && echo "  ✅ 생성 완료: $topic (partitions=$partitions)" \
      || echo "  ❌ 생성 실패: $topic"
  fi
}

echo ""
echo "══════════════════════════════════════════════════════"
echo " [1] Q-Sign 인증 이벤트 토픽"
echo "══════════════════════════════════════════════════════"

# §9.3 Q-Sign 인증 결과 이벤트
# - 인증 성공/실패/잠금 이벤트
# - partitionKey = identifierHash (동일 사용자 순서 보장)
# - retention 1년 (감사 목적)
create_topic "qsign.auth.events" 6 1 \
  "cleanup.policy=delete" \
  "retention.ms=31536000000" \
  "compression.type=lz4" \
  "min.insync.replicas=1" \
  "max.message.bytes=1048576"

# §9.3 Q-Sign DLQ (Dead Letter Queue)
create_topic "qsign.auth.events.dlq" 3 1 \
  "cleanup.policy=delete" \
  "retention.ms=604800000" \
  "compression.type=lz4"

echo ""
echo "══════════════════════════════════════════════════════"
echo " [2] Q-IM 사용자 이벤트 토픽"
echo "══════════════════════════════════════════════════════"

# §10.5.2 / §11.5.4 Q-IM 사용자 이벤트
# - cleanup.policy=compact: 최신 상태 유지 (compacted log)
# - partitionKey = qimUserId (사용자별 순서 보장)
# - IdO 가 구독하여 Q-IM 캐시 갱신
create_topic "qim.user.events" 12 1 \
  "cleanup.policy=compact" \
  "min.compaction.lag.ms=0" \
  "max.compaction.lag.ms=3600000" \
  "segment.bytes=104857600" \
  "delete.retention.ms=86400000" \
  "compression.type=lz4" \
  "min.insync.replicas=1" \
  "max.message.bytes=1048576"

# §11.5.6 Q-IM 사용자 스냅샷 토픽 (Compacted)
# - full user state 스냅샷
# - 신규 컨슈머가 최신 상태를 빠르게 복원할 때 사용
create_topic "qim.user.snapshot" 12 1 \
  "cleanup.policy=compact" \
  "min.compaction.lag.ms=0" \
  "segment.bytes=104857600" \
  "delete.retention.ms=86400000" \
  "compression.type=lz4" \
  "min.insync.replicas=1" \
  "max.message.bytes=10485760"

# §10.5.2 Q-IM DLQ
create_topic "qim.user.events.dlq" 6 1 \
  "cleanup.policy=delete" \
  "retention.ms=604800000"

echo ""
echo "══════════════════════════════════════════════════════"
echo " [3] IdO Handoff 이벤트 토픽"
echo "══════════════════════════════════════════════════════"

# §16.3 IdO Handoff 이벤트
# - HANDOFF_ISSUED / HANDOFF_CONSUMED / HANDOFF_EXPIRED / HANDOFF_REVOKED
# - partitionKey = correlationId
create_topic "ido.handoff.events" 6 1 \
  "cleanup.policy=delete" \
  "retention.ms=31536000000" \
  "compression.type=lz4" \
  "min.insync.replicas=1" \
  "max.message.bytes=1048576"

# §16.3 IdO Handoff DLQ
create_topic "ido.handoff.events.dlq" 3 1 \
  "cleanup.policy=delete" \
  "retention.ms=604800000"

echo ""
echo "══════════════════════════════════════════════════════"
echo " [4] Platform 세션 Advisory 토픽"
echo "══════════════════════════════════════════════════════"

# §14.9 / §12.5 세션 권고 이벤트 (Advisory)
# - SESSION_ADVISORY / FORCE_LOGOUT / AUTH_LEVEL_CHANGE
# - FE, Agency-Stub 모두 구독
# - partitionKey = qimUserId
create_topic "platform.session.advisory" 6 1 \
  "cleanup.policy=delete" \
  "retention.ms=86400000" \
  "compression.type=lz4" \
  "min.insync.replicas=1" \
  "max.message.bytes=1048576"

# §14.9 Advisory DLQ
create_topic "platform.session.advisory.dlq" 3 1 \
  "cleanup.policy=delete" \
  "retention.ms=604800000"

echo ""
echo "══════════════════════════════════════════════════════"
echo " [5] 감사 로그 토픽 (중앙 감사)"
echo "══════════════════════════════════════════════════════"

# 플랫폼 전역 감사 로그 (§15)
# 모든 서비스가 중요 감사 이벤트 발행
create_topic "platform.audit.log" 6 1 \
  "cleanup.policy=delete" \
  "retention.ms=63072000000" \
  "compression.type=lz4" \
  "min.insync.replicas=1" \
  "max.message.bytes=2097152"

echo ""
echo "══════════════════════════════════════════════════════"
echo " 토픽 목록 확인"
echo "══════════════════════════════════════════════════════"
kafka-topics --bootstrap-server "$BROKER" --list | sort

echo ""
echo "✅ Kafka 토픽 초기화 완료"
