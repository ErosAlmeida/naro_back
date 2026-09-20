package com.naro.publication.application.mapper;

import com.naro.publication.application.dto.CommentResponse;
import com.naro.publication.application.dto.LikeResponse;
import com.naro.publication.application.dto.LikeSummaryResponse;
import com.naro.publication.application.dto.PublicationEngagement;
import com.naro.publication.application.dto.PublicationResponse;
import com.naro.publication.domain.entity.Comment;
import com.naro.publication.domain.entity.Like;
import com.naro.publication.domain.entity.Publication;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface PublicationMapper {

    @Mapping(
            target = "authorId",
            source = "publication.author.id"
    )
    @Mapping(
            target = "authorUsername",
            source = "publication.author.username"
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
            target = "likedByCurrentUser",
            source = "engagement.likedByCurrentUser"
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
            target = "likedByCurrentUser",
            source = "likes.likedByCurrentUser"
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
            target = "userId",
            source = "user.id"
    )
    @Mapping(
            target = "username",
            source = "user.username"
    )
    LikeResponse toLikeResponse(
            Like like
    );
}