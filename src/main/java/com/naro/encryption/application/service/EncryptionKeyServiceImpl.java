package com.naro.encryption.application.service;

import com.naro.chat.domain.repository.ConversationRepository;
import com.naro.encryption.application.dto.CreateEncryptionIdentityRequest;
import com.naro.encryption.application.dto.E2eeStatusResponse;
import com.naro.encryption.application.dto.EncryptionIdentityResponse;
import com.naro.encryption.application.dto.EncryptionKeyResponse;
import com.naro.encryption.application.dto.PublicIdentityResponse;
import com.naro.encryption.application.dto.RegisterEncryptionKeyRequest;
import com.naro.encryption.application.dto.WrappedMasterKeyRequest;
import com.naro.encryption.config.E2eeProperties;
import com.naro.encryption.domain.entity.UserEncryptionIdentity;
import com.naro.encryption.domain.entity.UserEncryptionKey;
import com.naro.encryption.domain.repository.UserEncryptionIdentityRepository;
import com.naro.encryption.domain.repository.UserEncryptionKeyRepository;
import com.naro.exception.BadRequestException;
import com.naro.exception.E2eeError;
import com.naro.exception.E2eeException;
import com.naro.exception.NotFoundException;
import com.naro.user.domain.entity.User;
import com.naro.user.domain.repository.UserRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class EncryptionKeyServiceImpl implements EncryptionKeyService {

    static final int MAX_KEYS_PER_USER = 20;

    static final int MAX_IDENTITY_LOOKUP = 100;

    private final E2eeProperties properties;

    private final UserEncryptionIdentityRepository identityRepository;

    private final UserEncryptionKeyRepository keyRepository;

    private final UserRepository userRepository;

    private final ConversationRepository conversationRepository;

    public EncryptionKeyServiceImpl(
            E2eeProperties properties,
            UserEncryptionIdentityRepository identityRepository,
            UserEncryptionKeyRepository keyRepository,
            UserRepository userRepository,
            ConversationRepository conversationRepository
    ) {
        this.properties = properties;
        this.identityRepository = identityRepository;
        this.keyRepository = keyRepository;
        this.userRepository = userRepository;
        this.conversationRepository = conversationRepository;
    }

    @Override
    public E2eeStatusResponse getStatus() {
        return new E2eeStatusResponse(properties.available(), properties.required());
    }

    @Override
    @Transactional(readOnly = true)
    public EncryptionIdentityResponse getOwnIdentity(User currentUser) {
        requireAvailable();

        return identityRepository.findById(currentUser.getId())
                .map(EncryptionKeyServiceImpl::toOwnResponse)
                .orElseThrow(() -> new NotFoundException("Encryption identity not found"));
    }

    @Override
    @Transactional
    public EncryptionIdentityResponse createIdentity(CreateEncryptionIdentityRequest request, User currentUser) {
        requireAvailable();

        E2eeFormat.requireAlgorithm(request.algorithm(), E2eeFormat.IDENTITY_ALGORITHM);
        E2eeFormat.requireVersion(request.version());
        E2eeFormat.requirePublicKey(request.publicKey());
        E2eeFormat.requireWrappedPrivateKey(request.wrappedPrivateKey());
        E2eeFormat.requireIv(request.privateKeyIv());
        String proofHash = E2eeFormat.hashMasterKeyProof(request.masterKeyProof());
        requireWrappedMasterKey(request.key());

        // Duas abas ativando ao mesmo tempo: a segunda espera e recebe 409.
        User owner = lockUser(currentUser);

        if (identityRepository.existsById(owner.getId())) {
            throw new E2eeException(E2eeError.ALREADY_SET_UP);
        }

        UserEncryptionIdentity identity = identityRepository.saveAndFlush(new UserEncryptionIdentity(
                owner,
                request.publicKey(),
                request.wrappedPrivateKey(),
                request.privateKeyIv(),
                proofHash,
                request.algorithm(),
                request.version()
        ));

        keyRepository.save(toEntity(owner, request.key()));

        return toOwnResponse(identity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PublicIdentityResponse> getContactIdentities(Collection<UUID> userIds, User currentUser) {
        requireAvailable();

        if (userIds.size() > MAX_IDENTITY_LOOKUP) {
            throw new BadRequestException("Too many users requested");
        }

        Set<UUID> requested = new HashSet<>(userIds);
        Set<UUID> allowed = new HashSet<>();

        if (requested.remove(currentUser.getId())) {
            allowed.add(currentUser.getId());
        }

        if (!requested.isEmpty()) {
            allowed.addAll(conversationRepository.findContactIdsAmong(currentUser.getId(), requested));
        }

        if (allowed.isEmpty()) {
            return List.of();
        }

        return identityRepository.findAllByUserIdIn(allowed)
                .stream()
                .map(identity -> new PublicIdentityResponse(
                        identity.getUserId(),
                        identity.getPublicKey(),
                        identity.getAlgorithm(),
                        identity.getVersion()
                ))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<EncryptionKeyResponse> getKeys(User currentUser) {
        requireAvailable();

        return keyRepository.findAllByUser_IdOrderByCreatedAtAsc(currentUser.getId())
                .stream()
                .map(EncryptionKeyServiceImpl::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public EncryptionKeyResponse registerKey(RegisterEncryptionKeyRequest request, User currentUser) {
        requireAvailable();

        WrappedMasterKeyRequest wrapped = request.toWrappedMasterKey();
        requireWrappedMasterKey(wrapped);

        User owner = lockUser(currentUser);

        UserEncryptionIdentity identity = identityRepository.findById(owner.getId())
                .orElseThrow(() -> new E2eeException(E2eeError.SETUP_REQUIRED));

        requireProof(identity, request.masterKeyProof());

        if (keyRepository.existsByUser_IdAndCredentialId(owner.getId(), wrapped.credentialId())) {
            throw new E2eeException(E2eeError.KEY_EXISTS);
        }

        if (keyRepository.countByUser_Id(owner.getId()) >= MAX_KEYS_PER_USER) {
            throw new E2eeException(E2eeError.KEY_LIMIT);
        }

        return toResponse(keyRepository.save(toEntity(owner, wrapped)));
    }

    @Override
    @Transactional
    public void revokeKey(String credentialId, String masterKeyProof, User currentUser) {
        requireAvailable();

        // Mesmo lock do cadastro: duas revogações simultâneas nunca
        // apagam juntas as duas últimas passkeys.
        User owner = lockUser(currentUser);

        // Filtrado pelo dono: a chave de outra pessoa é simplesmente "não encontrada".
        UserEncryptionKey key = keyRepository.findByUser_IdAndCredentialId(owner.getId(), credentialId)
                .orElseThrow(() -> new NotFoundException("Encryption key not found"));

        UserEncryptionIdentity identity = identityRepository.findById(owner.getId())
                .orElseThrow(() -> new E2eeException(E2eeError.SETUP_REQUIRED));

        requireProof(identity, masterKeyProof);

        if (keyRepository.countByUser_Id(owner.getId()) <= 1) {
            throw new E2eeException(E2eeError.LAST_KEY);
        }

        keyRepository.delete(key);
    }

    private void requireAvailable() {
        if (!properties.available()) {
            throw new E2eeException(E2eeError.UNAVAILABLE);
        }
    }

    private User lockUser(User currentUser) {
        return userRepository.findByIdForUpdate(currentUser.getId())
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    private static void requireProof(UserEncryptionIdentity identity, String masterKeyProof) {
        if (!E2eeFormat.matchesMasterKeyProof(masterKeyProof, identity.getMasterKeyProofHash())) {
            throw new E2eeException(E2eeError.KEY_PROOF_INVALID);
        }
    }

    private static void requireWrappedMasterKey(WrappedMasterKeyRequest key) {
        E2eeFormat.requireAlgorithm(key.algorithm(), E2eeFormat.KEY_WRAP_ALGORITHM);
        E2eeFormat.requireVersion(key.version());
        E2eeFormat.requireCredentialId(key.credentialId());
        E2eeFormat.requireWrappedMasterKey(key.wrappedMasterKey());
        E2eeFormat.requireIv(key.iv());
    }

    private static UserEncryptionKey toEntity(User owner, WrappedMasterKeyRequest key) {
        return new UserEncryptionKey(
                owner,
                key.credentialId(),
                key.wrappedMasterKey(),
                key.iv(),
                key.algorithm(),
                key.version()
        );
    }

    private static EncryptionKeyResponse toResponse(UserEncryptionKey key) {
        return new EncryptionKeyResponse(
                key.getCredentialId(),
                key.getWrappedMasterKey(),
                key.getIv(),
                key.getAlgorithm(),
                key.getVersion(),
                key.getCreatedAt(),
                key.getUpdatedAt()
        );
    }

    private static EncryptionIdentityResponse toOwnResponse(UserEncryptionIdentity identity) {
        return new EncryptionIdentityResponse(
                identity.getUserId(),
                identity.getPublicKey(),
                identity.getWrappedPrivateKey(),
                identity.getPrivateKeyIv(),
                identity.getAlgorithm(),
                identity.getVersion(),
                identity.getCreatedAt()
        );
    }
}
