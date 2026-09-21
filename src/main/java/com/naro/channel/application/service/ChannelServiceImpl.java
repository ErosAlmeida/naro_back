package com.naro.channel.application.service;

import com.naro.channel.application.dto.ChannelResponse;
import com.naro.channel.application.dto.CreateChannelRequest;
import com.naro.channel.application.dto.UpdateChannelRequest;
import com.naro.channel.application.mapper.ChannelMapper;
import com.naro.channel.domain.entity.Channel;
import com.naro.channel.domain.repository.ChannelRepository;
import com.naro.exception.ConflictException;
import com.naro.exception.ForbiddenException;
import com.naro.exception.NotFoundException;
import com.naro.storage.service.ObjectStorageService;
import com.naro.user.domain.entity.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.UUID;

@Service
public class ChannelServiceImpl implements ChannelService {

    private final ChannelRepository channelRepository;

    private final ObjectStorageService storageService;

    private final ChannelMapper channelMapper;

    public ChannelServiceImpl(
            ChannelRepository channelRepository,
            ObjectStorageService storageService,
            ChannelMapper channelMapper
    ) {
        this.channelRepository = channelRepository;
        this.storageService = storageService;
        this.channelMapper = channelMapper;
    }

    @Override
    @Transactional
    public ChannelResponse createChannel(CreateChannelRequest request, User author) {
        if (channelRepository.existsBySlug(request.slug().trim())) {
            throw new ConflictException("Channel slug already exists");
        }
        String profileImageKey = null;
        String bannerImageKey = null;
        try {
            profileImageKey =
                    request.profile_image() == null || request.profile_image().isEmpty()
                            ? null
                            : storageService.upload("channels", request.profile_image());

            bannerImageKey =
                    request.banner_image() == null || request.banner_image().isEmpty()
                            ? null
                            : storageService.upload("channels", request.banner_image());

            Channel channel = channelMapper.toEntity(
                    request,
                    author,
                    profileImageKey,
                    bannerImageKey
            );

            channel.setName(request.name().trim());
            channel.setSlug(request.slug().trim());
            Channel saved =
                    channelRepository.saveAndFlush(channel);

            cleanImagesAfterCompletion(profileImageKey, bannerImageKey, null, null);
            return toResponse(saved);
        } catch (RuntimeException exception) {

            deleteImageSafely(profileImageKey);
            deleteImageSafely(bannerImageKey);

            throw translateConflict(exception);
        }


    }

    @Override
    @Transactional(readOnly = true)
    public List<ChannelResponse> getMyChannels(User owner) {
        return channelRepository.findByOwner_IdOrderByCreatedAtAsc(owner.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ChannelResponse getById(UUID channelId) {
        return toResponse(findChannel(channelId));
    }

    @Override
    @Transactional(readOnly = true)
    public ChannelResponse getBySlug(String slug) {
        return toResponse(channelRepository.findBySlug(slug.trim())
                .orElseThrow(() -> new NotFoundException("Channel not found")));
    }

    @Override
    @Transactional(readOnly = true)
    public Channel getOwnedChannel(UUID channelId, User currentUser) {
        Channel channel = findChannel(channelId);
        if (!channel.getOwner().getId().equals(currentUser.getId())) {
            throw new ForbiddenException("You can only act as your own channels");
        }
        return channel;
    }

    @Override
    @Transactional
    public ChannelResponse update(UUID channelId, UpdateChannelRequest request, User currentUser) {
        Channel channel = getOwnedChannel(channelId, currentUser);
        String slug = request.slug().trim();
        if (channelRepository.existsBySlugAndIdNot(slug, channelId)) {
            throw new ConflictException("Channel slug already exists");
        }

        String oldProfile = channel.getProfileImageKey();
        String oldBanner = channel.getBannerImageKey();
        String newProfile = null;
        String newBanner = null;
        try {
            if (request.profile_image() != null && !request.profile_image().isEmpty()) {
                newProfile = storageService.upload("channels", request.profile_image());
                channel.setProfileImageKey(newProfile);
            }
            if (request.banner_image() != null && !request.banner_image().isEmpty()) {
                newBanner = storageService.upload("channels", request.banner_image());
                channel.setBannerImageKey(newBanner);
            }
            channel.setName(request.name().trim());
            channel.setSlug(slug);
            channel.setDescription(request.description());
            Channel saved = channelRepository.saveAndFlush(channel);
            cleanImagesAfterCompletion(newProfile, newBanner, oldProfile, oldBanner);
            return toResponse(saved);
        } catch (RuntimeException exception) {
            deleteImageSafely(newProfile);
            deleteImageSafely(newBanner);
            throw translateConflict(exception);
        }
    }

    private ChannelResponse toResponse(Channel channel) {
        return channelMapper.toResponse(
                channel,
                createReadUrl(channel.getProfileImageKey()),
                createReadUrl(channel.getBannerImageKey())
        );
    }

    /* Canais sem imagem continuam válidos: a URL simplesmente não existe. */
    private String createReadUrl(String imageKey) {
        return imageKey == null ? null : storageService.createReadUrl(imageKey);
    }

    private Channel findChannel(UUID id) {
        return channelRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Channel not found"));
    }

    private RuntimeException translateConflict(RuntimeException exception) {
        return exception instanceof DataIntegrityViolationException
                ? new ConflictException("Channel could not be saved due to a data conflict")
                : exception;
    }

    private void cleanImagesAfterCompletion(String newProfile, String newBanner,
                                            String oldProfile, String oldBanner) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    if (newProfile != null) {
                        deleteImageSafely(oldProfile);
                    }
                    if (newBanner != null) {
                        deleteImageSafely(oldBanner);
                    }
                } else if (status == STATUS_ROLLED_BACK) {
                    deleteImageSafely(newProfile);
                    deleteImageSafely(newBanner);
                }
            }
        });
    }

    private void deleteImageSafely(String imageKey) {
        if (imageKey == null) {
            return;
        }

        try {
            storageService.delete(imageKey);
        } catch (RuntimeException ignored) {
            // adicionar log depois
        }
    }
}
