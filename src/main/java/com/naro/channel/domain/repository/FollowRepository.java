package com.naro.channel.domain.repository;

import com.naro.channel.domain.entity.Follow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface FollowRepository extends JpaRepository<Follow, UUID> {

    boolean existsByFollowerChannel_IdAndFollowingChannel_Id(UUID followerChannelId, UUID followingChannelId);

    Optional<Follow> findByFollowerChannel_IdAndFollowingChannel_Id(UUID followerChannelId, UUID followingChannelId);

    @EntityGraph(attributePaths = "followingChannel")
    Page<Follow> findByFollowerChannel_IdOrderByCreatedAtDescIdDesc(UUID followerChannelId, Pageable pageable);

    @EntityGraph(attributePaths = "followerChannel")
    Page<Follow> findByFollowingChannel_IdOrderByCreatedAtDescIdDesc(UUID followingChannelId, Pageable pageable);

    long countByFollowerChannel_Id(UUID followerChannelId);

    long countByFollowingChannel_Id(UUID followingChannelId);

    @Query("""
            SELECT new com.naro.channel.domain.repository.ChannelFollowCount(f.followingChannel.id, COUNT(f))
            FROM Follow f
            WHERE f.followingChannel.id IN :channelIds
            GROUP BY f.followingChannel.id
            """)
    List<ChannelFollowCount> countFollowersForChannels(@Param("channelIds") Collection<UUID> channelIds);

    @Query("""
            SELECT f.followingChannel.id
            FROM Follow f
            WHERE f.followerChannel.id = :followerChannelId
              AND f.followingChannel.id IN :channelIds
            """)
    Set<UUID> findFollowingChannelIds(
            @Param("followerChannelId") UUID followerChannelId,
            @Param("channelIds") Collection<UUID> channelIds
    );
}
