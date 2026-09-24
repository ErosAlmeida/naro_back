package com.naro.channel.domain.repository;

import com.naro.PostgresTestcontainersConfiguration;
import com.naro.channel.domain.entity.Channel;
import com.naro.channel.domain.entity.Follow;
import com.naro.user.domain.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class FollowRepositoryTest {

    @Autowired
    private FollowRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    private Channel follower;
    private Channel sibling;
    private Channel target;
    private Channel anotherTarget;

    @BeforeEach
    void setUp() {
        User owner = entityManager.persistAndFlush(new User("kc-owner", "owner", null, null, null));
        User otherOwner = entityManager.persistAndFlush(new User("kc-other", "other", null, null, null));
        follower = channel(owner, "follower");
        sibling = channel(owner, "sibling");
        target = channel(otherOwner, "target");
        anotherTarget = channel(otherOwner, "another-target");
    }

    @Test
    void queriesUseBothChannelIdentitiesAndReturnCorrectCountsAndPages() {
        repository.saveAndFlush(Follow.between(follower, target));
        repository.saveAndFlush(Follow.between(follower, anotherTarget));
        repository.saveAndFlush(Follow.between(sibling, target));

        assertThat(repository.existsByFollowerChannel_IdAndFollowingChannel_Id(follower.getId(), target.getId())).isTrue();
        assertThat(repository.existsByFollowerChannel_IdAndFollowingChannel_Id(target.getId(), follower.getId())).isFalse();
        assertThat(repository.countByFollowerChannel_Id(follower.getId())).isEqualTo(2);
        assertThat(repository.countByFollowingChannel_Id(target.getId())).isEqualTo(2);

        assertThat(repository.findByFollowerChannel_IdOrderByCreatedAtDescIdDesc(follower.getId(), PageRequest.of(0, 1)).getContent())
                .hasSize(1)
                .extracting(follow -> follow.getFollowingChannel().getId())
                .containsAnyOf(target.getId(), anotherTarget.getId());
        assertThat(repository.findByFollowingChannel_IdOrderByCreatedAtDescIdDesc(target.getId(), PageRequest.of(0, 10)).getContent())
                .extracting(follow -> follow.getFollowerChannel().getId())
                .containsExactlyInAnyOrder(follower.getId(), sibling.getId());

        assertThat(repository.countFollowersForChannels(List.of(target.getId(), anotherTarget.getId())))
                .containsExactlyInAnyOrder(
                        new ChannelFollowCount(target.getId(), 2),
                        new ChannelFollowCount(anotherTarget.getId(), 1)
                );
        assertThat(repository.findFollowingChannelIds(follower.getId(), List.of(target.getId(), anotherTarget.getId())))
                .containsExactlyInAnyOrder(target.getId(), anotherTarget.getId());
    }

    private Channel channel(User owner, String slug) {
        Channel channel = new Channel();
        channel.setOwner(owner);
        channel.setName(slug);
        channel.setSlug(slug);
        return entityManager.persistAndFlush(channel);
    }
}
