package com.naro.seed;

import java.util.List;

/**
 * Conteúdo de {@code seed/content.json}: textos escritos para o seed e as
 * imagens em {@code seed/images}. Autores de comentários são as chaves dos
 * canais do próprio manifesto.
 */
record SeedManifest(List<SeedChannel> channels) {

    record SeedChannel(
            String key,
            SeedUser user,
            String name,
            String slug,
            String description,
            String profileImage,
            String bannerImage,
            List<SeedPublication> publications
    ) {
    }

    record SeedUser(
            String username,
            String firstName,
            String lastName,
            String bio
    ) {
    }

    record SeedPublication(
            String image,
            String content,
            List<SeedComment> comments
    ) {

        List<SeedComment> commentsOrEmpty() {
            return comments == null ? List.of() : comments;
        }
    }

    record SeedComment(
            String author,
            String content,
            List<SeedComment> replies
    ) {

        List<SeedComment> repliesOrEmpty() {
            return replies == null ? List.of() : replies;
        }
    }
}
