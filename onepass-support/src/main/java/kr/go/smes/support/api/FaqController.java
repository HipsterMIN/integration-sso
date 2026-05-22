package kr.go.smes.support.api;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kr.go.smes.support.api.dto.FaqResponse;

@RestController
@RequestMapping("/api/v1/support/faqs")
public class FaqController {

    @GetMapping
    public List<FaqResponse> list() {
        return List.of();
    }

    @GetMapping("/{id}")
    public FaqResponse get(@PathVariable UUID id) {
        return FaqResponse.placeholder(id);
    }
}
