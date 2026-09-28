package com.naro.chat.domain.entity;

import com.naro.user.domain.entity.User;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Getter
@Entity
@Table(name = "chat_messages")
@NoArgsConstructor
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;


    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "conversation_id",
            nullable = false
    )
    private Conversation conversation;


    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "sender_id",
            nullable = false
    )
    private User sender;


    /*
     * Plaintext das mensagens anteriores ao E2EE (encrypted = false).
     * Mensagens E2EE nunca têm content: só ciphertext.
     */
    @Column(
            length = 4000
    )
    private String content;


    @Column(
            nullable = false
    )
    private boolean encrypted;


    /* AES-256-GCM (ciphertext + tag), em Base64. O servidor não decifra. */
    @Column(
            columnDefinition = "text"
    )
    private String ciphertext;


    @Column(
            name = "encryption_iv",
            length = 32
    )
    private String encryptionIv;


    @Column(
            name = "encryption_version"
    )
    private Integer encryptionVersion;


    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;


    /**
     * Mensagem sem criptografia (formato legado). Com
     * naro.security.e2ee.required=true o chat nunca cria esta forma.
     */
    public ChatMessage(
            Conversation conversation,
            User sender,
            String content
    ) {
        this.conversation = conversation;
        this.sender = sender;
        this.content = content;
        this.encrypted = false;
    }


    /**
     * Mensagem E2EE: o servidor guarda o ciphertext como recebeu,
     * sem decifrar, alterar ou registrar em log.
     */
    public static ChatMessage encrypted(
            Conversation conversation,
            User sender,
            String ciphertext,
            String encryptionIv,
            int encryptionVersion
    ) {
        ChatMessage message = new ChatMessage();
        message.conversation = conversation;
        message.sender = sender;
        message.encrypted = true;
        message.ciphertext = ciphertext;
        message.encryptionIv = encryptionIv;
        message.encryptionVersion = encryptionVersion;
        return message;
    }


    /*
     * Na precisão do Postgres (microssegundos): o instante que vai no evento
     * em tempo real é o mesmo que fica no banco e que os marcadores de
     * entrega/leitura comparam.
     */
    @PrePersist
    private void prePersist() {
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}