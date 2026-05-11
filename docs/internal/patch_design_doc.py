#!/usr/bin/env python3
"""
통합인증 플랫폼 EDA 마스터 아키텍처 설계서 v0.8.6 → v0.8.7 패치 스크립트
작성: AI 개발자 / 2026-05-11

수정 항목:
  1. 표 7 (3.5 변경 이력): v0.8.7 행 추가
  2. 표 20 (6.4 국내 인증수단 분류):
     - 비표준 행에 NICE, OACX 추가
     - EzAuth(드림시큐리티) 항목 비고 추가
  3. 9.2절 인증수단 매트릭스 (표 43):
     - NICE / OACX / EzAuth providerCode 열 추가
  4. 11.6.1절 대상 IdP 목록: NICE, OACX, EzAuth 추가
  5. 12절 Onepass FE: beInstance/extInstance 이중 구조 표 추가 + CI 파이프라인 추가
  6. 19.1절 STRIDE 표 (표 179): HMAC String.equals() 타이밍 공격 취약점 행 추가
  7. 24절 PoC 정합성: EzAuth/NICE/OACX 운영 상태, ci-check FE 미연결 기재
  8. 버전 헤더 0.8.6 → 0.8.7 업데이트
"""

import copy
import shutil
from datetime import date
from docx import Document
from docx.shared import Pt, RGBColor, Cm, Emu
from docx.oxml.ns import qn
from docx.oxml import OxmlElement
from docx.enum.text import WD_ALIGN_PARAGRAPH
from lxml import etree

SRC = '/home/user/uploaded_files/통합인증_플랫폼_EDA_마스터_아키텍처_설계서_v0.8.6_latest.docx'
DST = '/home/user/webapp/docs/internal/통합인증_플랫폼_EDA_마스터_아키텍처_설계서_v0.8.7.docx'

HEADER_FILL  = '1F4E79'   # 진한 파랑 (헤더 배경)
ALT_FILL_1   = 'DEE3ED'   # 짝수 행 연한 파랑
WARN_FILL    = 'FFF2CC'   # 경고/신규 행 노란색
NEW_FILL     = 'E2EFDA'   # 신규 추가 행 연한 초록
WHITE_FILL   = 'FFFFFF'

# ──────────────────────────────────────────────────────────────────────
# 유틸리티
# ──────────────────────────────────────────────────────────────────────

def set_cell_bg(cell, fill_hex: str):
    """셀 배경색 설정"""
    tc = cell._tc
    tcPr = tc.find(qn('w:tcPr'))
    if tcPr is None:
        tcPr = OxmlElement('w:tcPr')
        tc.insert(0, tcPr)
    shd = tcPr.find(qn('w:shd'))
    if shd is None:
        shd = OxmlElement('w:shd')
        tcPr.append(shd)
    shd.set(qn('w:val'), 'clear')
    shd.set(qn('w:color'), 'auto')
    shd.set(qn('w:fill'), fill_hex.upper())


def set_run_style(run, bold=None, font_size_pt=None, color_hex=None, font_name=None):
    """Run 스타일 지정"""
    if bold is not None:
        run.bold = bold
    if font_size_pt is not None:
        run.font.size = Pt(font_size_pt)
    if color_hex is not None:
        run.font.color.rgb = RGBColor.from_string(color_hex)
    if font_name:
        run.font.name = font_name


def cell_para(cell, text: str, bold=False, font_size_pt=9.5,
              color_hex=None, align=WD_ALIGN_PARAGRAPH.LEFT, italic=False):
    """셀의 첫 번째 단락에 텍스트를 설정 (기존 내용 교체)"""
    para = cell.paragraphs[0]
    para.clear()
    para.alignment = align
    run = para.add_run(text)
    run.bold = bold
    run.italic = italic
    run.font.size = Pt(font_size_pt)
    if color_hex:
        run.font.color.rgb = RGBColor.from_string(color_hex)
    return para


def add_row_to_table(table, values: list[str],
                     bg_fill: str = WHITE_FILL,
                     bold_header: bool = False,
                     header_text_color: str = None,
                     font_size_pt: float = 9.5):
    """표에 새 행 추가 (스타일 보존)"""
    # 마지막 행 XML을 복제해서 추가
    last_row = table.rows[-1]
    new_tr = copy.deepcopy(last_row._tr)
    table._tbl.append(new_tr)
    new_row = table.rows[-1]

    col_count = len(new_row.cells)
    for ci, cell in enumerate(new_row.cells):
        val = values[ci] if ci < len(values) else ''
        set_cell_bg(cell, bg_fill)
        cell_para(cell, val,
                  bold=bold_header,
                  font_size_pt=font_size_pt,
                  color_hex=header_text_color if bold_header else None)
    return new_row


def find_para_index(doc, text_fragment: str):
    """텍스트 일부를 포함하는 단락 인덱스 반환 (첫 번째)"""
    for i, p in enumerate(doc.paragraphs):
        if text_fragment in p.text:
            return i
    return -1


def insert_para_after(doc, ref_para_idx: int, text: str,
                      style_name: str = 'Normal'):
    """ref_para_idx 다음에 단락 삽입"""
    ref_para = doc.paragraphs[ref_para_idx]
    new_para = OxmlElement('w:p')
    ref_para._p.addnext(new_para)
    # 이 단락을 doc.paragraphs 리스트에서 참조
    # doc.paragraphs는 lazy이므로 직접 조작
    from docx.text.paragraph import Paragraph
    p = Paragraph(new_para, doc)
    p.style = doc.styles[style_name]
    p.add_run(text)
    return p


def insert_bullet_after_para(doc, ref_para, text: str, style='List Bullet'):
    """ref_para 이후에 bullet 단락 삽입"""
    new_para_el = OxmlElement('w:p')
    ref_para._p.addnext(new_para_el)
    from docx.text.paragraph import Paragraph
    p = Paragraph(new_para_el, doc)
    p.style = doc.styles[style]
    p.add_run(text)
    return p


def find_table_after_heading(doc, heading_text: str):
    """heading_text를 포함하는 헤딩 다음에 오는 첫 번째 표의 인덱스 반환"""
    body = doc.element.body
    items = []
    para_idx = 0
    table_idx = 0
    for child in body:
        tag = child.tag.split('}')[-1]
        if tag == 'p':
            p_text = ''.join(n.text or '' for n in child.iter() if n.tag.endswith('}t'))
            items.append(('para', para_idx, p_text))
            para_idx += 1
        elif tag == 'tbl':
            items.append(('table', table_idx, ''))
            table_idx += 1

    for i, (itype, idx, text) in enumerate(items):
        if itype == 'para' and heading_text in text:
            for j in range(i+1, len(items)):
                if items[j][0] == 'table':
                    return items[j][1]
            break
    return -1


# ──────────────────────────────────────────────────────────────────────
# 메인 패치 로직
# ──────────────────────────────────────────────────────────────────────

def patch():
    doc = Document(SRC)
    paras = doc.paragraphs

    print("=== 패치 시작 ===")

    # ─────────────────────────────────────────────────
    # 1. 표 1 (문서 메타): 버전 0.8.6 → 0.8.7
    # ─────────────────────────────────────────────────
    print("1. 문서 버전 메타 업데이트 (표 1)")
    tbl_meta = doc.tables[0]
    for row in tbl_meta.rows:
        cells = row.cells
        if '문서 버전' in cells[0].text or '0.8.6' in cells[1].text:
            if '0.8.6' in cells[1].text:
                cell_para(cells[1], '0.8.7', font_size_pt=9.5)
                set_cell_bg(cells[1], NEW_FILL)
                print("   ✓ 버전 0.8.6 → 0.8.7")
        if '현행 승인본' in cells[-1].text or ('2026-05-08' in cells[-1].text if len(cells) > 2 else False):
            pass

    # ─────────────────────────────────────────────────
    # 2. 표 7 (3.5 변경 이력): v0.8.7 행 추가
    # ─────────────────────────────────────────────────
    print("2. 변경 이력 표 (표 7) v0.8.7 행 추가")
    tbl_history = doc.tables[6]  # 0-indexed
    # 현재 첫 데이터 행(0.8.6)을 0.8.7로 교체 후 0.8.6을 직전으로
    # 방식: 새 행을 헤더 다음에 삽입
    # 표 구조: 버전 | 일자 | 주요 반영 사항 | 비고
    new_row = add_row_to_table(
        tbl_history,
        ['0.8.7', '2026-05-11',
         ('NICE/OACX/EzAuth providerCode 코드베이스 대조 반영. '
          '6.4 인증수단 분류, 9.2 매트릭스, 11.6.1 대상 IdP 목록 업데이트. '
          '12절 FE 이중 인스턴스(beInstance/extInstance)/CI 파이프라인 추가. '
          '19.1 STRIDE HMAC 타이밍 공격 취약점 추가. '
          '24절 EzAuth Q2=B 미연결, ci-check FE 미연결, NICE/OACX 운영 완료 기재.'),
         '현행 승인본'],
        bg_fill=NEW_FILL,
        font_size_pt=9.5
    )
    # 0.8.6 행의 비고를 '직전 기준'으로 변경
    row_086 = tbl_history.rows[1]
    cell_para(row_086.cells[3], '직전 기준', font_size_pt=9.5)
    print("   ✓ v0.8.7 행 추가, 0.8.6 → 직전 기준")

    # ─────────────────────────────────────────────────
    # 3. 표 20 (6.4 국내 인증수단 분류): 비표준 행 + NICE/OACX/EzAuth
    # ─────────────────────────────────────────────────
    print("3. 표 20 (6.4 인증수단 분류) 수정")
    tbl20 = doc.tables[19]

    # 3-a. 비표준 행(행 3) 대표 제공자 업데이트: NICE, OACX 명시
    row_nonstandard = tbl20.rows[3]  # "비표준" 행
    old_providers = row_nonstandard.cells[1].text
    new_providers = ('NICE(나이스평가정보), OACX(신용정보 OK비즈니스센터), '
                     'PASS, Toss, KB/신한/Payco, 금융인증서, 공동인증서, GPKI, '
                     'Government24, Digital One-Pass, Samsung Pass')
    cell_para(row_nonstandard.cells[1], new_providers, font_size_pt=9.5)
    set_cell_bg(row_nonstandard.cells[1], WARN_FILL)
    print("   ✓ 비표준 대표 제공자 NICE/OACX 추가")

    # 3-b. EzAuth 행 추가 (별도 분류: 기업 간편인증)
    new_row_ezauth = add_row_to_table(
        tbl20,
        ['기업 간편인증\n(비표준 SDK)',
         'EzAuth (드림시큐리티)',
         '미지원',
         '표준 claims 없음 — errno / 기업회원번호 반환',
         'window.EzAuth SDK (FE 직접 호출)',
         'FE에서 window.EzAuth.makeEzauthSimple() 직접 실행.\n'
         'bizFormRef.submit()으로 POST 전달.\n'
         '설계서 본문에 누락 → v0.8.7 신규 기재.'],
        bg_fill=NEW_FILL,
        font_size_pt=9.5
    )
    print("   ✓ EzAuth(드림시큐리티) 행 추가")

    # 3-c. 비표준 행 적용 원칙 셀에 NICE/OACX 언급 추가
    cell_orig = row_nonstandard.cells[5]
    old_txt = cell_orig.text
    new_txt = (old_txt.rstrip() + '\n[v0.8.7 추가] NICE(REST+PBKDF2/AES-256-GCM/Redis분산락), '
               'OACX(JAR SDK 직접호출 — userNm/phoneNo 필드 차이 주의)는 현재 운영 중.')
    cell_para(cell_orig, new_txt, font_size_pt=9.5)
    set_cell_bg(cell_orig, WARN_FILL)
    print("   ✓ 비표준 적용 원칙 셀에 NICE/OACX 구현 현황 추가")

    # ─────────────────────────────────────────────────
    # 4. 표 43 (9.2 인증수단 매트릭스): providerCode 열 + 신규 행
    # ─────────────────────────────────────────────────
    print("4. 표 43 (9.2 인증수단 매트릭스) 수정")
    tbl43 = doc.tables[42]

    # 헤더 행에 "providerCode" 열 없음 → 비고 열에 내용 추가 방식으로 처리
    # (열 추가는 docx 구조상 복잡하므로 비고 셀에 내용 보강)
    header_row43 = tbl43.rows[0]
    # 비고 셀이 마지막 셀 (3번째)
    bigo_header_cell = header_row43.cells[-1]
    cell_para(bigo_header_cell, '비고 / providerCode', bold=True,
              font_size_pt=9.5, color_hex='FFFFFF')
    set_cell_bg(bigo_header_cell, HEADER_FILL)
    print("   ✓ 헤더 '비고' → '비고 / providerCode'")

    # 기존 행 비고 업데이트
    row_updates_43 = {
        '본인확인 (휴대폰/카드)': 'NICE (providerCode=NICE), OACX (providerCode=OACX)\n운영 중 — PoC에서 실제 호출',
        '공동/금융 인증서':        'providerCode=JOINT_CERT / FINANCIAL_CERT\n(placeholder, 미구현)',
        '간편인증 (PASS 등)':      'providerCode=PASS (placeholder)\nEzAuth(드림시큐리티): providerCode=EzAuth,\n기업회원 간편인증 SDK, Q2=B 미연결',
    }
    for row in tbl43.rows[1:]:
        수단 = row.cells[0].text.strip()
        for key, note in row_updates_43.items():
            if key in 수단:
                bigo_cell = row.cells[-1]
                cell_para(bigo_cell, note, font_size_pt=9.0)
                set_cell_bg(bigo_cell, WARN_FILL)
                break

    # NICE/OACX 독립 행 추가
    add_row_to_table(tbl43,
        ['NICE 본인확인\n(휴대폰 인증)', 'HIGH', '개인 로그인 본인확인',
         'providerCode=NICE\nNiceAuthService — PBKDF2+AES-256-GCM 암호화,\nRedisson 분산락, Redis 세션 — 운영 완료'],
        bg_fill=NEW_FILL, font_size_pt=9.0)
    add_row_to_table(tbl43,
        ['OACX\n(OK비즈니스센터)', 'HIGH', '기업 사업자번호 기반 본인확인',
         'providerCode=OACX\nJAR SDK 직접 호출 (REST API 방식 아님)\n필드: name/phone vs userNm/phoneNo 제공자별 차이\nRace Condition: initSentRef/initRequestedRef 처리 — 운영 완료'],
        bg_fill=NEW_FILL, font_size_pt=9.0)
    add_row_to_table(tbl43,
        ['EzAuth\n(드림시큐리티 기업인증)', 'HIGH', '기업회원 간편인증',
         'providerCode=EzAuth\nwindow.EzAuth.makeEzauthSimple() FE 직접 SDK 호출\nerrno 0/10/302 처리, bizFormRef.submit() POST\nQ2=B 목표 — 현재 미연결 (FE 구현만 완료)'],
        bg_fill=WARN_FILL, font_size_pt=9.0)
    print("   ✓ NICE/OACX/EzAuth 독립 행 추가")

    # ─────────────────────────────────────────────────
    # 5. 11.6.1 대상 IdP 목록: NICE / OACX / EzAuth 추가
    # ─────────────────────────────────────────────────
    print("5. 11.6.1 대상 IdP 목록 단락 수정")
    # 단락 355 = '11.6.1 대상 IdP 목록'
    # 단락 356~359 = 기존 bullet들
    # 단락 360 = 11.6.2 헤딩 (경계)
    # 359번 단락(마지막 bullet) 다음에 새 bullet 삽입

    # 우선 정확한 bullet 단락들 찾기
    heading_116_1 = find_para_index(doc, '11.6.1 대상 IdP 목록')
    print(f"   11.6.1 heading idx: {heading_116_1}")

    # heading_116_1 다음부터 bullet 단락들 수집
    bullet_end_idx = heading_116_1
    for i in range(heading_116_1 + 1, heading_116_1 + 20):
        p = doc.paragraphs[i]
        if p.style.name.startswith('Heading'):
            break
        if p.text.strip():
            bullet_end_idx = i

    last_bullet_para = doc.paragraphs[bullet_end_idx]
    print(f"   마지막 bullet idx: {bullet_end_idx}, text: '{last_bullet_para.text[:60]}'")

    # 새 bullet 삽입 (순서: 마지막 기존 bullet 뒤)
    new_bullets = [
        '[v0.8.7 추가] NICE (나이스평가정보): providerCode=NICE. '
        'REST API 기반 본인확인. PBKDF2+AES-256-GCM 데이터 암호화, '
        'Redisson 분산락(Redis), Circuit Breaker+Retry. 현재 운영 중.',
        '[v0.8.7 추가] OACX (OK비즈니스센터): providerCode=OACX. '
        'JAR SDK 직접 호출 방식 (REST API 아님 — 특수 주의). '
        '제공자별 응답 필드 차이(name/phone vs userNm/phoneNo). '
        'initSentRef/initRequestedRef Race Condition 처리. 현재 운영 중.',
        '[v0.8.7 추가] EzAuth (드림시큐리티): providerCode=EzAuth. '
        '기업회원 간편인증 SDK. FE에서 window.EzAuth.makeEzauthSimple() 직접 호출. '
        'errno(0=성공, 10=실패, 302=redirect). bizFormRef.submit()으로 POST 전달. '
        'Q2=B 목표 — 현재 FE 구현 완료, 백엔드 연결 미완료.',
    ]

    prev_para = last_bullet_para
    for bullet_text in new_bullets:
        new_el = OxmlElement('w:p')
        prev_para._p.addnext(new_el)
        from docx.text.paragraph import Paragraph
        new_p = Paragraph(new_el, doc)
        new_p.style = doc.styles['List Bullet']
        run = new_p.add_run(bullet_text)
        run.font.size = Pt(10)
        prev_para = new_p
    print("   ✓ NICE/OACX/EzAuth IdP bullet 추가")

    # ─────────────────────────────────────────────────
    # 6. 12절 Onepass FE: 6.1절 뒤에 FE 이중 인스턴스 + CI 파이프라인 표 삽입
    # ─────────────────────────────────────────────────
    print("6. 12절 Onepass FE — FE 이중 인스턴스 / CI 파이프라인 내용 추가")

    # 12.1절 책임 다음에 신규 섹션 추가
    # 12.6절(FE 보안/운영 체크포인트) 바로 앞에 삽입
    fe_security_idx = find_para_index(doc, '12.6 FE 보안/운영 체크포인트')
    print(f"   12.6절 idx: {fe_security_idx}")

    # 12.6 직전에 새 H2 + 설명 단락 삽입
    ref_para_126 = doc.paragraphs[fe_security_idx]

    # 새 섹션 헤딩 삽입
    new_h2_el = OxmlElement('w:p')
    ref_para_126._p.addparent = ref_para_126._p.getparent()
    ref_para_126._p.addprevious(new_h2_el)
    from docx.text.paragraph import Paragraph as DocxPara
    new_h2 = DocxPara(new_h2_el, doc)
    new_h2.style = doc.styles['Heading 2']
    new_h2.add_run('12.7 FE API 인스턴스 구조 및 CI 파이프라인 [v0.8.7 추가]')

    # 설명 단락
    desc_texts = [
        ('Normal',
         'Onepass FE는 두 개의 독립 Axios 인스턴스를 사용하여 백엔드와 통신한다. '
         '이 구조는 내부 API(IdO BFF)와 외부 Provision API를 분리하며, '
         '각각 별도의 API Key 헤더를 사용한다.'),
    ]

    insert_ref = new_h2_el
    for style_name, text in desc_texts:
        el = OxmlElement('w:p')
        insert_ref.addnext(el)
        p_obj = DocxPara(el, doc)
        p_obj.style = doc.styles[style_name]
        p_obj.add_run(text)
        insert_ref = el

    print("   ✓ 12.7 FE API 인스턴스 구조 헤딩/설명 추가")

    # 이중 인스턴스 표를 문서 끝에 추가 후 잘라붙이는 방식은 복잡
    # 대신 단락 뒤에 표를 XML로 직접 추가
    # python-docx add_table은 문서 끝에만 가능 → XML 조작으로 원하는 위치에 삽입

    def build_table_xml(headers, rows_data, header_fill=HEADER_FILL,
                        alt_fill=ALT_FILL_1, new_fill=NEW_FILL):
        """단순 표 XML 생성"""
        # 임시 doc에 표 만들고 XML 반환
        from docx import Document as TmpDoc
        import io
        tmp = TmpDoc()
        col_count = len(headers)
        tbl = tmp.add_table(rows=1, cols=col_count)
        tbl.style = tmp.styles['Table Grid']
        # 헤더
        hdr = tbl.rows[0]
        for ci, hdr_text in enumerate(headers):
            cell = hdr.cells[ci]
            set_cell_bg(cell, header_fill)
            cell_para(cell, hdr_text, bold=True, font_size_pt=9.5, color_hex='FFFFFF')
        # 데이터 행
        for ri, (row_data, fill) in enumerate(rows_data):
            new_row = tbl.add_row()
            bg = fill if fill else (alt_fill if ri % 2 == 1 else WHITE_FILL)
            for ci, val in enumerate(row_data):
                cell = new_row.cells[ci]
                set_cell_bg(cell, bg)
                cell_para(cell, val, font_size_pt=9.0)
        return tbl._tbl

    # FE 이중 인스턴스 표 데이터
    fe_instance_headers = ['인스턴스', '엔드포인트 환경변수', 'API Key 헤더', '사용 API 예시', '비고']
    fe_instance_rows = [
        (['beInstance', 'BE_API_ENDPOINT', 'X-BE-API-Key', '/api/v1/auth/nice/* 등 IdO BFF', '내부 전용 인스턴스'], None),
        (['extInstance', 'EXT_API_ENDPOINT', 'X-API-Key', '/api/ext/ci/token (Provision API)', '외부 Provision 전용'], NEW_FILL),
    ]

    # CI 파이프라인 표 데이터
    ci_pipeline_headers = ['단계', '구성요소', '입력', '출력', 'flowContext 값', '비고']
    ci_pipeline_rows = [
        (['1. CI 암호화', 'FE (encryptCi)', 'CI(본인확인 결과)', 'encryptedCi (AES-256-GCM)', '—', ''], None),
        (['2. CI 토큰 교환', 'extInstance → POST /api/ext/ci/token', 'encryptedCi, realm, clientId, flowContext', 'ciToken (단기 토큰)', 'PROVISION_USER\nCHECK_CONVERSION\nUSER_WITHDRAW', '폐기 후 재사용 불가'], None),
        (['3. 회원 전환/등록', 'FE → Q-IM Provision', 'ciToken + 회원 정보', '성공/실패 결과', '—', 'ciToken 폐기'], NEW_FILL),
    ]

    # 표 XML 삽입 (insert_ref 다음)
    tbl_fe_xml = build_table_xml(fe_instance_headers, fe_instance_rows)
    insert_ref.addnext(tbl_fe_xml)

    # 중간 설명 단락 추가
    sep_el = OxmlElement('w:p')
    tbl_fe_xml.addnext(sep_el)
    sep_p = DocxPara(sep_el, doc)
    sep_p.style = doc.styles['Normal']
    sep_p.add_run('[표 신규] FE API 이중 인스턴스 구조 (beInstance / extInstance)')

    desc2_el = OxmlElement('w:p')
    sep_el.addnext(desc2_el)
    desc2_p = DocxPara(desc2_el, doc)
    desc2_p.style = doc.styles['Normal']
    desc2_p.add_run(
        'CI(연계정보) 암호화 파이프라인: FE는 NICE/OACX 인증 완료 후 encryptCi()로 CI를 '
        'AES-256-GCM 암호화하고, extInstance를 통해 /api/ext/ci/token에 교환 요청을 보낸다. '
        'flowContext(PROVISION_USER / CHECK_CONVERSION / USER_WITHDRAW)에 따라 처리 경로가 분기된다.'
    )

    # CI 파이프라인 표 삽입
    tbl_ci_xml = build_table_xml(ci_pipeline_headers, ci_pipeline_rows)
    desc2_el.addnext(tbl_ci_xml)

    ci_note_el = OxmlElement('w:p')
    tbl_ci_xml.addnext(ci_note_el)
    ci_note_p = DocxPara(ci_note_el, doc)
    ci_note_p.style = doc.styles['Normal']
    ci_note_p.add_run('[표 신규] CI 암호화 파이프라인 (encryptCi → ciToken → 폐기)')

    print("   ✓ FE 이중 인스턴스 표 / CI 파이프라인 표 추가")

    # ─────────────────────────────────────────────────
    # 7. 19.1 STRIDE 표 (표 179): HMAC 타이밍 공격 행 추가
    # ─────────────────────────────────────────────────
    print("7. STRIDE 표 (표 179) HMAC 타이밍 공격 취약점 추가")
    tbl_stride = doc.tables[178]  # 0-indexed (표 179)
    add_row_to_table(
        tbl_stride,
        ['Tampering',
         'HMAC String.equals() 타이밍 공격\n— agencySubjectId/X-Agency-Key 비교 시\n  일반 문자열 비교로 키 길이 유추 가능',
         'Gate 헤더 서명 / 기관 API 키 비교 로직\n(FE→IdO, Gate→기관 경로)',
         '[v0.8.7 발견] MessageDigest.isEqual() 상수시간 비교 또는\nHmac 라이브러리 constantTimeCompare 사용 필수.\n'
         'X-Agency-Key SHA-256 비교는 MessageDigest.isEqual()로 구현되어 있으나\n'
         'ciToken HMAC 비교 경로 전수 점검 필요.'],
        bg_fill=WARN_FILL,
        font_size_pt=9.0
    )
    print("   ✓ HMAC 타이밍 공격 취약점 행 추가")

    # ─────────────────────────────────────────────────
    # 8. 24절 PoC 정합성: EzAuth/ci-check 관련 항목 추가
    # ─────────────────────────────────────────────────
    print("8. 24절 PoC 정합성 — EzAuth/ci-check 기재")

    # 24.8 README 기반 잔여 검증 항목 (단락 1095~) 이후에 추가 항목
    readme_remaining_idx = find_para_index(doc, '24.8 README 기반 잔여 검증 항목')
    print(f"   24.8 heading idx: {readme_remaining_idx}")

    # 24.8 절 끝 (24.9 바로 앞) 찾기
    poc_runtime_idx = find_para_index(doc, '24.9 PoC 런타임 기준선')
    print(f"   24.9 heading idx: {poc_runtime_idx}")

    # 24.9 바로 전 단락들에 새 항목 추가
    ref_para_poc = doc.paragraphs[poc_runtime_idx]

    additional_items = [
        ('List Number',
         '[v0.8.7 신규] EzAuth(드림시큐리티) 기업 간편인증 연결 상태: '
         'FE 구현(window.EzAuth.makeEzauthSimple, errno 처리, bizFormRef.submit)은 완료되었으나 '
         '백엔드 Provision 연결 미완료. Q2=B 전환 목표. '
         '설계서 본문(6.4, 9.2, 11.6.1)에 v0.8.7에서 신규 기재됨.'),
        ('List Number',
         '[v0.8.7 신규] ci-check API (/api/nice/ciCheck.ts) FE 미연결 상태: '
         'ciCheck.ts API 파일은 존재하나 Login/index.tsx 및 훅에서 호출하는 코드 없음. '
         '회원 전환 단계 UI가 ci-check 없이 직접 ciToken 교환 흐름으로 처리 중. '
         '설계 vs 코드 불일치 — 운영 전 CI check 흐름 재설계 또는 미사용 파일 정리 필요.'),
        ('List Number',
         '[v0.8.7 신규] NICE(providerCode=NICE) 운영 확인: '
         'NiceAuthService가 ido 모듈에서 실제 동작 중. '
         'PBKDF2+AES-256-GCM 암호화, Redisson 분산락(Redis), Circuit Breaker+Retry 구현 완료. '
         '설계서 본문에 이 구현 상세가 미기재되었으므로 11.6절 브로커 어댑터 항목과 연계 필요.'),
        ('List Number',
         '[v0.8.7 신규] OACX(providerCode=OACX) 운영 확인: '
         'OacxClient JAR SDK 직접 호출 방식 사용 (REST API 방식 아님). '
         '제공자별 응답 필드 비대칭(name/phone vs userNm/phoneNo) 처리 구현 완료. '
         'usePersonalEasyAuth 훅에서 initSentRef/initRequestedRef Race Condition 방지 처리. '
         '설계서 11.6절은 일반 REST API 방식으로 기술되어 있어 JAR SDK 특수성 미반영됨.'),
        ('List Number',
         '[v0.8.7 신규] FE 이중 API 인스턴스 구조(beInstance/extInstance) 설계서 반영: '
         '12절에 v0.8.7에서 12.7절로 신규 기재. '
         'beInstance(BE_API_ENDPOINT + X-BE-API-Key)는 IdO BFF 전용, '
         'extInstance(EXT_API_ENDPOINT + X-API-Key)는 Provision API 전용. '
         '이 구조가 설계 문서에 반영되지 않으면 신규 개발자 혼란 야기 가능.'),
    ]

    prev_ref = ref_para_poc._p
    for style_name, text in reversed(additional_items):  # reversed로 순서 유지
        el = OxmlElement('w:p')
        prev_ref.addprevious(el)
        p_obj = DocxPara(el, doc)
        p_obj.style = doc.styles[style_name]
        p_obj.add_run(text)

    print("   ✓ 24.8절에 EzAuth/ci-check/NICE/OACX/FE이중구조 검증 항목 추가")

    # ─────────────────────────────────────────────────
    # 9. 저장
    # ─────────────────────────────────────────────────
    doc.save(DST)
    print(f"\n=== 패치 완료 → {DST} ===")
    return True


if __name__ == '__main__':
    patch()
