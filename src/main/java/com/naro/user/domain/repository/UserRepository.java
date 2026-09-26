package com.naro.user.domain.repository;

import com.naro.user.domain.entity.User;
import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByKeycloakId(String keycloakId);

    /** All unique indexes arbitrate concurrent first requests. The caller must
     * resolve by subject only; a username collision never grants that identity. */
    @Modifying
    @Query(value = """
            INSERT INTO users (id, keycloak_id, username, email, first_name, last_name, created_at, updated_at)
            VALUES (gen_random_uuid(), :keycloakId, :username, :email, :firstName, :lastName, now(), now())
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int provisionIfAbsent(@Param("keycloakId") String keycloakId,
                          @Param("username") String username,
                          @Param("email") String email,
                          @Param("firstName") String firstName,
                          @Param("lastName") String lastName);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :userId")
    Optional<User> findByIdForUpdate(@Param("userId") UUID userId);


    /*
     * pattern já vem em minúsculas, com curingas escapados
     * e envolvido em '%' pelo service.
     */
    @Query("""
            SELECT u
            FROM User u
            WHERE u.id <> :excludedUserId
              AND (
                    LOWER(u.username) LIKE :pattern ESCAPE '\\'
                    OR LOWER(CONCAT(COALESCE(u.firstName, ''), ' ', COALESCE(u.lastName, ''))) LIKE :pattern ESCAPE '\\'
              )
            ORDER BY u.username
            """)
    Page<User> search(
            @Param("pattern")
            String pattern,

            @Param("excludedUserId")
            UUID excludedUserId,

            Pageable pageable
    );


    /*
     * Update direto: não carrega a entidade
     * e não dispara @PreUpdate (updatedAt).
     */
    @Modifying
    @Query("""
            UPDATE User u
            SET u.lastSeenAt = :lastSeenAt
            WHERE u.id = :userId
            """)
    int updateLastSeenAt(
            @Param("userId")
            UUID userId,

            @Param("lastSeenAt")
            Instant lastSeenAt
    );


    @Query("""
            SELECT new com.naro.user.domain.repository.UserLastSeen(u.id, u.lastSeenAt)
            FROM User u
            WHERE u.id IN :userIds
            """)
    List<UserLastSeen> findLastSeenByIds(
            @Param("userIds")
            Collection<UUID> userIds
    );
}
