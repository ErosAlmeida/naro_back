package com.naro.friend.domain.repository;

import com.naro.friend.domain.entity.Friendship;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FriendshipRepository extends JpaRepository<Friendship, UUID> {

    /* Friendships are undirected; edges exposes each direction once. Mutuals
     * only count real accounts. Follows and channel activity never affect rank.
     * An explicit count query applies the same exclusions before pagination. */
    @Query(value = """
            WITH edges AS (
                SELECT user_a_id AS user_id, user_b_id AS friend_id FROM friendships
                UNION ALL
                SELECT user_b_id AS user_id, user_a_id AS friend_id FROM friendships
            ), candidates AS (
                SELECT u.id, u.username, u.first_name AS "firstName",
                       u.last_name AS "lastName", u.avatar_url AS "avatarUrl",
                       CASE
                           WHEN EXISTS (SELECT 1 FROM edges e
                                        WHERE e.user_id = :currentUserId AND e.friend_id = u.id)
                               THEN 'FRIEND'
                           WHEN EXISTS (SELECT 1 FROM friend_requests r WHERE r.status = 'PENDING'
                                        AND r.sender_id = :currentUserId AND r.receiver_id = u.id)
                               THEN 'REQUEST_SENT'
                           WHEN EXISTS (SELECT 1 FROM friend_requests r WHERE r.status = 'PENDING'
                                        AND r.receiver_id = :currentUserId AND r.sender_id = u.id)
                               THEN 'REQUEST_RECEIVED'
                           ELSE 'NONE'
                       END AS "relationshipStatus",
                       (SELECT COUNT(*) FROM edges own
                        JOIN edges theirs ON theirs.friend_id = own.friend_id AND theirs.user_id = u.id
                        JOIN users mutual ON mutual.id = own.friend_id
                        WHERE own.user_id = :currentUserId
                          AND mutual.keycloak_id NOT LIKE 'seed:%'
                          AND mutual.keycloak_id NOT LIKE 'friend-seed:%') AS "mutualFriendsCount"
                FROM users u
                WHERE u.id <> :currentUserId
                  AND u.keycloak_id NOT LIKE 'seed:%'
                  AND u.keycloak_id NOT LIKE 'friend-seed:%'
                  AND (LOWER(u.username) LIKE :pattern ESCAPE '\\'
                       OR LOWER(CONCAT(COALESCE(u.first_name, ''), ' ', COALESCE(u.last_name, '')))
                          LIKE :pattern ESCAPE '\\')
            )
            SELECT * FROM candidates
            WHERE NOT :recommendationsOnly OR "relationshipStatus" = 'NONE'
            ORDER BY "mutualFriendsCount" DESC, LOWER(username), id
            """, countQuery = """
            SELECT COUNT(*) FROM users u
            WHERE u.id <> :currentUserId
              AND u.keycloak_id NOT LIKE 'seed:%'
              AND u.keycloak_id NOT LIKE 'friend-seed:%'
              AND (LOWER(u.username) LIKE :pattern ESCAPE '\\'
                   OR LOWER(CONCAT(COALESCE(u.first_name, ''), ' ', COALESCE(u.last_name, '')))
                      LIKE :pattern ESCAPE '\\')
              AND (NOT :recommendationsOnly OR (
                  NOT EXISTS (SELECT 1 FROM friendships f
                              WHERE (f.user_a_id = :currentUserId AND f.user_b_id = u.id)
                                 OR (f.user_b_id = :currentUserId AND f.user_a_id = u.id))
                  AND NOT EXISTS (SELECT 1 FROM friend_requests r WHERE r.status = 'PENDING'
                                  AND ((r.sender_id = :currentUserId AND r.receiver_id = u.id)
                                    OR (r.receiver_id = :currentUserId AND r.sender_id = u.id)))
              ))
            """, nativeQuery = true)
    Page<FriendDiscoveryCandidate> findDiscoveryCandidates(@Param("pattern") String pattern,
                                                           @Param("currentUserId") UUID currentUserId,
                                                           @Param("recommendationsOnly") boolean recommendationsOnly,
                                                           Pageable pageable);

    @Query("""
            SELECT COUNT(f) > 0 FROM Friendship f
            WHERE (f.userA.id = :first AND f.userB.id = :second)
               OR (f.userA.id = :second AND f.userB.id = :first)
            """)
    boolean existsFriendshipBetweenUsers(@Param("first") UUID first, @Param("second") UUID second);

    @EntityGraph(attributePaths = {"userA", "userB"})
    @Query("""
            SELECT f FROM Friendship f
            WHERE (f.userA.id = :first AND f.userB.id = :second)
               OR (f.userA.id = :second AND f.userB.id = :first)
            """)
    Optional<Friendship> findFriendshipBetweenUsers(@Param("first") UUID first, @Param("second") UUID second);

    @EntityGraph(attributePaths = {"userA", "userB"})
    @Query("""
            SELECT f FROM Friendship f
            WHERE (f.userA.id = :userId AND f.userB.keycloakId NOT LIKE 'seed:%'
                   AND f.userB.keycloakId NOT LIKE 'friend-seed:%')
               OR (f.userB.id = :userId AND f.userA.keycloakId NOT LIKE 'seed:%'
                   AND f.userA.keycloakId NOT LIKE 'friend-seed:%')
            ORDER BY f.createdAt DESC, f.id DESC
            """)
    Page<Friendship> findFriendsByUser(@Param("userId") UUID userId, Pageable pageable);

    @EntityGraph(attributePaths = {"userA", "userB"})
    @Query("""
            SELECT f FROM Friendship f
            JOIN f.userA a JOIN f.userB b
            WHERE (a.id = :userId AND b.keycloakId NOT LIKE 'seed:%'
                   AND b.keycloakId NOT LIKE 'friend-seed:%'
                   AND (LOWER(b.username) LIKE :pattern ESCAPE '\\'
                        OR LOWER(CONCAT(COALESCE(b.firstName, ''), ' ', COALESCE(b.lastName, '')))
                           LIKE :pattern ESCAPE '\\'))
               OR (b.id = :userId AND a.keycloakId NOT LIKE 'seed:%'
                   AND a.keycloakId NOT LIKE 'friend-seed:%'
                   AND (LOWER(a.username) LIKE :pattern ESCAPE '\\'
                        OR LOWER(CONCAT(COALESCE(a.firstName, ''), ' ', COALESCE(a.lastName, '')))
                           LIKE :pattern ESCAPE '\\'))
            ORDER BY f.createdAt DESC, f.id DESC
            """)
    Page<Friendship> searchFriendsByUser(@Param("userId") UUID userId, @Param("pattern") String pattern,
                                         Pageable pageable);

    @EntityGraph(attributePaths = {"userA", "userB"})
    @Query("""
            SELECT f FROM Friendship f
            WHERE (f.userA.id = :userId AND f.userB.id IN :targetIds)
               OR (f.userB.id = :userId AND f.userA.id IN :targetIds)
            """)
    List<Friendship> findFriendshipsAmong(@Param("userId") UUID userId, @Param("targetIds") Collection<UUID> targetIds);
}
