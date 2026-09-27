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

    /* Manual search: any real account whose username contains the term, or whose
     * email is exactly the term, in any relationship state. The email is only
     * matched, never returned, and a partial email finds nobody. Friendships are
     * undirected, so every hop reads both columns through their own index. Mutual
     * friends are counted once for the few friends of friends and joined, instead
     * of once per matching user. Mutuals only count real accounts. Exact username
     * first, then prefix, then the rest, each by mutual friends. The count query
     * applies the same filters. */
    @Query(value = """
            WITH my_friends AS (
                SELECT f.user_b_id AS friend_id FROM friendships f WHERE f.user_a_id = :currentUserId
                UNION ALL
                SELECT f.user_a_id AS friend_id FROM friendships f WHERE f.user_b_id = :currentUserId
            ), real_friends AS (
                SELECT mf.friend_id FROM my_friends mf
                JOIN users mutual ON mutual.id = mf.friend_id
                WHERE mutual.keycloak_id NOT LIKE 'seed:%'
                  AND mutual.keycloak_id NOT LIKE 'friend-seed:%'
            ), friends_of_friends AS (
                SELECT f.user_b_id AS candidate_id, f.user_a_id AS mutual_id
                FROM friendships f JOIN real_friends rf ON f.user_a_id = rf.friend_id
                UNION ALL
                SELECT f.user_a_id AS candidate_id, f.user_b_id AS mutual_id
                FROM friendships f JOIN real_friends rf ON f.user_b_id = rf.friend_id
            ), mutual_counts AS (
                SELECT fof.candidate_id, COUNT(DISTINCT fof.mutual_id) AS mutual_friends
                FROM friends_of_friends fof
                GROUP BY fof.candidate_id
            )
            SELECT u.id, u.username, u.first_name AS "firstName",
                   u.last_name AS "lastName", u.avatar_url AS "avatarUrl",
                   CASE
                       WHEN EXISTS (SELECT 1 FROM my_friends mf WHERE mf.friend_id = u.id)
                           THEN 'FRIEND'
                       WHEN EXISTS (SELECT 1 FROM friend_requests r WHERE r.status = 'PENDING'
                                    AND r.sender_id = :currentUserId AND r.receiver_id = u.id)
                           THEN 'REQUEST_SENT'
                       WHEN EXISTS (SELECT 1 FROM friend_requests r WHERE r.status = 'PENDING'
                                    AND r.receiver_id = :currentUserId AND r.sender_id = u.id)
                           THEN 'REQUEST_RECEIVED'
                       ELSE 'NONE'
                   END AS "relationshipStatus",
                   COALESCE(mc.mutual_friends, 0) AS "mutualFriendsCount"
            FROM users u
            LEFT JOIN mutual_counts mc ON mc.candidate_id = u.id
            WHERE u.id <> :currentUserId
              AND u.keycloak_id NOT LIKE 'seed:%'
              AND u.keycloak_id NOT LIKE 'friend-seed:%'
              AND (LOWER(u.username) LIKE :pattern ESCAPE '\\' OR LOWER(u.email) = :term)
            ORDER BY LOWER(u.username) = :term DESC, STRPOS(LOWER(u.username), :term) = 1 DESC,
                     "mutualFriendsCount" DESC, LOWER(u.username), u.id
            """, countQuery = """
            SELECT COUNT(*) FROM users u
            WHERE u.id <> :currentUserId
              AND u.keycloak_id NOT LIKE 'seed:%'
              AND u.keycloak_id NOT LIKE 'friend-seed:%'
              AND (LOWER(u.username) LIKE :pattern ESCAPE '\\' OR LOWER(u.email) = :term)
            """, nativeQuery = true)
    Page<FriendDiscoveryCandidate> searchByUsernameOrEmail(@Param("term") String term,
                                                           @Param("pattern") String pattern,
                                                           @Param("currentUserId") UUID currentUserId,
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
