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

import kr.go.smes.support.api.dto.AnswerQnaRequest;
import kr.go.smes.support.api.dto.QnaResponse;

@RestController
@RequestMapping("/api/v1/admin/support")
public class AdminSupportController {

    @GetMapping("/qna")
    public List<QnaResponse> listQna() {
        return List.of();
    }

    @PostMapping("/qna/{id}/answer")
    @ResponseStatus(HttpStatus.CREATED)
    public QnaResponse answer(@PathVariable UUID id, @Valid @RequestBody AnswerQnaRequest request) {
        return QnaResponse.answeredPlaceholder(id, request.content());
    }
}
