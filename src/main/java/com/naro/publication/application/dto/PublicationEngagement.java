package com.naro.publication.application.dto;

public record PublicationEngagement(

        long likeCount,

        long commentCount,

        boolean likedByCurrentUser

) {

    public static PublicationEngagement none() {
        return new PublicationEngagement(0, 0, false);
    }
}
