package com.naro.channel.application.service;

import com.naro.channel.application.dto.ChannelResponse;
import com.naro.channel.application.dto.CreateChannelRequest;
import com.naro.channel.application.dto.UpdateChannelRequest;
import com.naro.channel.domain.entity.Channel;
import com.naro.user.domain.entity.User;

import java.util.List;
import java.util.UUID;

public interface ChannelService {

    ChannelResponse createChannel(
            CreateChannelRequest request,
            User author
    );


    /** Canais do usuário autenticado. Vazio quando ele ainda não criou nenhum. */
    List<ChannelResponse> getMyChannels(User owner);

    ChannelResponse getById(UUID channelId);

    ChannelResponse getBySlug(String slug);

    ChannelResponse update(UUID channelId, UpdateChannelRequest request, User currentUser);

    /** Resolve o canal selecionado e valida seu dono antes de qualquer ação pública. */
    Channel getOwnedChannel(UUID channelId, User currentUser);

}
