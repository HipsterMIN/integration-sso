package kr.go.smes.support.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "faq")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class FaqEntity {

    @Id
    @Column(name = "faq_id", nullable = false)
    private UUID faqId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "faq_group_id", nullable = false)
    private FaqGroupEntity faqGroup;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "agency_id")
    private String agencyId;

    @Column(name = "faq_qstn_cn", nullable = false, length = 500)
    private String faqQuestionContent;

    @Column(name = "faq_ans_cn", nullable = false)
    private String faqAnswerContent;

    @Column(name = "expsr_yn", nullable = false, length = 1, columnDefinition = "char(1)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String exposureYn;

    @Column(name = "sort_sn", nullable = false)
    private Integer sortSn;

    @Column(name = "use_yn", nullable = false, length = 1, columnDefinition = "char(1)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String useYn;

    @Column(name = "frst_regist_pnttm", nullable = false)
    private Instant frstRegistPnttm;

    @Column(name = "frst_register_id", nullable = false, length = 64)
    private String frstRegisterId;

    @Column(name = "last_updt_pnttm", nullable = false)
    private Instant lastUpdtPnttm;

    @Column(name = "last_updusr_id", nullable = false, length = 64)
    private String lastUpdusrId;
}
