package kr.go.smes.support.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
@Table(name = "faq_group")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class FaqGroupEntity {

    @Id
    @Column(name = "faq_group_id", nullable = false)
    private UUID faqGroupId;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "faq_group_cd", nullable = false, length = 50)
    private String faqGroupCode;

    @Column(name = "faq_group_nm", nullable = false, length = 120)
    private String faqGroupName;

    @Column(name = "faq_group_desc", length = 500)
    private String faqGroupDescription;

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
