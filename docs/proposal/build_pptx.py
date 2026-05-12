"""
OnePass Platform — 설계 기반 PPTX 생성기
TPO 100k 달성 가능한 아키텍처 vs 시연 프로젝트 구조적 문제 비교
"""

from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN
from pptx.util import Inches, Pt
import copy

# ─── 색상 팔레트 ───────────────────────────────────────────────
C_NAVY      = RGBColor(0x0F, 0x2D, 0x52)   # 주 배경 / 헤더
C_BLUE      = RGBColor(0x1A, 0x5C, 0xBF)   # 포인트 파란
C_CYAN      = RGBColor(0x00, 0xAE, 0xD6)   # 하이라이트 시안
C_GREEN     = RGBColor(0x00, 0xB0, 0x72)   # 긍정/완료
C_RED       = RGBColor(0xD7, 0x26, 0x38)   # 부정/경고
C_ORANGE    = RGBColor(0xF5, 0x8A, 0x07)   # 주의/중간
C_WHITE     = RGBColor(0xFF, 0xFF, 0xFF)
C_LIGHTGRAY = RGBColor(0xF4, 0xF6, 0xFA)
C_DARKGRAY  = RGBColor(0x44, 0x44, 0x55)
C_MIDGRAY   = RGBColor(0x88, 0x88, 0x99)
C_YELLOW    = RGBColor(0xFF, 0xD7, 0x00)

SLIDE_W = Inches(13.33)
SLIDE_H = Inches(7.5)


def new_prs():
    prs = Presentation()
    prs.slide_width  = SLIDE_W
    prs.slide_height = SLIDE_H
    return prs


def blank_slide(prs):
    blank_layout = prs.slide_layouts[6]
    return prs.slides.add_slide(blank_layout)


def fill_bg(slide, color):
    bg = slide.background
    fill = bg.fill
    fill.solid()
    fill.fore_color.rgb = color


def add_rect(slide, l, t, w, h, fill_color=None, line_color=None, line_width=Pt(0)):
    from pptx.util import Emu
    shape = slide.shapes.add_shape(
        1,  # MSO_SHAPE_TYPE.RECTANGLE
        l, t, w, h
    )
    shape.line.width = line_width
    if fill_color:
        shape.fill.solid()
        shape.fill.fore_color.rgb = fill_color
    else:
        shape.fill.background()
    if line_color:
        shape.line.color.rgb = line_color
    else:
        shape.line.fill.background()
    return shape


def add_text_box(slide, text, l, t, w, h,
                 font_size=Pt(14), bold=False, color=C_WHITE,
                 align=PP_ALIGN.LEFT, wrap=True, italic=False):
    txBox = slide.shapes.add_textbox(l, t, w, h)
    tf = txBox.text_frame
    tf.word_wrap = wrap
    p = tf.paragraphs[0]
    p.alignment = align
    run = p.add_run()
    run.text = text
    run.font.size = font_size
    run.font.bold = bold
    run.font.italic = italic
    run.font.color.rgb = color
    return txBox


def add_label_value(slide, label, value, l, t, label_w=Inches(2.2),
                    label_size=Pt(11), value_size=Pt(13),
                    label_color=C_MIDGRAY, value_color=C_WHITE):
    add_text_box(slide, label, l, t, label_w, Inches(0.35),
                 font_size=label_size, color=label_color)
    add_text_box(slide, value, l + label_w, t, Inches(3.5), Inches(0.35),
                 font_size=value_size, bold=True, color=value_color)


def add_multiline_tb(slide, lines, l, t, w, h,
                     base_size=Pt(13), color=C_WHITE, spacing=Pt(4)):
    """lines: list of (text, bold, size, color)"""
    txBox = slide.shapes.add_textbox(l, t, w, h)
    tf = txBox.text_frame
    tf.word_wrap = True
    first = True
    for (text, bold, size, clr) in lines:
        if first:
            p = tf.paragraphs[0]
            first = False
        else:
            p = tf.add_paragraph()
        p.space_before = spacing
        run = p.add_run()
        run.text = text
        run.font.size = size or base_size
        run.font.bold = bold
        run.font.color.rgb = clr or color
    return txBox


# ══════════════════════════════════════════════════════════════════
# SLIDE 1 — 표지
# ══════════════════════════════════════════════════════════════════
def slide_01_cover(prs):
    sl = blank_slide(prs)
    fill_bg(sl, C_NAVY)

    # 좌측 강조 사이드바
    add_rect(sl, Inches(0), Inches(0), Inches(0.45), SLIDE_H, fill_color=C_CYAN)

    # 우측 하단 데코 사각형
    add_rect(sl, Inches(9.5), Inches(5.2), Inches(3.83), Inches(2.3),
             fill_color=RGBColor(0x0A, 0x1E, 0x3A))

    # 우측 하단 포인트 라인
    add_rect(sl, Inches(9.5), Inches(5.2), Inches(3.83), Inches(0.08),
             fill_color=C_CYAN)

    # 메인 타이틀
    add_text_box(sl,
                 "OnePass 통합인증 플랫폼",
                 Inches(0.8), Inches(1.3), Inches(11), Inches(0.9),
                 font_size=Pt(36), bold=True, color=C_WHITE)

    # 서브 타이틀
    add_text_box(sl,
                 "100,000 TPO를 향한 설계 — 왜 지금 방향을 바꿔야 하는가",
                 Inches(0.8), Inches(2.25), Inches(11.5), Inches(0.65),
                 font_size=Pt(22), bold=False, color=C_CYAN)

    # 구분선
    add_rect(sl, Inches(0.8), Inches(3.0), Inches(7.5), Inches(0.05),
             fill_color=RGBColor(0x2A, 0x4A, 0x7A))

    # 메타 정보
    metas = [
        ("일시", "2026년 5월 13일  10:30"),
        ("목적", "정식 프로젝트 착수 방향 정렬"),
        ("대상", "각 개발팀 및 관계자"),
    ]
    for i, (k, v) in enumerate(metas):
        y = Inches(3.3) + i * Inches(0.52)
        add_text_box(sl, k, Inches(0.85), y, Inches(1.4), Inches(0.45),
                     font_size=Pt(12), color=C_MIDGRAY)
        add_text_box(sl, v, Inches(2.1), y, Inches(6), Inches(0.45),
                     font_size=Pt(13), bold=True, color=C_WHITE)

    # 우하단 버전
    add_text_box(sl, "integration-sso  v2.3.0  |  Sprint 10 완료",
                 Inches(9.6), Inches(5.35), Inches(3.6), Inches(0.5),
                 font_size=Pt(11), color=C_MIDGRAY, align=PP_ALIGN.RIGHT)

    add_text_box(sl, "Build ✓  397 Tests ✓",
                 Inches(9.6), Inches(5.82), Inches(3.6), Inches(0.5),
                 font_size=Pt(12), bold=True, color=C_GREEN, align=PP_ALIGN.RIGHT)

    return sl


# ══════════════════════════════════════════════════════════════════
# SLIDE 2 — 목차
# ══════════════════════════════════════════════════════════════════
def slide_02_agenda(prs):
    sl = blank_slide(prs)
    fill_bg(sl, C_NAVY)
    add_rect(sl, Inches(0), Inches(0), Inches(0.45), SLIDE_H, fill_color=C_CYAN)

    add_text_box(sl, "AGENDA", Inches(0.8), Inches(0.3), Inches(4), Inches(0.55),
                 font_size=Pt(13), bold=True, color=C_CYAN)
    add_text_box(sl, "오늘 논의할 내용", Inches(0.8), Inches(0.75), Inches(9), Inches(0.65),
                 font_size=Pt(26), bold=True, color=C_WHITE)
    add_rect(sl, Inches(0.8), Inches(1.45), Inches(11.7), Inches(0.05),
             fill_color=RGBColor(0x2A, 0x4A, 0x7A))

    items = [
        ("01", "현재 상황 — 시연 프로젝트의 구조적 한계",
         "단일 장애점·확장 불가·설계 불일치를 한눈에"),
        ("02", "TPO 100k란 무엇인가",
         "목표 수치의 의미와 달성 조건"),
        ("03", "목표 설계 — integration-sso 아키텍처",
         "수평 확장·EDA·장애 격리가 내장된 구조"),
        ("04", "시연 vs 목표 설계 비교",
         "11개 항목 기준 정밀 대조"),
        ("05", "왜 지금 방향을 전환해야 하는가",
         "지금 결정하지 않으면 발생하는 비용"),
        ("06", "단계별 전환 로드맵",
         "3단계 착수 계획 및 팀별 역할"),
    ]

    for i, (num, title, sub) in enumerate(items):
        y = Inches(1.65) + i * Inches(0.92)
        # 번호 원
        add_rect(sl, Inches(0.8), y + Inches(0.06), Inches(0.62), Inches(0.62),
                 fill_color=C_BLUE, line_color=C_CYAN, line_width=Pt(1.5))
        add_text_box(sl, num, Inches(0.8), y + Inches(0.05), Inches(0.62), Inches(0.65),
                     font_size=Pt(14), bold=True, color=C_CYAN, align=PP_ALIGN.CENTER)
        add_text_box(sl, title, Inches(1.6), y, Inches(5.5), Inches(0.45),
                     font_size=Pt(14), bold=True, color=C_WHITE)
        add_text_box(sl, sub, Inches(1.6), y + Inches(0.42), Inches(6.5), Inches(0.38),
                     font_size=Pt(11), color=C_MIDGRAY)

    return sl


# ══════════════════════════════════════════════════════════════════
# SLIDE 3 — 현재 상황: 시연 프로젝트 구조
# ══════════════════════════════════════════════════════════════════
def slide_03_current_problem(prs):
    sl = blank_slide(prs)
    fill_bg(sl, C_NAVY)
    add_rect(sl, Inches(0), Inches(0), Inches(0.45), SLIDE_H, fill_color=C_RED)

    add_text_box(sl, "01  현재 상황", Inches(0.8), Inches(0.28), Inches(5), Inches(0.45),
                 font_size=Pt(12), bold=True, color=C_RED)
    add_text_box(sl, "시연 프로젝트의 구조적 한계",
                 Inches(0.8), Inches(0.7), Inches(11), Inches(0.65),
                 font_size=Pt(28), bold=True, color=C_WHITE)
    add_rect(sl, Inches(0.8), Inches(1.42), Inches(11.7), Inches(0.05),
             fill_color=RGBColor(0x2A, 0x4A, 0x7A))

    # 왼쪽: 아키텍처 다이어그램 (텍스트 기반)
    add_rect(sl, Inches(0.8), Inches(1.6), Inches(5.8), Inches(5.5),
             fill_color=RGBColor(0x0A, 0x1A, 0x38))

    add_text_box(sl, "[ 현재 시연 프로젝트 구조 ]",
                 Inches(0.95), Inches(1.72), Inches(5.5), Inches(0.4),
                 font_size=Pt(11), bold=True, color=C_ORANGE, align=PP_ALIGN.CENTER)

    diagram = (
        "        브라우저 (FE)\n"
        "   ┌──────────────────────┐\n"
        "   │  beInstance   extInstance │\n"
        "   └──────┬───────────┬────┘\n"
        "          │ /api/v1/  │ /api/ext/**\n"
        "          │ auth/**   │  (26개 API)\n"
        "          ▼           ▼\n"
        "       [ido BFF]   [Q-IM] ◄── 실질적\n"
        "      (7개 엔드  (인증+회원+        전면\n"
        "       포인트만)  프로비저닝\n"
        "                  +약관+동의\n"
        "                  +기업인증…)"
    )
    add_text_box(sl, diagram, Inches(0.9), Inches(2.15), Inches(5.6), Inches(3.8),
                 font_size=Pt(11), color=RGBColor(0xAA, 0xCC, 0xFF))

    add_rect(sl, Inches(0.9), Inches(5.9), Inches(5.6), Inches(0.95),
             fill_color=RGBColor(0x4A, 0x10, 0x10))
    add_text_box(sl, "⚠  단일 장애점: Q-IM 장애 = 서비스 전체 중단\n"
                     "    Pod 늘려도 병목은 Q-IM 한 곳에 집중",
                 Inches(0.95), Inches(5.95), Inches(5.5), Inches(0.85),
                 font_size=Pt(11), bold=False, color=C_ORANGE)

    # 오른쪽: 3가지 핵심 문제
    problems = [
        (C_RED, "PROBLEM 1",
         "Q-IM이 전면 시스템 역할",
         [
             "• FE→Q-IM 직접 호출 26개 API",
             "• 회원·프로비저닝·약관·동의·기업인증",
             "  모두 Q-IM 직접 접근",
             "• ido BFF는 NICE/OACX 7개만 처리",
             "→ 보안 정책·감사로그 집행 불가",
         ]),
        (C_ORANGE, "PROBLEM 2",
         "확장 불가능한 단층 구조",
         [
             "• Q-IM 호출 경로에 Rate Limit 없음",
             "• Circuit Breaker 미적용",
             "• 기관별 트래픽 격리 없음",
             "• 수평 확장해도 Q-IM이 병목",
             "→ TPO 수만 이상에서 붕괴",
         ]),
        (C_YELLOW, "PROBLEM 3",
         "설계 불일치 & 시연용 코드",
         [
             "• SKIP_AUTH=true — 인증 전체 우회",
             "• AES_GCM_KEY 평문 JS 번들 노출",
             "• Math.random() 임시 비밀번호",
             "• q-sign OIDC 브로커 미구현",
             "→ 운영 전환 시 전면 재작업",
         ]),
    ]

    for i, (color, label, title, bullets) in enumerate(problems):
        y = Inches(1.6) + i * Inches(1.85)
        add_rect(sl, Inches(6.85), y, Inches(6.1), Inches(1.75),
                 fill_color=RGBColor(0x0D, 0x23, 0x44))
        add_rect(sl, Inches(6.85), y, Inches(1.25), Inches(0.38),
                 fill_color=color)
        add_text_box(sl, label, Inches(6.85), y, Inches(1.25), Inches(0.38),
                     font_size=Pt(9), bold=True, color=C_WHITE, align=PP_ALIGN.CENTER)
        add_text_box(sl, title, Inches(8.2), y + Inches(0.02), Inches(4.6), Inches(0.38),
                     font_size=Pt(13), bold=True, color=color)
        bullet_text = "\n".join(bullets)
        add_text_box(sl, bullet_text, Inches(6.95), y + Inches(0.45),
                     Inches(5.9), Inches(1.25),
                     font_size=Pt(10.5), color=RGBColor(0xBB, 0xCC, 0xDD))

    return sl


# ══════════════════════════════════════════════════════════════════
# SLIDE 4 — TPO 100k란 무엇인가
# ══════════════════════════════════════════════════════════════════
def slide_04_tpo100k(prs):
    sl = blank_slide(prs)
    fill_bg(sl, C_NAVY)
    add_rect(sl, Inches(0), Inches(0), Inches(0.45), SLIDE_H, fill_color=C_CYAN)

    add_text_box(sl, "02  목표 수치", Inches(0.8), Inches(0.28), Inches(5), Inches(0.45),
                 font_size=Pt(12), bold=True, color=C_CYAN)
    add_text_box(sl, "TPO 100,000 — 이게 왜 중요한가",
                 Inches(0.8), Inches(0.7), Inches(11), Inches(0.65),
                 font_size=Pt(28), bold=True, color=C_WHITE)
    add_rect(sl, Inches(0.8), Inches(1.42), Inches(11.7), Inches(0.05),
             fill_color=RGBColor(0x2A, 0x4A, 0x7A))

    # TPO 정의 박스
    add_rect(sl, Inches(0.8), Inches(1.6), Inches(11.7), Inches(1.1),
             fill_color=RGBColor(0x0A, 0x2A, 0x50))
    add_rect(sl, Inches(0.8), Inches(1.6), Inches(0.12), Inches(1.1),
             fill_color=C_CYAN)
    add_text_box(sl,
                 "TPO (Transactions Per Operation)  =  일 단위 통합인증 처리 건수",
                 Inches(1.1), Inches(1.68), Inches(10), Inches(0.42),
                 font_size=Pt(15), bold=True, color=C_WHITE)
    add_text_box(sl,
                 "연계 기관 수 × 기관별 일 평균 인증 요청 건수  |  피크 시간대 집중도 포함",
                 Inches(1.1), Inches(2.1), Inches(10.5), Inches(0.42),
                 font_size=Pt(12), color=C_MIDGRAY)

    # 3단 카드
    cards = [
        (C_ORANGE, "현재 시연 프로젝트",
         "~수백 TPO",
         ["단일 인스턴스 Q-IM 직접 호출",
          "Rate Limit 없음",
          "Circuit Breaker 미적용",
          "→ 실 부하 시 붕괴"]),
        (C_BLUE, "중기 목표",
         "10,000 TPO",
         ["ido 수평 확장 (Pod 3~5개)",
          "Redis 캐싱 적용",
          "기관별 Rate Limit 활성",
          "→ Resilience4j CB 보호"]),
        (C_GREEN, "최종 목표",
         "100,000 TPO",
         ["EDA (Kafka) 비동기 처리",
          "Q-IM 읽기 복제본 분리",
          "K8s HPA 자동 확장",
          "→ 피크 5× 버스트 수용"]),
    ]

    for i, (color, label, tpo, bullets) in enumerate(cards):
        x = Inches(0.8) + i * Inches(4.05)
        add_rect(sl, x, Inches(2.88), Inches(3.85), Inches(4.2),
                 fill_color=RGBColor(0x0D, 0x23, 0x44))
        add_rect(sl, x, Inches(2.88), Inches(3.85), Inches(0.42), fill_color=color)
        add_text_box(sl, label, x, Inches(2.88), Inches(3.85), Inches(0.42),
                     font_size=Pt(12), bold=True, color=C_WHITE, align=PP_ALIGN.CENTER)
        add_text_box(sl, tpo, x, Inches(3.38), Inches(3.85), Inches(0.72),
                     font_size=Pt(28), bold=True, color=color, align=PP_ALIGN.CENTER)
        for j, b in enumerate(bullets):
            add_text_box(sl, b, x + Inches(0.15),
                         Inches(4.18) + j * Inches(0.5),
                         Inches(3.6), Inches(0.45),
                         font_size=Pt(11), color=RGBColor(0xBB, 0xCC, 0xDD))

    # 달성 조건 요약
    add_rect(sl, Inches(0.8), Inches(7.05), Inches(11.7), Inches(0.32),
             fill_color=RGBColor(0x00, 0x3A, 0x2A))
    add_text_box(sl,
                 "TPO 100k 달성 3대 조건:  ① 수평 확장 가능한 Stateless 설계  "
                 "② 동기 직접 호출 → EDA 비동기 전환  ③ 단일 장애점 제거",
                 Inches(0.95), Inches(7.07), Inches(11.5), Inches(0.28),
                 font_size=Pt(11), bold=True, color=C_GREEN)

    return sl


# ══════════════════════════════════════════════════════════════════
# SLIDE 5 — 목표 아키텍처 (integration-sso)
# ══════════════════════════════════════════════════════════════════
def slide_05_target_arch(prs):
    sl = blank_slide(prs)
    fill_bg(sl, C_NAVY)
    add_rect(sl, Inches(0), Inches(0), Inches(0.45), SLIDE_H, fill_color=C_GREEN)

    add_text_box(sl, "03  목표 설계", Inches(0.8), Inches(0.28), Inches(5), Inches(0.45),
                 font_size=Pt(12), bold=True, color=C_GREEN)
    add_text_box(sl, "integration-sso — 100k TPO를 위한 아키텍처",
                 Inches(0.8), Inches(0.7), Inches(12), Inches(0.65),
                 font_size=Pt(26), bold=True, color=C_WHITE)
    add_rect(sl, Inches(0.8), Inches(1.42), Inches(11.7), Inches(0.05),
             fill_color=RGBColor(0x2A, 0x4A, 0x7A))

    # ── 아키텍처 레이어 다이어그램 ──
    layers = [
        # (y, h, bg_color, border_color, label, sublabel, tag_color, tag)
        (Inches(1.55), Inches(0.78), RGBColor(0x06,0x3A,0x5C),
         C_CYAN, "브라우저 / onepass-fe",
         "단일 인스턴스 → /api/v1/** → ido BFF  |  extInstance 없음 (목표)",
         C_CYAN, "FE"),
        (Inches(2.42), Inches(0.88), RGBColor(0x06,0x28,0x4A),
         C_BLUE, "ido  —  정책 오케스트레이터 + FE BFF  (수평 확장 가능)",
         "Handoff·Auth·SLO·Rate Limit·Circuit Breaker·Audit Log · K8s HPA",
         C_BLUE, "SCALE-OUT"),
        (Inches(3.38), Inches(0.78), RGBColor(0x0A,0x1E,0x38),
         C_CYAN, "Kafka  —  비동기 이벤트 버스",
         "qsign.auth.events  |  qim.user.events  |  Outbox 패턴 (At-least-once)",
         C_CYAN, "EDA"),
        (Inches(4.22), Inches(0.88), RGBColor(0x06,0x28,0x4A),
         C_GREEN, "Q-Sign  (인증 SoR)                      Q-IM  (식별 SoR)",
         "OIDC 브로커·PKCE·JWT                    qimUserId·CI AES-256-GCM·DI HMAC",
         C_GREEN, "SoR"),
        (Inches(5.18), Inches(0.72), RGBColor(0x0A,0x1E,0x38),
         C_MIDGRAY, "인프라:  Redis Cluster  |  PostgreSQL  |  MariaDB  |  K8s Secrets/ConfigMap",
         "Prometheus · Grafana · Loki · OTel AOP 계측  |  Flyway 마이그레이션",
         C_MIDGRAY, "INFRA"),
    ]

    for (y, h, bg, border, label, sub, tag_c, tag) in layers:
        add_rect(sl, Inches(0.8), y, Inches(11.7), h,
                 fill_color=bg, line_color=border, line_width=Pt(1.2))
        add_rect(sl, Inches(0.8), y, Inches(1.1), h, fill_color=tag_c)
        add_text_box(sl, tag, Inches(0.8), y, Inches(1.1), h,
                     font_size=Pt(9), bold=True, color=C_WHITE, align=PP_ALIGN.CENTER)
        add_text_box(sl, label, Inches(2.0), y + Inches(0.06), Inches(10.3), Inches(0.4),
                     font_size=Pt(13), bold=True, color=C_WHITE)
        add_text_box(sl, sub, Inches(2.0), y + h - Inches(0.38), Inches(10.3), Inches(0.36),
                     font_size=Pt(10.5), color=C_MIDGRAY)

    # 화살표 텍스트
    for y_arrow in [Inches(2.35), Inches(3.3), Inches(4.15), Inches(5.1)]:
        add_text_box(sl, "▼", Inches(6.4), y_arrow, Inches(0.5), Inches(0.2),
                     font_size=Pt(10), color=C_MIDGRAY, align=PP_ALIGN.CENTER)

    # 외부 연동 우측
    add_rect(sl, Inches(0.8), Inches(6.0), Inches(11.7), Inches(0.72),
             fill_color=RGBColor(0x04, 0x15, 0x2A))
    add_text_box(sl, "외부 연동",
                 Inches(0.8), Inches(6.0), Inches(1.1), Inches(0.72),
                 font_size=Pt(9), bold=True, color=C_MIDGRAY, align=PP_ALIGN.CENTER)
    ext_items = [
        "NICE IDO (휴대폰 인증)",
        "OACX SDK (간편서명)",
        "Keycloak (소셜 IdP 브로커)",
        "NHN Cloud SKM (키 관리)",
        "agency-stub (유관기관 E2E)",
    ]
    for i, item in enumerate(ext_items):
        add_text_box(sl, f"• {item}",
                     Inches(2.0) + i * Inches(2.25), Inches(6.08),
                     Inches(2.2), Inches(0.55),
                     font_size=Pt(10), color=RGBColor(0x88, 0xAA, 0xCC))

    # 수평확장 설명 배지
    add_rect(sl, Inches(9.8), Inches(2.48), Inches(2.6), Inches(0.38),
             fill_color=C_BLUE)
    add_text_box(sl, "→ K8s HPA: Pod 자동 증가",
                 Inches(9.85), Inches(2.5), Inches(2.5), Inches(0.34),
                 font_size=Pt(10), bold=True, color=C_WHITE)

    return sl


# ══════════════════════════════════════════════════════════════════
# SLIDE 6 — 시연 vs 목표 설계 비교표
# ══════════════════════════════════════════════════════════════════
def slide_06_comparison(prs):
    sl = blank_slide(prs)
    fill_bg(sl, C_NAVY)
    add_rect(sl, Inches(0), Inches(0), Inches(0.45), SLIDE_H, fill_color=C_BLUE)

    add_text_box(sl, "04  비교", Inches(0.8), Inches(0.28), Inches(5), Inches(0.45),
                 font_size=Pt(12), bold=True, color=C_CYAN)
    add_text_box(sl, "시연 프로젝트  vs  integration-sso 목표 설계",
                 Inches(0.8), Inches(0.7), Inches(12), Inches(0.65),
                 font_size=Pt(26), bold=True, color=C_WHITE)
    add_rect(sl, Inches(0.8), Inches(1.42), Inches(11.7), Inches(0.05),
             fill_color=RGBColor(0x2A, 0x4A, 0x7A))

    # 헤더행
    headers = ["검토 항목", "시연 프로젝트", "integration-sso 목표", "상태"]
    col_x   = [Inches(0.8), Inches(3.5), Inches(7.7), Inches(11.6)]
    col_w   = [Inches(2.6), Inches(4.1), Inches(3.8), Inches(1.6)]

    add_rect(sl, Inches(0.8), Inches(1.55), Inches(12.2), Inches(0.42),
             fill_color=RGBColor(0x0F, 0x3A, 0x70))
    for j, hdr in enumerate(headers):
        add_text_box(sl, hdr, col_x[j], Inches(1.57), col_w[j], Inches(0.38),
                     font_size=Pt(11), bold=True, color=C_CYAN)

    rows = [
        # (항목, 시연, 목표, 상태색, 상태아이콘)
        ("FE → 백엔드 경로",
         "이중 경로\n(ido + Q-IM 직접)",
         "단일 경로\n(ido BFF만)",
         C_RED, "🔴"),
        ("인증 우회",
         "SKIP_AUTH=true\n활성 상태",
         "제거 완료\n(운영 동일 코드)",
         C_RED, "🔴"),
        ("암호화 키 관리",
         "AES_GCM_KEY\n.env 평문 번들",
         "서버사이드\nVault/KMS",
         C_RED, "🔴"),
        ("OIDC 브로커",
         "Keycloak 위임\n표준 엔드포인트 없음",
         "q-sign이 표준 OP\n/.well-known 포함",
         C_RED, "🔴"),
        ("수평 확장",
         "Q-IM 단층\n확장 불가",
         "ido K8s HPA\nStateless 설계",
         C_RED, "🔴"),
        ("장애 격리",
         "Circuit Breaker\n미적용",
         "Resilience4j CB\n기관별 격리",
         C_RED, "🔴"),
        ("Rate Limiting",
         "기관별 제어 없음\n폭주 시 Q-IM 붕괴",
         "AgencyRateLimiter\n기관별 TPS 제어",
         C_RED, "🔴"),
        ("비동기 처리",
         "동기 직접 호출\nQ-IM 응답 대기",
         "Kafka EDA\nOutbox 패턴",
         C_RED, "🔴"),
        ("감사 로그",
         "Q-IM 직접 구간\n추적 불가",
         "BrokerAuditLog\n전 구간 기록",
         C_RED, "🔴"),
        ("임시 비밀번호",
         "Math.random()\nFE 클라이언트",
         "SecureRandom\nBE API 발급 ✅",
         C_ORANGE, "🟡"),
        ("CI 보안(Q3=B)",
         "FE에서 CI 암호화\n직접 Q-IM 전송",
         "CI FE 미반환\nBE 내부 처리 ✅",
         C_ORANGE, "🟡"),
    ]

    row_h = Inches(0.51)
    for i, (item, demo, target, sc, icon) in enumerate(rows):
        y = Inches(2.0) + i * row_h
        bg = RGBColor(0x0D, 0x23, 0x44) if i % 2 == 0 else RGBColor(0x09, 0x1A, 0x32)
        add_rect(sl, Inches(0.8), y, Inches(12.2), row_h, fill_color=bg)
        # 항목
        add_text_box(sl, item, col_x[0], y + Inches(0.05), col_w[0], row_h - Inches(0.05),
                     font_size=Pt(10.5), bold=True, color=C_WHITE)
        # 시연
        add_text_box(sl, demo, col_x[1], y + Inches(0.03), col_w[1], row_h - Inches(0.03),
                     font_size=Pt(9.5), color=RGBColor(0xFF, 0xAA, 0x88))
        # 목표
        add_text_box(sl, target, col_x[2], y + Inches(0.03), col_w[2], row_h - Inches(0.03),
                     font_size=Pt(9.5), color=RGBColor(0x88, 0xFF, 0xCC))
        # 상태
        add_text_box(sl, icon, col_x[3], y + Inches(0.1), col_w[3], row_h - Inches(0.1),
                     font_size=Pt(16), align=PP_ALIGN.CENTER,
                     color=sc)

    return sl


# ══════════════════════════════════════════════════════════════════
# SLIDE 7 — 왜 지금 방향을 전환해야 하는가
# ══════════════════════════════════════════════════════════════════
def slide_07_why_now(prs):
    sl = blank_slide(prs)
    fill_bg(sl, C_NAVY)
    add_rect(sl, Inches(0), Inches(0), Inches(0.45), SLIDE_H, fill_color=C_ORANGE)

    add_text_box(sl, "05  전환 근거", Inches(0.8), Inches(0.28), Inches(5), Inches(0.45),
                 font_size=Pt(12), bold=True, color=C_ORANGE)
    add_text_box(sl, "왜 지금 방향을 바꿔야 하는가",
                 Inches(0.8), Inches(0.7), Inches(12), Inches(0.65),
                 font_size=Pt(28), bold=True, color=C_WHITE)
    add_rect(sl, Inches(0.8), Inches(1.42), Inches(11.7), Inches(0.05),
             fill_color=RGBColor(0x2A, 0x4A, 0x7A))

    # 기술 부채 가속 그래프 (텍스트 그래프)
    add_rect(sl, Inches(0.8), Inches(1.58), Inches(5.6), Inches(3.5),
             fill_color=RGBColor(0x0A, 0x1A, 0x38))
    add_text_box(sl, "시간 경과에 따른 재작업 비용",
                 Inches(0.85), Inches(1.65), Inches(5.5), Inches(0.38),
                 font_size=Pt(11), bold=True, color=C_ORANGE, align=PP_ALIGN.CENTER)

    graph_lines = [
        "  재                     시연 방식 계속  ╱",
        "  작                                ╱",
        "  업                            ╱",
        "  비       전환 비용 ●        ╱  ← 기하급수 증가",
        "  용              ▔▔▔▔▔▔▔▔▔▔",
        "       지금   3개월후   6개월후   운영 전환",
    ]
    for i, line in enumerate(graph_lines):
        clr = C_ORANGE if "시연" in line else (C_GREEN if "전환" in line and "●" in line else RGBColor(0x88, 0xAA, 0xCC))
        add_text_box(sl, line, Inches(0.9), Inches(2.1) + i * Inches(0.44),
                     Inches(5.4), Inches(0.42),
                     font_size=Pt(11), color=clr)

    # 비용 비교 박스
    add_rect(sl, Inches(0.8), Inches(5.18), Inches(5.6), Inches(2.0),
             fill_color=RGBColor(0x0D, 0x23, 0x44))
    add_text_box(sl, "지금 전환하지 않으면",
                 Inches(0.85), Inches(5.22), Inches(5.5), Inches(0.38),
                 font_size=Pt(12), bold=True, color=C_RED)
    delayed_costs = [
        "→ 각 팀이 다른 방향으로 더 깊이 개발",
        "→ 3개월 후 코드 충돌·통합 비용 폭증",
        "→ 운영 전환 시 사실상 전면 재개발",
        "→ 보안 우회 코드가 운영 환경에 진입",
    ]
    for i, c in enumerate(delayed_costs):
        add_text_box(sl, c, Inches(0.9), Inches(5.65) + i * Inches(0.36),
                     Inches(5.4), Inches(0.34),
                     font_size=Pt(11), color=RGBColor(0xFF, 0xAA, 0x88))

    # 오른쪽: 3대 근거
    reasons = [
        (C_CYAN, "설계 원칙 충돌",
         "현재 구조는 ADR-001(IdO 완전 중재 패턴)과\n"
         "정면 충돌합니다. FE가 Q-IM을 직접 호출하는\n"
         "구조는 보안 정책·감사·Rate Limit을 집행할\n"
         "단일 지점 자체가 없습니다."),
        (C_GREEN, "재사용 가능한 기반 이미 존재",
         "integration-sso BE는 이미 93% 완성.\n"
         "빌드 성공, 397개 테스트 통과 상태.\n"
         "지금 기준점을 잡으면 추가 Sprint만으로\n"
         "운영 전환이 가능합니다."),
        (C_ORANGE, "확장 한계는 구조에서 결정됨",
         "성능은 코드 최적화로 개선하지만,\n"
         "확장성은 설계에서 결정됩니다.\n"
         "단층 Q-IM 직접 호출 구조는 아무리\n"
         "튜닝해도 TPO 상한이 존재합니다."),
    ]

    for i, (color, title, body) in enumerate(reasons):
        y = Inches(1.58) + i * Inches(1.9)
        add_rect(sl, Inches(6.6), y, Inches(6.1), Inches(1.78),
                 fill_color=RGBColor(0x0D, 0x23, 0x44))
        add_rect(sl, Inches(6.6), y, Inches(0.12), Inches(1.78), fill_color=color)
        add_text_box(sl, title, Inches(6.85), y + Inches(0.1), Inches(5.7), Inches(0.4),
                     font_size=Pt(13), bold=True, color=color)
        add_text_box(sl, body, Inches(6.85), y + Inches(0.5), Inches(5.8), Inches(1.25),
                     font_size=Pt(11), color=RGBColor(0xBB, 0xCC, 0xDD))

    return sl


# ══════════════════════════════════════════════════════════════════
# SLIDE 8 — 단계별 전환 로드맵
# ══════════════════════════════════════════════════════════════════
def slide_08_roadmap(prs):
    sl = blank_slide(prs)
    fill_bg(sl, C_NAVY)
    add_rect(sl, Inches(0), Inches(0), Inches(0.45), SLIDE_H, fill_color=C_GREEN)

    add_text_box(sl, "06  로드맵", Inches(0.8), Inches(0.28), Inches(5), Inches(0.45),
                 font_size=Pt(12), bold=True, color=C_GREEN)
    add_text_box(sl, "3단계 전환 로드맵 — 팀별 역할 포함",
                 Inches(0.8), Inches(0.7), Inches(12), Inches(0.65),
                 font_size=Pt(28), bold=True, color=C_WHITE)
    add_rect(sl, Inches(0.8), Inches(1.42), Inches(11.7), Inches(0.05),
             fill_color=RGBColor(0x2A, 0x4A, 0x7A))

    phases = [
        {
            "phase": "Phase 1",
            "title": "FE 구조 정렬",
            "period": "착수 후 4주",
            "color": C_BLUE,
            "goal": "FE → ido 단일 경로 전환",
            "tasks": [
                "extInstance 26개 API → ido BFF 경유로 전환",
                "SKIP_AUTH 스위치 완전 제거",
                "AES_GCM_KEY FE 노출 제거 → Q3=B 원칙 적용",
                "Math.random() → GET /api/v1/auth/provision/temp-password 연결",
                "Mock 데이터 INITIAL_DATA 완전 제거",
            ],
            "team": "FE팀 주도  /  BE팀 API 지원",
            "output": "FE 단일 경로 달성 · 보안 우회 코드 Zero",
        },
        {
            "phase": "Phase 2",
            "title": "q-sign OIDC 표준화",
            "period": "5~10주",
            "color": C_CYAN,
            "goal": "q-sign이 표준 OIDC Provider 역할 수행",
            "tasks": [
                "/.well-known/openid-configuration 구현",
                "/authorize · /token · /userinfo · /jwks 엔드포인트 구현",
                "strict-mode=true 활성 (내부 서명 검증 강화)",
                "외부 서비스(SP)가 q-sign을 OIDC OP로 직접 연동",
                "기업인증 진위확인 정식 API 연동",
            ],
            "team": "BE팀(q-sign) 주도  /  ido 연동 지원",
            "output": "표준 OIDC 브로커 완성 · 외부 SP 연동 가능",
        },
        {
            "phase": "Phase 3",
            "title": "운영 전환 & 확장",
            "period": "11~16주",
            "color": C_GREEN,
            "goal": "K8s 배포 · 모니터링 · TPO 10k → 100k 확장",
            "tasks": [
                "K8s HPA 자동 확장 설정 (ido Pod 3→N개)",
                "Kafka EDA 비동기 처리 전환 (동기 → 이벤트)",
                "Q-IM 읽기 복제본 분리 (조회 부하 분산)",
                "Grafana SLO 대시보드 운영 모니터링 연결",
                "k6 부하 테스트 100k TPO 달성 검증",
            ],
            "team": "인프라팀 주도  /  전 팀 협업",
            "output": "운영 배포 완료 · TPO 100k 목표 달성",
        },
    ]

    for i, ph in enumerate(phases):
        x = Inches(0.8) + i * Inches(4.1)
        color = ph["color"]

        # 페이즈 카드 배경
        add_rect(sl, x, Inches(1.58), Inches(3.88), Inches(5.65),
                 fill_color=RGBColor(0x0D, 0x23, 0x44))
        # 헤더
        add_rect(sl, x, Inches(1.58), Inches(3.88), Inches(0.82), fill_color=color)
        add_text_box(sl, ph["phase"], x, Inches(1.58), Inches(3.88), Inches(0.38),
                     font_size=Pt(11), bold=True, color=C_WHITE, align=PP_ALIGN.CENTER)
        add_text_box(sl, ph["title"], x, Inches(1.95), Inches(3.88), Inches(0.42),
                     font_size=Pt(14), bold=True, color=C_WHITE, align=PP_ALIGN.CENTER)

        # 기간
        add_rect(sl, x + Inches(0.95), Inches(2.46), Inches(1.98), Inches(0.3),
                 fill_color=RGBColor(0x05, 0x12, 0x28))
        add_text_box(sl, f"⏱  {ph['period']}", x + Inches(0.95), Inches(2.48),
                     Inches(1.98), Inches(0.28),
                     font_size=Pt(10), bold=True, color=color, align=PP_ALIGN.CENTER)

        # 목표
        add_text_box(sl, f"목표: {ph['goal']}",
                     x + Inches(0.1), Inches(2.85), Inches(3.7), Inches(0.38),
                     font_size=Pt(10.5), bold=True, color=C_WHITE)

        # 할 일
        for j, task in enumerate(ph["tasks"]):
            add_text_box(sl, f"• {task}",
                         x + Inches(0.1), Inches(3.28) + j * Inches(0.42),
                         Inches(3.75), Inches(0.4),
                         font_size=Pt(9.5), color=RGBColor(0xBB, 0xCC, 0xDD))

        # 담당팀
        add_rect(sl, x, Inches(5.78), Inches(3.88), Inches(0.35),
                 fill_color=RGBColor(0x04, 0x12, 0x26))
        add_text_box(sl, f"팀: {ph['team']}",
                     x + Inches(0.1), Inches(5.8), Inches(3.75), Inches(0.32),
                     font_size=Pt(9.5), color=C_MIDGRAY)

        # 산출물
        add_rect(sl, x, Inches(6.15), Inches(3.88), Inches(0.42), fill_color=color)
        add_text_box(sl, f"✓  {ph['output']}",
                     x + Inches(0.1), Inches(6.17), Inches(3.75), Inches(0.38),
                     font_size=Pt(10), bold=True, color=C_WHITE)

    # 하단 화살표 타임라인
    add_rect(sl, Inches(0.8), Inches(6.65), Inches(11.7), Inches(0.08),
             fill_color=RGBColor(0x2A, 0x4A, 0x7A))
    for i in range(3):
        add_text_box(sl, "▶", Inches(4.5) + i * Inches(4.1), Inches(6.6),
                     Inches(0.5), Inches(0.25),
                     font_size=Pt(12), color=C_MIDGRAY)

    add_text_box(sl, "착수 (5월 13일)",
                 Inches(0.8), Inches(6.72), Inches(2), Inches(0.35),
                 font_size=Pt(10), bold=True, color=C_GREEN)
    add_text_box(sl, "→ 4주",
                 Inches(4.4), Inches(6.72), Inches(1.5), Inches(0.35),
                 font_size=Pt(10), color=C_MIDGRAY)
    add_text_box(sl, "→ 10주",
                 Inches(8.45), Inches(6.72), Inches(1.5), Inches(0.35),
                 font_size=Pt(10), color=C_MIDGRAY)
    add_text_box(sl, "TPO 100k 달성",
                 Inches(10.8), Inches(6.72), Inches(2.4), Inches(0.35),
                 font_size=Pt(10), bold=True, color=C_GREEN)

    return sl


# ══════════════════════════════════════════════════════════════════
# SLIDE 9 — 마무리 / 결론
# ══════════════════════════════════════════════════════════════════
def slide_09_closing(prs):
    sl = blank_slide(prs)
    fill_bg(sl, C_NAVY)
    add_rect(sl, Inches(0), Inches(0), Inches(0.45), SLIDE_H, fill_color=C_CYAN)

    # 상단 강조 배너
    add_rect(sl, Inches(0.45), Inches(0), Inches(SLIDE_W - Inches(0.45)), Inches(0.12),
             fill_color=C_CYAN)

    add_text_box(sl, "결론 및 요청사항",
                 Inches(0.8), Inches(0.5), Inches(12), Inches(0.65),
                 font_size=Pt(30), bold=True, color=C_WHITE)

    # 핵심 메시지 3개
    msgs = [
        (C_RED,    "🔴",
         "시연 프로젝트의 가장 큰 문제는 '미완성'이 아닙니다",
         "Q-IM 직접 호출 구조와 단층 아키텍처는 운영 전환과 TPO 확장이\n"
         "구조적으로 불가능합니다. 코드를 고쳐서 해결할 수 있는 문제가 아닙니다."),
        (C_CYAN,   "🔵",
         "integration-sso는 그 답을 이미 설계해 두었습니다",
         "수평 확장 가능한 ido BFF, Kafka EDA, Resilience4j Circuit Breaker,\n"
         "K8s HPA — TPO 100k를 위한 구성 요소가 93% 구현 완료 상태입니다."),
        (C_GREEN,  "🟢",
         "지금이 방향을 잡기 가장 좋은 시점입니다",
         "각 팀이 서로 다른 방향으로 더 깊이 들어가기 전에,\n"
         "오늘 회의에서 방향을 정렬하면 전환 비용을 최소화할 수 있습니다."),
    ]

    for i, (color, icon, title, body) in enumerate(msgs):
        y = Inches(1.3) + i * Inches(1.72)
        add_rect(sl, Inches(0.8), y, Inches(11.7), Inches(1.6),
                 fill_color=RGBColor(0x0D, 0x23, 0x44))
        add_rect(sl, Inches(0.8), y, Inches(0.12), Inches(1.6), fill_color=color)
        add_text_box(sl, icon, Inches(1.0), y + Inches(0.1), Inches(0.6), Inches(0.6),
                     font_size=Pt(20), align=PP_ALIGN.CENTER, color=color)
        add_text_box(sl, title, Inches(1.7), y + Inches(0.1), Inches(10.6), Inches(0.45),
                     font_size=Pt(14), bold=True, color=color)
        add_text_box(sl, body, Inches(1.7), y + Inches(0.58), Inches(10.5), Inches(1.0),
                     font_size=Pt(12), color=RGBColor(0xCC, 0xDD, 0xEE))

    # 오늘 결정 요청 박스
    add_rect(sl, Inches(0.8), Inches(6.42), Inches(11.7), Inches(0.78),
             fill_color=RGBColor(0x00, 0x3A, 0x5A))
    add_rect(sl, Inches(0.8), Inches(6.42), Inches(11.7), Inches(0.06),
             fill_color=C_CYAN)
    add_text_box(sl, "오늘 회의에서 결정해 주실 사항",
                 Inches(0.95), Inches(6.5), Inches(4), Inches(0.38),
                 font_size=Pt(11), bold=True, color=C_CYAN)
    asks = [
        "① integration-sso를 정식 개발 기준으로 채택",
        "② 각 팀 5월 13일부터 Phase 1 착수",
        "③ 팀별 역할 분담 확정 (FE팀 / BE팀 / 인프라팀)",
    ]
    for j, ask in enumerate(asks):
        add_text_box(sl, ask,
                     Inches(0.95) + j * Inches(3.9), Inches(6.88),
                     Inches(3.8), Inches(0.3),
                     font_size=Pt(11), bold=True, color=C_WHITE)

    return sl


# ══════════════════════════════════════════════════════════════════
# SLIDE 10 — 전환해도 여러분의 코드는 그대로입니다
# ══════════════════════════════════════════════════════════════════
def slide_10_low_impact(prs):
    sl = blank_slide(prs)
    fill_bg(sl, C_NAVY)
    add_rect(sl, Inches(0), Inches(0), Inches(0.45), SLIDE_H, fill_color=C_GREEN)

    add_text_box(sl, "07  영향 분석", Inches(0.8), Inches(0.28), Inches(5), Inches(0.45),
                 font_size=Pt(12), bold=True, color=C_GREEN)
    add_text_box(sl, "전환해도 여러분이 만든 코드는 그대로입니다",
                 Inches(0.8), Inches(0.7), Inches(12), Inches(0.65),
                 font_size=Pt(26), bold=True, color=C_WHITE)
    add_rect(sl, Inches(0.8), Inches(1.42), Inches(11.7), Inches(0.05),
             fill_color=RGBColor(0x2A, 0x4A, 0x7A))

    # 핵심 메시지 배너
    add_rect(sl, Inches(0.8), Inches(1.55), Inches(11.7), Inches(0.72),
             fill_color=RGBColor(0x00, 0x3A, 0x2A))
    add_rect(sl, Inches(0.8), Inches(1.55), Inches(0.12), Inches(0.72),
             fill_color=C_GREEN)
    add_text_box(sl,
                 "이번 전환은 각 팀이 만든 비즈니스 로직에 손대지 않습니다."
                 "  연결 방식만 바꿉니다.",
                 Inches(1.05), Inches(1.63), Inches(11.3), Inches(0.52),
                 font_size=Pt(14), bold=True, color=C_GREEN)

    # 3단 카드 (FE팀 / Q-IM팀 / ido·BE팀)
    teams = [
        {
            "color": C_CYAN,
            "team": "FE 팀",
            "impact_level": "변경 최소",
            "icon": "🔵",
            "keep": [
                "화면 UI / UX 컴포넌트 전체 유지",
                "비즈니스 로직 (Step1 ~ Step6) 그대로",
                "API 파라미터·응답 형식 동일",
                "26개 API 시그니처 Q-IM이 그대로 유지",
            ],
            "change": [
                "extInstance.ts  baseURL 1줄 변경",
                "  Q-IM 직접 → ido 경유 (같은 경로명)",
                "SKIP_AUTH / AES_GCM_KEY 환경변수 제거",
                "Math.random() → BE API 1회 호출로 교체",
            ],
        },
        {
            "color": C_ORANGE,
            "team": "Q-IM 팀",
            "impact_level": "변경 없음",
            "icon": "🟠",
            "keep": [
                "Q-IM 내부 로직 코드 변경 없음",
                "API 엔드포인트 전체 유지",
                "DB 스키마·데이터 모델 그대로",
                "기존 인증·회원·프로비저닝 로직 보존",
            ],
            "change": [
                "호출자가 FE → ido로 바뀜 (내부 변경 없음)",
                "관리자 기능: ido Admin API가 흡수",
                "  (Q-IM 관리자 UI → ido 관리자 UI로 이동)",
                "신규 개발 없음 — 검토·확인 작업만",
            ],
        },
        {
            "color": C_BLUE,
            "team": "ido / BE 팀",
            "impact_level": "소규모 추가",
            "icon": "🔷",
            "keep": [
                "기존 7개 Auth API 구현 그대로 유지",
                "Kafka·Outbox 패턴 이미 구현 완료",
                "AgencyAdminController 9개 API 완성",
                "QimSpReceiverController proxy 완성",
            ],
            "change": [
                "/api/ext/** → Q-IM forward proxy 라우팅 추가",
                "  (QimSpReceiverController 패턴 재사용)",
                "extInstance 26개 API forward 설정",
                "인프라팀과 webpack proxy 경로 합의",
            ],
        },
    ]

    for i, t in enumerate(teams):
        x = Inches(0.8) + i * Inches(4.1)
        color = t["color"]

        # 카드 배경
        add_rect(sl, x, Inches(2.38), Inches(3.88), Inches(4.75),
                 fill_color=RGBColor(0x0D, 0x23, 0x44))
        # 헤더
        add_rect(sl, x, Inches(2.38), Inches(3.88), Inches(0.68), fill_color=color)
        add_text_box(sl, t["team"], x, Inches(2.38), Inches(2.5), Inches(0.38),
                     font_size=Pt(14), bold=True, color=C_WHITE, align=PP_ALIGN.CENTER)
        # 영향 레벨 배지
        add_rect(sl, x + Inches(2.55), Inches(2.43), Inches(1.28), Inches(0.28),
                 fill_color=RGBColor(0x00, 0x00, 0x00))
        add_text_box(sl, t["impact_level"],
                     x + Inches(2.55), Inches(2.43), Inches(1.28), Inches(0.28),
                     font_size=Pt(9), bold=True, color=color, align=PP_ALIGN.CENTER)
        add_text_box(sl, t["icon"], x + Inches(0.05), Inches(2.42),
                     Inches(0.45), Inches(0.5),
                     font_size=Pt(16), color=C_WHITE)

        # "유지되는 것" 섹션
        add_text_box(sl, "✅ 그대로 유지",
                     x + Inches(0.1), Inches(3.14), Inches(3.7), Inches(0.32),
                     font_size=Pt(10), bold=True, color=C_GREEN)
        for j, item in enumerate(t["keep"]):
            add_text_box(sl, f"  {item}",
                         x + Inches(0.1), Inches(3.46) + j * Inches(0.33),
                         Inches(3.75), Inches(0.31),
                         font_size=Pt(9.5), color=RGBColor(0x88, 0xFF, 0xCC))

        # 구분선
        add_rect(sl, x + Inches(0.1), Inches(4.82), Inches(3.68), Inches(0.03),
                 fill_color=RGBColor(0x2A, 0x4A, 0x7A))

        # "변경되는 것" 섹션
        add_text_box(sl, "🔧 변경되는 것",
                     x + Inches(0.1), Inches(4.9), Inches(3.7), Inches(0.32),
                     font_size=Pt(10), bold=True, color=C_ORANGE)
        for j, item in enumerate(t["change"]):
            clr = RGBColor(0xFF, 0xCC, 0x88) if not item.startswith("  ") else RGBColor(0xAA, 0x99, 0x77)
            add_text_box(sl, item,
                         x + Inches(0.1), Inches(5.26) + j * Inches(0.33),
                         Inches(3.75), Inches(0.31),
                         font_size=Pt(9.5), color=clr)

    # 하단 강조 메시지
    add_rect(sl, Inches(0.8), Inches(7.12), Inches(11.7), Inches(0.3),
             fill_color=RGBColor(0x00, 0x3A, 0x2A))
    add_text_box(sl,
                 "코드 재사용률 약 95%  |  각 팀 순 추가 개발량: FE ~3일  ·  Q-IM ~0일  ·  ido/BE ~5일  ·  인프라 ~1일",
                 Inches(0.85), Inches(7.13), Inches(11.6), Inches(0.27),
                 font_size=Pt(11), bold=True, color=C_GREEN, align=PP_ALIGN.CENTER)

    return sl


# ══════════════════════════════════════════════════════════════════
# SLIDE 11 — 관리자 페이지 흡수 + API 전환 비용은 낮습니다
# ══════════════════════════════════════════════════════════════════
def slide_11_admin_and_cost(prs):
    sl = blank_slide(prs)
    fill_bg(sl, C_NAVY)
    add_rect(sl, Inches(0), Inches(0), Inches(0.45), SLIDE_H, fill_color=C_CYAN)

    add_text_box(sl, "08  전환 비용", Inches(0.8), Inches(0.28), Inches(5), Inches(0.45),
                 font_size=Pt(12), bold=True, color=C_CYAN)
    add_text_box(sl, "이미 만들어진 것을 연결만 합니다",
                 Inches(0.8), Inches(0.7), Inches(12), Inches(0.65),
                 font_size=Pt(28), bold=True, color=C_WHITE)
    add_rect(sl, Inches(0.8), Inches(1.42), Inches(11.7), Inches(0.05),
             fill_color=RGBColor(0x2A, 0x4A, 0x7A))

    # ── 왼쪽: 관리자 페이지 흡수 ──
    add_rect(sl, Inches(0.8), Inches(1.58), Inches(5.7), Inches(5.6),
             fill_color=RGBColor(0x0A, 0x1A, 0x38))
    add_rect(sl, Inches(0.8), Inches(1.58), Inches(5.7), Inches(0.42),
             fill_color=C_CYAN)
    add_text_box(sl, "Q-IM 관리자 → ido 관리자로 흡수",
                 Inches(0.82), Inches(1.6), Inches(5.66), Inches(0.38),
                 font_size=Pt(12), bold=True, color=C_NAVY, align=PP_ALIGN.CENTER)

    # 이미 구현된 AgencyAdminController API 목록
    add_text_box(sl, "ido AgencyAdminController — 이미 구현 완료",
                 Inches(0.9), Inches(2.08), Inches(5.5), Inches(0.35),
                 font_size=Pt(10.5), bold=True, color=C_CYAN)

    admin_apis = [
        ("POST",   "/api/v1/admin/agencies",              "기관 등록"),
        ("GET",    "/api/v1/admin/agencies",              "기관 목록 조회"),
        ("PUT",    "/api/v1/admin/agencies/{code}",       "기관 정보 수정"),
        ("POST",   "/api/v1/admin/agencies/{code}/activate",   "기관 활성화"),
        ("POST",   "/api/v1/admin/agencies/{code}/deactivate", "기관 비활성화"),
        ("POST",   "/api/v1/admin/agencies/{code}/rotate-key", "API 키 로테이션"),
        ("GET",    "/api/v1/admin/agencies/{code}/history",    "변경 이력 조회"),
        ("GET",    "/api/v1/admin/agencies/{code}/stats",      "통계 조회"),
    ]
    method_colors = {
        "GET":  RGBColor(0x00, 0xB0, 0x72),
        "POST": RGBColor(0x1A, 0x5C, 0xBF),
        "PUT":  RGBColor(0xF5, 0x8A, 0x07),
    }
    for i, (method, path, desc) in enumerate(admin_apis):
        y = Inches(2.5) + i * Inches(0.35)
        mc = method_colors.get(method, C_MIDGRAY)
        add_rect(sl, Inches(0.9), y, Inches(0.5), Inches(0.28), fill_color=mc)
        add_text_box(sl, method, Inches(0.9), y, Inches(0.5), Inches(0.28),
                     font_size=Pt(8), bold=True, color=C_WHITE, align=PP_ALIGN.CENTER)
        add_text_box(sl, path, Inches(1.48), y, Inches(3.2), Inches(0.28),
                     font_size=Pt(8.5), color=RGBColor(0x88, 0xCC, 0xFF))
        add_text_box(sl, desc, Inches(4.7), y, Inches(1.65), Inches(0.28),
                     font_size=Pt(8.5), color=C_MIDGRAY)

    # 결론 박스
    add_rect(sl, Inches(0.85), Inches(5.45), Inches(5.6), Inches(1.52),
             fill_color=RGBColor(0x00, 0x2A, 0x1A))
    add_text_box(sl, "Q-IM 관리자 기능",
                 Inches(0.9), Inches(5.5), Inches(5.5), Inches(0.32),
                 font_size=Pt(11), bold=True, color=C_CYAN)
    add_text_box(sl,
                 "기관 등록·수정·활성화·API 키 관리 등 핵심 관리 기능이\n"
                 "ido AgencyAdminController에 이미 구현되어 있습니다.\n"
                 "Q-IM 관리자 UI를 ido 관리자 화면에서 동일하게 제공할 수 있어\n"
                 "Q-IM 관리자 페이지는 ido로 자연스럽게 흡수됩니다.",
                 Inches(0.9), Inches(5.86), Inches(5.5), Inches(1.05),
                 font_size=Pt(10.5), color=RGBColor(0x88, 0xFF, 0xCC))

    # ── 오른쪽: API 전환 비용 낮음 근거 ──
    add_rect(sl, Inches(6.7), Inches(1.58), Inches(5.8), Inches(5.6),
             fill_color=RGBColor(0x0A, 0x1A, 0x38))
    add_rect(sl, Inches(6.7), Inches(1.58), Inches(5.8), Inches(0.42),
             fill_color=C_BLUE)
    add_text_box(sl, "API 전환 비용이 낮은 3가지 이유",
                 Inches(6.72), Inches(1.6), Inches(5.76), Inches(0.38),
                 font_size=Pt(12), bold=True, color=C_WHITE, align=PP_ALIGN.CENTER)

    reasons = [
        {
            "no": "①",
            "color": C_CYAN,
            "title": "FE baseURL 1줄 변경 → 26개 API 전환",
            "body": (
                "extInstance.ts의 baseURL 한 줄만 바꾸면\n"
                "26개 Q-IM 직접 호출이 모두 ido 경유로 전환됩니다.\n"
                "API 경로명·파라미터·응답 형식은 Q-IM이 그대로 유지하므로\n"
                "FE 비즈니스 로직은 수정이 필요 없습니다."
            ),
            "code": "// extInstance.ts — 변경 전\nbaseURL: process.env.EXT_API_ENDPOINT  // → Q-IM\n// 변경 후\nbaseURL: process.env.BE_API_ENDPOINT   // → ido",
        },
        {
            "no": "②",
            "color": C_GREEN,
            "title": "QimSpReceiverController — proxy 패턴 이미 구현",
            "body": (
                "ido에 이미 /api/qim/sp/v1/ proxy 패턴이 구현되어 있습니다.\n"
                "/api/ext/** forward proxy도 동일한 패턴으로\n"
                "낮은 비용에 적용 가능합니다."
            ),
            "code": "@RequestMapping(\"/api/qim/sp/v1\")\n// member/query · member/register 이미 구현\n// /api/ext/** forward도 동일 패턴 적용",
        },
        {
            "no": "③",
            "color": C_ORANGE,
            "title": "Kafka — ido에 이미 구현 완료",
            "body": (
                "KafkaTemplate, Outbox 패턴이 ido에 이미 구현되어 있습니다.\n"
                "비동기 처리 전환 시 추가 인프라 설치 없이\n"
                "기존 구현을 재사용하면 됩니다."
            ),
            "code": "// KeycloakOidcService.java — 이미 구현\nprivate final KafkaTemplate<String,Object> kafkaTemplate;\n// qsign.auth.events 발행 → Outbox 완성",
        },
    ]

    for i, r in enumerate(reasons):
        y = Inches(2.1) + i * Inches(1.68)
        color = r["color"]
        add_rect(sl, Inches(6.75), y, Inches(5.7), Inches(1.6),
                 fill_color=RGBColor(0x0D, 0x23, 0x44))
        add_rect(sl, Inches(6.75), y, Inches(0.38), Inches(1.6), fill_color=color)
        add_text_box(sl, r["no"], Inches(6.75), y + Inches(0.55),
                     Inches(0.38), Inches(0.5),
                     font_size=Pt(18), bold=True, color=C_WHITE, align=PP_ALIGN.CENTER)
        add_text_box(sl, r["title"],
                     Inches(7.2), y + Inches(0.06), Inches(5.2), Inches(0.36),
                     font_size=Pt(11), bold=True, color=color)
        add_text_box(sl, r["body"],
                     Inches(7.2), y + Inches(0.45), Inches(5.15), Inches(0.72),
                     font_size=Pt(9.5), color=RGBColor(0xBB, 0xCC, 0xDD))
        # 코드 인용
        add_rect(sl, Inches(7.2), y + Inches(1.22), Inches(5.15), Inches(0.34),
                 fill_color=RGBColor(0x04, 0x0E, 0x1C))
        add_text_box(sl, r["code"],
                     Inches(7.24), y + Inches(1.24), Inches(5.08), Inches(0.3),
                     font_size=Pt(7.5), color=RGBColor(0x88, 0xCC, 0xFF))

    return sl


# ══════════════════════════════════════════════════════════════════
# SLIDE 12 — 팀별 할 일 한눈에 (Sprint 1주 이내)
# ══════════════════════════════════════════════════════════════════
def slide_12_team_tasks(prs):
    sl = blank_slide(prs)
    fill_bg(sl, C_NAVY)
    add_rect(sl, Inches(0), Inches(0), Inches(0.45), SLIDE_H, fill_color=C_YELLOW)

    add_text_box(sl, "09  팀별 할 일", Inches(0.8), Inches(0.28), Inches(5), Inches(0.45),
                 font_size=Pt(12), bold=True, color=C_YELLOW)
    add_text_box(sl, "각 팀이 해야 할 일  —  Sprint 1주 이내 완료 가능",
                 Inches(0.8), Inches(0.7), Inches(12), Inches(0.65),
                 font_size=Pt(26), bold=True, color=C_WHITE)
    add_rect(sl, Inches(0.8), Inches(1.42), Inches(11.7), Inches(0.05),
             fill_color=RGBColor(0x2A, 0x4A, 0x7A))

    teams = [
        {
            "team":    "FE 팀",
            "days":    "~3일",
            "color":   C_CYAN,
            "tasks": [
                ("extInstance.ts  baseURL 환경변수 1줄 변경",
                 "EXT_API_ENDPOINT → BE_API_ENDPOINT (ido 주소)"),
                ("SKIP_AUTH=true 환경변수 제거 + Private.tsx 우회 코드 삭제",
                 "lines 67, 109, 130 제거 — 정상 인증 흐름 활성"),
                ("AES_GCM_KEY 환경변수 제거",
                 "webpack DefinePlugin에서 삭제, CI 암호화는 BE 처리로 이관"),
                ("Math.random() 임시비밀번호 → BE API 호출로 교체",
                 "Step5.tsx:73–76  →  GET /api/v1/auth/provision/temp-password"),
                ("ConversionContext MOCK_MEMBER/MOCK_BUSINESS 초기값 제거",
                 "INITIAL_DATA에서 '홍길동' 등 Mock 데이터 삭제"),
            ],
        },
        {
            "team":    "Q-IM 팀",
            "days":    "~1일",
            "color":   C_ORANGE,
            "tasks": [
                ("기존 Q-IM API 엔드포인트 명세 공유",
                 "ido proxy forward 설정 시 경로·파라미터 확인용"),
                ("관리자 기능 이관 검토·확인",
                 "ido AgencyAdminController 9개 API와 기존 관리자 기능 대조"),
                ("내부 코드 변경 없음 — 검토·승인만",
                 "Q-IM 비즈니스 로직은 그대로 유지"),
            ],
        },
        {
            "team":    "ido / BE 팀",
            "days":    "~5일",
            "color":   C_BLUE,
            "tasks": [
                ("/api/ext/** → Q-IM forward proxy 라우팅 추가",
                 "QimSpReceiverController 패턴 재사용 — 신규 패턴 불필요"),
                ("extInstance 26개 API forward 설정 구현",
                 "경로 매핑·헤더 forwarding·X-API-Key 전달"),
                ("GET /api/v1/auth/provision/temp-password 엔드포인트 확인",
                 "SecurePasswordGenerator 연결 — 이미 구현 완료"),
                ("FE → ido 단일 경로 E2E 통합 테스트",
                 "extInstance 제거 후 beInstance 단일 흐름 검증"),
                ("AgencyAdminController API 문서화",
                 "Q-IM 관리자 UI 연동을 위한 API 스펙 공유"),
            ],
        },
        {
            "team":    "인프라 팀",
            "days":    "~1일",
            "color":   C_MIDGRAY,
            "tasks": [
                ("webpack proxy 설정 변경",
                 "/api/ext 타겟을 Q-IM → ido로 변경"),
                ("K8s ConfigMap 환경변수 업데이트",
                 "EXT_API_ENDPOINT 제거, SKIP_AUTH 제거, AES_GCM_KEY 제거"),
                ("개발 환경 재배포 및 동작 확인",
                 "ido Pod 재시작 후 26개 API 경로 연결 검증"),
            ],
        },
    ]

    # 2×2 레이아웃
    positions = [
        (Inches(0.8),  Inches(1.58)),
        (Inches(6.95), Inches(1.58)),
        (Inches(0.8),  Inches(4.55)),
        (Inches(6.95), Inches(4.55)),
    ]
    card_w = Inches(5.95)
    card_h = Inches(2.82)

    for i, (t, (cx, cy)) in enumerate(zip(teams, positions)):
        color = t["color"]

        # 카드 배경
        add_rect(sl, cx, cy, card_w, card_h,
                 fill_color=RGBColor(0x0D, 0x23, 0x44))
        # 헤더
        add_rect(sl, cx, cy, card_w, Inches(0.45), fill_color=color)
        add_text_box(sl, t["team"], cx, cy, Inches(3.5), Inches(0.45),
                     font_size=Pt(14), bold=True, color=C_WHITE)
        # 예상 일수 배지
        add_rect(sl, cx + Inches(4.65), cy + Inches(0.08),
                 Inches(1.22), Inches(0.3),
                 fill_color=RGBColor(0x00, 0x00, 0x00))
        add_text_box(sl, f"예상 {t['days']}",
                     cx + Inches(4.65), cy + Inches(0.08),
                     Inches(1.22), Inches(0.3),
                     font_size=Pt(9.5), bold=True, color=color,
                     align=PP_ALIGN.CENTER)

        # 태스크 목록
        for j, (task, detail) in enumerate(t["tasks"]):
            ty = cy + Inches(0.52) + j * Inches(0.47)
            # 번호 도트
            add_rect(sl, cx + Inches(0.1), ty + Inches(0.06),
                     Inches(0.18), Inches(0.18), fill_color=color)
            add_text_box(sl, task,
                         cx + Inches(0.38), ty, Inches(5.48), Inches(0.28),
                         font_size=Pt(10), bold=True, color=C_WHITE)
            add_text_box(sl, detail,
                         cx + Inches(0.38), ty + Inches(0.28), Inches(5.48), Inches(0.2),
                         font_size=Pt(8.5), color=C_MIDGRAY)

    # 하단 총계 배너
    add_rect(sl, Inches(0.8), Inches(7.18), Inches(11.7), Inches(0.24),
             fill_color=RGBColor(0x00, 0x2A, 0x00))
    add_text_box(sl,
                 "전체 총 개발 소요: FE 3일 + Q-IM 1일 + ido/BE 5일 + 인프라 1일  =  최대 10 person-days  |  "
                 "팀 간 순차 의존성 없음 — 병렬 진행 가능",
                 Inches(0.85), Inches(7.18), Inches(11.6), Inches(0.24),
                 font_size=Pt(10), bold=True, color=C_GREEN, align=PP_ALIGN.CENTER)

    return sl


# ══════════════════════════════════════════════════════════════════
# MAIN
# ══════════════════════════════════════════════════════════════════
def main():
    prs = new_prs()

    slide_01_cover(prs)
    slide_02_agenda(prs)
    slide_03_current_problem(prs)
    slide_04_tpo100k(prs)
    slide_05_target_arch(prs)
    slide_06_comparison(prs)
    slide_07_why_now(prs)
    slide_08_roadmap(prs)
    slide_09_closing(prs)
    slide_10_low_impact(prs)
    slide_11_admin_and_cost(prs)
    slide_12_team_tasks(prs)

    out = "/home/user/webapp/docs/proposal/onepass-architecture-proposal-2026-05-13.pptx"
    prs.save(out)
    print(f"✅ PPTX 생성 완료: {out}")
    print(f"   슬라이드 수: {len(prs.slides)}장")


if __name__ == "__main__":
    main()
