package com.naro.realtime.rest;

import com.naro.config.CurrentUser;
import com.naro.realtime.application.dto.WebSocketTicketResponse;
import com.naro.realtime.application.service.WebSocketTicketService;
import com.naro.user.domain.entity.User;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Realtime", description = "Acesso ao WebSocket (/ws)")
@RestController
@RequestMapping("/api/v1/realtime/tickets")
public class WebSocketTicketController {

    private final WebSocketTicketService ticketService;


    public WebSocketTicketController(
            WebSocketTicketService ticketService
    ) {
        this.ticketService = ticketService;
    }


    @PostMapping
    @Operation(summary = "Emite um ticket de uso único para o CONNECT do WebSocket")
    public WebSocketTicketResponse issue(

            @CurrentUser
            User currentUser

    ) {

        return ticketService.issue(currentUser);
    }
}
