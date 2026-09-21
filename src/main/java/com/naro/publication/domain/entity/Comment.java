package com.naro.publication.domain.entity;

import com.naro.channel.domain.entity.Channel;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Comentário direto em uma publicação ou resposta de outro comentário,
 * sem limite de profundidade:
 *
 *   comentário raiz: parentComment == null
 *   resposta:        parentComment != null
 *
 * Todo comentário guarda a publicação original, inclusive as respostas
 * (herdada do pai em reply). O banco garante isso com a FK composta
 * fk_comments_parent.
 *
 * Não há coleção de respostas mapeada: elas são buscadas paginadas pelo
 * repository, para que carregar um comentário nunca carregue a árvore.
 */
@Getter
@Entity
@Table(name = "comments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Comment {

    public static final int MAX_CONTENT_LENGTH = 1000;


    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;


    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "publication_id",
            nullable = false,
            updatable = false
    )
    private Publication publication;


    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "parent_comment_id",
            updatable = false
    )
    private Comment parentComment;


    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "channel_id",
            nullable = false,
            updatable = false
    )
    private Channel author;


    @Column(
            nullable = false,
            length = MAX_CONTENT_LENGTH
    )
    private String content;


    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;


    public static Comment onPublication(
            Channel author,
            Publication publication,
            String content
    ) {
        Comment comment = new Comment();

        comment.author = Objects.requireNonNull(author, "author");
        comment.publication = Objects.requireNonNull(publication, "publication");
        comment.content = content;

        return comment;
    }


    /** Resposta a um comentário de qualquer nível. A publicação é a do pai. */
    public static Comment reply(
            Channel author,
            Comment parent,
            String content
    ) {
        Comment comment = new Comment();

        comment.author = Objects.requireNonNull(author, "author");
        comment.parentComment = Objects.requireNonNull(parent, "parent");
        comment.publication = parent.getPublication();
        comment.content = content;

        return comment;
    }


    public boolean isRootComment() {
        return parentComment == null;
    }


    public boolean isReply() {
        return parentComment != null;
    }


    /*
     * getId() em um proxy LAZY não inicializa o autor,
     * então essa checagem não gera query extra.
     */
    public boolean isAuthoredBy(Channel channel) {
        return author.getId().equals(channel.getId());
    }


    @PrePersist
    private void prePersist() {
        this.createdAt = Instant.now();
    }
}
