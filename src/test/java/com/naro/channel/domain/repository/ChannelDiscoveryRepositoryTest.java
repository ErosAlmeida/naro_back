package com.naro.channel.domain.repository;

import com.naro.PostgresTestcontainersConfiguration;
import com.naro.channel.domain.entity.Channel;
import com.naro.user.domain.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ChannelDiscoveryRepositoryTest {

    @Autowired private ChannelRepository repository;
    @Autowired private TestEntityManager entityManager;

    @Test
    void discoverExcludesAllOfTheUsersChannelsAndSearchesNameOrSlugWithPagination() {
        User owner = entityManager.persistAndFlush(new User("kc-owner", "owner", null, null, null));
        User other = entityManager.persistAndFlush(new User("kc-other", "other", null, null, null));
        channel(owner, "My Java", "my-java");
        channel(owner, "My Games", "my-games");
        Channel byName = channel(other, "Java Brasil", "brasil");
        Channel bySlug = channel(other, "Spring Brasil", "java-spring");
        channel(other, "Nintendo", "nintendo");

        var all = repository.findByOwner_IdNotOrderByCreatedAtDescIdDesc(owner.getId(), PageRequest.of(0, 10));
        assertThat(all.getTotalElements()).isEqualTo(3);
        assertThat(all.getContent()).extracting(Channel::getOwner).extracting(User::getId)
                .doesNotContain(owner.getId());

        var first = repository.searchDiscoverable(owner.getId(), "%java%", PageRequest.of(0, 1));
        var second = repository.searchDiscoverable(owner.getId(), "%java%", PageRequest.of(1, 1));
        assertThat(first.getTotalElements()).isEqualTo(2);
        assertThat(first.getContent()).hasSize(1);
        assertThat(second.getContent()).hasSize(1);
        assertThat(Set.of(first.getContent().getFirst().getId(), second.getContent().getFirst().getId()))
                .containsExactlyInAnyOrder(byName.getId(), bySlug.getId());
    }

    private Channel channel(User owner, String name, String slug) {
        Channel channel = new Channel();
        channel.setOwner(owner);
        channel.setName(name);
        channel.setSlug(slug);
        return entityManager.persistAndFlush(channel);
    }
}
