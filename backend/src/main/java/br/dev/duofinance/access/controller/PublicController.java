package br.dev.duofinance.access.controller;

import br.dev.duofinance.access.dto.JoinWaitlistRequest;
import br.dev.duofinance.access.dto.RegistrationModeResponse;
import br.dev.duofinance.access.service.WaitlistService;
import br.dev.duofinance.shared.config.AppProperties;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public")
public class PublicController {

    private final AppProperties props;
    private final WaitlistService waitlist;

    public PublicController(AppProperties props, WaitlistService waitlist) {
        this.props = props;
        this.waitlist = waitlist;
    }

    @GetMapping("/registration-mode")
    public RegistrationModeResponse registrationMode() {
        return new RegistrationModeResponse(props.registration().mode());
    }

    @PostMapping("/waitlist")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void join(@Valid @RequestBody JoinWaitlistRequest request) {
        waitlist.join(request.email(), request.locale());
    }
}
