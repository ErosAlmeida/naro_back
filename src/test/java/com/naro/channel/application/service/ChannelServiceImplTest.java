package com.naro.channel.application.service;

import com.naro.channel.application.dto.CreateChannelRequest;
import com.naro.channel.application.dto.UpdateChannelRequest;
import com.naro.channel.application.mapper.ChannelMapperImpl;
import com.naro.channel.domain.entity.Channel;
import com.naro.channel.domain.repository.ChannelRepository;
import com.naro.exception.ConflictException;
import com.naro.exception.ForbiddenException;
import com.naro.exception.NotFoundException;
import com.naro.storage.service.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.naro.support.Fixtures.channel;
import static com.naro.support.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChannelServiceImplTest {
    @Mock
    private ChannelRepository repository;
    @Mock
    private ObjectStorageService storage;

    private ChannelServiceImpl service;
    private final Channel channel = channel("leonardo");

    @BeforeEach
    void setUp() {
        service = new ChannelServiceImpl(repository, storage, new ChannelMapperImpl());
    }

    @Test
    void findsByIdAndSlugWithoutExposingOwnerEntity() {
        when(repository.findById(channel.getId())).thenReturn(Optional.of(channel));
        when(repository.findBySlug(channel.getSlug())).thenReturn(Optional.of(channel));
        var response = service.getById(channel.getId());
        assertThat(response.ownerId()).isEqualTo(channel.getOwner().getId());
        assertThat(service.getBySlug(channel.getSlug())).isEqualTo(response);
    }

    @Test
    void listsChannelsOfAuthenticatedOwner() {
        var owner = channel.getOwner();
        channel.setProfileImageKey("channels/avatar.png");
        when(storage.createReadUrl("channels/avatar.png")).thenReturn("read://avatar");
        when(repository.findByOwner_IdOrderByCreatedAtAsc(owner.getId())).thenReturn(List.of(channel));
        var mine = service.getMyChannels(owner);
        assertThat(mine).singleElement().satisfies(response -> {
            assertThat(response.id()).isEqualTo(channel.getId());
            assertThat(response.ownerId()).isEqualTo(owner.getId());
            assertThat(response.slug()).isEqualTo(channel.getSlug());
            assertThat(response.profileImageUrl()).isEqualTo("read://avatar");
            assertThat(response.bannerImageUrl()).isNull();
        });
    }

    @Test
    void ownerWithoutChannelsGetsAnEmptyList() {
        var owner = user("newcomer");
        when(repository.findByOwner_IdOrderByCreatedAtAsc(owner.getId())).thenReturn(List.of());
        assertThat(service.getMyChannels(owner)).isEmpty();
    }

    @Test
    void missingChannelReturnsNotFound() {
        assertThatThrownBy(() -> service.getById(UUID.randomUUID())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.getBySlug("missing")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void createsChannelForAuthenticatedOwner() {
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        var response = service.createChannel(new CreateChannelRequest(" Name ", " slug ", "bio", null, null), channel.getOwner());
        assertThat(response.name()).isEqualTo("Name");
        assertThat(response.slug()).isEqualTo("slug");
        assertThat(response.ownerId()).isEqualTo(channel.getOwner().getId());
    }

    @Test
    void onlyOwnerCanSelectOrUpdateChannel() {
        when(repository.findById(channel.getId())).thenReturn(Optional.of(channel));
        assertThat(service.getOwnedChannel(channel.getId(), channel.getOwner())).isSameAs(channel);
        var other = user("other");
        assertThatThrownBy(() -> service.getOwnedChannel(channel.getId(), other)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.update(channel.getId(), update("new-slug"), other)).isInstanceOf(ForbiddenException.class);
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(storage);
    }

    @Test
    void updateKeepsOwnerAndImagesWhenNoNewFilesAreSent() {
        channel.setProfileImageKey("old-profile");
        channel.setBannerImageKey("old-banner");
        when(repository.findById(channel.getId())).thenReturn(Optional.of(channel));
        when(repository.saveAndFlush(channel)).thenReturn(channel);
        when(storage.createReadUrl(anyString())).thenAnswer(call -> "read://" + call.getArgument(0));
        var response = service.update(channel.getId(), update("new-slug"), channel.getOwner());
        assertThat(response.slug()).isEqualTo("new-slug");
        assertThat(response.name()).isEqualTo("Updated");
        assertThat(response.ownerId()).isEqualTo(channel.getOwner().getId());
        assertThat(response.profileImageUrl()).isEqualTo("read://old-profile");
        assertThat(response.bannerImageUrl()).isEqualTo("read://old-banner");
        verify(storage, never()).upload(any(), any());
    }

    @Test
    void duplicateSlugIsRejectedBeforeUploading() {
        when(repository.existsBySlug("taken")).thenReturn(true);
        assertThatThrownBy(() -> service.createChannel(new CreateChannelRequest("name", "taken", null, null, null), channel.getOwner()))
                .isInstanceOf(ConflictException.class);
        when(repository.findById(channel.getId())).thenReturn(Optional.of(channel));
        when(repository.existsBySlugAndIdNot("taken", channel.getId())).thenReturn(true);
        assertThatThrownBy(() -> service.update(channel.getId(), update("taken"), channel.getOwner()))
                .isInstanceOf(ConflictException.class);
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(storage);
    }

    @Test
    void concurrentSlugConflictBecomes409AndCleansUploadedImage() {
        var image = image("profile");
        when(storage.upload("channels", image)).thenReturn("new-profile");
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_channels_slug"));
        assertThatThrownBy(() -> service.createChannel(new CreateChannelRequest("name", "slug", null, image, null), channel.getOwner()))
                .isInstanceOf(ConflictException.class);
        verify(storage).delete("new-profile");
    }

    @Test
    void failedBannerUploadCleansProfileImage() {
        var profile = image("profile");
        var banner = image("banner");
        when(storage.upload("channels", profile)).thenReturn("new-profile");
        when(storage.upload("channels", banner)).thenThrow(new IllegalStateException("storage unavailable"));
        assertThatThrownBy(() -> service.createChannel(new CreateChannelRequest("name", "slug", null, profile, banner), channel.getOwner()))
                .isInstanceOf(IllegalStateException.class);
        verify(storage).delete("new-profile");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void replacesOldImageOnlyAfterCommit() {
        verifyImageCleanup(TransactionSynchronization.STATUS_COMMITTED, "old-profile");
    }

    @Test
    void rollbackDeletesNewImageAndPreservesOldImage() {
        verifyImageCleanup(TransactionSynchronization.STATUS_ROLLED_BACK, "new-profile");
    }

    private void verifyImageCleanup(int status, String deletedKey) {
        var profile = image("profile");
        channel.setProfileImageKey("old-profile");
        when(repository.findById(channel.getId())).thenReturn(Optional.of(channel));
        when(repository.saveAndFlush(channel)).thenReturn(channel);
        when(storage.upload("channels", profile)).thenReturn("new-profile");
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.update(channel.getId(), new UpdateChannelRequest("name", "slug", null, profile, null), channel.getOwner());
            verify(storage, never()).delete(any());
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(status));
            verify(storage).delete(deletedKey);
            verify(storage, times(1)).delete(any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private UpdateChannelRequest update(String slug) {
        return new UpdateChannelRequest("Updated", slug, "description", null, null);
    }

    private MockMultipartFile image(String name) {
        return new MockMultipartFile(name, name + ".png", "image/png", new byte[]{1});
    }
}
