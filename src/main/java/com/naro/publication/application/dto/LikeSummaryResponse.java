package com.naro.publication.application.dto;

/**
 * Likes de uma publicação ou de um comentário, do ponto de vista
 * do canal selecionado. Também usado para enriquecer páginas
 * (publicações, comentários) sem N+1.
 */
public record LikeSummaryResponse(

        long likeCount,

        boolean likedByCurrentChannel

) {

    public static LikeSummaryResponse none() {
        return new LikeSummaryResponse(0, false);
    }
}
