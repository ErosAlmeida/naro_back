# Criptografia ponta a ponta (E2EE) do chat privado

```
SEM CRIPTOGRAFIA SEGURA
=
SEM CHAT PRIVADO
```

Com o E2EE obrigatório, toda mensagem privada nova é cifrada e decifrada
**somente no navegador**. O Spring recebe, guarda e repassa ciphertext; ele não
tem chave para ler nada e nunca tenta. Se o dispositivo não consegue garantir o
fluxo criptográfico, o chat privado fica bloqueado nele. Não existe envio sem
criptografia, downgrade automático nem implementação alternativa.

## Visão geral

```
Passkey (Face ID, Touch ID, Windows Hello, PIN)
   │  WebAuthn, userVerification = "required"
   ▼
WebAuthn PRF ──── 32 bytes, estáveis por passkey, nunca saem do browser
   │  HKDF-SHA-256
   ▼
KEK (Key Encryption Key)
   │  AES-256-GCM
   ▼
wrappedMasterKey ──── guardada no servidor (uma por passkey)
```

```
Master Key (256 bits aleatórios, uma por usuário)
   │  HKDF-SHA-256 ("identity-wrap")
   ▼
chave privada de identidade ECDH P-256 ──── guardada cifrada no servidor
   │  ECDH(minha privada, pública do contato) + HKDF(conversa, participantes)
   ▼
chave da conversa
   │  AES-256-GCM, IV novo de 96 bits por mensagem
   ▼
mensagens
```

### Por que existe a chave de identidade

A Master Key é de um usuário só. Uma mensagem cifrada diretamente com a Master Key
do Leonardo não poderia ser lida pela Maria, que não tem essa chave, e mandar a
Master Key para ela destruiria a proteção. Por isso cada usuário tem também um par
de chaves ECDH P-256:

- a **pública** vai para o servidor e é entregue aos contatos;
- a **privada** vai cifrada com uma subchave da Master Key e só abre no browser.

Os dois lados de uma conversa direta calculam a mesma chave com
`ECDH(privada de um, pública do outro)`, sem trocar essa chave pela rede. É o menor
acréscimo que torna a conversa legível para os dois, sem Signal Protocol.

## O que o servidor recebe

| Recebe | Nunca recebe |
| --- | --- |
| `wrappedMasterKey` + `iv` (Master Key cifrada com a KEK) | Master Key |
| `credentialId` da passkey | KEK ou saída do PRF |
| chave pública de identidade | chave privada de identidade em claro |
| chave privada cifrada + `privateKeyIv` | plaintext de mensagens E2EE |
| SHA-256 da prova de posse da Master Key | a prova em si (só em trânsito, para comparar) |
| `ciphertext`, `iv`, `encryptionVersion` de cada mensagem | chave das conversas |

A **prova de posse** é um HMAC-SHA-256 feito com uma subchave da Master Key. O
servidor guarda só o SHA-256 dela e a exige para cadastrar ou revogar passkeys.
Assim, uma sessão roubada sozinha não consegue apagar as passkeys de alguém nem
cadastrar uma própria. A prova não revela nada da Master Key.

## Compatibilidade do dispositivo

Uma única verificação, `detectEncryptionCapabilities()`
(`features/encryption/capabilities.ts` no front), decide:

```ts
type EncryptionCapabilities = {
  webCrypto: boolean        // contexto seguro, crypto.subtle, crypto.getRandomValues
  webAuthn: boolean         // PublicKeyCredential, navigator.credentials.create/get
  prf: boolean              // getClientCapabilities()['extension:prf'] === true
  userVerification: boolean // autenticador com biometria/PIN, ou celular via QR
  supported: boolean        // todos acima
}
```

Recurso que o navegador não declara conta como ausente. A checagem é prévia: a
garantia final está na cerimônia da passkey, que exige a saída do PRF e confere o
bit UV (verificação do usuário) nos dados do autenticador. Sem os dois, nada é
desbloqueado.

Dispositivo incompatível vê só:

> **Conversas privadas indisponíveis**
>
> Este dispositivo não possui os recursos de segurança necessários para acessar o
> chat privado do Naro.

O resto do Naro (feed, canais, amigos, presença) continua funcionando.

## Estados do chat

```
CHECKING_SECURITY ─▶ UNSUPPORTED
        │
        ├─▶ NEEDS_SETUP ─(Ativar proteção)─▶ UNLOCKING ─▶ READY
        ├─▶ LOCKED ──────(Desbloquear)─────▶ UNLOCKING ─▶ READY
        └─▶ ERROR
```

Keycloak autenticado **não** significa `READY`. O `ChatSecurityGuard` fica antes de
tudo: sem `READY`, o browser não busca conversas nem ciphertext, não abre o canal
`/user/queue/chat`, não mostra o campo de mensagem e não cria Master Key.

| Situação | Estado |
| --- | --- |
| Dispositivo sem Web Crypto, WebAuthn, PRF ou verificação do usuário | `UNSUPPORTED` |
| Conta sem identidade e sem passkeys | `NEEDS_SETUP` |
| Conta com identidade e passkeys | `LOCKED` |
| Passkey cancelada, saída do PRF ausente, Master Key que não abre | `LOCKED`, com aviso |
| Servidor fora do ar | `ERROR` |
| Identidade sem nenhuma passkey | `ERROR` (nunca uma Master Key nova) |

## Master Key

Gerada uma única vez, na primeira ativação, com `crypto.getRandomValues`.

**Se a conta já tem Master Key e o dispositivo não consegue abri-la, o chat fica
bloqueado.** Não existe caminho no código de desbloqueio que gere outra: uma Master
Key nova tornaria o histórico ilegível. A tela mostra
"Não foi possível desbloquear suas conversas neste dispositivo.".

Em memória, a Master Key é uma `CryptoKey` HKDF **não extraível**. Ela nunca vai
para `localStorage`, `sessionStorage`, cookies ou IndexedDB. Recarregar a página
ou sair da conta pede a passkey de novo; "Bloquear conversas agora" apaga as
chaves da memória na hora.

## Passkey

- `userVerification: "required"` em toda cerimônia (`preferred` não é aceito).
- Passkey residente (`residentKey: "required"`), `attestation: "none"`.
- Entrada fixa do PRF (`naro/e2ee/v1/prf`); a saída continua única por passkey.
- A saída do PRF usada para cifrar a Master Key vem sempre de uma asserção (`get`),
  o mesmo caminho do desbloqueio. Por isso a ativação pede duas confirmações.
- A passkey não autentica no servidor: login continua no Keycloak. Ela é só a
  origem da KEK.

## Primeira ativação

```
abre /chat → compatível → sem identidade → "Proteja suas conversas"
   → cria passkey → asserção com PRF → KEK
   → gera Master Key → cifra com a KEK → gera identidade ECDH
   → cifra a privada com a Master Key → prova de posse
   → POST /api/v1/encryption/identity (identidade + primeira passkey, uma transação)
   → READY
```

## Novo dispositivo

```
login Keycloak → abre /chat → verifica suporte → busca identidade e passkeys
   → WebAuthn → passkey (sincronizada ou do celular, via QR) → verificação do usuário
   → PRF → KEK → abre a Master Key → abre a chave de identidade → READY
```

Só então o canal do WebSocket é assinado e as mensagens privadas são carregadas.

## Vários dispositivos

```
MASTER_KEY
   ├── cifrada para a passkey do Mac (iCloud Keychain)
   ├── cifrada para a passkey do Windows (Windows Hello)
   └── cifrada para a passkey do Galaxy (Google Password Manager)
```

Todas as passkeys protegem **a mesma** Master Key. Em "Proteção das conversas", com
a sessão desbloqueada, "Adicionar passkey deste dispositivo" confirma com a passkey
atual, cria a nova e cadastra a Master Key cifrada para ela (com a prova de posse).
A última passkey não pode ser removida: sem ela, ninguém abriria o histórico.

## Mensagens

Envio pelo WebSocket (`SEND /app/chat/send`), com o E2EE obrigatório:

```json
{
  "conversationId": "…",
  "ciphertext": "base64(AES-256-GCM(texto) + tag)",
  "iv": "base64(12 bytes aleatórios)",
  "encryptionVersion": 1,
  "clientMessageId": "…"
}
```

- `additionalData` do AES-GCM: `naro/e2ee/v1/message|<conversationId>|<senderId>`. Se o
  servidor trocar o remetente, a conversa ou um byte do ciphertext, a decifragem
  falha e a mensagem aparece como "Não foi possível verificar esta mensagem.", sem
  texto.
- IV novo a cada mensagem, sempre com `crypto.getRandomValues`.
- O payload é validado por um schema estrito antes do WebSocket: um objeto com
  `content` nunca é publicado no modo E2EE. Falhou a criptografia, a mensagem não
  sai e a interface avisa.

O Spring confere só o formato (Base64, IV de 12 bytes, versão 1, tamanho) e repassa
o evento aos participantes exatamente como recebeu. Ele nunca chama `decrypt()`.

### Erro `E2EE_REQUIRED`

Com `naro.security.e2ee.required=true`, uma mensagem com `content` é recusada antes
de qualquer acesso ao banco, mesmo que também traga ciphertext:

```json
{ "code": "E2EE_REQUIRED", "conversationId": "…", "clientMessageId": "…" }
```

O evento vai para `/user/queue/errors`; nada é persistido, entregue ou registrado
em log. Outros códigos: `E2EE_INVALID_PAYLOAD` (formato), `E2EE_UNAVAILABLE`
(E2EE desligado).

## Banco de dados

Migration `V12__add_end_to_end_encryption.sql`, compatível com produção:

- `chat_messages.content` passa a aceitar `NULL`; nenhuma coluna é removida.
- Novas colunas: `encrypted` (padrão `false`), `ciphertext`, `encryption_iv`,
  `encryption_version`.
- `ck_chat_messages_payload` garante exatamente uma forma:

| Mensagem | `content` | `encrypted` | `ciphertext`, `encryption_iv`, `encryption_version` |
| --- | --- | --- | --- |
| antiga (legada) | texto | `false` | `NULL` |
| nova (E2EE) | `NULL` | `true` | preenchidos |

- `user_encryption_identities`: uma identidade por usuário, imutável.
- `user_encryption_keys`: N passkeys por usuário, `UNIQUE (user_id, credential_id)`,
  FK para a identidade (não existe passkey sem identidade).

Não há `DROP`, `TRUNCATE` nem reescrita de mensagens.

## Mensagens antigas

As mensagens anteriores ao E2EE continuam onde estão, em texto, e são mostradas com
um cadeado aberto ("Enviada antes da criptografia ponta a ponta"). Elas **não** são
cifradas automaticamente pela migration: isso exigiria que o servidor lesse e
reescrevesse conteúdo privado. Mensagem antiga é só compatibilidade histórica e
nunca justifica aceitar uma nova em texto.

## Feature flags

```yaml
naro:
  security:
    e2ee:
      available: ${E2EE_AVAILABLE:…}
      required: ${E2EE_REQUIRED:…}
```

| Fase | `available` | `required` | Efeito |
| --- | --- | --- | --- |
| 0 | `false` | `false` | endpoints de chaves desligados; chat legado |
| 1 | `true` | `false` | infraestrutura no ar para teste interno; **o front continua no chat legado para todos** (sem mistura aleatória de mensagens E2EE e texto) |
| 2 | `true` | `true` | chat privado somente E2EE; o Spring recusa texto; o front bloqueia navegadores incompatíveis |

`required=true` com `available=false` impede a aplicação de subir.

O front não tem flag própria: o layout do chat lê `GET /api/v1/encryption/status`.
Se a resposta não vier, o chat mostra erro. Nunca se assume o modo legado por falta
de resposta.

Padrões por perfil: `prod` = fase 1; `local` = fase 2 (para testar o fluxo inteiro em
`http://localhost:3000`; `E2EE_REQUIRED=false` volta ao legado); testes = fase 1,
com as classes de E2EE subindo em fase 2.

**Para ativar a fase 2 em produção**, troque o padrão em `application-prod.yml`
(`required: ${E2EE_REQUIRED:true}`) e faça o push, ou defina `E2EE_REQUIRED=true` em
`/etc/naro/backend.env`. Abas abertas no modo legado que tentarem enviar texto
recebem `E2EE_REQUIRED` e recarregam o chat no modo E2EE.

## Keycloak × chaves criptográficas

| Keycloak | E2EE |
| --- | --- |
| identidade, login, JWT, autorização | Master Key, KEK, chaves das conversas |
| sabe quem é o usuário | não sabe nada das chaves |
| sessão do BFF (cookie HttpOnly) | chaves só na memória da aba |

Nenhuma chave é derivada da senha do Keycloak, do JWT ou de algo que o servidor
conheça.

## Endpoints

| Método | Rota | O quê |
| --- | --- | --- |
| `GET` | `/api/v1/encryption/status` | `{ available, required }` |
| `GET` | `/api/v1/encryption/identity` | identidade do usuário (privada cifrada); `404` antes da ativação |
| `POST` | `/api/v1/encryption/identity` | ativa: identidade + primeira passkey; `409` se já ativada |
| `GET` | `/api/v1/encryption/identities?userId=…` | chaves públicas de quem divide conversa com o usuário |
| `GET` | `/api/v1/encryption/keys` | passkeys (Master Key cifrada) do usuário |
| `POST` | `/api/v1/encryption/keys` | cadastra outra passkey (`masterKeyProof` obrigatório) |
| `DELETE` | `/api/v1/encryption/keys/{credentialId}` | revoga; header `X-E2EE-Key-Proof`; `409` para a última |

O dono é sempre o usuário do JWT; nenhum request carrega `userId`. A chave de outra
pessoa não existe para quem pergunta (`404`). Erros de E2EE vêm em Problem Details
com a propriedade `code` (`E2EE_LAST_KEY`, `E2EE_KEY_PROOF_INVALID`, …).

## XSS

Com as chaves na memória da aba, XSS é a ameaça principal: um script injetado
poderia decifrar o que a aba decifra enquanto ela estiver desbloqueada. Medidas:

- mensagens são sempre texto (`{message.content}` no React), nunca HTML;
  não há `dangerouslySetInnerHTML` no projeto;
- chaves em `CryptoKey` não extraível: um script injetado não consegue levar a
  Master Key, a chave de identidade ou a KEK para fora da aba;
- nenhuma biblioteca de terceiros nova na área do chat;
- nada criptográfico é registrado em log.

**Recomendado a seguir:** uma Content-Security-Policy com nonce
(`script-src 'self' 'nonce-…'`), hoje ausente.

## Logs

Nunca são registrados: Master Key, KEK, saída do PRF, plaintext, prova de posse. O
payload recusado por `E2EE_REQUIRED` não é ecoado no erro. Os erros de validação do
front (Zod) não incluem os valores recebidos.

## Limitações atuais

- **Sem recuperação.** Perder todas as passkeys é perder o histórico E2EE. Passkeys
  sincronizadas (iCloud Keychain, Google Password Manager) e uma segunda passkey
  reduzem o risco. Não existe redefinição pela interface; ela exigiria uma nova
  identidade e tornaria o histórico E2EE ilegível para os dois lados.
- **Revogar não troca chaves.** A passkey removida deixa de abrir a Master Key pelo
  servidor, mas um dispositivo que já copiou a Master Key cifrada e ainda tem a
  passkey continua capaz de abri-la. Rotação da Master Key fica para depois.
- **Chaves públicas vêm do servidor.** Não há verificação de código de segurança
  entre contatos; um servidor malicioso poderia entregar outra chave pública na
  primeira vez. Dentro de uma sessão, uma chave que muda bloqueia a conversa
  (`KEY_CHANGED`).
- **Sem sigilo futuro (forward secrecy).** A chave de cada conversa é estática; quem
  obtiver uma chave privada de identidade lê o histórico daquela conversa.
- **Metadados visíveis ao servidor:** quem conversa com quem, quando e o tamanho
  aproximado das mensagens. "Digitando…" e presença não são cifrados.
- **Replay:** o servidor pode reenviar um ciphertext antigo como mensagem nova; a
  AAD impede trocar remetente ou conversa, não repetir.
- **Grupos:** sem E2EE de grupo; o envio em grupo fica bloqueado no modo E2EE
  (não existe criação pública de grupos).
- **Contato sem proteção:** só é possível conversar com quem já ativou o E2EE. A tela
  avisa e libera sozinha quando o contato ativar.
- A passkey é pedida de novo a cada recarregamento da página.
- **Virada para a fase 2:** uma aba aberta antes, ainda no modo legado, pode tentar
  enviar uma mensagem em texto. O servidor a recusa com `E2EE_REQUIRED` (não guarda,
  não entrega, não registra) e a aba recarrega o chat no modo E2EE. Fechar as abas
  antigas elimina essa janela.

## Por que ainda não usamos o Signal Protocol

X3DH/PQXDH e Double Ratchet dão sigilo futuro, recuperação após comprometimento e
verificação de identidade, mas trazem pré-chaves por dispositivo, estado por
sessão, sincronização entre dispositivos e muito mais superfície para erro. Esta
primeira versão prioriza regras rígidas e simples: nada sai sem criptografia, nada
é aceito sem criptografia, nada é desbloqueado sem passkey com verificação do
usuário. Os formatos têm `version` para permitir evoluir o protocolo sem apagar o
histórico.

## Testes

Backend (`./mvnw test`):

- `EncryptionEndpointsIntegrationTest`: cadastro, chaves só do dono, prova de posse,
  revogação da própria, recusa da alheia, última passkey, chaves públicas só de
  contatos, material malformado.
- `E2eeRealtimeIntegrationTest`: WebSocket real transporta o ciphertext sem alterar,
  recusa texto com `E2EE_REQUIRED` sem persistir nem entregar, mensagens antigas
  continuam legíveis.
- `EncryptionMigrationTest`: V12 sobre um banco com mensagens antigas.
- `ChatMessagePayloadPolicyTest`: as flags, sem caminho de volta para texto.

Frontend (`pnpm test`, WebAuthn simulado):

- `crypto.test.ts`: Master Key, KEK, wrap/unwrap, identidade, mensagens; IV diferente
  a cada cifragem; chave errada e ciphertext alterado falham.
- `capabilities.test.ts`: Web Crypto, WebAuthn, PRF ou verificação ausentes bloqueiam.
- `passkey.test.ts`: `userVerification: "required"`, PRF obrigatório, bit UV.
- `session.test.ts`: ativação, novo dispositivo, Master Key que não abre (sem gerar
  outra), cancelamento, várias passkeys, contatos sem proteção.
- `send.test.ts`: nenhum envio sem criptografia por fallback; canal do WebSocket só
  em `READY`.
