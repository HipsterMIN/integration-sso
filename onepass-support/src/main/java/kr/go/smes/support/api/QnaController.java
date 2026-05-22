package kr.go.smes.support.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import kr.go.smes.support.api.dto.CreateQnaRequest;
import kr.go.smes.support.api.dto.QnaResponse;

@RestController
@RequestMapping("/api/v1/support/qna")
public class QnaController {

    @GetMapping
    public List<QnaResponse> list() {
        return List.of();
    }

    @GetMapping("/{id}")
    public QnaResponse get(@PathVariable UUID id) {
        return QnaResponse.placeholder(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QnaResponse create(@Valid @RequestBody CreateQnaRequest request) {
        return QnaResponse.createdPlaceholder(request.title());
    }
}
