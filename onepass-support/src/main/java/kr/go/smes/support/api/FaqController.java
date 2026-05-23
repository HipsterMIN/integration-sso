package kr.go.smes.support.api;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import kr.go.smes.support.application.FaqService;
import kr.go.smes.support.api.dto.FaqGroupResponse;
import kr.go.smes.support.api.dto.FaqResponse;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/support/faqs")
@RequiredArgsConstructor
public class FaqController {

    private final FaqService faqService;

    @GetMapping
    @Operation(summary = "FAQ 목록 조회", description = "groupCode를 전달하면 해당 그룹 FAQ만 조회합니다.")
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(
            array = @ArraySchema(schema = @Schema(implementation = FaqResponse.class))
    ))
    public List<FaqResponse> list(@RequestParam(value = "groupCode", required = false) String groupCode) {
        return faqService.listFaqs(groupCode);
    }

    @GetMapping("/groups")
    @Operation(summary = "FAQ 그룹 목록 조회", description = "FAQ 분류 그룹(종류) 목록입니다.")
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(
            array = @ArraySchema(schema = @Schema(implementation = FaqGroupResponse.class))
    ))
    public List<FaqGroupResponse> groups() {
        return faqService.listGroups();
    }

    @GetMapping("/{id}")
    @Operation(summary = "FAQ 단건 조회")
    public FaqResponse get(@PathVariable UUID id) {
        return faqService.getFaq(id);
    }
}
