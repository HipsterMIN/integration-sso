package io.github.hipstermin.idem.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.common.compress.Compression;
import org.apache.kafka.common.record.CompressionType;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.Record;
import org.apache.kafka.common.record.RecordBatch;
import org.apache.kafka.common.record.SimpleRecord;
import org.apache.kafka.common.utils.Utils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * kafka-clients 의 압축 코덱 가드 — 루트 {@code build.gradle.kts} 가 덮어쓰는 코덱 jar(lz4-java 포크 {@code at.yawk.lz4}·
 * {@code org.xerial.snappy:snappy-java})가 kafka-clients 와 맞물리는지, 그리고 취약점 수정판 아래로 내려가지 않는지 지킨다
 * ({@code docs/certification/vulnerability-review-2026-10.md} §3 lz4, {@code vulnerability-review-2026-11.md} §8 snappy).
 *
 * <ul>
 *   <li><b>왕복</b>: 프로듀서가 만드는 것과 같은 레코드 배치를 코덱별로 만들고(브로커가 넘겨주는 바이트), 컨슈머가 하듯
 *       새 버퍼에서 읽어 CRC·내용을 맞춘다. Idem 의 프로듀서는 전부 lz4 로 보내지만, 받는 쪽은 배치에 적힌 코덱으로 푼다 —
 *       그래서 다섯 코덱을 다 본다. 브로커는 필요 없다.</li>
 *   <li><b>바닥 버전</b>: 덮어쓰기 줄은 Spring Boot 를 올릴 때 지우게 되어 있다. 그때 kafka-clients 가 끌고 오는 판이 수정판보다
 *       낮으면 PR 단계에서 실패한다(이미지 스캔은 main 머지 뒤에만 돈다).</li>
 * </ul>
 *
 * <p>kafka-clients 의 {@code org.apache.kafka.common.record}·{@code compress} 는 공개 계약이 아니다 — kafka-clients 를 올려
 * 컴파일이 깨지면 같은 왕복을 새 API 로 다시 쓴다(지우지 않는다).
 */
@DisplayName("Kafka 압축 코덱 — 덮어쓴 코덱 jar 가 kafka-clients 와 맞물리고 수정판 이상이다")
class KafkaCompressionCodecTest {

    /** snappy 블록(32KiB)·lz4 블록(64KiB)을 여러 개 넘기도록 150KB 쯤 만든다. */
    private static final int RECORDS = 500;

    @ParameterizedTest(name = "{0}")
    @EnumSource(CompressionType.class)
    @DisplayName("코덱별 레코드 배치 왕복 — 압축 → 새 버퍼에서 읽기 → CRC·내용 일치")
    void recordBatchRoundTrip(CompressionType type) {
        SimpleRecord[] sent = new SimpleRecord[RECORDS];
        List<String> expected = new ArrayList<>(RECORDS);
        for (int i = 0; i < RECORDS; i++) {
            String value = "{\"eventId\":\"01JD" + String.format("%06d", i) + "\",\"type\":\"idem.gate.auth.succeeded\",\"userId\":\"u-"
                    + (i % 37) + "\",\"agency\":\"SVC_" + (i % 5) + "\",\"payload\":\"" + "가나다라마바사".repeat(1 + i % 20) + "\"}";
            expected.add(value);
            sent[i] = new SimpleRecord(1_700_000_000_000L + i, ("k-" + i).getBytes(StandardCharsets.UTF_8),
                    value.getBytes(StandardCharsets.UTF_8));
        }

        MemoryRecords written = MemoryRecords.withRecords(Compression.of(type).build(), sent);
        MemoryRecords read = MemoryRecords.readableRecords(written.buffer().duplicate());

        List<String> received = new ArrayList<>(RECORDS);
        int rawBytes = 0;
        for (RecordBatch batch : read.batches()) {
            assertThat(batch.compressionType()).as("배치에 적힌 코덱").isEqualTo(type);
            batch.ensureValid();
            for (Record record : batch) {
                assertThat(Utils.utf8(record.key())).isEqualTo("k-" + received.size());
                received.add(Utils.utf8(record.value()));
                rawBytes += record.valueSize();
            }
        }
        assertThat(received).as("%s 로 보낸 레코드가 그대로 돌아온다", type).isEqualTo(expected);
        if (type != CompressionType.NONE) {
            assertThat(written.sizeInBytes()).as("%s 가 실제로 줄였다(원문 %d 바이트)", type, rawBytes).isLessThan(rawBytes / 2);
        }
    }

    @ParameterizedTest(name = "{0} ≥ {2}")
    @CsvSource({
        // 좌표, jar 안의 클래스, 바닥 버전, 근거
        "org.xerial.snappy:snappy-java, org.xerial.snappy.SnappyInputStream, 1.1.10.10,"
                + " 'CVE-2026-108106·CVE-2026-93451 은 1.1.10.9+, kafka-clients 가 쓰는 SnappyInputStream 보강은 1.1.10.10+'",
        "at.yawk.lz4:lz4-java, net.jpountz.lz4.LZ4Factory, 1.11.4, 'CVE-2026-106451 은 1.11.4+ (유지 관리 포크)'"
    })
    @DisplayName("코덱 jar 가 취약점 수정판 이상이다")
    void codecJarIsAtLeastTheFixedVersion(String coordinates, String className, String floor, String reason) throws Exception {
        String version = bundleVersion(Class.forName(className));
        assertThat(compareVersions(version, floor))
                .as("%s 가 %s 이다 — %s 이상이어야 한다(%s). 루트 build.gradle.kts 의 dependencyManagement 덮어쓰기를 확인한다",
                        coordinates, version, floor, reason)
                .isGreaterThanOrEqualTo(0);
    }

    /** 클래스가 실린 jar 의 매니페스트 {@code Bundle-Version} — 두 코덱 jar 모두 OSGi 번들이라 좌표의 버전이 그대로 적혀 있다. */
    private static String bundleVersion(Class<?> type) throws IOException {
        URL url = type.getResource(type.getSimpleName() + ".class");
        assertThat(url).as("%s 의 클래스 파일 위치", type.getName()).isNotNull();
        assertThat(url.getProtocol()).as("%s 는 jar 에서 읽혀야 한다: %s", type.getName(), url).isEqualTo("jar");
        JarURLConnection connection = (JarURLConnection) url.openConnection();
        connection.setUseCaches(false);
        String version = connection.getManifest().getMainAttributes().getValue("Bundle-Version");
        assertThat(version).as("%s 의 Bundle-Version", connection.getJarFileURL()).isNotBlank();
        return version;
    }

    /** 점·하이픈으로 나눈 숫자 마디를 왼쪽부터 비교한다. 숫자가 아닌 마디(한정자)는 0 으로 본다. */
    static int compareVersions(String left, String right) {
        String[] a = left.split("[.-]");
        String[] b = right.split("[.-]");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? numeric(a[i]) : 0;
            int y = i < b.length ? numeric(b[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static int numeric(String part) {
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
