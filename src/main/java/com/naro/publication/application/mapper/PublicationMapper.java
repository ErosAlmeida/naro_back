package com.naro.publication.application.mapper;

import com.naro.publication.application.dto.CommentResponse;
import com.naro.publication.application.dto.LikeResponse;
import com.naro.publication.application.dto.LikeSummaryResponse;
import com.naro.publication.application.dto.PublicationEngagement;
import com.naro.publication.application.dto.PublicationResponse;
import com.naro.channel.application.dto.ChannelSummaryResponse;
import com.naro.channel.domain.entity.Channel;
import com.naro.publication.domain.entity.Comment;
import com.naro.publication.domain.entity.Like;
import com.naro.publication.domain.entity.Publication;
import com.naro.storage.service.ObjectReadUrls;

import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        uses = ObjectReadUrls.class,
        injectionStrategy = InjectionStrategy.CONSTRUCTOR,
        unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface PublicationMapper {

    /*
     * Autoria pública: usado automaticamente para o `author` da publicação e
     * do comentário, com a foto do canal já pronta para exibição.
     */
    @Mapping(
            target = "profileImageUrl",
            source = "profileImageKey",
            qualifiedByName = "readUrl"
    )
    ChannelSummaryResponse toAuthor(Channel channel);


    @Mapping(
            target = "author",
            source = "publication.author"
    )
    @Mapping(
            target = "imageUrl",
            source = "imageUrl"
    )
    @Mapping(
            target = "likeCount",
            source = "engagement.likeCount"
    )
    @Mapping(
            target = "commentCount",
            source = "engagement.commentCount"
    )
    @Mapping(
            target = "likedByCurrentChannel",
            source = "engagement.likedByCurrentChannel"
    )
    PublicationResponse toResponse(
            Publication publication,
            String imageUrl,
            PublicationEngagement engagement
    );


    @Mapping(
            target = "id",
            source = "comment.id"
    )
    @Mapping(
            target = "publicationId",
            source = "comment.publication.id"
    )
    @Mapping(
            target = "parentCommentId",
            source = "comment.parentComment.id"
    )
    @Mapping(
            target = "content",
            source = "comment.content"
    )
    @Mapping(
            target = "author",
            source = "comment.author"
    )
    @Mapping(
            target = "likeCount",
            source = "likes.likeCount"
    )
    @Mapping(
            target = "likedByCurrentChannel",
            source = "likes.likedByCurrentChannel"
    )
    @Mapping(
            target = "replyCount",
            source = "replyCount"
    )
    @Mapping(
            target = "createdAt",
            source = "comment.createdAt"
    )
    CommentResponse toCommentResponse(
            Comment comment,
            LikeSummaryResponse likes,
            long replyCount
    );


    @Mapping(
            target = "channelId",
            source = "channel.id"
    )
    @Mapping(
            target = "slug",
            source = "channel.slug"
    )
    LikeResponse toLikeResponse(
            Like like
    );
}