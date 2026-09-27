package com.naro.friend.rest;

import com.naro.config.CurrentUser;
import com.naro.friend.application.dto.FriendDiscoveryResponse;
import com.naro.friend.application.dto.FriendRequestResponse;
import com.naro.friend.application.dto.FriendResponse;
import com.naro.friend.application.dto.FriendRelationshipResponse;
import com.naro.friend.application.service.FriendService;
import com.naro.user.domain.entity.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Friends", description = "Amizades privadas entre usuários")
@RestController
@RequestMapping("/api/v1/friends")
public class FriendController {

    private final FriendService friendService;

    public FriendController(FriendService friendService) {
        this.friendService = friendService;
    }

    @GetMapping("/relationships/{userId}")
    @Operation(summary = "Consulta o relacionamento e a solicitação pendente com uma pessoa")
    public FriendRelationshipResponse relationship(@PathVariable UUID userId, @CurrentUser User currentUser) {
        return friendService.getRelationship(userId, currentUser);
    }

    @GetMapping("/discover")
    @Operation(summary = "Busca pessoas pelo username (com ou sem @) ou pelo email exato e informa o relacionamento com o usuário autenticado")
    public PagedModel<EntityModel<FriendDiscoveryResponse>> discover(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser User currentUser,
            PagedResourcesAssembler<FriendDiscoveryResponse> pagedAssembler
    ) {
        return pagedAssembler.toModel(friendService.discover(search,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100)), currentUser));
    }

    @PostMapping("/requests/{userId}")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Envia uma solicitação de amizade como usuário autenticado")
    public ResponseEntity<FriendRequestResponse> sendRequest(
            @PathVariable UUID userId,
            @CurrentUser User currentUser
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(friendService.sendRequest(userId, currentUser));
    }

    @GetMapping("/requests/received")
    @Operation(summary = "Lista solicitações pendentes recebidas")
    public PagedModel<EntityModel<FriendRequestResponse>> receivedRequests(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser User currentUser,
            PagedResourcesAssembler<FriendRequestResponse> pagedAssembler
    ) {
        return pagedAssembler.toModel(friendService.getReceivedRequests(
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100)), currentUser));
    }

    @GetMapping("/requests/sent")
    @Operation(summary = "Lista solicitações pendentes enviadas")
    public PagedModel<EntityModel<FriendRequestResponse>> sentRequests(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser User currentUser,
            PagedResourcesAssembler<FriendRequestResponse> pagedAssembler
    ) {
        return pagedAssembler.toModel(friendService.getSentRequests(
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100)), currentUser));
    }

    @PostMapping("/requests/{requestId}/accept")
    @Operation(summary = "Aceita uma solicitação recebida e cria a amizade")
    public FriendRequestResponse acceptRequest(@PathVariable UUID requestId, @CurrentUser User currentUser) {
        return friendService.acceptRequest(requestId, currentUser);
    }

    @PostMapping("/requests/{requestId}/reject")
    @Operation(summary = "Rejeita uma solicitação recebida")
    public FriendRequestResponse rejectRequest(@PathVariable UUID requestId, @CurrentUser User currentUser) {
        return friendService.rejectRequest(requestId, currentUser);
    }

    @DeleteMapping("/requests/{requestId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cancela uma solicitação pendente enviada")
    public ResponseEntity<Void> cancelRequest(@PathVariable UUID requestId, @CurrentUser User currentUser) {
        friendService.cancelRequest(requestId, currentUser);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    @Operation(summary = "Lista os amigos do usuário autenticado, mais recentes primeiro")
    public PagedModel<EntityModel<FriendResponse>> friends(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser User currentUser,
            PagedResourcesAssembler<FriendResponse> pagedAssembler
    ) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100));
        return pagedAssembler.toModel(search == null
                ? friendService.getFriends(pageable, currentUser)
                : friendService.searchFriends(search, pageable, currentUser));
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove somente a amizade com o usuário informado")
    public ResponseEntity<Void> removeFriend(@PathVariable UUID userId, @CurrentUser User currentUser) {
        friendService.removeFriend(userId, currentUser);
        return ResponseEntity.noContent().build();
    }
}
