package com.naro.publication.domain.repository;

import java.util.UUID;

/**
 * Resultado de contagens agrupadas por id (likes por publicação ou
 * comentário, comentários por publicação, respostas por comentário),
 * usado para montar páginas sem N+1.
 */
public record IdCount(

        UUID id,

        long count

) {
}
