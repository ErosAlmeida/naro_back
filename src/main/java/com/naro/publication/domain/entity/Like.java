package com.naro.publication.domain.entity;

import com.naro.user.domain.entity.User;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Like de um usuário em uma publicação OU em um comentário (de qualquer nível).
 *
 * Exatamente um dos dois está preenchido:
 *   - aqui, porque um Like só nasce por Like.publication(...) ou Like.comment(...)
 *   - no banco, pela constraint ck_likes_publication_xor_comment
 */
@Getter
@Entity
@Table(name = "likes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Like {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;


    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "user_id",
            nullable = false,
            updatable = false
    )
    private User user;


    /* Preenchido quando o like é em uma publicação. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "publication_id",
            updatable = false
    )
    private Publication publication;


    /* Preenchido quando o like é em um comentário. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "comment_id",
            updatable = false
    )
    private Comment comment;


    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;


    public static Like publication(
            User user,
            Publication publication
    ) {
        Like like = new Like();

        like.user = Objects.requireNonNull(user, "user");
        like.publication = Objects.requireNonNull(publication, "publication");

        return like;
    }


    public static Like comment(
            User user,
            Comment comment
    ) {
        Like like = new Like();

        like.user = Objects.requireNonNull(user, "user");
        like.comment = Objects.requireNonNull(comment, "comment");

        return like;
    }


    @PrePersist
    private void prePersist() {
        this.createdAt = Instant.now();
    }
}
