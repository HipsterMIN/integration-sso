package io.github.hipstermin.idem.registry.conversion;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 유관 기관 레지스트리 — 68개 기관 등록 정보 관리
 *
 * <p>설계서 PPTX 2.1 기준 유관 시스템 목록.
 * PoC 단계: agency-stub 단일 인스턴스를 다수 기관 코드로 시뮬레이션.
 * 운영 단계: DB(agency_registry 테이블) 또는 외부 설정 서버에서 로드.
 *
 * <h3>기관 코드 체계</h3>
 * <pre>
 *   GOV_SMES   — 소상공인시장진흥공단
 *   GOV_MSS    — 중소벤처기업부
 *   GOV_MOEL   — 고용노동부
 *   GOV_NTS    — 국세청
 *   GOV_MOHW   — 보건복지부
 *   GOV_MOLIT  — 국토교통부
 *   GOV_MOF    — 기획재정부
 *   GOV_MOIS   — 행정안전부
 *   ... (총 68개, 이하 GOV_AG_01 ~ GOV_AG_60 으로 대표)
 * </pre>
 */
@Slf4j
@Component
public class AgencyRegistry {

    /** agency-stub 기본 URL — PoC에서 모든 기관이 동일 stub으로 연결 */
    @Value("${qim.agency.stub-base-url:http://agency-stub:8083}")
    private String stubBaseUrl;

    /** agency-stub API 키 */
    @Value("${qim.agency.stub-api-key:stub-api-key-dev-001}")
    private String stubApiKey;

    /** 기관별 API 타임아웃 (ms) */
    @Value("${qim.agency.lookup-timeout-ms:3000}")
    private int timeoutMs;

    private List<AgencyRegistration> agencies;

    @PostConstruct
    void init() {
        agencies = buildAgencyList();
        log.info("[AgencyRegistry] 기관 레지스트리 초기화 완료: {}개 기관 등록",
                agencies.stream().filter(AgencyRegistration::isActive).count());
    }

    /** 활성 기관 목록 반환 */
    public List<AgencyRegistration> getActiveAgencies() {
        return Collections.unmodifiableList(
                agencies.stream().filter(AgencyRegistration::isActive).toList());
    }

    /** 전체 기관 목록 반환 */
    public List<AgencyRegistration> getAllAgencies() {
        return Collections.unmodifiableList(agencies);
    }

    // ── 68개 기관 목록 구성 ───────────────────────────────────────────────────

    private List<AgencyRegistration> buildAgencyList() {
        List<AgencyRegistration> list = new ArrayList<>();

        // ── 핵심 정부 기관 (8개) ─────────────────────────────────────────────
        list.add(reg("GOV_SMES",  "소상공인시장진흥공단"));
        list.add(reg("GOV_MSS",   "중소벤처기업부"));
        list.add(reg("GOV_MOEL",  "고용노동부"));
        list.add(reg("GOV_NTS",   "국세청"));
        list.add(reg("GOV_MOHW",  "보건복지부"));
        list.add(reg("GOV_MOLIT", "국토교통부"));
        list.add(reg("GOV_MOF",   "기획재정부"));
        list.add(reg("GOV_MOIS",  "행정안전부"));

        // ── 공공기관 (12개) ──────────────────────────────────────────────────
        list.add(reg("PUB_KIBO",    "기술보증기금"));
        list.add(reg("PUB_KOSMES",  "중소기업진흥공단"));
        list.add(reg("PUB_KVIC",    "한국벤처투자"));
        list.add(reg("PUB_KOICA",   "한국국제협력단"));
        list.add(reg("PUB_NHI",     "국민건강보험공단"));
        list.add(reg("PUB_NPS",     "국민연금공단"));
        list.add(reg("PUB_COMWEL",  "근로복지공단"));
        list.add(reg("PUB_KOEF",    "한국고용정보원"));
        list.add(reg("PUB_KOSHA",   "한국산업안전보건공단"));
        list.add(reg("PUB_HRD",     "한국산업인력공단"));
        list.add(reg("PUB_KISA",    "한국인터넷진흥원"));
        list.add(reg("PUB_NIPA",    "정보통신산업진흥원"));

        // ── 금융 기관 (10개) ─────────────────────────────────────────────────
        list.add(reg("FIN_IBK",    "기업은행"));
        list.add(reg("FIN_KDB",    "산업은행"));
        list.add(reg("FIN_NACF",   "농협은행"));
        list.add(reg("FIN_KEXIM",  "수출입은행"));
        list.add(reg("FIN_KFCC",   "새마을금고중앙회"));
        list.add(reg("FIN_SBC",    "신용보증기금"));
        list.add(reg("FIN_NICE",   "NICE평가정보"));
        list.add(reg("FIN_KCB",    "코리아크레딧뷰로"));
        list.add(reg("FIN_KOFIA",  "금융투자협회"));
        list.add(reg("FIN_FSS",    "금융감독원"));

        // ── 지방자치단체 대표 (18개) ─────────────────────────────────────────
        list.add(reg("LOC_SEOUL",   "서울특별시"));
        list.add(reg("LOC_BUSAN",   "부산광역시"));
        list.add(reg("LOC_DAEGU",   "대구광역시"));
        list.add(reg("LOC_INCHEON", "인천광역시"));
        list.add(reg("LOC_GWANGJU", "광주광역시"));
        list.add(reg("LOC_DAEJEON", "대전광역시"));
        list.add(reg("LOC_ULSAN",   "울산광역시"));
        list.add(reg("LOC_SEJONG",  "세종특별자치시"));
        list.add(reg("LOC_GYEONGGI","경기도"));
        list.add(reg("LOC_GANGWON", "강원특별자치도"));
        list.add(reg("LOC_CHUNGBUK","충청북도"));
        list.add(reg("LOC_CHUNGNAM","충청남도"));
        list.add(reg("LOC_JEONBUK", "전북특별자치도"));
        list.add(reg("LOC_JEONNAM", "전라남도"));
        list.add(reg("LOC_GYEONGBUK","경상북도"));
        list.add(reg("LOC_GYEONGNAM","경상남도"));
        list.add(reg("LOC_JEJU",    "제주특별자치도"));
        list.add(reg("LOC_TECH",    "테크노파크연합"));

        // ── 산하 기관 / 연구소 (10개) ────────────────────────────────────────
        list.add(reg("RES_ETRI",   "한국전자통신연구원"));
        list.add(reg("RES_KISTI",  "한국과학기술정보연구원"));
        list.add(reg("RES_KBIZ",   "중소기업중앙회"));
        list.add(reg("RES_KCCI",   "대한상공회의소"));
        list.add(reg("RES_KFTA",   "한국무역협회"));
        list.add(reg("RES_KOTRA",  "대한무역투자진흥공사"));
        list.add(reg("RES_KIF",    "한국금융연구원"));
        list.add(reg("RES_KIEP",   "대외경제정책연구원"));
        list.add(reg("RES_KERI",   "한국경제연구원"));
        list.add(reg("RES_KDI",    "한국개발연구원"));

        // ── 추가 기관 (10개 — 총 68개 완성) ─────────────────────────────────
        for (int i = 1; i <= 10; i++) {
            list.add(reg(String.format("GOV_AG_%02d", i),
                         String.format("유관기관_%02d", i)));
        }

        return list;
    }

    private AgencyRegistration reg(String code, String name) {
        return AgencyRegistration.builder()
                .agencyCode(code)
                .agencyName(name)
                .baseUrl(stubBaseUrl)
                .apiKey(stubApiKey)
                .timeoutMs(timeoutMs)
                .active(true)
                .build();
    }
}
