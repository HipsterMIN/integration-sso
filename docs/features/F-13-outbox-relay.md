# F-13: IdO Outbox Relay (기존 기능)

> **환경변수**: `IDO_OUTBOX_RELAY_ENABLED`  
> **기본값**: `true` (안정 운영 중)  
> **소스**: `ido/src/main/java/kr/go/smes/ido/infrastructure/outbox/IdoOutboxRelay.java`

---

## 1. 이 기능은 무엇인가?

`ido.outbox` 테이블의 PENDING 이벤트를 Kafka로 재발행하는 스케줄러입니다.

```
이벤트 발생 → DB outbox INSERT (트랜잭션) → Kafka 발행 시도
                                               ├─ 성공 → COMPLETED
                                               └─ 실패 → PENDING
                                                          ↑
                                               IdoOutboxRelay가 500ms마다 폴링
```

**왜 이게 필요한가?**  
Kafka 발행은 트랜잭션 밖에서 일어납니다. DB 커밋은 성공했지만 Kafka 발행이 실패하면 이벤트가 유실될 수 있습니다. Outbox 패턴은 이를 방지합니다.

---

## 2. F-13 vs F-21의 차이

| | F-13 IdO Outbox Relay | F-21 Provisioning Outbox Relay |
|---|---|---|
| 소스 | `IdoOutboxRelay.java` | `ProvisioningOutboxRelay.java` |
| 테이블 | `ido.outbox` | `ido.provisioning_outbox` |
| 목적지 | Kafka 토픽 | 기관 HTTP POST |
| 스케줄 | 500ms | 30초 |
| 백오프 | 없음 (Kafka 빠름) | 지수 (1→5→30분) |

---

## 3. On/Off 시나리오

**false로 설정 권장 상황**:
- Kafka 없는 개발/테스트 환경
- Kafka 장애 중 (불필요한 오류 로그 방지)

```bash
# Kafka 없는 로컬 개발 환경
IDO_OUTBOX_RELAY_ENABLED=false  # false로 설정
```

---

## 연관 문서
- [F-21 Provisioning Outbox Relay](F-21-provisioning-relay.md)
- [Phase-Gate 전략](../phased-rollout-strategy.md)
