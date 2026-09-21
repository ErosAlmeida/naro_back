package com.naro.channel.application.mapper;

import com.naro.channel.application.dto.ChannelResponse;
import com.naro.channel.application.dto.CreateChannelRequest;
import com.naro.channel.domain.entity.Channel;
import com.naro.user.domain.entity.User;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface ChannelMapper {

    @Mapping(target = "ownerId", source = "channel.owner.id")
    @Mapping(target = "profileImageUrl", source = "profileImageUrl")
    @Mapping(target = "bannerImageUrl", source = "bannerImageUrl")
    ChannelResponse toResponse(
            Channel channel,
            String profileImageUrl,
            String bannerImageUrl
    );

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "owner", source = "author")
    @Mapping(target = "name", source = "request.name")
    @Mapping(target = "slug", source = "request.slug")
    @Mapping(target = "description", source = "request.description")
    @Mapping(target = "profileImageKey", source = "profileImageKey")
    @Mapping(target = "bannerImageKey", source = "bannerImageKey")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Channel toEntity(
            CreateChannelRequest request,
            User author,
            String profileImageKey,
            String bannerImageKey
    );
}
